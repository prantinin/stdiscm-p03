package com.example.media.consumer.queue;

/**
 * Interface for reading queue status and recording metrics.
 * Designed so M2's LeakyBucket implementation can plug directly into M3's GUI controllers.
 */
public interface QueueStatusSupplier {
    /**
     * Returns current snapshot of queue and worker status.
     */
    QueueStatus getStatus();

    /**
     * Records a successfully saved video job.
     */
    void recordSaved();

    /**
     * Records a dropped upload (due to queue full).
     */
    void recordDropped();

    /**
     * Records a duplicate upload attempt.
     */
    void recordDuplicate();

    /**
     * Updates queue length and capacity dynamically.
     */
    void updateQueue(int currentLength, int capacity);
}
