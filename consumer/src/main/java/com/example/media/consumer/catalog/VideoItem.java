package com.example.media.consumer.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents a video entry in the catalog.
 * Thread-safe and immutable.
 */
public class VideoItem {
    private final String id;
    private final String name;
    private final long size;
    private final double duration;
    private final String uploadedAt;
    private final String producerId;
    private final String hash;
    private final String videoPath;
    private final String previewPath;
    private final String thumbnailPath;

    public VideoItem(
            String id,
            String name,
            long size,
            double duration,
            String uploadedAt,
            String producerId,
            String hash,
            String videoPath,
            String previewPath,
            String thumbnailPath) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.size = size;
        this.duration = duration;
        this.uploadedAt = uploadedAt != null ? uploadedAt : Instant.now().toString();
        this.producerId = producerId != null ? producerId : "P1";
        this.hash = hash != null ? hash : "";
        this.videoPath = videoPath;
        this.previewPath = previewPath;
        this.thumbnailPath = thumbnailPath;
    }

    @JsonProperty("id")
    public String getId() {
        return id;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    @JsonProperty("size")
    public long getSize() {
        return size;
    }

    @JsonProperty("duration")
    public double getDuration() {
        return duration;
    }

    @JsonProperty("uploaded_at")
    public String getUploadedAt() {
        return uploadedAt;
    }

    @JsonProperty("uploadedAt")
    public String getUploadedAtCamel() {
        return uploadedAt;
    }

    @JsonProperty("producer_id")
    public String getProducerId() {
        return producerId;
    }

    @JsonProperty("producerId")
    public String getProducerIdCamel() {
        return producerId;
    }

    @JsonProperty("hash")
    public String getHash() {
        return hash;
    }

    @JsonProperty("thumbnail_url")
    public String getThumbnailUrl() {
        return "/thumb/" + id;
    }

    @JsonProperty("thumbnailUrl")
    public String getThumbnailUrlCamel() {
        return "/thumb/" + id;
    }

    @JsonProperty("preview_url")
    public String getPreviewUrl() {
        return "/preview/" + id;
    }

    @JsonProperty("previewUrl")
    public String getPreviewUrlCamel() {
        return "/preview/" + id;
    }

    @JsonProperty("media_url")
    public String getMediaUrl() {
        return "/media/" + id;
    }

    @JsonProperty("mediaUrl")
    public String getMediaUrlCamel() {
        return "/media/" + id;
    }

    public String getVideoPath() {
        return videoPath;
    }

    public String getPreviewPath() {
        return previewPath;
    }

    public String getThumbnailPath() {
        return thumbnailPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VideoItem videoItem = (VideoItem) o;
        return Objects.equals(id, videoItem.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "VideoItem{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", size=" + size +
                ", duration=" + duration +
                ", producerId='" + producerId + '\'' +
                '}';
    }
}
