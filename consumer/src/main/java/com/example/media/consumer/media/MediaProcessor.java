package com.example.media.consumer.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Thread-safe placeholder media processor.
 *
 * <p>These methods provide file outputs until ffmpeg/ffprobe processing is
 * implemented. Calls do not share mutable state.</p>
 */
public class MediaProcessor {
    public double probeDuration(Path src) {
        Objects.requireNonNull(src, "src must not be null");
        return 5.0;
    }

    public void makePreview(Path src, Path dst) {
        copyOrCreatePlaceholder(src, dst);
    }

    public void makeThumbnail(Path src, Path dst) {
        copyOrCreatePlaceholder(src, dst);
    }

    public long[] compress(Path src, Path dst, int crf) {
        long sourceSize = sizeIfRegularFile(src);
        copyOrCreatePlaceholder(src, dst);
        return new long[] {sourceSize, sizeIfRegularFile(dst)};
    }

    private static void copyOrCreatePlaceholder(Path src, Path dst) {
        Objects.requireNonNull(src, "src must not be null");
        Objects.requireNonNull(dst, "dst must not be null");
        try {
            Path parent = dst.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.isRegularFile(src)) {
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.writeString(dst, "media placeholder");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create media output at " + dst, e);
        }
    }

    private static long sizeIfRegularFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return 0L;
        }
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read media file size: " + path, e);
        }
    }
}