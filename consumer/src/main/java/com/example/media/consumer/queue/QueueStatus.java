package com.example.media.consumer.queue;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO representing status of the leaky-bucket queue and consumer stats.
 * Conforms to §6.2: queue length, capacity, saved count, dropped count, duplicate count, c, q.
 */
public class QueueStatus {
    private final int queueLength;
    private final int queueCapacity;
    private final boolean full;
    private final long savedCount;
    private final long droppedCount;
    private final long duplicateCount;
    private final int c;
    private final int q;

    public QueueStatus(
            int queueLength,
            int queueCapacity,
            boolean full,
            long savedCount,
            long droppedCount,
            long duplicateCount,
            int c,
            int q) {
        this.queueLength = queueLength;
        this.queueCapacity = queueCapacity;
        this.full = full;
        this.savedCount = savedCount;
        this.droppedCount = droppedCount;
        this.duplicateCount = duplicateCount;
        this.c = c;
        this.q = q;
    }

    @JsonProperty("queue_length")
    public int getQueueLength() {
        return queueLength;
    }

    @JsonProperty("queueLength")
    public int getQueueLengthCamel() {
        return queueLength;
    }

    @JsonProperty("queue_capacity")
    public int getQueueCapacity() {
        return queueCapacity;
    }

    @JsonProperty("queueCapacity")
    public int getQueueCapacityCamel() {
        return queueCapacity;
    }

    @JsonProperty("full")
    public boolean isFull() {
        return full;
    }

    @JsonProperty("saved_count")
    public long getSavedCount() {
        return savedCount;
    }

    @JsonProperty("savedCount")
    public long getSavedCountCamel() {
        return savedCount;
    }

    @JsonProperty("dropped_count")
    public long getDroppedCount() {
        return droppedCount;
    }

    @JsonProperty("droppedCount")
    public long getDroppedCountCamel() {
        return droppedCount;
    }

    @JsonProperty("duplicate_count")
    public long getDuplicateCount() {
        return duplicateCount;
    }

    @JsonProperty("duplicateCount")
    public long getDuplicateCountCamel() {
        return duplicateCount;
    }

    @JsonProperty("c")
    public int getC() {
        return c;
    }

    @JsonProperty("q")
    public int getQ() {
        return q;
    }
}
