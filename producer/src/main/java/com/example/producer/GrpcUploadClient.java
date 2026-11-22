package com.example.producer;

import com.example.media.proto.MediaUploadServiceGrpc;
import com.example.media.proto.UploadChunk;
import com.example.media.proto.UploadMeta;
import com.example.media.proto.UploadResult;
import com.example.media.proto.QueueStatusRequest;
import com.example.media.proto.QueueStatusResponse;

import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * gRPC client used by Producer workers to upload videos to the Consumer.
 */
public class GrpcUploadClient {

    private static final int CHUNK_SIZE = 256 * 1024;
    private static final long RPC_TIMEOUT_SECONDS = 120;

    private final ManagedChannel channel;
    private final MediaUploadServiceGrpc.MediaUploadServiceStub asyncStub;
    private final MediaUploadServiceGrpc.MediaUploadServiceBlockingStub blockingStub;

    public GrpcUploadClient(ManagedChannel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("Channel cannot be null");
        }

        this.channel = channel;
        this.asyncStub = MediaUploadServiceGrpc.newStub(channel);
        this.blockingStub = MediaUploadServiceGrpc.newBlockingStub(channel);
    }

    /**
     * Uploads a video using client-streaming RPC.
     * The metadata message is always sent first.
     */
    public UploadResult upload(
            Path video,
            String producerId,
            String sha256
    ) throws IOException, InterruptedException {

        if (video == null || !Files.isRegularFile(video)) {
            throw new IllegalArgumentException("Video file does not exist: " + video);
        }

        if (producerId == null || producerId.isBlank()) {
            throw new IllegalArgumentException("Producer ID cannot be blank");
        }

        if (sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException("SHA-256 cannot be blank");
        }

        long fileSize = Files.size(video);
        String filename = video.getFileName().toString();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<UploadResult> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        StreamObserver<UploadChunk> requestObserver =
                asyncStub
                        .withDeadlineAfter(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .upload(new StreamObserver<UploadResult>() {

                            @Override
                            public void onNext(UploadResult value) {
                                result.set(value);
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                error.set(throwable);
                                latch.countDown();
                            }

                            @Override
                            public void onCompleted() {
                                latch.countDown();
                            }
                        });

        try {
            UploadMeta metadata = UploadMeta.newBuilder()
                    .setProducerId(producerId)
                    .setFilename(filename)
                    .setSizeBytes(fileSize)
                    .setSha256(sha256)
                    .build();

            requestObserver.onNext(
                    UploadChunk.newBuilder()
                            .setMeta(metadata)
                            .build()
            );

            byte[] buffer = new byte[CHUNK_SIZE];

            try (InputStream input = Files.newInputStream(video)) {
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1) {
                    UploadChunk chunk = UploadChunk.newBuilder()
                            .setData(
                                    com.google.protobuf.ByteString.copyFrom(
                                            buffer, 0, bytesRead
                                    )
                            )
                            .build();

                    requestObserver.onNext(chunk);
                }
            }

            requestObserver.onCompleted();

        } catch (IOException | RuntimeException exception) {
            requestObserver.onError(exception);
            throw exception;
        }

        boolean completed = latch.await(
                RPC_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
        );

        if (!completed) {
            throw new IOException("Upload timed out");
        }

        if (error.get() != null) {
            throw new IOException("gRPC upload failed", error.get());
        }

        if (result.get() == null) {
            throw new IOException("Consumer returned no upload result");
        }

        return result.get();
    }

    /**
     * Retrieves the current Consumer queue status.
     */
    public QueueStatusResponse getQueueStatus() {
        return blockingStub
                .withDeadlineAfter(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .getQueueStatus(QueueStatusRequest.newBuilder().build());
    }

    public ManagedChannel getChannel() {
        return channel;
    }
}
