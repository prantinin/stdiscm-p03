package com.example.producer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProducerWorkerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRecognizeSupportedVideoExtensions() {
        assertTrue(ProducerWorker.isSupportedVideo(Path.of("video.mp4")));
        assertTrue(ProducerWorker.isSupportedVideo(Path.of("video.mkv")));
        assertTrue(ProducerWorker.isSupportedVideo(Path.of("video.avi")));
        assertTrue(ProducerWorker.isSupportedVideo(Path.of("video.mov")));
        assertTrue(ProducerWorker.isSupportedVideo(Path.of("video.webm")));
    }

    @Test
    void shouldRejectUnsupportedFileExtensions() {
        assertFalse(ProducerWorker.isSupportedVideo(Path.of("document.pdf")));
        assertFalse(ProducerWorker.isSupportedVideo(Path.of("image.png")));
        assertFalse(ProducerWorker.isSupportedVideo(Path.of("video.txt")));
    }

    @Test
    void shouldRecognizeUppercaseExtensions() {
        assertTrue(ProducerWorker.isSupportedVideo(Path.of("VIDEO.MP4")));
        assertTrue(ProducerWorker.isSupportedVideo(Path.of("VIDEO.MKV")));
    }

    @Test
    void shouldCalculateSha256Correctly() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello");

        String hash = ProducerWorker.calculateSha256(file);

        assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                hash
        );
    }

    @Test
    void shouldCalculateSha256ForEmptyFile() throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.createFile(file);

        String hash = ProducerWorker.calculateSha256(file);

        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                hash
        );
    }
}
