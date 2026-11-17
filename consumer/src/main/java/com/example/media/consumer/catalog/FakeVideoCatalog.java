package com.example.media.consumer.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Fake video catalog mock service conforming to §6.4:
 * "Until M2's catalog is ready, provide a FakeVideoCatalog or mock service that
 * returns 10 sample videos pointing to local sample files. Enable it through USE_FAKE=true."
 */
public class FakeVideoCatalog extends DefaultVideoCatalog {
    private static final Logger log = LoggerFactory.getLogger(FakeVideoCatalog.class);

    public FakeVideoCatalog() {
        populateSampleVideos();
    }

    public void populateSampleVideos() {
        clear();

        // Check for local sample files in sample_videos/
        List<Path> availableLocalVideos = new ArrayList<>();
        String[] candidateDirs = {"sample_videos/producer1", "sample_videos/producer2", "../sample_videos/producer1", "../sample_videos/producer2"};

        for (String dirStr : candidateDirs) {
            Path p = Paths.get(dirStr);
            if (Files.exists(p) && Files.isDirectory(p)) {
                try (var s = Files.list(p)) {
                    s.filter(Files::isRegularFile)
                     .filter(f -> f.getFileName().toString().endsWith(".mp4"))
                     .forEach(availableLocalVideos::add);
                } catch (Exception ignored) {}
            }
        }

        log.info("FakeVideoCatalog found {} real local sample video files for mock playback", availableLocalVideos.size());

        String[] mockNames = {
                "short5.mp4",
                "sample15.mp4",
                "large120.mp4",
                "lecture_intro.mp4",
                "demo_clip_01.mp4",
                "drone_footage.mp4",
                "nature_walk.mp4",
                "presentation_final.mp4",
                "timelapse_sunset.mp4",
                "traffic_study.mp4"
        };

        double[] durations = {5.0, 15.0, 120.0, 42.5, 9.8, 64.0, 18.2, 85.0, 12.0, 30.5};
        long[] sizes = {33365L, 93054L, 1711685L, 5242880L, 819200L, 12582912L, 2097152L, 15728640L, 1048576L, 4194304L};
        String[] producers = {"P1", "P1", "P2", "P3", "P2", "P1", "P3", "P2", "P1", "P3"};

        Instant now = Instant.now();

        for (int i = 0; i < 10; i++) {
            String id = "mock-" + (i + 1);
            String name = mockNames[i];
            double duration = durations[i];
            long size = sizes[i];
            String producer = producers[i];
            String uploadedAt = now.minus((10 - i) * 3L + 1, ChronoUnit.MINUTES).toString();
            String hash = String.format("%064x", (i + 1) * 123456789L);

            String localVideoPath = null;
            if (!availableLocalVideos.isEmpty()) {
                Path selected = availableLocalVideos.get(i % availableLocalVideos.size());
                localVideoPath = selected.toAbsolutePath().toString();
                try {
                    size = Files.size(selected);
                } catch (Exception ignored) {}
            }

            // Preview and thumbnail paths
            String previewPath = localVideoPath; // Can fallback directly to local video file
            String thumbPath = null; // Controller generates dynamic SVG if null

            add(new VideoItem(
                    id,
                    name,
                    size,
                    duration,
                    uploadedAt,
                    producer,
                    hash,
                    localVideoPath,
                    previewPath,
                    thumbPath
            ));
        }

        log.info("Initialized FakeVideoCatalog with {} mock videos.", size());
    }
}
