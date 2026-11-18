
package com.example.media.consumer.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MediaProcessor {

    private static final long TIMEOUT_SECONDS = 120;

    /**
     * Executes an external media-processing command.
     *
     * Captures stdout and stderr, enforces a 120-second timeout,
     * and throws MediaException if execution fails.
     *
     * @param command command and its arguments
     * @return standard output produced by the command
     */
    private String runCommand(List<String> command)
            throws MediaException {

        Path stdoutFile = null;
        Path stderrFile = null;
        Process process = null;

        try {
            stdoutFile = Files.createTempFile("media-out-", ".tmp");
            stderrFile = Files.createTempFile("media-err-", ".tmp");

            ProcessBuilder builder = new ProcessBuilder(command);

            builder.redirectOutput(stdoutFile.toFile());
            builder.redirectError(stderrFile.toFile());

            process = builder.start();

            boolean finished = process.waitFor(
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!finished) {
                process.destroyForcibly();
                process.waitFor();

                throw new MediaException(
                        "Media command timed out after "
                                + TIMEOUT_SECONDS + " seconds."
                );
            }

            String stdout = Files.readString(
                    stdoutFile, StandardCharsets.UTF_8
            );

            String stderr = Files.readString(
                    stderrFile, StandardCharsets.UTF_8
            );

            if (process.exitValue() != 0) {
                throw new MediaException(
                        "Media command failed with exit code "
                                + process.exitValue()
                                + ". Error: " + stderr
                );
            }

            return stdout.trim();

        } catch (InterruptedException e) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }

            Thread.currentThread().interrupt();

            throw new MediaException(
                    "Media command was interrupted.", e
            );

        } catch (IOException e) {
            throw new MediaException(
                    "Unable to execute media command: "
                            + e.getMessage(), e
            );

        } finally {
            deleteTemporaryFile(stdoutFile);
            deleteTemporaryFile(stderrFile);
        }
    }


    /**
     * Returns the duration of a video in seconds.
     *
     * Uses ffprobe to inspect the input video.
     *
     * @param file path to the source video
     * @return duration in seconds
     * @throws MediaException if the file is invalid or cannot be inspected
     */
    public double probeDuration(Path file) throws MediaException {

        if (file == null || !Files.isRegularFile(file)) {
            throw new MediaException(
                    "Video file does not exist: " + file
            );
        }

        List<String> command = List.of(
                "ffprobe",
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=nw=1:nk=1",
                file.toAbsolutePath().toString()
        );

        String output = runCommand(command);

        try {
            double duration = Double.parseDouble(output);

            if (!Double.isFinite(duration) || duration < 0) {
                throw new NumberFormatException(
                        "Invalid duration value"
                );
            }

            return duration;

        } catch (NumberFormatException e) {
            throw new MediaException(
                    "Unable to read video duration: " + output, e
            );
        }
    }


    /**
     * Creates a preview clip containing the first 10 seconds of a video.
     *
     * The output is encoded as H.264 MP4, scaled to 360p,
     * and has no audio. If the source is shorter than 10 seconds,
     * the entire video is used.
     *
     * @param input path to the source video
     * @param output path where the preview clip will be saved
     * @throws MediaException if preview generation fails
     */
    public void makePreview(Path input, Path output) throws MediaException {

        if (input == null || !Files.isRegularFile(input)) {
            throw new MediaException("Video file does not exist: " + input);
        }

        if (output == null) {
            throw new MediaException("Output path cannot be null.");
        }

        Path temporaryOutput = null;

        try {
            Path absoluteOutput = output.toAbsolutePath();
            Path outputDirectory = absoluteOutput.getParent();

            Files.createDirectories(outputDirectory);

            temporaryOutput = Files.createTempFile(
                    outputDirectory,
                    "preview-",
                    ".mp4"
            );

            List<String> command = List.of(
                    "ffmpeg",
                    "-y",
                    "-i", input.toAbsolutePath().toString(),
                    "-t", "10",
                    "-vf", "scale=-2:360",
                    "-an",
                    "-c:v", "libx264",
                    "-pix_fmt", "yuv420p",
                    "-movflags", "+faststart",
                    temporaryOutput.toString()
            );

            runCommand(command);

            if (!Files.isRegularFile(temporaryOutput)
                    || Files.size(temporaryOutput) == 0) {
                throw new MediaException(
                        "Preview video was not created: " + output
                );
            }

            try {
                Files.move(
                        temporaryOutput,
                        absoluteOutput,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(
                        temporaryOutput,
                        absoluteOutput,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
            }

        } catch (IOException e) {
            throw new MediaException(
                    "Unable to create preview: " + e.getMessage(), e
            );

        } finally {
            deleteTemporaryFile(temporaryOutput);
        }
    }


    public void makeThumbnail(Path input, Path output) throws MediaException {
        if (input == null || !Files.isRegularFile(input)) {
            throw new MediaException("Video file does not exist: " + input);
        }

        if (output == null) {
            throw new MediaException("Output path cannot be null.");
        }

        Path temporaryOutput = null;

        try {
            Path absoluteOutput = output.toAbsolutePath();
            Path outputDirectory = absoluteOutput.getParent();

            Files.createDirectories(outputDirectory);

            temporaryOutput = Files.createTempFile(
                    outputDirectory,
                    "thumbnail-",
                    ".jpg"
            );

            List<String> command = List.of(
                    "ffmpeg", "-y",
                    "-i", input.toAbsolutePath().toString(),
                    "-frames:v", "1",
                    "-vf", "scale=320:-2",
                    "-q:v", "2",
                    temporaryOutput.toString()
            );

            runCommand(command);

            if (!Files.isRegularFile(temporaryOutput)
                    || Files.size(temporaryOutput) == 0) {
                throw new MediaException(
                        "Thumbnail was not created: " + output
                );
            }

            try {
                Files.move(
                        temporaryOutput,
                        absoluteOutput,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(
                        temporaryOutput,
                        absoluteOutput,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
            }

        } catch (IOException e) {
            throw new MediaException(
                    "Unable to create thumbnail: " + e.getMessage(), e
            );
        } finally {
            deleteTemporaryFile(temporaryOutput);
        }
    }


    public record CompressionResult(
            long originalSizeBytes,
            long compressedSizeBytes
    ) {}


    public CompressionResult compress(Path input, Path output)
            throws MediaException {
        return compress(input, output, 28);
    }

    public CompressionResult compress(Path input, Path output, int crf)
            throws MediaException {

        if (input == null || !Files.isRegularFile(input)) {
            throw new MediaException("Video file does not exist: " + input);
        }

        if (output == null) {
            throw new MediaException("Output path cannot be null.");
        }

        if (crf < 0 || crf > 51) {
            throw new MediaException("CRF must be between 0 and 51.");
        }

        Path temporaryOutput = null;

        try {
            long originalSize = Files.size(input);

            Path absoluteOutput = output.toAbsolutePath();
            Path outputDirectory = absoluteOutput.getParent();

            Files.createDirectories(outputDirectory);

            temporaryOutput = Files.createTempFile(
                    outputDirectory,
                    "compressed-",
                    ".mp4"
            );

            List<String> command = List.of(
                    "ffmpeg", "-y",
                    "-i", input.toAbsolutePath().toString(),
                    "-map", "0:v:0",
                    "-map", "0:a?",
                    "-c:v", "libx264",
                    "-crf", String.valueOf(crf),
                    "-preset", "medium",
                    "-c:a", "aac",
                    "-b:a", "128k",
                    "-movflags", "+faststart",
                    temporaryOutput.toString()
            );

            runCommand(command);

            if (!Files.isRegularFile(temporaryOutput)
                    || Files.size(temporaryOutput) == 0) {
                throw new MediaException(
                        "Compressed video was not created: " + output
                );
            }

            long compressedSize = Files.size(temporaryOutput);

            try {
                Files.move(
                        temporaryOutput,
                        absoluteOutput,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(
                        temporaryOutput,
                        absoluteOutput,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
            }

            return new CompressionResult(originalSize, compressedSize);

        } catch (IOException e) {
            throw new MediaException(
                    "Unable to compress video: " + e.getMessage(), e
            );
        } finally {
            deleteTemporaryFile(temporaryOutput);
        }
    }


    private boolean fileIsEmpty(Path file) throws MediaException {
        try {
            return Files.size(file) == 0;
        } catch (IOException e) {
            throw new MediaException(
                    "Unable to verify preview image: " + e.getMessage(), e
            );
        }
    }


    private void deleteTemporaryFile(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // Temporary-file cleanup should not hide
                // the original processing error.
            }
        }
    }
}
