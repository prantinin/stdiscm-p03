package com.example.media.consumer.catalog;

import java.util.List;
import java.util.Optional;

/**
 * Thread-safe video catalog interface.
 * Implements catalog requirements per §5.5 and §6.2.
 */
public interface VideoCatalog {
    /**
     * Adds or updates a video in the catalog.
     */
    void add(VideoItem video);

    /**
     * Returns a defensive copy of all catalog videos, ordered newest first.
     */
    List<VideoItem> list();

    /**
     * Finds a video by its unique ID.
     */
    Optional<VideoItem> get(String id);

    /**
     * Returns total number of registered videos.
     */
    int size();

    /**
     * Clears catalog (useful for testing).
     */
    void clear();
}
