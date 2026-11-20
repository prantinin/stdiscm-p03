package com.example.media.consumer.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thread-safe ffmpeg/ffprobe helpers for consumer workers.
 */
public class MediaProcessor {
    private static final long COMMAND_TIMEOUT_SECONDS = 120;
    private static final int BUFFER_SIZE = 8192;
    private static final int PREVIEW_SECONDS = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CommandRunner commandRunner;

    public MediaProcessor() {
        this(MediaProcessor::runProcess);
    }

    public MediaProcessor(CommandRunner commandRunner) {
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner must not be null");
    }

    public double probeDuration(Path src) throws MediaException {
        Objects.requireNonNull(src, "src must not be null");
        String output = commandRunner.run(List.of(
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=nw=1:nk=1",
                src.toString()));
        try {
            double duration = Double.parseDouble(output.trim());
            if (!Double.isFinite(duration) || duration <= 0) {
                throw new MediaException("Invalid media duration " + duration + " for " + src);
            }
            return duration;
        } catch (NumberFormatException e) {
            throw new MediaException("No media duration found for " + src, e);
        }
    }

    public void makePreview(Path src, Path dst) throws MediaException {
        Objects.requireNonNull(src, "src must not be null");
        encode(List.of(
                "ffmpeg", "-v", "error", "-y",
                "-i", src.toString(),
                "-t", Integer.toString(PREVIEW_SECONDS),
                "-an",
                "-vf", "scale=-2:360",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart"), dst);
    }

    public void makeThumbnail(Path src, Path dst) throws MediaException {
        Objects.requireNonNull(src, "src must not be null");
        double duration = probeDuration(src);
        String seek = duration >= 1 ? "1" : "0";
        encode(List.of(
                "ffmpeg", "-v", "error", "-y",
                "-ss", seek,
                "-i", src.toString(),
                "-frames:v", "1",
                "-vf", "scale=320:-1"), dst);
    }

    public long[] compress(Path src, Path dst, int crf) throws MediaException {
        Objects.requireNonNull(src, "src must not be null");
        long sourceSize;
        try {
            sourceSize = Files.size(src);
        } catch (IOException e) {
            throw new MediaException("Unable to read media file size: " + src, e);
        }

        encode(List.of(
                "ffmpeg", "-v", "error", "-y",
                "-i", src.toString(),
                "-c:v", "libx264",
                "-crf", Integer.toString(crf),
                "-preset", "veryfast",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "96k",
                "-movflags", "+faststart"), dst);

        try {
            return new long[] {sourceSize, Files.size(dst)};
        } catch (IOException e) {
            throw new MediaException("Unable to read compressed file size: " + dst, e);
        }
    }

    private void encode(List<String> command, Path dst) throws MediaException {
        Objects.requireNonNull(dst, "dst must not be null");
        Path absoluteDst = dst.toAbsolutePath();
        Path parent = absoluteDst.getParent();
        Path tmp = temporaryPath(absoluteDst);
        try {
            Files.createDirectories(parent);
            List<String> fullCommand = new ArrayList<>(command);
            fullCommand.add(tmp.toString());
            commandRunner.run(fullCommand);
            try {
                Files.move(tmp, absoluteDst,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, absoluteDst, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            if (e instanceof MediaException mediaException) {
                throw mediaException;
            }
            throw new MediaException("Unable to write media output to " + dst, e);
        }
    }

    private static Path temporaryPath(Path dst) {
        String filename = dst.getFileName().toString();
        int extensionIndex = filename.lastIndexOf('.');
        String stem = extensionIndex > 0 ? filename.substring(0, extensionIndex) : filename;
        String extension = extensionIndex > 0 ? filename.substring(extensionIndex) : "";
        return dst.resolveSibling(stem + "." + Long.toHexString(RANDOM.nextLong()) + ".tmp" + extension);
    }

    private static String runProcess(List<String> command) throws MediaException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new MediaException(
                    command.get(0) + " could not be started; ensure ffmpeg and ffprobe are on PATH", e);
        }

        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readOutput(process));
        try {
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                throw new MediaException(command.get(0) + " timed out after "
                        + COMMAND_TIMEOUT_SECONDS + " seconds");
            }

            String text = new String(
                    output.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                String tail = text.length() <= 300 ? text : text.substring(text.length() - 300);
                throw new MediaException(command.get(0) + " failed: " + tail.trim());
            }
            return text;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new MediaException(command.get(0) + " was interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
            throw new MediaException("Unable to read output from " + command.get(0), e);
        }
    }

    private static byte[] readOutput(Process process) {
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    @FunctionalInterface
    public interface CommandRunner {
        String run(List<String> command) throws MediaException;
    }
}
