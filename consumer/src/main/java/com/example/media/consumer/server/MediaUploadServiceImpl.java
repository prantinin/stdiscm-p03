package com.example.media.consumer.server;

import com.example.media.consumer.ConsumerConfig;
import com.example.media.consumer.dedupe.DedupeRegistry;
import com.example.media.consumer.queue.Job;
import com.example.media.consumer.queue.LeakyBucket;
import com.example.media.proto.MediaUploadServiceGrpc;
import com.example.media.proto.QueueStatusRequest;
import com.example.media.proto.QueueStatusResponse;
import com.example.media.proto.UploadChunk;
import com.example.media.proto.UploadMeta;
import com.example.media.proto.UploadResult;
import com.example.media.proto.UploadStatus;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Receives streamed media uploads and admits complete jobs to the worker queue.
 */
public final class MediaUploadServiceImpl extends MediaUploadServiceGrpc.MediaUploadServiceImplBase {
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");
    private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;
    private static final String[] ALLOWED_EXTENSIONS = {".mp4", ".mkv", ".avi", ".mov", ".webm"};

    private final ConsumerConfig config;
    private final DedupeRegistry dedupeRegistry;
    private final LeakyBucket leakyBucket;
    private final long maxUploadBytes;

    public MediaUploadServiceImpl(
            ConsumerConfig config,
            DedupeRegistry dedupeRegistry,
            LeakyBucket leakyBucket) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.dedupeRegistry = Objects.requireNonNull(dedupeRegistry, "dedupeRegistry must not be null");
        this.leakyBucket = Objects.requireNonNull(leakyBucket, "leakyBucket must not be null");
        long maxMb = config.getMaxUploadSizeMb();
        this.maxUploadBytes = maxMb > Long.MAX_VALUE / BYTES_PER_MEGABYTE
                ? Long.MAX_VALUE
                : maxMb * BYTES_PER_MEGABYTE;
    }

    @Override
    public StreamObserver<UploadChunk> upload(StreamObserver<UploadResult> responseObserver) {
        return new StreamObserver<>() {
            private final AtomicBoolean terminal = new AtomicBoolean();
            private final AtomicBoolean cleanupStarted = new AtomicBoolean();
            private UploadMeta meta;
            private Path tempPartPath;
            private OutputStream outputStream;
            private MessageDigest digest;
            private long bytesReceived;
            private boolean slotReserved;
            private boolean pendingHashReserved;

            @Override
            public synchronized void onNext(UploadChunk chunk) {
                if (terminal.get()) {
                    return;
                }

                if (meta == null) {
                    if (chunk.getPayloadCase() != UploadChunk.PayloadCase.META) {
                        rejectInvalid("The first upload chunk must contain metadata.");
                        return;
                    }
                    beginUpload(chunk.getMeta());
                    return;
                }

                if (chunk.getPayloadCase() != UploadChunk.PayloadCase.DATA) {
                    rejectInvalid("Only data chunks are allowed after upload metadata.");
                    return;
                }
                writeData(chunk.getData());
            }

            private void beginUpload(UploadMeta uploadMeta) {
                if (!isValid(uploadMeta)) {
                    rejectInvalid("Upload metadata is invalid.");
                    return;
                }
                meta = uploadMeta;

                if (config.isEnableDedup()) {
                    if (!dedupeRegistry.reservePending(meta.getSha256())) {
                        if (terminal.compareAndSet(false, true)) {
                            leakyBucket.recordDuplicate();
                            respond(UploadStatus.DUPLICATE, "Duplicate upload.", "", 0);
                        }
                        return;
                    }
                    pendingHashReserved = true;
                }

                if (!leakyBucket.tryReserve()) {
                    if (terminal.compareAndSet(false, true)) {
                        finishAfterCleanup(
                                UploadStatus.DROPPED_QUEUE_FULL,
                                "Upload queue is full.",
                                "",
                                1000);
                    }
                    return;
                }
                slotReserved = true;

                try {
                    Path tempDirectory = config.getUploadDir().resolve("tmp");
                    Files.createDirectories(tempDirectory);
                    tempPartPath = tempDirectory.resolve(UUID.randomUUID() + ".part");
                    outputStream = Files.newOutputStream(tempPartPath);
                    digest = MessageDigest.getInstance("SHA-256");
                } catch (IOException | NoSuchAlgorithmException e) {
                    fail(e);
                }
            }

            private void writeData(ByteString data) {
                try (InputStream input = data.newInput()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (read > meta.getSizeBytes() - bytesReceived) {
                            rejectInvalid("Received more data than declared in upload metadata.");
                            return;
                        }
                        outputStream.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        bytesReceived += read;
                    }
                } catch (IOException e) {
                    fail(e);
                }
            }

            @Override
            public synchronized void onError(Throwable error) {
                if (!terminal.compareAndSet(false, true)) {
                    return;
                }
                leakyBucket.recordRejected();
                IOException cleanupError = cleanup();
                if (cleanupError != null) {
                    error.addSuppressed(cleanupError);
                }
                responseObserver.onError(error);
            }

            @Override
            public synchronized void onCompleted() {
                if (terminal.get()) {
                    return;
                }
                if (meta == null) {
                    rejectInvalid("Upload metadata is missing.");
                    return;
                }

                try {
                    outputStream.close();
                    outputStream = null;
                } catch (IOException e) {
                    fail(e);
                    return;
                }

                String actualSha256 = HexFormat.of().formatHex(digest.digest());
                if (bytesReceived != meta.getSizeBytes()
                        || !actualSha256.equalsIgnoreCase(meta.getSha256())) {
                    rejectInvalid("Uploaded size or SHA-256 does not match the metadata.");
                    return;
                }

                Job job = new Job(tempPartPath, meta);
                if (!leakyBucket.enqueue(job)) {
                    fail(new IOException("Reserved upload could not be enqueued."));
                    return;
                }

                slotReserved = false;
                pendingHashReserved = false;
                terminal.set(true);
                leakyBucket.recordAccepted();
                respond(UploadStatus.ACCEPTED, "Upload accepted.", "", 0);
            }

            private void rejectInvalid(String message) {
                if (!terminal.compareAndSet(false, true)) {
                    return;
                }
                leakyBucket.recordRejected();
                finishAfterCleanup(UploadStatus.REJECTED_INVALID, message, "", 0);
            }

            private void finishAfterCleanup(
                    UploadStatus status,
                    String message,
                    String storedName,
                    int retryAfterMs) {
                IOException cleanupError = cleanup();
                if (cleanupError != null) {
                    responseObserver.onError(cleanupError);
                    return;
                }
                respond(status, message, storedName, retryAfterMs);
            }

            private void fail(Throwable error) {
                if (!terminal.compareAndSet(false, true)) {
                    return;
                }
                leakyBucket.recordRejected();
                IOException cleanupError = cleanup();
                if (cleanupError != null) {
                    error.addSuppressed(cleanupError);
                }
                responseObserver.onError(error);
            }

            private IOException cleanup() {
                if (!cleanupStarted.compareAndSet(false, true)) {
                    return null;
                }

                IOException failure = null;
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        failure = e;
                    } finally {
                        outputStream = null;
                    }
                }

                if (tempPartPath != null) {
                    try {
                        Files.deleteIfExists(tempPartPath);
                    } catch (IOException e) {
                        if (failure == null) {
                            failure = e;
                        } else {
                            failure.addSuppressed(e);
                        }
                    }
                }

                if (slotReserved) {
                    slotReserved = false;
                    leakyBucket.releaseSlot();
                }
                if (pendingHashReserved) {
                    pendingHashReserved = false;
                    dedupeRegistry.releasePending(meta.getSha256());
                }
                return failure;
            }

            private void respond(UploadStatus status, String message, String storedName, int retryAfterMs) {
                responseObserver.onNext(UploadResult.newBuilder()
                        .setStatus(status)
                        .setMessage(message)
                        .setStoredName(storedName)
                        .setRetryAfterMs(retryAfterMs)
                        .build());
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void getQueueStatus(
            QueueStatusRequest request,
            StreamObserver<QueueStatusResponse> responseObserver) {
        int activeOrReserved = leakyBucket.getActiveOrReservedCount();
        responseObserver.onNext(QueueStatusResponse.newBuilder()
                .setQueueLength(activeOrReserved)
                .setQueueCapacity(leakyBucket.getCapacityQ())
                .setFull(leakyBucket.getAvailableSlots() == 0)
                .build());
        responseObserver.onCompleted();
    }

    private boolean isValid(UploadMeta meta) {
        if (meta.getSizeBytes() <= 0 || meta.getSizeBytes() > maxUploadBytes) {
            return false;
        }
        if (!SHA256_PATTERN.matcher(meta.getSha256()).matches()) {
            return false;
        }

        String filename = meta.getFilename().toLowerCase(Locale.ROOT);
        for (String extension : ALLOWED_EXTENSIONS) {
            if (filename.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
}
