package com.example.media.consumer;

import com.example.media.consumer.catalog.DefaultVideoCatalog;
import com.example.media.consumer.dedupe.DedupeRegistry;
import com.example.media.consumer.media.MediaProcessor;
import com.example.media.consumer.queue.LeakyBucket;
import com.example.media.consumer.server.ConsumerRuntime;
import com.example.media.consumer.server.MediaUploadServiceImpl;
import com.example.media.proto.MediaUploadServiceGrpc;
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

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerIntegrationTest {
    private static final long WAIT_SECONDS = 10;

    @TempDir
    Path tempDir;

    private ConsumerConfig config;
    private DedupeRegistry dedupeRegistry;
    private LeakyBucket leakyBucket;
    private DefaultVideoCatalog videoCatalog;
    private ConsumerRuntime consumerRuntime;
    private ManagedChannel channel;
    private MediaUploadServiceGrpc.MediaUploadServiceStub stub;

    @AfterEach
    void stopRuntime() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        if (consumerRuntime != null) {
            consumerRuntime.close();
        }
    }

    @Test
    void twentyMidStreamCancellationsReleaseEverySlotAndTemporaryFile() throws Exception {
        startRuntime(2, 5, 0);

        for (int i = 0; i < 20; i++) {
            byte[] content = ("partial upload " + i).getBytes(StandardCharsets.UTF_8);
            UploadMeta meta = metadata("P1", "cancel-" + i + ".mp4", content);
            RecordingObserver response = new RecordingObserver();
            @SuppressWarnings("unchecked")
            ClientCallStreamObserver<UploadChunk> request =
                    (ClientCallStreamObserver<UploadChunk>) stub.upload(response);

            request.onNext(metaChunk(meta));
            await(() -> leakyBucket.getAvailableSlots() == 4 && tempFileCount() == 1);
            request.onNext(dataChunk(Arrays.copyOf(content, content.length / 2)));
            request.cancel("integration-test cancellation", null);

            await(() -> leakyBucket.getAvailableSlots() == 5 && tempFileCount() == 0);
            assertTrue(dedupeRegistry.reservePending(meta.getSha256()));
            dedupeRegistry.releasePending(meta.getSha256());
        }
    }

    @Test
    void fiveProducersWithCapacityTwoReconcileSavesAndDrops() throws Exception {
        startRuntime(1, 2, 0);
        UploadSession[] heldOpen = new UploadSession[2];

        for (int i = 0; i < heldOpen.length; i++) {
            byte[] content = ("held upload " + i).getBytes(StandardCharsets.UTF_8);
            heldOpen[i] = beginUpload("P" + i, "held-" + i + ".mp4", content);
            heldOpen[i].request.onNext(dataChunk(content));
        }
        await(() -> leakyBucket.getAvailableSlots() == 0 && tempFileCount() == 2);

        int dropped = 0;
        for (int i = 2; i < 5; i++) {
            byte[] content = ("dropped upload " + i).getBytes(StandardCharsets.UTF_8);
            UploadSession droppedSession = beginUpload("P" + i, "drop-" + i + ".mp4", content);
            UploadResult result = droppedSession.response.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(UploadStatus.DROPPED_QUEUE_FULL, result.getStatus());
            dropped++;
        }

        for (UploadSession session : heldOpen) {
            session.request.onCompleted();
            assertEquals(UploadStatus.ACCEPTED, session.response.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatus());
        }
        await(() -> leakyBucket.getSavedCount() == 2);

        long sent = 5;
        assertEquals(3, dropped);
        assertEquals(sent, leakyBucket.getSavedCount() + leakyBucket.getDroppedCount());
        assertEquals(2, leakyBucket.getAcceptedCount());
    }

    @Test
    void simultaneousIdenticalFilenamesAreSavedWithDistinctStoredNames() throws Exception {
        startRuntime(2, 5, 0);
        byte[] first = "first video data".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second video data".getBytes(StandardCharsets.UTF_8);

        UploadSession firstSession = beginUpload("P1", "same-name.mp4", first);
        UploadSession secondSession = beginUpload("P1", "same-name.mp4", second);
        firstSession.request.onNext(dataChunk(first));
        secondSession.request.onNext(dataChunk(second));
        firstSession.request.onCompleted();
        secondSession.request.onCompleted();
        assertEquals(UploadStatus.ACCEPTED,
                firstSession.response.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatus());
        assertEquals(UploadStatus.ACCEPTED,
                secondSession.response.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatus());
        await(() -> leakyBucket.getSavedCount() == 2);

        List<String> storedNames = videoCatalog.list().stream()
                .filter(item -> item.getName().equals("same-name.mp4"))
                .map(item -> Path.of(item.getVideoPath()).getFileName().toString())
                .toList();
        assertEquals(2, storedNames.size());
        assertNotEquals(storedNames.get(0), storedNames.get(1));
        for (String storedName : storedNames) {
            assertTrue(Files.isRegularFile(config.getUploadDir().resolve(storedName)));
        }
    }

    @Test
    void duplicateShaIsRejectedEvenWhenFirstUploadIsStillQueued() throws Exception {
        startRuntime(1, 5, 0);
        byte[] content = "duplicate video bytes".getBytes(StandardCharsets.UTF_8);

        assertEquals(UploadStatus.ACCEPTED, upload("P1", "first.mp4", content).getStatus());
        UploadSession duplicate = beginUpload("P2", "second.mp4", content);

        UploadResult result = duplicate.response.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(UploadStatus.DUPLICATE, result.getStatus());
        assertEquals(1, leakyBucket.getDuplicateCount());
        await(() -> leakyBucket.getSavedCount() == 1);
        assertEquals(1, videoCatalog.size());
    }

    @Test
    void compressionReplacesStoredFileAndRemovesItsTemporaryOutput() throws Exception {
        startRuntime(1, 2, 0, true);
        byte[] content = "compressible media data".getBytes(StandardCharsets.UTF_8);

        assertEquals(UploadStatus.ACCEPTED, upload("P1", "compressed.mp4", content).getStatus());
        await(() -> leakyBucket.getSavedCount() == 1);

        Path storedPath = Path.of(videoCatalog.list().get(0).getVideoPath());
        assertArrayEquals(content, Files.readAllBytes(storedPath));
        assertEquals(0, tempFileCount());
    }

    private void startRuntime(int workerCount, int capacity, long workerDelayMs) throws Exception {
        startRuntime(workerCount, capacity, workerDelayMs, false);
    }

    private void startRuntime(
            int workerCount,
            int capacity,
            long workerDelayMs,
            boolean enableCompress) throws Exception {
        config = ConsumerConfig.parse(new String[] {
                "--c", Integer.toString(workerCount),
                "--q", Integer.toString(capacity),
                "--upload-dir", tempDir.resolve("uploads").toString(),
                "--preview-dir", tempDir.resolve("previews").toString(),
                "--grpc-port", Integer.toString(findAvailablePort()),
                "--worker-delay-ms", Long.toString(workerDelayMs),
                "--enable-compress", Boolean.toString(enableCompress)
        });
        dedupeRegistry = new DedupeRegistry();
        leakyBucket = new LeakyBucket(capacity);
        videoCatalog = new DefaultVideoCatalog();
        MediaProcessor mediaProcessor = new MediaProcessor();
        MediaUploadServiceImpl service = new MediaUploadServiceImpl(config, dedupeRegistry, leakyBucket);

        consumerRuntime = new ConsumerRuntime(
                config,
                leakyBucket,
                dedupeRegistry,
                videoCatalog,
                mediaProcessor,
                service).start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", config.getGrpcPort())
                .usePlaintext()
                .build();
        stub = MediaUploadServiceGrpc.newStub(channel);
    }

    private UploadResult upload(String producerId, String filename, byte[] content) throws Exception {
        UploadSession session = beginUpload(producerId, filename, content);
        session.request.onNext(dataChunk(content));
        session.request.onCompleted();
        return session.response.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private UploadSession beginUpload(String producerId, String filename, byte[] content) throws Exception {
        RecordingObserver response = new RecordingObserver();
        StreamObserver<UploadChunk> request = stub.upload(response);
        request.onNext(metaChunk(metadata(producerId, filename, content)));
        return new UploadSession(request, response.future);
    }

    private static UploadMeta metadata(String producerId, String filename, byte[] content) throws Exception {
        return UploadMeta.newBuilder()
                .setProducerId(producerId)
                .setFilename(filename)
                .setSizeBytes(content.length)
                .setSha256(sha256(content))
                .build();
    }

    private static UploadChunk metaChunk(UploadMeta meta) {
        return UploadChunk.newBuilder().setMeta(meta).build();
    }

    private static UploadChunk dataChunk(byte[] content) {
        return UploadChunk.newBuilder().setData(ByteString.copyFrom(content)).build();
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private int tempFileCount() {
        Path tempPath = config.getUploadDir().resolve("tmp");
        try (Stream<Path> files = Files.list(tempPath)) {
            return (int) files.count();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to inspect upload temp directory", e);
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
        assertTrue(condition.getAsBoolean(), "Condition was not satisfied within " + WAIT_SECONDS + " seconds");
    }

    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class RecordingObserver implements StreamObserver<UploadResult> {
        private final CompletableFuture<UploadResult> future = new CompletableFuture<>();

        @Override
        public void onNext(UploadResult value) {
            future.complete(value);
        }

        @Override
        public void onError(Throwable error) {
            future.completeExceptionally(error);
        }

        @Override
        public void onCompleted() {
        }
    }

    private record UploadSession(
            StreamObserver<UploadChunk> request,
            CompletableFuture<UploadResult> response) {
    }
}
