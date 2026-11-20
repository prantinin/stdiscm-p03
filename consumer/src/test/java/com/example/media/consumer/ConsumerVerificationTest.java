package com.example.media.consumer;

import com.example.media.consumer.catalog.DefaultVideoCatalog;
import com.example.media.consumer.catalog.VideoCatalog;
import com.example.media.consumer.dedupe.DedupeRegistry;
import com.example.media.consumer.gui.ConsumerGuiApplication;
import com.example.media.consumer.media.MediaException;
import com.example.media.consumer.media.MediaProcessor;
import com.example.media.consumer.queue.Job;
import com.example.media.consumer.queue.LeakyBucket;
import com.example.media.consumer.queue.QueueStatusSupplier;
import com.example.media.consumer.server.ConsumerRuntime;
import com.example.media.consumer.server.MediaUploadServiceImpl;
import com.example.media.consumer.worker.ConsumerWorker;
import com.example.media.proto.MediaUploadServiceGrpc;
import com.example.media.proto.QueueStatusRequest;
import com.example.media.proto.QueueStatusResponse;
import com.example.media.proto.UploadChunk;
import com.example.media.proto.UploadMeta;
import com.example.media.proto.UploadResult;
import com.example.media.proto.UploadStatus;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerVerificationTest {
    private static final long WAIT_SECONDS = 12;

    @TempDir
    Path tempDir;

    private Fixture fixture;

    @AfterEach
    void closeFixture() throws Exception {
        if (fixture != null) {
            fixture.close();
            fixture = null;
        }
    }

    @Test
    void cliConfigurationStartsSharedGrpcRuntimeAndGui() throws Exception {
        int grpcPort = availablePort();
        Path uploads = tempDir.resolve("app-uploads");
        Path previews = tempDir.resolve("app-previews");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ConsumerGuiApplication.class)
                .properties("server.port=0")
                .run(
                        "--server.port=0",
                        "--c=2",
                        "--q=3",
                        "--grpc-port=" + grpcPort,
                        "--upload-dir=" + uploads,
                        "--preview-dir=" + previews,
                        "--enable-dedup=true",
                        "--enable-compress=false",
                        "--media.use-fake=false",
                        "--media.grpc.enabled=true")) {
            ConsumerConfig config = context.getBean(ConsumerConfig.class);
            LeakyBucket bucket = context.getBean(LeakyBucket.class);
            DefaultVideoCatalog defaultCatalog = context.getBean(DefaultVideoCatalog.class);
            VideoCatalog guiCatalog = context.getBean(VideoCatalog.class);
            ConsumerRuntime runtime = context.getBean(ConsumerRuntime.class);
            QueueStatusSupplier statusSupplier = context.getBean(QueueStatusSupplier.class);

            assertEquals(2, config.getC());
            assertEquals(3, config.getQ());
            assertEquals(grpcPort, config.getGrpcPort());
            String maxUploadSizeFromEnv = System.getenv("MAX_UPLOAD_SIZE_MB");
            long expectedMaxUploadSize = maxUploadSizeFromEnv == null || maxUploadSizeFromEnv.isBlank()
                    ? 500
                    : Long.parseLong(maxUploadSizeFromEnv);
            assertEquals(expectedMaxUploadSize, config.getMaxUploadSizeMb());
            assertTrue(config.isEnableDedup());
            assertFalse(config.isEnableCompress());
            assertTrue(Files.isDirectory(uploads.resolve("tmp")));
            assertTrue(Files.isDirectory(previews));
            assertEquals(3, bucket.getCapacityQ());
            assertSame(defaultCatalog, guiCatalog);
            assertNotNull(runtime);
            assertEquals(2, statusSupplier.getStatus().getC());
            assertEquals(3, statusSupplier.getStatus().getQ());

            ServletWebServerApplicationContext webContext =
                    (ServletWebServerApplicationContext) context;
            int guiPort = webContext.getWebServer().getPort();
            assertTrue(guiPort > 0);
            HttpResponse<String> guiResponse = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + guiPort + "/api/videos"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, guiResponse.statusCode());

            ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort)
                    .usePlaintext()
                    .build();
            try {
                QueueStatusResponse grpcStatus = MediaUploadServiceGrpc.newBlockingStub(channel)
                        .getQueueStatus(QueueStatusRequest.getDefaultInstance());
                assertEquals(3, grpcStatus.getQueueCapacity());
                assertFalse(grpcStatus.getFull());
            } finally {
                channel.shutdownNow();
                channel.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void rejectsMissingMetadataAndEveryInvalidMetadataConstraintWithoutLeaks() throws Exception {
        fixture = newFixture(1, 4, 1, 0, true, false, new CopyingMediaProcessor(), false);
        byte[] bytes = "valid bytes".getBytes(StandardCharsets.UTF_8);

        RecordingUploadObserver missingMeta = new RecordingUploadObserver();
        StreamObserver<UploadChunk> missingMetaRequest = fixture.service.upload(missingMeta);
        missingMetaRequest.onNext(dataChunk(bytes));
        assertEquals(UploadStatus.REJECTED_INVALID, missingMeta.result.getStatus());
        assertNoUploadResources(fixture, 4);

        List<UploadMeta> invalidMetadata = List.of(
                metadata("P1", "video.txt", bytes.length, sha256(bytes)),
                metadata("P1", "video.mp4", 0, sha256(bytes)),
                metadata("P1", "video.mp4", 1024L * 1024L + 1, "a".repeat(64)),
                metadata("P1", "video.mp4", bytes.length, "not-a-sha256"));

        for (UploadMeta invalid : invalidMetadata) {
            RecordingUploadObserver response = new RecordingUploadObserver();
            fixture.service.upload(response).onNext(metaChunk(invalid));
            assertEquals(UploadStatus.REJECTED_INVALID, response.result.getStatus());
            assertNoUploadResources(fixture, 4);
        }
    }

    @Test
    void acceptsAllSpecifiedExtensionsCaseInsensitively() throws Exception {
        fixture = newFixture(1, 5, 1, 0, true, false, new CopyingMediaProcessor(), false);
        String[] extensions = {".mp4", ".mkv", ".avi", ".mov", ".webm", ".MOV"};

        for (int i = 0; i < extensions.length; i++) {
            byte[] bytes = ("extension " + i).getBytes(StandardCharsets.UTF_8);
            UploadMeta meta = metadata("P1", "video" + extensions[i], bytes.length,
                    String.format("%064x", i + 1));
            RecordingUploadObserver response = new RecordingUploadObserver();
            StreamObserver<UploadChunk> request = fixture.service.upload(response);

            request.onNext(metaChunk(meta));

            assertFalse(response.completed);
            assertEquals(4, fixture.bucket.getAvailableSlots());
            assertEquals(1, uploadPartCount(fixture));
            request.onError(new IllegalStateException("test cleanup"));
            assertNoUploadResources(fixture, 5);
        }
    }

    @Test
    void rejectsWrongByteCountAndWrongStreamDigestAndReleasesReservations() throws Exception {
        fixture = newFixture(1, 2, 1, 0, true, false, new CopyingMediaProcessor(), false);
        byte[] bytes = "streamed bytes".getBytes(StandardCharsets.UTF_8);
        List<UploadMeta> wrongMetadata = List.of(
                metadata("P1", "wrong-size.mp4", bytes.length + 1L, sha256(bytes)),
                metadata("P1", "wrong-hash.mp4", bytes.length,
                        sha256("different content".getBytes(StandardCharsets.UTF_8))));

        for (UploadMeta meta : wrongMetadata) {
            RecordingUploadObserver response = new RecordingUploadObserver();
            StreamObserver<UploadChunk> request = fixture.service.upload(response);
            request.onNext(metaChunk(meta));
            request.onNext(dataChunk(bytes));
            request.onCompleted();

            assertEquals(UploadStatus.REJECTED_INVALID, response.result.getStatus());
            assertNoUploadResources(fixture, 2);
            assertTrue(fixture.dedupeRegistry.reservePending(meta.getSha256()));
            fixture.dedupeRegistry.releasePending(meta.getSha256());
        }
    }

    @Test
    void reservesDedupeBeforeBucketAndReleasesHashOnQueueDrop() throws Exception {
        AtomicReference<LeakyBucket> bucketReference = new AtomicReference<>();
        AtomicInteger permitsWhenDedupeReserved = new AtomicInteger(-1);
        DedupeRegistry registry = new DedupeRegistry() {
            @Override
            public boolean reservePending(String sha256) {
                permitsWhenDedupeReserved.set(bucketReference.get().getAvailableSlots());
                return super.reservePending(sha256);
            }
        };
        fixture = newFixture(1, 2, 1, 0, true, false, new CopyingMediaProcessor(), false, registry);
        bucketReference.set(fixture.bucket);

        byte[] content = "ordered reservation".getBytes(StandardCharsets.UTF_8);
        RecordingUploadObserver successResponse = new RecordingUploadObserver();
        StreamObserver<UploadChunk> success = fixture.service.upload(successResponse);
        success.onNext(metaChunk(metadata("P1", "ordered.mp4", content.length, sha256(content))));
        assertEquals(2, permitsWhenDedupeReserved.get());
        success.onNext(dataChunk(content));
        success.onCompleted();
        assertEquals(UploadStatus.ACCEPTED, successResponse.result.getStatus());

        Job acceptedJob = fixture.bucket.pollJob();
        Files.deleteIfExists(acceptedJob.getTempPartPath());

        fixture.bucket.tryReserve();
        fixture.bucket.tryReserve();
        byte[] droppedBytes = "queue full".getBytes(StandardCharsets.UTF_8);
        UploadMeta droppedMeta = metadata("P2", "dropped.mp4", droppedBytes.length, sha256(droppedBytes));
        RecordingUploadObserver droppedResponse = new RecordingUploadObserver();
        fixture.service.upload(droppedResponse).onNext(metaChunk(droppedMeta));

        assertEquals(0, permitsWhenDedupeReserved.get());
        assertEquals(UploadStatus.DROPPED_QUEUE_FULL, droppedResponse.result.getStatus());
        assertEquals(1000, droppedResponse.result.getRetryAfterMs());
        assertTrue(fixture.dedupeRegistry.reservePending(droppedMeta.getSha256()));
        fixture.dedupeRegistry.releasePending(droppedMeta.getSha256());
        fixture.bucket.releaseSlot();
        fixture.bucket.releaseSlot();
        assertNoUploadResources(fixture, 2);
    }

    @Test
    void reportsQueueStatusFromReservedSemaphorePermits() throws Exception {
        fixture = newFixture(1, 3, 1, 0, true, false, new CopyingMediaProcessor(), false);
        for (int i = 0; i < 3; i++) {
            assertTrue(fixture.bucket.tryReserve());
        }

        RecordingQueueStatusObserver response = new RecordingQueueStatusObserver();
        fixture.service.getQueueStatus(QueueStatusRequest.getDefaultInstance(), response);

        assertEquals(3, response.result.getQueueLength());
        assertEquals(3, response.result.getQueueCapacity());
        assertTrue(response.result.getFull());
        assertEquals(0, fixture.bucket.getAvailableSlots());
        for (int i = 0; i < 3; i++) {
            fixture.bucket.releaseSlot();
        }
    }

    @Test
    void queueOfferFailureCleansTemporaryFileSlotAndPendingHash() throws Exception {
        fixture = newFixture(1, 1, 1, 0, true, false, new CopyingMediaProcessor(), false);
        UploadMeta queuedMeta = metadata("P0", "queued.mp4", 1, "b".repeat(64));
        assertTrue(fixture.bucket.enqueue(new Job(
                fixture.config.getUploadDir().resolve("tmp/queued.part"), queuedMeta)));

        byte[] content = "queue offer failure".getBytes(StandardCharsets.UTF_8);
        UploadMeta meta = metadata("P1", "failed.mp4", content.length, sha256(content));
        RecordingUploadObserver response = new RecordingUploadObserver();
        StreamObserver<UploadChunk> request = fixture.service.upload(response);
        request.onNext(metaChunk(meta));
        request.onNext(dataChunk(content));
        request.onCompleted();

        assertNotNull(response.error);
        assertEquals(1, fixture.bucket.getAvailableSlots());
        assertEquals(0, uploadPartCount(fixture));
        assertTrue(fixture.dedupeRegistry.reservePending(meta.getSha256()));
        fixture.dedupeRegistry.releasePending(meta.getSha256());
    }

    @Test
    void twentyClientCancellationsLeaveEverySlotAndNoPartFiles() throws Exception {
        fixture = newFixture(1, 5, 1, 0, true, false, new CopyingMediaProcessor(), true);

        for (int i = 0; i < 20; i++) {
            byte[] content = ("cancellation " + i).getBytes(StandardCharsets.UTF_8);
            UploadMeta meta = metadata("P1", "cancel-" + i + ".mp4", content.length, sha256(content));
            RecordingUploadObserver response = new RecordingUploadObserver();
            @SuppressWarnings("unchecked")
            ClientCallStreamObserver<UploadChunk> request =
                    (ClientCallStreamObserver<UploadChunk>) fixture.stub.upload(response);
            request.onNext(metaChunk(meta));

            await(() -> fixture.bucket.getAvailableSlots() == 4 && uploadPartCountUnchecked(fixture) == 1);
            request.onNext(dataChunk(
                    ByteString.copyFrom(content, 0, Math.max(1, content.length / 2)).toByteArray()));
            request.cancel("cancelled by verification test", null);

            await(() -> fixture.bucket.getAvailableSlots() == 5 && uploadPartCountUnchecked(fixture) == 0);
        }
        assertEquals(5, fixture.bucket.getAvailableSlots());
    }

    @Test
    void fiveSimultaneousProducersAtCapacityTwoReconcileEveryOutcome() throws Exception {
        fixture = newFixture(1, 2, 1, 1500, true, false, new CopyingMediaProcessor(), true);
        List<UploadSession> sessions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            byte[] content = ("producer " + i).getBytes(StandardCharsets.UTF_8);
            sessions.add(beginUpload(fixture, "P" + i, "producer-" + i + ".mp4", content));
        }

        await(() -> fixture.bucket.getAvailableSlots() == 0
                && sessions.stream().filter(session -> session.response.isDone()).count() >= 3);

        for (UploadSession session : sessions) {
            if (!session.response.isDone()) {
                session.request.onNext(dataChunk(session.content));
                session.request.onCompleted();
            }
        }

        long acceptedResponses = 0;
        long droppedResponses = 0;
        long duplicateResponses = 0;
        long rejectedResponses = 0;
        for (UploadSession session : sessions) {
            UploadResult result = session.response.get(WAIT_SECONDS, TimeUnit.SECONDS);
            switch (result.getStatus()) {
                case ACCEPTED -> acceptedResponses++;
                case DROPPED_QUEUE_FULL -> droppedResponses++;
                case DUPLICATE -> duplicateResponses++;
                case REJECTED_INVALID -> rejectedResponses++;
                default -> throw new AssertionError("Unexpected upload result: " + result.getStatus());
            }
        }
        long expectedSaved = acceptedResponses;
        await(() -> fixture.bucket.getSavedCount() == expectedSaved);

        long sent = sessions.size();
        assertEquals(5, sent);
        assertTrue(droppedResponses > 0);
        assertEquals(acceptedResponses, fixture.bucket.getAcceptedCount());
        assertEquals(droppedResponses, fixture.bucket.getDroppedCount());
        assertEquals(duplicateResponses, fixture.bucket.getDuplicateCount());
        assertEquals(rejectedResponses, fixture.bucket.getRejectedCount());
        assertEquals(sent, fixture.bucket.getSavedCount()
                + fixture.bucket.getDroppedCount()
                + fixture.bucket.getDuplicateCount()
                + fixture.bucket.getRejectedCount());
    }

    @Test
    void simultaneousSameFilenameUploadsAreStoredWithDifferentNames() throws Exception {
        fixture = newFixture(2, 5, 5, 0, true, false, new CopyingMediaProcessor(), true);
        byte[] first = "first contents".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second contents".getBytes(StandardCharsets.UTF_8);
        UploadSession firstUpload = beginUpload(fixture, "P1", "same_name.mp4", first);
        UploadSession secondUpload = beginUpload(fixture, "P2", "same_name.mp4", second);
        firstUpload.request.onNext(dataChunk(first));
        secondUpload.request.onNext(dataChunk(second));
        firstUpload.request.onCompleted();
        secondUpload.request.onCompleted();

        assertEquals(UploadStatus.ACCEPTED, firstUpload.response.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatus());
        assertEquals(UploadStatus.ACCEPTED, secondUpload.response.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatus());
        await(() -> fixture.bucket.getSavedCount() == 2);

        List<String> storedNames = fixture.catalog.list().stream()
                .filter(video -> video.getName().equals("same_name.mp4"))
                .map(video -> Path.of(video.getVideoPath()).getFileName().toString())
                .toList();
        assertEquals(2, storedNames.size());
        assertNotEquals(storedNames.get(0), storedNames.get(1));
        for (String name : storedNames) {
            assertTrue(Files.isRegularFile(fixture.config.getUploadDir().resolve(name)));
        }
    }

    @Test
    void exactDuplicateHashReturnsDuplicateBeforeAnyDataIsSent() throws Exception {
        fixture = newFixture(1, 4, 4, 0, true, false, new CopyingMediaProcessor(), true);
        byte[] content = "same hash content".getBytes(StandardCharsets.UTF_8);
        UploadResult first = upload(fixture, "P1", "first.mp4", content);
        assertEquals(UploadStatus.ACCEPTED, first.getStatus());
        await(() -> fixture.bucket.getSavedCount() == 1);

        UploadSession duplicate = beginUpload(fixture, "P2", "second.mp4", content);
        UploadResult result = duplicate.response.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(UploadStatus.DUPLICATE, result.getStatus());
        assertEquals(1, fixture.bucket.getDuplicateCount());
        assertEquals(0, uploadPartCount(fixture));
        assertEquals(1, fixture.catalog.size());
        assertEquals(4, fixture.bucket.getAvailableSlots());
    }

    @Test
    void workerContinuesAfterProbeFailureAndRejectsTheCorruptJob() throws Exception {
        AtomicInteger probeCalls = new AtomicInteger();
        MediaProcessor processor = new CopyingMediaProcessor() {
            @Override
            public double probeDuration(Path src) throws MediaException {
                if (probeCalls.getAndIncrement() == 0) {
                    throw new MediaException("corrupt media");
                }
                return 5.0;
            }
        };
        fixture = newFixture(1, 2, 2, 0, true, false, processor, false);
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<?> worker = workerExecutor.submit(new ConsumerWorker(
                    fixture.bucket,
                    fixture.config,
                    fixture.dedupeRegistry,
                    fixture.catalog,
                    processor));

            UploadMeta corrupt = enqueueJob(fixture, "corrupt.mp4", "corrupt bytes");
            await(() -> fixture.bucket.getRejectedCount() == 1
                    && regularUploadFileCountUnchecked(fixture) == 0);
            assertTrue(fixture.dedupeRegistry.reservePending(corrupt.getSha256()));
            fixture.dedupeRegistry.releasePending(corrupt.getSha256());

            enqueueJob(fixture, "good.mp4", "good bytes");
            await(() -> fixture.bucket.getSavedCount() == 1);
            assertEquals(1, fixture.catalog.size());
            assertFalse(worker.isDone());
        } finally {
            workerExecutor.shutdownNow();
            workerExecutor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    void mediaProcessorProducesTenSecondPreviewThumbnailAndCompressedOutputAtomically() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        MediaProcessor processor = new MediaProcessor(command -> {
            commands.add(List.copyOf(command));
            if (command.get(0).equals("ffprobe")) {
                return "12.5\n";
            }
            try {
                Files.writeString(Path.of(command.get(command.size() - 1)), "encoded output");
            } catch (IOException e) {
                throw new MediaException("Unable to create fake media output", e);
            }
            return "";
        });
        Path source = tempDir.resolve("source.mp4");
        Path preview = tempDir.resolve("preview.mp4");
        Path thumbnail = tempDir.resolve("thumbnail.jpg");
        Path compressed = tempDir.resolve("compressed.mp4");
        Files.writeString(source, "source video");

        assertEquals(12.5, processor.probeDuration(source));
        processor.makePreview(source, preview);
        assertEquals("10", argumentAfter(commands.get(1), "-t"));
        assertTrue(commands.get(1).contains("-an"));
        assertTrue(Files.readString(preview).contains("encoded output"));

        processor.makeThumbnail(source, thumbnail);
        List<String> thumbnailCommand = commands.get(commands.size() - 1);
        assertEquals("1", argumentAfter(thumbnailCommand, "-ss"));
        assertEquals("1", argumentAfter(thumbnailCommand, "-frames:v"));
        assertTrue(Files.exists(thumbnail));

        long[] sizes = processor.compress(source, compressed, 28);
        List<String> compressCommand = commands.get(commands.size() - 1);
        assertEquals("28", argumentAfter(compressCommand, "-crf"));
        assertArrayEquals(new long[] {Files.size(source), Files.size(compressed)}, sizes);
        assertEquals(0, temporaryMediaFileCount(tempDir));
    }

    @Test
    void disablingDedupeAllowsRepeatedHashes() throws Exception {
        fixture = newFixture(1, 2, 2, 0, false, false, new CopyingMediaProcessor(), false);
        byte[] content = "dedupe disabled".getBytes(StandardCharsets.UTF_8);

        assertEquals(UploadStatus.ACCEPTED,
                directComplete(fixture, "P1", "one.mp4", content).result.getStatus());
        assertEquals(UploadStatus.ACCEPTED,
                directComplete(fixture, "P2", "two.mp4", content).result.getStatus());
        assertEquals(0, fixture.bucket.getDuplicateCount());
        assertEquals(2, fixture.bucket.getAcceptedCount());
    }

    private Fixture newFixture(
            int c,
            int q,
            long maxUploadMb,
            long workerDelayMs,
            boolean enableDedup,
            boolean enableCompress,
            MediaProcessor processor,
            boolean startGrpc) throws Exception {
        return newFixture(c, q, maxUploadMb, workerDelayMs, enableDedup,
                enableCompress, processor, startGrpc, new DedupeRegistry());
    }

    private Fixture newFixture(
            int c,
            int q,
            long maxUploadMb,
            long workerDelayMs,
            boolean enableDedup,
            boolean enableCompress,
            MediaProcessor processor,
            boolean startGrpc,
            DedupeRegistry registry) throws Exception {
        Path root = tempDir.resolve("fixture-" + System.nanoTime());
        ConsumerConfig config = ConsumerConfig.parse(new String[] {
                "--c", Integer.toString(c),
                "--q", Integer.toString(q),
                "--grpc-port", Integer.toString(availablePort()),
                "--upload-dir", root.resolve("uploads").toString(),
                "--preview-dir", root.resolve("previews").toString(),
                "--max-upload-size-mb", Long.toString(maxUploadMb),
                "--enable-dedup", Boolean.toString(enableDedup),
                "--enable-compress", Boolean.toString(enableCompress),
                "--worker-delay-ms", Long.toString(workerDelayMs)
        });
        LeakyBucket bucket = new LeakyBucket(q);
        DefaultVideoCatalog catalog = new DefaultVideoCatalog();
        MediaUploadServiceImpl service = new MediaUploadServiceImpl(config, registry, bucket);
        Fixture created = new Fixture(config, registry, bucket, catalog, service);
        if (startGrpc) {
            created.runtime = new ConsumerRuntime(
                    config, bucket, registry, catalog, processor, service).start();
            created.channel = ManagedChannelBuilder
                    .forAddress("127.0.0.1", config.getGrpcPort())
                    .usePlaintext()
                    .build();
            created.stub = MediaUploadServiceGrpc.newStub(created.channel);
        }
        return created;
    }

    private UploadMeta enqueueJob(Fixture target, String filename, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        UploadMeta meta = metadata("P1", filename, bytes.length, sha256(bytes));
        Path tempPart = target.config.getUploadDir().resolve("tmp").resolve(filename + ".part");
        Files.write(tempPart, bytes);
        assertTrue(target.dedupeRegistry.reservePending(meta.getSha256()));
        assertTrue(target.bucket.tryReserve());
        assertTrue(target.bucket.enqueue(new Job(tempPart, meta)));
        return meta;
    }

    private static UploadSession beginUpload(
            Fixture target,
            String producerId,
            String filename,
            byte[] content) throws Exception {
        RecordingUploadObserver response = new RecordingUploadObserver();
        StreamObserver<UploadChunk> request = target.stub.upload(response);
        request.onNext(metaChunk(metadata(producerId, filename, content.length, sha256(content))));
        return new UploadSession(request, response.future, content);
    }

    private static UploadResult upload(
            Fixture target,
            String producerId,
            String filename,
            byte[] content) throws Exception {
        UploadSession session = beginUpload(target, producerId, filename, content);
        session.request.onNext(dataChunk(content));
        session.request.onCompleted();
        return session.response.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static RecordingUploadObserver directComplete(
            Fixture target,
            String producerId,
            String filename,
            byte[] content) throws Exception {
        RecordingUploadObserver response = new RecordingUploadObserver();
        StreamObserver<UploadChunk> request = target.service.upload(response);
        request.onNext(metaChunk(metadata(producerId, filename, content.length, sha256(content))));
        request.onNext(dataChunk(content));
        request.onCompleted();
        return response;
    }

    private static UploadMeta metadata(String producerId, String filename, long size, String sha256) {
        return UploadMeta.newBuilder()
                .setProducerId(producerId)
                .setFilename(filename)
                .setSizeBytes(size)
                .setSha256(sha256)
                .build();
    }

    private static UploadChunk metaChunk(UploadMeta meta) {
        return UploadChunk.newBuilder().setMeta(meta).build();
    }

    private static UploadChunk dataChunk(byte[] bytes) {
        return UploadChunk.newBuilder().setData(ByteString.copyFrom(bytes)).build();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void assertNoUploadResources(Fixture target, int expectedSlots) throws IOException {
        assertEquals(expectedSlots, target.bucket.getAvailableSlots());
        assertEquals(0, uploadPartCount(target));
    }

    private static long uploadPartCount(Fixture target) throws IOException {
        try (Stream<Path> paths = Files.list(target.config.getUploadDir().resolve("tmp"))) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".part")).count();
        }
    }

    private static long uploadPartCountUnchecked(Fixture target) {
        try {
            return uploadPartCount(target);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static long regularUploadFileCount(Fixture target) throws IOException {
        try (Stream<Path> paths = Files.list(target.config.getUploadDir())) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static long regularUploadFileCountUnchecked(Fixture target) {
        try {
            return regularUploadFileCount(target);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static long temporaryMediaFileCount(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().contains(".tmp")).count();
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "Condition not reached within " + WAIT_SECONDS + " seconds");
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String argumentAfter(List<String> command, String option) {
        return command.get(command.indexOf(option) + 1);
    }

    private static final class RecordingUploadObserver implements StreamObserver<UploadResult> {
        private final CompletableFuture<UploadResult> future = new CompletableFuture<>();
        private UploadResult result;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(UploadResult value) {
            result = value;
            future.complete(value);
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            future.completeExceptionally(throwable);
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }

    private static final class RecordingQueueStatusObserver implements StreamObserver<QueueStatusResponse> {
        private QueueStatusResponse result;

        @Override
        public void onNext(QueueStatusResponse value) {
            result = value;
        }

        @Override
        public void onError(Throwable error) {
            throw new AssertionError(error);
        }

        @Override
        public void onCompleted() {
        }
    }

    private static class CopyingMediaProcessor extends MediaProcessor {
        @Override
        public double probeDuration(Path src) throws MediaException {
            return 5.0;
        }

        @Override
        public void makePreview(Path src, Path dst) throws MediaException {
            copy(src, dst);
        }

        @Override
        public void makeThumbnail(Path src, Path dst) throws MediaException {
            copy(src, dst);
        }

        @Override
        public long[] compress(Path src, Path dst, int crf) throws MediaException {
            try {
                long oldSize = Files.size(src);
                copy(src, dst);
                return new long[] {oldSize, Files.size(dst)};
            } catch (IOException e) {
                throw new MediaException("Unable to copy test media", e);
            }
        }

        private static void copy(Path src, Path dst) throws MediaException {
            try {
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new MediaException("Unable to copy test media", e);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ConsumerConfig config;
        private final DedupeRegistry dedupeRegistry;
        private final LeakyBucket bucket;
        private final DefaultVideoCatalog catalog;
        private final MediaUploadServiceImpl service;
        private ConsumerRuntime runtime;
        private ManagedChannel channel;
        private MediaUploadServiceGrpc.MediaUploadServiceStub stub;

        private Fixture(
                ConsumerConfig config,
                DedupeRegistry dedupeRegistry,
                LeakyBucket bucket,
                DefaultVideoCatalog catalog,
                MediaUploadServiceImpl service) {
            this.config = config;
            this.dedupeRegistry = dedupeRegistry;
            this.bucket = bucket;
            this.catalog = catalog;
            this.service = service;
        }

        @Override
        public void close() throws Exception {
            if (channel != null) {
                channel.shutdownNow();
                channel.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
            }
            if (runtime != null) {
                runtime.close();
            }
        }
    }

    private record UploadSession(
            StreamObserver<UploadChunk> request,
            CompletableFuture<UploadResult> response,
            byte[] content) {
    }
}
