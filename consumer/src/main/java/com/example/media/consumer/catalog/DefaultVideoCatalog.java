package com.example.media.consumer.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * Thread-safe video catalog using ReentrantReadWriteLock.
 * Conforms to §5.5: add(video), list() (newest first, defensive copy), get(id).
 * Supports rebuilding or discovering videos from an uploads directory.
 */
public class DefaultVideoCatalog implements VideoCatalog {
    private static final Logger log = LoggerFactory.getLogger(DefaultVideoCatalog.class);

    private final Map<String, VideoItem> itemMap = new LinkedHashMap<>();
    private final List<VideoItem> itemsNewestFirst = new ArrayList<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    @Override
    public void add(VideoItem video) {
        Objects.requireNonNull(video, "Video item cannot be null");
        rwLock.writeLock().lock();
        try {
            itemMap.put(video.getId(), video);
            // Re-order: newest at head
            itemsNewestFirst.removeIf(item -> item.getId().equals(video.getId()));
            itemsNewestFirst.add(0, video);
            log.info("Catalog added video id={} name={} (total={})", video.getId(), video.getName(), itemsNewestFirst.size());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public List<VideoItem> list() {
        rwLock.readLock().lock();
        try {
            return new ArrayList<>(itemsNewestFirst);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public Optional<VideoItem> get(String id) {
        if (id == null) return Optional.empty();
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(itemMap.get(id));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        rwLock.readLock().lock();
        try {
            return itemsNewestFirst.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void clear() {
        rwLock.writeLock().lock();
        try {
            itemMap.clear();
            itemsNewestFirst.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Rebuilds or indexes existing videos from uploads and previews directory.
     */
    public void scanAndRegisterExisting(String uploadsDir, String previewsDir) {
        Path uploadPath = Paths.get(uploadsDir);
        if (!Files.exists(uploadPath) || !Files.isDirectory(uploadPath)) {
            return;
        }

        try (Stream<Path> stream = Files.list(uploadPath)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> !p.getFileName().toString().endsWith(".part") && !p.getFileName().toString().startsWith("."))
                  .forEach(p -> {
                      String filename = p.getFileName().toString();
                      String id = "vid-" + Math.abs(filename.hashCode());
                      long size = 0;
                      try {
                          size = Files.size(p);
                      } catch (IOException ignored) {}

                      String previewPath = Paths.get(previewsDir, "preview_" + filename).toString();
                      String thumbPath = Paths.get(previewsDir, "thumb_" + filename + ".jpg").toString();

                      add(new VideoItem(
                              id,
                              filename,
                              size,
                              15.0, // fallback duration
                              Instant.ofEpochMilli(p.toFile().lastModified()).toString(),
                              "P1",
                              "",
                              p.toAbsolutePath().toString(),
                              Files.exists(Paths.get(previewPath)) ? previewPath : null,
                              Files.exists(Paths.get(thumbPath)) ? thumbPath : null
                      ));
                  });
        } catch (IOException e) {
            log.warn("Failed scanning uploads directory {}: {}", uploadsDir, e.getMessage());
        }
    }
}
