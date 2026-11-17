package com.example.media.consumer.queue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe default queue status supplier.
 * Maintains counts for saved, dropped, and duplicate uploads, as well as queue length and capacity.
 */
@Component
public class DefaultQueueStatusSupplier implements QueueStatusSupplier {
    private final AtomicInteger queueLength = new AtomicInteger(0);
    private final AtomicInteger queueCapacity = new AtomicInteger(5);
    private final AtomicLong savedCount = new AtomicLong(0);
    private final AtomicLong droppedCount = new AtomicLong(0);
    private final AtomicLong duplicateCount = new AtomicLong(0);

    private final int c;
    private final int q;

    public DefaultQueueStatusSupplier(
            @Value("${media.c:2}") int c,
            @Value("${media.q:5}") int q) {
        this.c = c;
        this.q = q;
        this.queueCapacity.set(q);
    }

    @Override
    public QueueStatus getStatus() {
        int curLen = queueLength.get();
        int cap = queueCapacity.get();
        boolean full = curLen >= cap;
        return new QueueStatus(
                curLen,
                cap,
                full,
                savedCount.get(),
                droppedCount.get(),
                duplicateCount.get(),
                c,
                q
        );
    }

    @Override
    public void recordSaved() {
        savedCount.incrementAndGet();
    }

    @Override
    public void recordDropped() {
        droppedCount.incrementAndGet();
    }

    @Override
    public void recordDuplicate() {
        duplicateCount.incrementAndGet();
    }

    @Override
    public void updateQueue(int currentLength, int capacity) {
        this.queueLength.set(currentLength);
        this.queueCapacity.set(capacity);
    }

    public void setQueueLength(int length) {
        this.queueLength.set(length);
    }

    public void setSavedCount(long count) {
        this.savedCount.set(count);
    }

    public void setDroppedCount(long count) {
        this.droppedCount.set(count);
    }

    public void setDuplicateCount(long count) {
        this.duplicateCount.set(count);
    }
}
