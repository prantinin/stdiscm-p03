package com.example.producer;

import com.example.media.proto.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GrpcUploadClientTest {

    private Server server;
    private GrpcUploadClient client;
    private io.grpc.ManagedChannel channel;

    private final List<UploadChunk> receivedChunks = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {

        server = ServerBuilder.forPort(0)
                .addService(new MediaUploadServiceGrpc.MediaUploadServiceImplBase() {

                    @Override
                    public StreamObserver<UploadChunk> upload(
                            StreamObserver<UploadResult> responseObserver) {

                        return new StreamObserver<UploadChunk>() {

                            @Override
                            public void onNext(UploadChunk chunk) {
                                receivedChunks.add(chunk);
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                responseObserver.onError(throwable);
                            }

                            @Override
                            public void onCompleted() {
                                responseObserver.onNext(
                                        UploadResult.newBuilder()
                                                .setStatus(UploadStatus.ACCEPTED)
                                                .setMessage("Upload successful")
                                                .setStoredName("test-video.mp4")
                                                .build()
                                );

                                responseObserver.onCompleted();
                            }
                        };
                    }

                    @Override
                    public void getQueueStatus(
                            QueueStatusRequest request,
                            StreamObserver<QueueStatusResponse> responseObserver) {

                        responseObserver.onNext(
                                QueueStatusResponse.newBuilder()
                                        .setQueueLength(2)
                                        .setQueueCapacity(10)
                                        .setFull(false)
                                        .build()
                        );

                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();

        channel = io.grpc.ManagedChannelBuilder
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
    void uploadsVideoWithMetadataFirst() throws Exception {

        Path video = Files.createTempFile("test-video", ".mp4");

        try {
            byte[] data = new byte[1000];
            Files.write(video, data);

            UploadResult result = client.upload(
                    video,
                    "producer1",
                    "a".repeat(64)
            );

            assertEquals(UploadStatus.ACCEPTED, result.getStatus());
            assertEquals("test-video.mp4", result.getStoredName());

            assertFalse(receivedChunks.isEmpty());

            // First message must contain metadata.
            assertTrue(receivedChunks.get(0).hasMeta());

            UploadMeta meta = receivedChunks.get(0).getMeta();

            assertEquals("producer1", meta.getProducerId());
            assertEquals(video.getFileName().toString(), meta.getFilename());
            assertEquals(1000, meta.getSizeBytes());
            assertEquals("a".repeat(64), meta.getSha256());

            // Remaining messages must contain data.
            for (int i = 1; i < receivedChunks.size(); i++) {
                assertTrue(receivedChunks.get(i).hasData());
            }

        } finally {
            Files.deleteIfExists(video);
        }
    }

    @Test
    void splitsLargeVideoIntoChunks() throws Exception {

        Path video = Files.createTempFile("large-video", ".mp4");

        try {
            byte[] data = new byte[600 * 1024];
            Files.write(video, data);

            client.upload(video, "producer1", "b".repeat(64));

            // One metadata message followed by three data chunks.
            assertEquals(4, receivedChunks.size());

            assertTrue(receivedChunks.get(0).hasMeta());

            for (int i = 1; i < receivedChunks.size(); i++) {
                assertTrue(receivedChunks.get(i).hasData());
                assertTrue(
                        receivedChunks.get(i).getData().size()
                                <= 256 * 1024
                );
            }

        } finally {
            Files.deleteIfExists(video);
        }
    }

    @Test
    void uploadsEmptyVideoWithMetadataOnly() throws Exception {

        Path video = Files.createTempFile("empty-video", ".mp4");

        try {
            UploadResult result = client.upload(
                    video,
                    "producer1",
                    "c".repeat(64)
            );

            assertEquals(UploadStatus.ACCEPTED, result.getStatus());
            assertEquals(1, receivedChunks.size());
            assertTrue(receivedChunks.get(0).hasMeta());

        } finally {
            Files.deleteIfExists(video);
        }
    }

    @Test
    void retrievesQueueStatus() {

        QueueStatusResponse status = client.getQueueStatus();

        assertEquals(2, status.getQueueLength());
        assertEquals(10, status.getQueueCapacity());
        assertFalse(status.getFull());
    }

    @Test
    void rejectsMissingVideo() {

        Path missing = Path.of("does-not-exist.mp4");

        assertThrows(
                IllegalArgumentException.class,
                () -> client.upload(missing, "producer1", "a".repeat(64))
        );
    }
}
