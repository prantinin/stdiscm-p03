package com.example.producer;
import com.example.media.proto.UploadResult;
import com.example.media.proto.UploadStatus;
import io.grpc.Status;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

public class ProducerWorker implements Runnable {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".mp4", ".mkv", ".avi", ".mov", ".webm"
    );

    private static final int HASH_BUFFER_SIZE = 1024 * 1024;
    private static final int MAX_QUEUE_RETRIES = 3;
    private static final int MAX_NETWORK_RETRIES = 3;
    private static final long[] NETWORK_RETRY_DELAYS_MS = {1000, 2000, 4000};
    private static final long BACKPRESSURE_POLL_INTERVAL_MS = 1000;

    private final String producerId;
    private final Path producerDirectory;
    private final GrpcUploadClient uploadClient;
    private final ProducerStats stats;
    private final String onDropMode;
    private final boolean respectBackpressure;

    public ProducerWorker(
            String producerId,
            Path producerDirectory,
            GrpcUploadClient uploadClient,
            ProducerStats stats
    ) {
        this(producerId, producerDirectory, uploadClient, stats, "retry", false);
    }

    public ProducerWorker(
            String producerId,
            Path producerDirectory,
            GrpcUploadClient uploadClient,
            ProducerStats stats,
            String onDropMode
    ) {
        this(producerId, producerDirectory, uploadClient, stats, onDropMode, false);
    }

    public ProducerWorker(
            String producerId,
            Path producerDirectory,
            GrpcUploadClient uploadClient,
            ProducerStats stats,
            String onDropMode,
            boolean respectBackpressure
    ) {
        this.producerId = producerId;
        this.producerDirectory = producerDirectory;
        this.uploadClient = uploadClient;
        this.stats = stats;
        this.onDropMode = onDropMode;
        this.respectBackpressure = respectBackpressure;
    }

    @Override
    public void run() {
        System.out.println("[" + producerId + "] Starting producer worker.");

        if (!Files.exists(producerDirectory)) {
            System.out.println("[" + producerId + "] Folder does not exist: "
                    + producerDirectory);
            return;
        }

        if (!Files.isDirectory(producerDirectory)) {
            System.out.println("[" + producerId + "] Path is not a directory: "
                    + producerDirectory);
            return;
        }

        try (var files = Files.list(producerDirectory)) {
            var videoFiles = files
                    .filter(Files::isRegularFile)
                    .filter(ProducerWorker::isSupportedVideo)
                    .sorted()
                    .toList();

            if (videoFiles.isEmpty()) {
                System.out.println("[" + producerId + "] No supported videos found.");
                return;
            }

            System.out.println("[" + producerId + "] Found "
                    + videoFiles.size() + " video(s).");

            for (Path video : videoFiles) {
                processVideo(video);
            }

        } catch (IOException e) {
            System.err.println("[" + producerId + "] Failed to scan folder: "
                    + e.getMessage());
        }

        System.out.println("[" + producerId + "] Finished. " + stats.summary());
    }

    private void waitForQueueSpace() throws InterruptedException {
        if (!respectBackpressure) {
            return;
        }

        while (true) {
            var queueStatus = uploadClient.getQueueStatus();

            if (!queueStatus.getFull()) {
                return;
            }

            System.out.println("[" + producerId
                    + "] Consumer queue is full ("
                    + queueStatus.getQueueLength() + "/"
                    + queueStatus.getQueueCapacity()
                    + "). Waiting before uploading.");

            Thread.sleep(BACKPRESSURE_POLL_INTERVAL_MS);
        }
    }

    private void processVideo(Path video) {
        try {
            System.out.println("[" + producerId + "] Processing "
                    + video.getFileName());

            String sha256 = calculateSha256(video);
            long fileSize = Files.size(video);

            waitForQueueSpace();

            stats.recordSent();

            UploadResult result = null;

            for (int attempt = 0; attempt <= MAX_QUEUE_RETRIES; attempt++) {

                result = uploadWithNetworkRetries(video, sha256);

                stats.addBytesSent(fileSize);

                if (result.getStatus() != UploadStatus.DROPPED_QUEUE_FULL) {
                    break;
                }

                // Skip immediately if --on-drop skip was selected.
                if (onDropMode.equals("skip")) {
                    System.out.println("[" + producerId
                            + "] Queue full. Skipping " + video.getFileName());
                    break;
                }

                if (attempt < MAX_QUEUE_RETRIES) {
                    int retryDelay = result.getRetryAfterMs();

                    System.out.println("[" + producerId + "] Queue full. Retrying "
                            + video.getFileName() + " in " + retryDelay + " ms. Attempt "
                            + (attempt + 1) + " of " + MAX_QUEUE_RETRIES);

                    Thread.sleep(retryDelay);
                }
            }

            UploadStatus status = result.getStatus();

            switch (status) {
                case ACCEPTED -> {
                    stats.recordAccepted();
                    System.out.println("[" + producerId + "] Accepted: "
                            + video.getFileName());
                }

                case DROPPED_QUEUE_FULL -> {
                    stats.recordDropped();
                    System.out.println("[" + producerId + "] Dropped: "
                            + video.getFileName() + " - " + result.getMessage());
                }

                case DUPLICATE -> {
                    stats.recordDuplicate();
                    System.out.println("[" + producerId + "] Duplicate: "
                            + video.getFileName());
                }

                case REJECTED_INVALID -> {
                    stats.recordFailed();
                    System.out.println("[" + producerId + "] Rejected: "
                            + video.getFileName() + " - " + result.getMessage());
                }

                default -> {
                    stats.recordFailed();
                    System.err.println("[" + producerId + "] Unknown upload status: "
                            + status);
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stats.recordFailed();
            System.err.println("[" + producerId + "] Upload interrupted.");

        } catch (IOException | RuntimeException e) {
            stats.recordFailed();
            System.err.println("[" + producerId + "] Upload failed for "
                    + video.getFileName() + ": " + e.getMessage());
        }
    }

    public static boolean isSupportedVideo(Path file) {
        String filename = file.getFileName().toString().toLowerCase();

        return SUPPORTED_EXTENSIONS.stream()
                .anyMatch(filename::endsWith);
    }

    public static String calculateSha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[HASH_BUFFER_SIZE];
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            return HexFormat.of().formatHex(digest.digest());

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    private UploadResult uploadWithNetworkRetries(
            Path video,
            String sha256
    ) throws IOException, InterruptedException {

        for (int attempt = 0; attempt <= MAX_NETWORK_RETRIES; attempt++) {
            try {
                return uploadClient.upload(video, producerId, sha256);

            } catch (IOException | RuntimeException e) {

                Status.Code code = Status.fromThrowable(e).getCode();

                boolean retryable =
                        code == Status.Code.UNAVAILABLE ||
                        code == Status.Code.DEADLINE_EXCEEDED;

                if (!retryable || attempt == MAX_NETWORK_RETRIES) {
                    throw e;
                }

                long delay = NETWORK_RETRY_DELAYS_MS[attempt];

                System.out.println("[" + producerId
                        + "] Network error (" + code + "). Retrying "
                        + video.getFileName() + " in " + delay
                        + " ms. Attempt " + (attempt + 1)
                        + " of " + MAX_NETWORK_RETRIES);

                Thread.sleep(delay);
            }
        }

        throw new IllegalStateException("Unexpected retry loop exit");
    }
}
