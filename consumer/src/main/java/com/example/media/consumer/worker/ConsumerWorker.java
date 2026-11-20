package com.example.media.consumer.worker;

import com.example.media.consumer.ConsumerConfig;
import com.example.media.consumer.catalog.VideoCatalog;
import com.example.media.consumer.catalog.VideoItem;
import com.example.media.consumer.dedupe.DedupeRegistry;
import com.example.media.consumer.media.MediaProcessor;
import com.example.media.consumer.queue.Job;
import com.example.media.consumer.queue.LeakyBucket;
import com.example.media.proto.UploadMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Consumes accepted upload jobs and publishes processed media to the catalog.
 */
public final class ConsumerWorker implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ConsumerWorker.class);
    private static final int COMPRESSION_CRF = 28;

    private final LeakyBucket leakyBucket;
    private final ConsumerConfig config;
    private final DedupeRegistry dedupeRegistry;
    private final VideoCatalog videoCatalog;
    private final MediaProcessor mediaProcessor;

    public ConsumerWorker(
            LeakyBucket leakyBucket,
            ConsumerConfig config,
            DedupeRegistry dedupeRegistry,
            VideoCatalog videoCatalog,
            MediaProcessor mediaProcessor) {
        this.leakyBucket = leakyBucket;
        this.config = config;
        this.dedupeRegistry = dedupeRegistry;
        this.videoCatalog = videoCatalog;
        this.mediaProcessor = mediaProcessor;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            Job job;
            try {
                job = leakyBucket.takeJob();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            processSafely(job);
        }
    }

    private void processSafely(Job job) {
        UploadMeta meta = job.getMeta();
        List<Path> filesToDeleteOnFailure = new ArrayList<>();
        filesToDeleteOnFailure.add(job.getTempPartPath());

        try {
            if (config.getWorkerDelayMs() > 0) {
                Thread.sleep(config.getWorkerDelayMs());
            }
            process(job, filesToDeleteOnFailure);
        } catch (InterruptedException e) {
            cleanupFailedJob(filesToDeleteOnFailure, meta);
            Thread.currentThread().interrupt();
            log.info("Worker interrupted while processing {}", meta.getFilename());
        } catch (Exception e) {
            cleanupFailedJob(filesToDeleteOnFailure, meta);
            log.error("Failed to process uploaded media {}", meta.getFilename(), e);
        }
    }

    private void process(Job job, List<Path> filesToDeleteOnFailure) throws IOException {
        UploadMeta meta = job.getMeta();
        String storedName = createStoredName(meta);
        Path storedPath = config.getUploadDir().resolve(storedName);
        filesToDeleteOnFailure.add(storedPath);

        move(job.getTempPartPath(), storedPath, false);

        double duration = mediaProcessor.probeDuration(storedPath);
        Path previewPath = config.getPreviewDir().resolve("preview_" + storedName);
        Path thumbnailPath = config.getPreviewDir().resolve("thumb_" + storedName + ".jpg");
        filesToDeleteOnFailure.add(previewPath);
        filesToDeleteOnFailure.add(thumbnailPath);
        mediaProcessor.makePreview(storedPath, previewPath);
        mediaProcessor.makeThumbnail(storedPath, thumbnailPath);

        if (config.isEnableCompress()) {
            Path compressedPath = config.getUploadDir()
                    .resolve("tmp")
                    .resolve(UUID.randomUUID() + "_" + storedName);
            filesToDeleteOnFailure.add(compressedPath);
            long beforeSize = Files.size(storedPath);
            mediaProcessor.compress(storedPath, compressedPath, COMPRESSION_CRF);
            long afterSize = Files.size(compressedPath);
            log.info("Compressed {} from {} bytes to {} bytes", storedName, beforeSize, afterSize);
            move(compressedPath, storedPath, true);
        }

        VideoItem video = new VideoItem(
                storedName,
                meta.getFilename(),
                Files.size(storedPath),
                duration,
                Instant.now().toString(),
                meta.getProducerId(),
                meta.getSha256(),
                storedPath.toAbsolutePath().toString(),
                previewPath.toAbsolutePath().toString(),
                thumbnailPath.toAbsolutePath().toString());

        dedupeRegistry.commit(meta.getSha256(), storedName);
        videoCatalog.add(video);
        leakyBucket.recordSaved();
        log.info("Saved uploaded media {} (total saved={})", storedName, leakyBucket.getSavedCount());
    }

    private void cleanupFailedJob(List<Path> files, UploadMeta meta) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.error("Failed to remove partial media output {}", file, e);
            }
        }
        dedupeRegistry.releasePending(meta.getSha256());
    }

    private static void move(Path source, Path destination, boolean replaceExisting) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        try {
            if (replaceExisting) {
                Files.move(source, destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException e) {
            if (replaceExisting) {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, destination);
            }
        }
    }

    private static String createStoredName(UploadMeta meta) {
        String producerId = sanitizeComponent(meta.getProducerId());
        String filename = meta.getFilename().replace('\\', '/');
        int lastSeparator = filename.lastIndexOf('/');
        if (lastSeparator >= 0) {
            filename = filename.substring(lastSeparator + 1);
        }
        filename = sanitizeComponent(filename);

        String timestamp = Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
        return producerId + "_" + timestamp + "_" + filename;
    }

    private static String sanitizeComponent(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        while (sanitized.startsWith(".")) {
            sanitized = sanitized.substring(1);
        }
        if (sanitized.isBlank()) {
            return "unknown";
        }
        return sanitized;
    }
}
