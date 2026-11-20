package com.example.media.consumer.queue;

import com.example.media.proto.UploadMeta;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeakyBucketTest {
    @Test
    void takingJobReleasesCapacityBeforeProcessingCompletes() throws InterruptedException {
        LeakyBucket bucket = new LeakyBucket(1);
        UploadMeta meta = UploadMeta.newBuilder().setFilename("clip.mp4").build();
        Job job = new Job(Path.of("upload.part"), meta);

        assertTrue(bucket.tryReserve());
        assertFalse(bucket.tryReserve());
        assertEquals(1, bucket.getDroppedCount());
        assertTrue(bucket.enqueue(job));

        assertSame(job, bucket.takeJob());
        assertEquals(1, bucket.getAvailableSlots());
        assertEquals(0, bucket.getActiveOrReservedCount());
        assertTrue(bucket.tryReserve());
    }

    @Test
    void exposesCounterUpdatesAndCapacity() {
        LeakyBucket bucket = new LeakyBucket(3);

        bucket.recordAccepted();
        bucket.recordDuplicate();
        bucket.recordRejected();
        bucket.recordSaved();

        assertEquals(3, bucket.getCapacityQ());
        assertEquals(3, bucket.getAvailableSlots());
        assertEquals(0, bucket.getActiveOrReservedCount());
        assertEquals(1, bucket.getAcceptedCount());
        assertEquals(0, bucket.getDroppedCount());
        assertEquals(1, bucket.getDuplicateCount());
        assertEquals(1, bucket.getRejectedCount());
        assertEquals(1, bucket.getSavedCount());
    }
}
