package com.example.media.consumer.queue;

import com.example.media.proto.UploadMeta;

import java.nio.file.Path;
import java.util.Objects;

/**
 * An uploaded media job and its protocol metadata.
 *
 * <p>The metadata type is generic so this queue can hold the generated
 * {@code UploadMeta} type without depending on a particular protobuf package.</p>
 */
public final class Job {
    private final Path tempPartPath;
    private final UploadMeta meta;

    public Job(Path tempPartPath, UploadMeta meta) {
        this.tempPartPath = Objects.requireNonNull(tempPartPath, "tempPartPath must not be null");
        this.meta = Objects.requireNonNull(meta, "meta must not be null");
    }

    public Path getTempPartPath() {
        return tempPartPath;
    }

    public UploadMeta getMeta() {
        return meta;
    }
}
