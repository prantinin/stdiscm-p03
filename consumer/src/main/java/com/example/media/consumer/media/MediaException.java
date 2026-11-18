
package com.example.media.consumer.media;

/**
 * Exception thrown when a media-processing operation fails.
 *
 * Used to report failures from FFmpeg and FFprobe operations,
 * including invalid files, unsuccessful commands, and timeouts.
 */
public class MediaException extends Exception {

    public MediaException(String message) {
        super(message);
    }

    public MediaException(String message, Throwable cause) {
        super(message, cause);
    }
}
