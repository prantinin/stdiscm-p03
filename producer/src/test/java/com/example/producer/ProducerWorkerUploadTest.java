package com.example.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.media.proto.*;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.Status;

class ProducerWorkerUploadTest {

    private Server server;
    private ManagedChannel channel;
    private GrpcUploadClient client;
    private List<UploadStatus> responseStatuses = new ArrayList<>(List.of(UploadStatus.ACCEPTED));
    private int responseIndex = 0;
    private int unavailableResponses = 0;
    private int uploadAttemptCount = 0;
    private int queueStatusCallCount = 0;
    private int fullQueueResponses = 0;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {

        server = ServerBuilder.forPort(0)
                .addService(new MediaUploadServiceGrpc.MediaUploadServiceImplBase() {

                    @Override
                    public void getQueueStatus(
                            QueueStatusRequest request,
                            StreamObserver<QueueStatusResponse> responseObserver) {

                        queueStatusCallCount++;

                        boolean full = queueStatusCallCount <= fullQueueResponses;

                        responseObserver.onNext(
                                QueueStatusResponse.newBuilder()
                                        .setQueueLength(full ? 10 : 2)
                                        .setQueueCapacity(10)
                                        .setFull(full)
                                        .build()
                        );

                        responseObserver.onCompleted();
                    }

                    @Override
                    public StreamObserver<UploadChunk> upload(
                            StreamObserver<UploadResult> responseObserver) {

                        return new StreamObserver<UploadChunk>() {

                            @Override
                            public void onNext(UploadChunk chunk) {
                                // Receive uploaded chunks.
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                responseObserver.onError(throwable);
                            }

                            @Override
                            public void onCompleted() {
                                uploadAttemptCount++;

                                if (uploadAttemptCount <= unavailableResponses) {
                                    responseObserver.onError(
                                            Status.UNAVAILABLE.asRuntimeException()
                                    );
                                    return;
                                }
                                
                                int index = Math.min(responseIndex++, responseStatuses.size() - 1);
                                UploadStatus status = responseStatuses.get(index);

                                responseObserver.onNext(
                                        UploadResult.newBuilder()
                                                .setStatus(status)
                                                .setMessage("Mock upload response")
                                                .setStoredName("test-video.mp4")
                                                .setRetryAfterMs(1)
                                                .build()
                                );

                                responseObserver.onCompleted();
                            }
                        };
                    }
                })
                .build()
                .start();

        channel = ManagedChannelBuilder
                .forAddress("localhost", server.getPort())
                .usePlaintext()
                .build();

        client = new GrpcUploadClient(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }

        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldProcessAndUploadVideoSuccessfully() throws Exception {

        Path producerFolder = tempDir.resolve("producer1");
        Files.createDirectories(producerFolder);

        Path video = producerFolder.resolve("sample.mp4");
        byte[] videoData = new byte[1000];
        Files.write(video, videoData);

        ProducerStats stats = new ProducerStats();

        ProducerWorker worker = new ProducerWorker(
                "P1",
                producerFolder,
                client,
                stats
        );

        worker.run();

        assertEquals(1, stats.getSent());
        assertEquals(1, stats.getAccepted());
        assertEquals(0, stats.getDropped());
        assertEquals(0, stats.getDuplicates());
        assertEquals(0, stats.getFailed());
        assertEquals(1000, stats.getBytesSent());
    }

    @Test
    void shouldIgnoreUnsupportedFiles() throws Exception {

        Path producerFolder = tempDir.resolve("producer2");
        Files.createDirectories(producerFolder);

        Files.writeString(
                producerFolder.resolve("document.pdf"),
                "Not a video"
        );

        ProducerStats stats = new ProducerStats();

        ProducerWorker worker = new ProducerWorker(
                "P2",
                producerFolder,
                client,
                stats
        );

        worker.run();

        assertEquals(0, stats.getSent());
        assertEquals(0, stats.getAccepted());
        assertEquals(0, stats.getFailed());
    }

    @Test
    void shouldRecordDuplicateUpload() throws Exception {

        responseStatuses = new ArrayList<>(List.of(UploadStatus.DUPLICATE));
        responseIndex = 0;

        Path producerFolder = tempDir.resolve("producer3");
        Files.createDirectories(producerFolder);

        Path video = producerFolder.resolve("duplicate.mp4");
        Files.write(video, new byte[1000]);

        ProducerStats stats = new ProducerStats();

        ProducerWorker worker = new ProducerWorker(
                "P3",
                producerFolder,
                client,
                stats
        );

        worker.run();

        assertEquals(1, stats.getSent());
        assertEquals(0, stats.getAccepted());
        assertEquals(0, stats.getDropped());
        assertEquals(1, stats.getDuplicates());
        assertEquals(0, stats.getFailed());
        assertEquals(1000, stats.getBytesSent());
    }

    @Test
    void shouldRecordDroppedUpload() throws Exception {

        responseStatuses = new ArrayList<>(List.of(UploadStatus.DROPPED_QUEUE_FULL));
        responseIndex = 0;

        Path producerFolder = tempDir.resolve("producer4");
        Files.createDirectories(producerFolder);

        Path video = producerFolder.resolve("dropped.mp4");
        Files.write(video, new byte[1000]);

        ProducerStats stats = new ProducerStats();

        ProducerWorker worker = new ProducerWorker(
                "P4",
                producerFolder,
                client,
                stats
        );

        worker.run();

        assertEquals(1, stats.getSent());
        assertEquals(0, stats.getAccepted());
        assertEquals(1, stats.getDropped());
        assertEquals(0, stats.getDuplicates());
        assertEquals(0, stats.getFailed());
    }

    @Test
    void shouldRecordRejectedUpload() throws Exception {

        responseStatuses = new ArrayList<>(List.of(UploadStatus.REJECTED_INVALID));
        responseIndex = 0;

        Path producerFolder = tempDir.resolve("producer5");
        Files.createDirectories(producerFolder);

        Path video = producerFolder.resolve("invalid.mp4");
        Files.write(video, new byte[1000]);

        ProducerStats stats = new ProducerStats();

        ProducerWorker worker = new ProducerWorker(
                "P5",
                producerFolder,
                client,
                stats
        );

        worker.run();

        assertEquals(1, stats.getSent());
        assertEquals(0, stats.getAccepted());
        assertEquals(0, stats.getDropped());
        assertEquals(0, stats.getDuplicates());
        assertEquals(1, stats.getFailed());
    }

    @Test
    void shouldRetryQueueFullAndAcceptUpload() throws Exception {
        responseStatuses = new ArrayList<>(List.of(
                UploadStatus.DROPPED_QUEUE_FULL,
                UploadStatus.ACCEPTED
        ));

        Path video = tempDir.resolve("sample.mp4");
        Files.write(video, new byte[1000]);

        ProducerStats stats = new ProducerStats();

        ProducerWorker worker = new ProducerWorker(
                "P1",
                tempDir,
                client,
                stats
        );

        worker.run();

        assertEquals(1, stats.getSent());
        assertEquals(1, stats.getAccepted());
        assertEquals(0, stats.getDropped());
        assertEquals(0, stats.getDuplicates());
        assertEquals(0, stats.getFailed());
        assertEquals(2000, stats.getBytesSent());
    }

    @Test
    void shouldRetryNetworkFailureAndAcceptUpload() throws Exception {
        unavailableResponses = 1;

        responseStatuses = new ArrayList<>(
                List.of(UploadStatus.ACCEPTED)
        );

        responseIndex = 0;
        uploadAttemptCount = 0;

        Path video = tempDir.resolve("sample.mp4");
        Files.write(video, new byte[1000]);

        ProducerStats stats = new ProducerStats();

        ProducerWorker worker = new ProducerWorker(
                "P1",
                tempDir,
                client,
                stats
        );

        worker.run();

        assertEquals(1, stats.getSent());
        assertEquals(1, stats.getAccepted());
        assertEquals(0, stats.getDropped());
        assertEquals(0, stats.getDuplicates());
        assertEquals(0, stats.getFailed());

        assertEquals(2, uploadAttemptCount);
    }

    @Test
    void shouldSkipUploadWhenQueueIsFullAndSkipModeIsEnabled() throws Exception {
        responseStatuses = new ArrayList<>(
                List.of(UploadStatus.DROPPED_QUEUE_FULL)
        );

        responseIndex = 0;
        uploadAttemptCount = 0;

        Path video = tempDir.resolve("skip-test.mp4");
        Files.write(video, new byte[1000]);

        ProducerStats stats = new ProducerStats();

        ProducerWorker worker = new ProducerWorker(
                "P1",
                tempDir,
                client,
                stats,
                "skip"
        );

        worker.run();

        assertEquals(1, stats.getSent());
        assertEquals(0, stats.getAccepted());
        assertEquals(1, stats.getDropped());
        assertEquals(0, stats.getDuplicates());
        assertEquals(0, stats.getFailed());

        // Only one upload attempt should happen.
        assertEquals(1, uploadAttemptCount);
    }

    @Test
    void shouldRetryUploadWhenQueueIsFullAndRetryModeIsEnabled() throws Exception {
        responseStatuses = new ArrayList<>(List.of(
                UploadStatus.DROPPED_QUEUE_FULL,
                UploadStatus.ACCEPTED
        ));

        responseIndex = 0;
        uploadAttemptCount = 0;

        Path video = tempDir.resolve("retry-test.mp4");
        Files.write(video, new byte[1000]);

        ProducerStats stats = new ProducerStats();

        ProducerWorker worker = new ProducerWorker(
                "P1",
                tempDir,
                client,
                stats,
                "retry"
        );

        worker.run();

        assertEquals(1, stats.getSent());
        assertEquals(1, stats.getAccepted());
        assertEquals(0, stats.getDropped());
        assertEquals(0, stats.getDuplicates());
        assertEquals(0, stats.getFailed());

        // First attempt was dropped; second attempt was accepted.
        assertEquals(2, uploadAttemptCount);
    }

    @Test
    void shouldWaitForQueueSpaceBeforeUploading() throws Exception {
        fullQueueResponses = 2;
        queueStatusCallCount = 0;

        responseStatuses = new ArrayList<>(
                List.of(UploadStatus.ACCEPTED)
        );

        responseIndex = 0;
        uploadAttemptCount = 0;

        Path video = tempDir.resolve("backpressure-test.mp4");
        Files.write(video, new byte[1000]);

        ProducerStats stats = new ProducerStats();

        ProducerWorker worker = new ProducerWorker(
                "P1",
                tempDir,
                client,
                stats,
                "retry",
                true
        );

        worker.run();

        assertEquals(3, queueStatusCallCount);
        assertEquals(1, uploadAttemptCount);
        assertEquals(1, stats.getAccepted());
        assertEquals(0, stats.getDropped());
        assertEquals(0, stats.getFailed());
    }
}
