package com.example.producer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProducerStatsTest {

    @Test
    void countersStartAtZero() {
        ProducerStats stats = new ProducerStats();

        assertEquals(0, stats.getSent());
        assertEquals(0, stats.getAccepted());
        assertEquals(0, stats.getDropped());
        assertEquals(0, stats.getDuplicates());
        assertEquals(0, stats.getFailed());
        assertEquals(0, stats.getBytesSent());
    }

    @Test
    void recordsUploadStatistics() {
        ProducerStats stats = new ProducerStats();

        stats.recordSent();
        stats.recordSent();
        stats.recordAccepted();
        stats.recordDropped();
        stats.recordDuplicate();
        stats.recordFailed();
        stats.addBytesSent(1024);

        assertEquals(2, stats.getSent());
        assertEquals(1, stats.getAccepted());
        assertEquals(1, stats.getDropped());
        assertEquals(1, stats.getDuplicates());
        assertEquals(1, stats.getFailed());
        assertEquals(1024, stats.getBytesSent());
    }

    @Test
    void rejectsNegativeBytes() {
        ProducerStats stats = new ProducerStats();

        assertThrows(
                IllegalArgumentException.class,
                () -> stats.addBytesSent(-1)
        );
    }

    @Test
    void elapsedTimeIsNonNegative() {
        ProducerStats stats = new ProducerStats();

        assertTrue(stats.getElapsedMillis() >= 0);
    }

    @Test
    void summaryContainsStatistics() {
        ProducerStats stats = new ProducerStats();

        stats.recordSent();
        stats.recordAccepted();
        stats.addBytesSent(500);

        String summary = stats.summary();

        assertTrue(summary.contains("Sent: 1"));
        assertTrue(summary.contains("Accepted: 1"));
        assertTrue(summary.contains("Bytes sent: 500"));
    }
}
