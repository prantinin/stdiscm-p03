package com.example.media.consumer.media;

import java.io.IOException;

/**
 * Indicates an invalid media file or a failed media-processing command.
 */
public class MediaException extends IOException {
    public MediaException(String message) {
        super(message);
    }

    public MediaException(String message, Throwable cause) {
        super(message, cause);
    }
}
