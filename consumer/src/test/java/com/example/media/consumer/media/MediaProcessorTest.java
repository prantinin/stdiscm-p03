
package com.example.media.consumer.media;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class MediaProcessorTest {

    private final MediaProcessor processor = new MediaProcessor();

    @Test
    void testProbeDurationNormalVideo() throws MediaException {

        Path video = Path.of(
                "../sample_videos/producer1/sample15.mp4"
        );

        double duration = processor.probeDuration(video);

        assertTrue(duration > 14.0 && duration < 16.0);
    }

    @Test
    void testProbeDurationShortVideo() throws MediaException {

        Path video = Path.of(
                "../sample_videos/producer1/short5.mp4"
        );

        double duration = processor.probeDuration(video);

        assertTrue(duration > 4.0 && duration < 6.0);
    }

    @Test
    void testProbeDurationCorruptVideo() {
        Path video = Path.of("../sample_videos/bad/corrupt-probe.mp4");
        assertThrows(MediaException.class, () -> processor.probeDuration(video));
    }

    @Test
    void testProbeDurationFakeVideo() {

        Path video = Path.of(
                "../sample_videos/bad/fake.mp4"
        );

        assertThrows(
                MediaException.class,
                () -> processor.probeDuration(video)
        );
    }

    @Test
    void testProbeDurationMissingFile() {

        Path video = Path.of(
                "../sample_videos/nonexistent.mp4"
        );

        assertThrows(
                MediaException.class,
                () -> processor.probeDuration(video)
        );
    }

    @Test
    void testMakePreviewCreatesVideo() throws Exception {
        Path video = Path.of("../sample_videos/producer1/sample15.mp4");
        Path output = Path.of("target/test-preview.mp4");

        processor.makePreview(video, output);

        assertTrue(Files.exists(output));
        assertTrue(Files.size(output) > 0);

        double duration = processor.probeDuration(output);

        assertTrue(
                duration > 9.0 && duration <= 10.1,
                "Preview should be approximately 10 seconds"
        );
    }


    @Test
    void testMakePreviewUsesEntireShortVideo() throws Exception {
        Path video = Path.of("../sample_videos/producer1/short5.mp4");
        Path output = Path.of("target/test-short-preview.mp4");

        processor.makePreview(video, output);

        double duration = processor.probeDuration(output);

        assertTrue(
                duration > 4.0 && duration < 6.0,
                "Short video preview should contain the entire video"
        );
    }


    @Test
    void testMakePreviewRejectsMissingVideo() {
        Path video = Path.of("../sample_videos/nonexistent.mp4");
        Path output = Path.of("target/missing-preview.mp4");

        assertThrows(
                MediaException.class,
                () -> processor.makePreview(video, output)
        );
    }


    @Test
    void testMakeThumbnailCreatesImage() throws Exception {
        Path video = Path.of("../sample_videos/producer1/sample15.mp4");
        Path output = Path.of("target/test-thumbnail.jpg");

        processor.makeThumbnail(video, output);

        assertTrue(Files.exists(output));
        assertTrue(Files.size(output) > 0);

        // Check that Java can recognize the output as an image.
        assertNotNull(javax.imageio.ImageIO.read(output.toFile()));
    }

    @Test
    void testMakeThumbnailWorksWithShortVideo() throws Exception {
        Path video = Path.of("../sample_videos/producer1/short5.mp4");
        Path output = Path.of("target/test-short-thumbnail.jpg");

        processor.makeThumbnail(video, output);

        assertTrue(Files.exists(output));
        assertNotNull(javax.imageio.ImageIO.read(output.toFile()));
    }

    @Test
    void testMakeThumbnailRejectsMissingVideo() {
        Path video = Path.of("../sample_videos/nonexistent.mp4");
        Path output = Path.of("target/missing-thumbnail.jpg");

        assertThrows(
                MediaException.class,
                () -> processor.makeThumbnail(video, output)
        );
    }

    @Test
    void testCompressUsesDefaultCrf() throws Exception {
        Path video = Path.of("../sample_videos/producer1/sample15.mp4");
        Path output = Path.of("target/test-compressed.mp4");

        MediaProcessor.CompressionResult result =
                processor.compress(video, output);

        assertTrue(Files.exists(output));
        assertTrue(result.originalSizeBytes() > 0);
        assertTrue(result.compressedSizeBytes() > 0);

        assertEquals(Files.size(video), result.originalSizeBytes());
        assertEquals(Files.size(output), result.compressedSizeBytes());

        double duration = processor.probeDuration(output);
        assertTrue(duration > 14.0 && duration < 16.0);
    }

    @Test
    void testCompressWithCustomCrf() throws Exception {
        Path video = Path.of("../sample_videos/producer1/sample15.mp4");
        Path output = Path.of("target/test-compressed-crf30.mp4");

        MediaProcessor.CompressionResult result =
                processor.compress(video, output, 30);

        assertTrue(Files.exists(output));
        assertTrue(result.compressedSizeBytes() > 0);
    }

    @Test
    void testCompressRejectsMissingVideo() {
        Path video = Path.of("../sample_videos/nonexistent.mp4");
        Path output = Path.of("target/missing-compressed.mp4");

        assertThrows(
                MediaException.class,
                () -> processor.compress(video, output)
        );
    }

    @Test
    void testCompressRejectsInvalidCrf() {
        Path video = Path.of("../sample_videos/producer1/sample15.mp4");
        Path output = Path.of("target/invalid-crf.mp4");

        assertThrows(
                MediaException.class,
                () -> processor.compress(video, output, 52)
        );
    }

}
