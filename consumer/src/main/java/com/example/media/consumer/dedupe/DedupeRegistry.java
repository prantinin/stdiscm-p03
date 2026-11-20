package com.example.media.consumer.dedupe;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of stored and in-progress SHA-256 values.
 */
public class DedupeRegistry {
    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final String PENDING = "";

    private final ConcurrentHashMap<String, String> entries = new ConcurrentHashMap<>();

    /**
     * Atomically reserves a hash unless it is already pending or stored.
     */
    public boolean reservePending(String sha256) {
        return entries.putIfAbsent(normalize(sha256), PENDING) == null;
    }

    /**
     * Marks a hash as stored, replacing any pending reservation.
     */
    public void commit(String sha256, String storedName) {
        entries.put(normalize(sha256), Objects.requireNonNull(storedName, "storedName must not be null"));
    }

    /**
     * Releases a pending reservation without removing a committed entry.
     */
    public void releasePending(String sha256) {
        entries.remove(normalize(sha256), PENDING);
    }

    /**
     * Hashes regular files directly under the uploads directory and registers
     * them as stored. Subdirectories, including uploads/tmp, are not traversed.
     */
    public void rebuildFromFolder(Path uploadsDir) {
        Objects.requireNonNull(uploadsDir, "uploadsDir must not be null");
        if (!Files.exists(uploadsDir)) {
            return;
        }

        Map<String, String> found = new ConcurrentHashMap<>();
        try (var files = Files.list(uploadsDir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().contains(".tmp"))
                    .filter(path -> !path.getFileName().toString().endsWith(".part"))
                    .forEach(path -> found.put(sha256(path), path.getFileName().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to rebuild dedupe registry from " + uploadsDir, e);
        }

        found.forEach((hash, name) -> entries.compute(hash, (key, current) ->
                current == null || PENDING.equals(current) ? name : current));
    }

    private static String normalize(String sha256) {
        String value = Objects.requireNonNull(sha256, "sha256 must not be null")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("sha256 must not be blank");
        }
        return value;
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to hash uploaded file " + path, e);
        }
    }
}