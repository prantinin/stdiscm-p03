
package com.example.media.consumer.dedupe;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DedupeRegistryTest {

    @Test
    void testReservePendingSucceedsForNewHash() {
        DedupeRegistry registry = new DedupeRegistry();

        assertTrue(registry.reservePending("abc123"));
    }

    @Test
    void testReservePendingRejectsDuplicateHash() {
        DedupeRegistry registry = new DedupeRegistry();

        assertTrue(registry.reservePending("abc123"));
        assertFalse(registry.reservePending("abc123"));
    }

    @Test
    void testReservePendingRejectsInvalidHash() {
        DedupeRegistry registry = new DedupeRegistry();

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.reservePending(null)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.reservePending(" ")
        );
    }


    @Test
    void testOnlyOneThreadCanReserveSameHash() throws Exception {
        DedupeRegistry registry = new DedupeRegistry();

        int threadCount = 20;

        ExecutorService executor =
                Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            java.util.List<Future<Boolean>> results =
                    new java.util.ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();

                    // Wait until all threads are ready.
                    start.await();

                    return registry.reservePending("same-hash");
                }));
            }

            // Wait until all 20 threads are ready.
            assertTrue(ready.await(5, TimeUnit.SECONDS));

            // Release all threads at approximately the same time.
            start.countDown();

            int successfulReservations = 0;

            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    successfulReservations++;
                }
            }

            assertEquals(1, successfulReservations);

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void testCommitMovesHashFromPendingToStored() {
        DedupeRegistry registry = new DedupeRegistry();

        assertTrue(registry.reservePending("hash-1"));

        registry.commit("hash-1", "video1.mp4");

        // The same hash must no longer be reservable.
        assertFalse(registry.reservePending("hash-1"));
    }

    @Test
    void testCommitRejectsUnreservedHash() {
        DedupeRegistry registry = new DedupeRegistry();

        assertThrows(
                IllegalStateException.class,
                () -> registry.commit("hash-1", "video1.mp4")
        );
    }

    @Test
    void testReleasePendingAllowsReservationAgain() {
        DedupeRegistry registry = new DedupeRegistry();

        assertTrue(registry.reservePending("hash-1"));

        registry.releasePending("hash-1");

        // The hash can now be reserved again.
        assertTrue(registry.reservePending("hash-1"));
    }

    @Test
    void testReleasePendingDoesNotRemoveStoredHash() {
        DedupeRegistry registry = new DedupeRegistry();

        assertTrue(registry.reservePending("hash-1"));
        registry.commit("hash-1", "video1.mp4");

        registry.releasePending("hash-1");

        assertFalse(registry.reservePending("hash-1"));
    }

    @Test
    void testRebuildFromFolderRecognizesExistingFile() throws Exception {
        DedupeRegistry registry = new DedupeRegistry();

        Path uploadsDir = Files.createTempDirectory("uploads-test");
        Path video = uploadsDir.resolve("video1.mp4");

        Files.writeString(video, "sample video content");

        registry.rebuildFromFolder(uploadsDir);

        // Rebuilt registry should recognize the existing file.
        String hash = calculateTestHash(video);

        assertFalse(registry.reservePending(hash));
    }

    @Test
    void testRebuildFromFolderClearsPendingReservations() throws Exception {
        DedupeRegistry registry = new DedupeRegistry();

        assertTrue(registry.reservePending("pending-hash"));

        Path uploadsDir = Files.createTempDirectory("uploads-empty");

        registry.rebuildFromFolder(uploadsDir);

        assertTrue(registry.reservePending("pending-hash"));
    }

    @Test
    void testRebuildFromFolderRejectsInvalidDirectory() {
        DedupeRegistry registry = new DedupeRegistry();

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.rebuildFromFolder(
                        Path.of("nonexistent-uploads-folder")
                )
        );
    }

    private String calculateTestHash(Path file) throws Exception {
        java.security.MessageDigest digest =
                java.security.MessageDigest.getInstance("SHA-256");

        byte[] hash = digest.digest(Files.readAllBytes(file));

        StringBuilder result = new StringBuilder();

        for (byte b : hash) {
            result.append(String.format("%02x", b));
        }

        return result.toString();
    }

}
