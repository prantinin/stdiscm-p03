package com.example.media.consumer.queue;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded upload queue. Reservations cover uploads being received and queued;
 * capacity is released when a worker takes a job, not when processing ends.
 */
public final class LeakyBucket {
    private final int capacityQ;
    private final Semaphore slots;
    private final ArrayBlockingQueue<Job> queue;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong duplicate = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong saved = new AtomicLong();

    public LeakyBucket(int capacityQ) {
        if (capacityQ <= 0) {
            throw new IllegalArgumentException("capacityQ must be greater than zero");
        }
        this.capacityQ = capacityQ;
        this.slots = new Semaphore(capacityQ);
        this.queue = new ArrayBlockingQueue<>(capacityQ);
    }

    /**
     * Attempts to reserve one in-flight upload slot without blocking.
     */
    public boolean tryReserve() {
        if (slots.tryAcquire()) {
            return true;
        }
        dropped.incrementAndGet();
        return false;
    }

    /**
     * Releases a previously reserved upload slot.
     */
    public void releaseSlot() {
        slots.release();
    }

    /**
     * Adds a job to the bounded worker queue without blocking.
     */
    public boolean enqueue(Job job) {
        return queue.offer(Objects.requireNonNull(job, "job must not be null"));
    }

    /**
     * Waits for the next job and releases its upload slot as soon as it leaves
     * the queue, allowing another upload while the worker processes this job.
     */
    public Job takeJob() throws InterruptedException {
        Job job = queue.take();
        slots.release();
        return job;
    }

    /**
     * Removes a queued job without blocking and releases its upload slot.
     * Used to discard work that remains queued during application shutdown.
     */
    public Job pollJob() {
        Job job = queue.poll();
        if (job != null) {
            slots.release();
        }
        return job;
    }

    public int getCapacityQ() {
        return capacityQ;
    }

    public int getActiveOrReservedCount() {
        return capacityQ - slots.availablePermits();
    }

    public int getAvailableSlots() {
        return slots.availablePermits();
    }

    public long getAcceptedCount() {
        return accepted.get();
    }

    public long getDroppedCount() {
        return dropped.get();
    }

    public long getDuplicateCount() {
        return duplicate.get();
    }

    public long getRejectedCount() {
        return rejected.get();
    }

    public long getSavedCount() {
        return saved.get();
    }

    public void recordAccepted() {
        accepted.incrementAndGet();
    }

    public void recordDropped() {
        dropped.incrementAndGet();
    }

    public void recordDuplicate() {
        duplicate.incrementAndGet();
    }

    public void recordRejected() {
        rejected.incrementAndGet();
    }

    public void recordSaved() {
        saved.incrementAndGet();
    }
}
