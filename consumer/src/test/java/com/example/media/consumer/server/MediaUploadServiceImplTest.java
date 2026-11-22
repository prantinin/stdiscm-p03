package com.example.media.consumer.server;

import com.example.media.consumer.ConsumerConfig;
import com.example.media.consumer.dedupe.DedupeRegistry;
import com.example.media.consumer.queue.Job;
import com.example.media.consumer.queue.LeakyBucket;
import com.example.media.proto.UploadChunk;
import com.example.media.proto.UploadMeta;
import com.example.media.proto.UploadResult;
import com.example.media.proto.UploadStatus;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaUploadServiceImplTest {
    @TempDir
    Path tempDir;

    private DedupeRegistry dedupeRegistry;
    private LeakyBucket leakyBucket;
    private MediaUploadServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        ConsumerConfig config = ConsumerConfig.parse(new String[] {
                "--upload-dir", tempDir.resolve("uploads").toString(),
                "--preview-dir", tempDir.resolve("previews").toString(),
                "--max-upload-size-mb", "1"
        });
        dedupeRegistry = new DedupeRegistry();
        leakyBucket = new LeakyBucket(1);
        service = new MediaUploadServiceImpl(config, dedupeRegistry, leakyBucket);
    }

    @Test
    void acceptsValidStreamAndTransfersJobToQueue() throws Exception {
        byte[] content = "video data".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(content);
        RecordingObserver observer = new RecordingObserver();
        StreamObserver<UploadChunk> request = service.upload(observer);

        request.onNext(metaChunk(metadata("clip.mp4", content.length, hash)));
        request.onNext(dataChunk(content));
        request.onCompleted();

        assertEquals(UploadStatus.ACCEPTED, observer.result.getStatus());
        assertTrue(observer.completed);
        assertEquals(1, leakyBucket.getAcceptedCount());
        assertEquals(0, leakyBucket.getAvailableSlots());
        Job job = leakyBucket.takeJob();
        assertArrayEquals(content, Files.readAllBytes(job.getTempPartPath()));
        assertEquals(1, leakyBucket.getAvailableSlots());
        assertFalse(dedupeRegistry.reservePending(hash));
    }

    @Test
    void rejectsHashMismatchAndReleasesFileSlotAndDedupeReservation() throws Exception {
        byte[] content = "not the claimed content".getBytes(StandardCharsets.UTF_8);
        String claimedHash = "0".repeat(64);
        RecordingObserver observer = new RecordingObserver();
        StreamObserver<UploadChunk> request = service.upload(observer);

        request.onNext(metaChunk(metadata("clip.mp4", content.length, claimedHash)));
        request.onNext(dataChunk(content));
        request.onCompleted();

        assertEquals(UploadStatus.REJECTED_INVALID, observer.result.getStatus());
        assertEquals(1, leakyBucket.getAvailableSlots());
        assertEquals(1, leakyBucket.getRejectedCount());
        assertTrue(tempDirectoryIsEmpty());
        assertTrue(dedupeRegistry.reservePending(claimedHash));
        dedupeRegistry.releasePending(claimedHash);
    }

    @Test
    void clientDisconnectCleansUpTemporaryUploadAndReservations() throws Exception {
        byte[] content = "partial".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(content);
        RecordingObserver observer = new RecordingObserver();
        StreamObserver<UploadChunk> request = service.upload(observer);

        request.onNext(metaChunk(metadata("clip.mkv", content.length, hash)));
        request.onNext(dataChunk(content));
        request.onError(new IllegalStateException("client disconnected"));

        assertTrue(observer.error instanceof IllegalStateException);
        assertEquals(1, leakyBucket.getAvailableSlots());
        assertEquals(1, leakyBucket.getRejectedCount());
        assertTrue(tempDirectoryIsEmpty());
        assertTrue(dedupeRegistry.reservePending(hash));
        dedupeRegistry.releasePending(hash);
    }

    @Test
    void duplicateIsReturnedBeforeReadingVideoData() throws Exception {
        byte[] content = "duplicate".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(content);
        assertTrue(dedupeRegistry.reservePending(hash));
        RecordingObserver observer = new RecordingObserver();
        StreamObserver<UploadChunk> request = service.upload(observer);

        request.onNext(metaChunk(metadata("clip.webm", content.length, hash)));
        request.onNext(dataChunk(content));
        request.onCompleted();

        assertEquals(UploadStatus.DUPLICATE, observer.result.getStatus());
        assertEquals(1, leakyBucket.getDuplicateCount());
        assertEquals(1, leakyBucket.getAvailableSlots());
        assertEquals(0, leakyBucket.getAcceptedCount());
    }

    @Test
    void fullQueueDropsUploadAndReleasesPendingHash() throws Exception {
        byte[] content = "queued elsewhere".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(content);
        assertTrue(leakyBucket.tryReserve());
        RecordingObserver observer = new RecordingObserver();
        StreamObserver<UploadChunk> request = service.upload(observer);

        request.onNext(metaChunk(metadata("clip.avi", content.length, hash)));

        assertEquals(UploadStatus.DROPPED_QUEUE_FULL, observer.result.getStatus());
        assertEquals(1000, observer.result.getRetryAfterMs());
        assertEquals(1, leakyBucket.getDroppedCount());
        assertTrue(dedupeRegistry.reservePending(hash));
        dedupeRegistry.releasePending(hash);
        leakyBucket.releaseSlot();
    }

    @Test
    void rejectsWhenFirstChunkDoesNotContainMetadata() {
        RecordingObserver observer = new RecordingObserver();
        StreamObserver<UploadChunk> request = service.upload(observer);

        request.onNext(dataChunk(new byte[] {1}));

        assertEquals(UploadStatus.REJECTED_INVALID, observer.result.getStatus());
        assertTrue(observer.completed);
        assertEquals(1, leakyBucket.getRejectedCount());
        assertEquals(1, leakyBucket.getAvailableSlots());
    }

    private boolean tempDirectoryIsEmpty() throws Exception {
        try (Stream<Path> files = Files.list(tempDir.resolve("uploads").resolve("tmp"))) {
            return files.findAny().isEmpty();
        }
    }

    private static UploadMeta metadata(String filename, long size, String hash) {
        return UploadMeta.newBuilder()
                .setFilename(filename)
                .setSizeBytes(size)
                .setSha256(hash)
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

    private static final class RecordingObserver implements StreamObserver<UploadResult> {
        private UploadResult result;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(UploadResult value) {
            result = value;
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
