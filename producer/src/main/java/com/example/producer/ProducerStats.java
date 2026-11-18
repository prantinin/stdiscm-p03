package com.example.producer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe statistics tracker for Producer uploads.
 */
public class ProducerStats {

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();

    private final long startTime;

    public ProducerStats() {
        this.startTime = System.nanoTime();
    }

    public void recordSent() {
        sent.incrementAndGet();
    }

    public void recordAccepted() {
        accepted.incrementAndGet();
    }

    public void recordDropped() {
        dropped.incrementAndGet();
    }

    public void recordDuplicate() {
        duplicates.incrementAndGet();
    }

    public void recordFailed() {
        failed.incrementAndGet();
    }

    public void addBytesSent(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("Bytes cannot be negative");
        }
        bytesSent.addAndGet(bytes);
    }

    public long getSent() {
        return sent.get();
    }

    public long getAccepted() {
        return accepted.get();
    }

    public long getDropped() {
        return dropped.get();
    }

    public long getDuplicates() {
        return duplicates.get();
    }

    public long getFailed() {
        return failed.get();
    }

    public long getBytesSent() {
        return bytesSent.get();
    }

    public long getElapsedMillis() {
        return (System.nanoTime() - startTime) / 1_000_000;
    }

    public String summary() {
        return String.format(
                "Sent: %d | Accepted: %d | Dropped: %d | Duplicates: %d | Failed: %d | Bytes sent: %d | Elapsed: %d ms",
                getSent(),
                getAccepted(),
                getDropped(),
                getDuplicates(),
                getFailed(),
                getBytesSent(),
                getElapsedMillis()
        );
    }
}
