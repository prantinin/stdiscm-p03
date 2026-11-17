package com.example.media.consumer.gui;

import com.example.media.consumer.catalog.VideoCatalog;
import com.example.media.consumer.catalog.VideoItem;
import com.example.media.consumer.queue.QueueStatus;
import com.example.media.consumer.queue.QueueStatusSupplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API Controller delivering video catalog items and queue/worker status.
 * Conforms to §6.2:
 * GET /api/videos -> list of videos
 * GET /api/status -> queue length, capacity, saved, dropped, duplicate, c, q
 */
@RestController
@RequestMapping("/api")
public class VideoApiController {

    private final VideoCatalog videoCatalog;
    private final QueueStatusSupplier queueStatusSupplier;

    public VideoApiController(VideoCatalog videoCatalog, QueueStatusSupplier queueStatusSupplier) {
        this.videoCatalog = videoCatalog;
        this.queueStatusSupplier = queueStatusSupplier;
    }

    /**
     * GET /api/videos
     * Returns JSON list from catalog: id, name, size, duration, uploaded_at, producer_id, thumbnail URL, preview URL
     */
    @GetMapping("/videos")
    public ResponseEntity<List<VideoItem>> getVideos() {
        List<VideoItem> videos = videoCatalog.list();
        return ResponseEntity.ok(videos);
    }

    /**
     * GET /api/status
     * Returns Queue length, capacity, saved count, dropped count, duplicate count, c, q
     */
    @GetMapping("/status")
    public ResponseEntity<QueueStatus> getStatus() {
        QueueStatus status = queueStatusSupplier.getStatus();
        return ResponseEntity.ok(status);
    }
}
