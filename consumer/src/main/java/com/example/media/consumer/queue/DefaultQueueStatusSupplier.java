package com.example.media.consumer.queue;

import com.example.media.consumer.ConsumerConfig;
import org.springframework.stereotype.Component;

/**
 * Thread-safe default queue status supplier.
 * Reads queue state and counters from the consumer's shared leaky bucket.
 */
@Component
public class DefaultQueueStatusSupplier implements QueueStatusSupplier {
    private final LeakyBucket leakyBucket;
    private final int c;
    private final int q;

    public DefaultQueueStatusSupplier(LeakyBucket leakyBucket, ConsumerConfig config) {
        this.leakyBucket = leakyBucket;
        this.c = config.getC();
        this.q = config.getQ();
    }

    @Override
    public QueueStatus getStatus() {
        int curLen = leakyBucket.getActiveOrReservedCount();
        int cap = leakyBucket.getCapacityQ();
        return new QueueStatus(
                curLen,
                cap,
                leakyBucket.getAvailableSlots() == 0,
                leakyBucket.getSavedCount(),
                leakyBucket.getDroppedCount(),
                leakyBucket.getDuplicateCount(),
                c,
                q
        );
    }

    @Override
    public void recordSaved() {
        leakyBucket.recordSaved();
    }

    @Override
    public void recordDropped() {
        leakyBucket.recordDropped();
    }

    @Override
    public void recordDuplicate() {
        leakyBucket.recordDuplicate();
    }
}
