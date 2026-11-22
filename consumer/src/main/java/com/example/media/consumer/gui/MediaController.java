
package com.example.media.consumer.gui;

import com.example.media.consumer.catalog.VideoCatalog;
import com.example.media.consumer.catalog.VideoItem;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
public class MediaController {

    private final VideoCatalog videoCatalog;

    public MediaController(VideoCatalog videoCatalog) {
        this.videoCatalog = videoCatalog;
    }

    @GetMapping("/thumb/{id}")
    public ResponseEntity<?> getThumbnail(@PathVariable String id) {
        VideoItem video = videoCatalog.get(id).orElse(null);

        if (video == null) {
            return ResponseEntity.notFound().build();
        }

        Path thumbnailPath = existingFile(video.getThumbnailPath());

        if (thumbnailPath != null) {
            Resource resource = new FileSystemResource(thumbnailPath);
            MediaType contentType = MediaTypeFactory
                    .getMediaType(resource)
                    .orElse(MediaType.IMAGE_JPEG);

            return ResponseEntity.ok()
                    .contentType(contentType)
                    .body(resource);
        }

        // Generate a simple SVG placeholder when no thumbnail exists.
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg"
                     width="320" height="180" viewBox="0 0 320 180">
                    <rect width="320" height="180" fill="#222222"/>
                    <polygon points="135,55 135,125 195,90" fill="white"/>
                    <text x="160" y="155" fill="white"
                          font-size="12" text-anchor="middle">
                        %s
                    </text>
                </svg>
                """.formatted(escapeXml(video.getName()));

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("image/svg+xml"))
                .body(svg.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/media/{id}")
    public ResponseEntity<Resource> getMedia(@PathVariable String id) {
        VideoItem video = videoCatalog.get(id).orElse(null);

        if (video == null) {
            return ResponseEntity.notFound().build();
        }

        Path mediaPath = existingFile(video.getVideoPath());

        if (mediaPath == null) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(mediaPath);

        MediaType contentType = MediaTypeFactory
                .getMediaType(resource)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);

        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(mediaPath.toFile().length())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(resource);
    }

    @GetMapping("/preview/{id}")
    public ResponseEntity<Resource> getPreview(@PathVariable String id) {
        VideoItem video = videoCatalog.get(id).orElse(null);

        if (video == null) {
            return ResponseEntity.notFound().build();
        }

        // Fall back to the original video if no separate preview exists.
        Path previewPath = existingFile(video.getPreviewPath());

        if (previewPath == null) {
            previewPath = existingFile(video.getVideoPath());
        }

        if (previewPath == null) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(previewPath);

        return ResponseEntity.ok()
                .contentType(MediaTypeFactory.getMediaType(resource)
                        .orElse(MediaType.APPLICATION_OCTET_STREAM))
                .contentLength(previewPath.toFile().length())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(resource);
    }

    private Path existingFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }

        Path path = Paths.get(filePath);

        return Files.isRegularFile(path) ? path : null;
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
