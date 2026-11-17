package com.example.media.consumer.gui;

import com.example.media.consumer.catalog.VideoCatalog;
import com.example.media.consumer.catalog.VideoItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * Controller handling video streaming, previews, and thumbnails.
 * Implements byte-range seeking for GET /media/{id} via ResourceRegion per §6.2.
 */
@RestController
public class MediaStreamingController {
    private static final Logger log = LoggerFactory.getLogger(MediaStreamingController.class);
    private static final long CHUNK_SIZE = 1024 * 1024; // 1 MB chunk

    private final VideoCatalog videoCatalog;

    public MediaStreamingController(VideoCatalog videoCatalog) {
        this.videoCatalog = videoCatalog;
    }

    /**
     * GET /media/{id}
     * Delivers full video with HTTP Range requests (HTTP 206 Partial Content)
     * so video seeking works in Chrome, Firefox, Safari.
     */
    @GetMapping("/media/{id}")
    public ResponseEntity<ResourceRegion> getMedia(
            @PathVariable("id") String id,
            @RequestHeader HttpHeaders headers) throws IOException {

        Optional<VideoItem> videoOpt = videoCatalog.get(id);
        if (videoOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        VideoItem item = videoOpt.get();
        Path videoPath = resolveVideoPath(item);
        if (videoPath == null || !Files.exists(videoPath)) {
            log.warn("Full video file not found on disk for id={} path={}", id, item.getVideoPath());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Resource resource = new FileSystemResource(videoPath.toFile());
        long contentLength = resource.contentLength();
        HttpRange range = headers.getRange().isEmpty() ? null : headers.getRange().get(0);
        ResourceRegion region = createResourceRegion(resource, range, contentLength);

        MediaType mediaType = MediaTypeFactory.getMediaType(resource)
                .orElse(MediaType.parseMediaType("video/mp4"));

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(region);
    }

    /**
     * GET /preview/{id}
     * Delivers 10-second preview clip. Supports range requests or streaming.
     */
    @GetMapping("/preview/{id}")
    public ResponseEntity<ResourceRegion> getPreview(
            @PathVariable("id") String id,
            @RequestHeader HttpHeaders headers) throws IOException {

        Optional<VideoItem> videoOpt = videoCatalog.get(id);
        if (videoOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        VideoItem item = videoOpt.get();
        Path previewPath = resolvePreviewPath(item);

        if (previewPath == null || !Files.exists(previewPath)) {
            // Fallback to main video file if preview is not yet generated
            previewPath = resolveVideoPath(item);
        }

        if (previewPath == null || !Files.exists(previewPath)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Resource resource = new FileSystemResource(previewPath.toFile());
        long contentLength = resource.contentLength();
        HttpRange range = headers.getRange().isEmpty() ? null : headers.getRange().get(0);
        ResourceRegion region = createResourceRegion(resource, range, contentLength);

        MediaType mediaType = MediaTypeFactory.getMediaType(resource)
                .orElse(MediaType.parseMediaType("video/mp4"));

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(region);
    }

    /**
     * GET /thumb/{id}
     * Delivers thumbnail image (JPEG/PNG). If not generated yet, returns a dynamically
     * rendered SVG thumbnail so the card displays cleanly without broken images.
     */
    @GetMapping("/thumb/{id}")
    public ResponseEntity<?> getThumbnail(@PathVariable("id") String id) {
        Optional<VideoItem> videoOpt = videoCatalog.get(id);
        if (videoOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Video not found");
        }

        VideoItem item = videoOpt.get();
        if (item.getThumbnailPath() != null) {
            Path thumbPath = Paths.get(item.getThumbnailPath());
            if (Files.exists(thumbPath)) {
                try {
                    byte[] data = Files.readAllBytes(thumbPath);
                    String contentType = thumbPath.toString().endsWith(".png") ? "image/png" : "image/jpeg";
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_TYPE, contentType)
                            .body(data);
                } catch (IOException e) {
                    log.warn("Failed reading thumbnail file: {}", e.getMessage());
                }
            }
        }

        // Dynamic SVG thumbnail fallback
        String svg = generateSvgThumbnail(item);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "image/svg+xml;charset=UTF-8")
                .body(svg.getBytes(StandardCharsets.UTF_8));
    }

    private ResourceRegion createResourceRegion(Resource resource, HttpRange range, long contentLength) {
        if (range != null) {
            long start = range.getRangeStart(contentLength);
            long end = range.getRangeEnd(contentLength);
            long rangeLength = Math.min(CHUNK_SIZE, end - start + 1);
            return new ResourceRegion(resource, start, rangeLength);
        } else {
            long rangeLength = Math.min(CHUNK_SIZE, contentLength);
            return new ResourceRegion(resource, 0, rangeLength);
        }
    }

    private Path resolveVideoPath(VideoItem item) {
        if (item.getVideoPath() == null) return null;
        return Paths.get(item.getVideoPath());
    }

    private Path resolvePreviewPath(VideoItem item) {
        if (item.getPreviewPath() == null) return null;
        return Paths.get(item.getPreviewPath());
    }

    private String generateSvgThumbnail(VideoItem item) {
        String safeName = escapeXml(item.getName());
        String durationStr = String.format("%.0fs", item.getDuration());
        String producerStr = escapeXml(item.getProducerId());

        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 480 270\" width=\"480\" height=\"270\">"
                + "<defs>"
                + "  <linearGradient id=\"bg\" x1=\"0%\" y1=\"0%\" x2=\"100%\" y2=\"100%\">"
                + "    <stop offset=\"0%\" stop-color=\"#1e293b\"/>"
                + "    <stop offset=\"100%\" stop-color=\"#0f172a\"/>"
                + "  </linearGradient>"
                + "</defs>"
                + "<rect width=\"100%\" height=\"100%\" fill=\"url(#bg)\"/>"
                + "<circle cx=\"240\" cy=\"120\" r=\"36\" fill=\"#334155\" opacity=\"0.8\"/>"
                + "<polygon points=\"232,104 256,120 232,136\" fill=\"#60a5fa\"/>"
                + "<text x=\"240\" y=\"185\" fill=\"#f8fafc\" font-family=\"sans-serif\" font-size=\"16\" font-weight=\"bold\" text-anchor=\"middle\">"
                + safeName + "</text>"
                + "<rect x=\"16\" y=\"16\" width=\"48\" height=\"22\" rx=\"4\" fill=\"#2563eb\"/>"
                + "<text x=\"40\" y=\"32\" fill=\"#ffffff\" font-family=\"sans-serif\" font-size=\"12\" font-weight=\"bold\" text-anchor=\"middle\">"
                + producerStr + "</text>"
                + "<rect x=\"410\" y=\"232\" width=\"54\" height=\"22\" rx=\"4\" fill=\"#000000\" opacity=\"0.75\"/>"
                + "<text x=\"437\" y=\"248\" fill=\"#ffffff\" font-family=\"sans-serif\" font-size=\"12\" font-weight=\"bold\" text-anchor=\"middle\">"
                + durationStr + "</text>"
                + "</svg>";
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
