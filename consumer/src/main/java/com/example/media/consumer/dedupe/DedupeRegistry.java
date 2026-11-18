
package com.example.media.consumer.dedupe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Tracks video hashes to prevent duplicate uploads.
 *
 * A hash can either be pending (currently being uploaded)
 * or stored (successfully uploaded).
 */
public class DedupeRegistry {

    private final Set<String> pendingHashes =
            ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, String> storedHashes =
            new ConcurrentHashMap<>();

    /**
     * Attempts to reserve a hash for an upload.
     *
     * @param sha256 SHA-256 hash of the video
     * @return true if the reservation succeeds, false if
     *         the hash is already pending or stored
     */
    public boolean reservePending(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException(
                    "SHA-256 hash cannot be null or blank."
            );
        }

        synchronized (this) {
            if (storedHashes.containsKey(sha256)) {
                return false;
            }

            return pendingHashes.add(sha256);
        }
    }
    
    /**
     * Marks a previously reserved hash as successfully stored.
     *
     * @param sha256 SHA-256 hash of the video
     * @param storedName name or path of the stored video
     */
    public void commit(String sha256, String storedName) {
        if (sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException(
                    "SHA-256 hash cannot be null or blank."
            );
        }

        if (storedName == null || storedName.isBlank()) {
            throw new IllegalArgumentException(
                    "Stored name cannot be null or blank."
            );
        }

        synchronized (this) {
            if (!pendingHashes.contains(sha256)) {
                throw new IllegalStateException(
                        "Hash has not been reserved: " + sha256
                );
            }

            storedHashes.put(sha256, storedName);
            pendingHashes.remove(sha256);
        }
    }

    /**
     * Releases a pending reservation, usually after an upload fails.
     *
     * @param sha256 SHA-256 hash of the video
     */
    public void releasePending(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException(
                    "SHA-256 hash cannot be null or blank."
            );
        }

        synchronized (this) {
            pendingHashes.remove(sha256);
        }
    }

    /**
     * Rebuilds the stored-hash registry from an existing uploads folder.
     *
     * Each regular file is hashed using SHA-256. Existing stored entries
     * are replaced, and pending reservations are cleared.
     *
     * @param uploadsDir directory containing uploaded videos
     */
    public void rebuildFromFolder(Path uploadsDir) throws IOException {
        if (uploadsDir == null || !Files.isDirectory(uploadsDir)) {
            throw new IllegalArgumentException(
                    "Uploads directory does not exist: " + uploadsDir
            );
        }

        synchronized (this) {
            storedHashes.clear();
            pendingHashes.clear();

            try (Stream<Path> files = Files.walk(uploadsDir)) {
                files.filter(Files::isRegularFile)
                        .forEach(path -> {
                            try {
                                String hash = calculateSha256(path);
                                storedHashes.put(hash, path.toString());
                            } catch (IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        });
            } catch (java.io.UncheckedIOException e) {
                throw e.getCause();
            }
        }
    }

    /**
     * Calculates the SHA-256 hash of a file.
     */
    private String calculateSha256(Path file) throws IOException {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");

            try (java.io.InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            byte[] hashBytes = digest.digest();
            StringBuilder result = new StringBuilder();

            for (byte b : hashBytes) {
                result.append(String.format("%02x", b));
            }

            return result.toString();

        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is unavailable.", e
            );
        }
    }


}
