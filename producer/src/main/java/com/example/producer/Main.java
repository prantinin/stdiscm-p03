package com.example.producer;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 50051;
    private static final int DEFAULT_PRODUCERS = 1;
    private static final String DEFAULT_ROOT = "sample_videos";
    private static final String DEFAULT_ON_DROP = "retry";

    public static void main(String[] args) throws InterruptedException {
        int producerCount = DEFAULT_PRODUCERS;
        String rootDirectory = DEFAULT_ROOT;
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        String onDrop = DEFAULT_ON_DROP;
        boolean respectBackpressure = false;

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--p" -> producerCount = Integer.parseInt(args[++i]);
                    case "--root" -> rootDirectory = args[++i];
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--on-drop" -> onDrop = args[++i];
                    case "--respect-backpressure" -> respectBackpressure = true;
                    case "--help" -> {
                        printUsage();
                        return;
                    }
                    default -> throw new IllegalArgumentException(
                            "Unknown argument: " + args[i]);
                }
            }

            if (producerCount < 1) {
                throw new IllegalArgumentException(
                        "Producer count must be at least 1.");
            }

            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException(
                        "Port must be between 1 and 65535.");
            }

            if (!onDrop.equals("retry") && !onDrop.equals("skip")) {
                throw new IllegalArgumentException(
                        "--on-drop must be either 'retry' or 'skip'.");
            }

        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            System.err.println("Invalid arguments: " + e.getMessage());
            printUsage();
            return;
        }

        Path root = Path.of(rootDirectory);

        System.out.println("==================================");
        System.out.println("       MEDIA PRODUCER");
        System.out.println("==================================");
        System.out.println("Producers: " + producerCount);
        System.out.println("Root folder: " + root.toAbsolutePath());
        System.out.println("Consumer: " + host + ":" + port);
        System.out.println();

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build();

        ExecutorService executor =
                Executors.newFixedThreadPool(producerCount);

        List<ProducerStats> allStats = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        try {
            GrpcUploadClient uploadClient = new GrpcUploadClient(channel);

            for (int i = 1; i <= producerCount; i++) {
                String producerId = "P" + i;
                Path producerDirectory =
                        root.resolve("producer" + i);

                ProducerStats stats = new ProducerStats();
                allStats.add(stats);

                ProducerWorker worker = new ProducerWorker(
                    producerId,
                    producerDirectory,
                    uploadClient,
                    stats,
                    onDrop,
                    respectBackpressure
                );

                executor.submit(worker);
            }

        } finally {
            executor.shutdown();

            if (!executor.awaitTermination(1, TimeUnit.DAYS)) {
                System.err.println(
                        "Workers did not finish in time. Forcing shutdown.");
                executor.shutdownNow();
            }

            channel.shutdown();

            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        }

        printFinalSummary(allStats, startTime);
    }

    private static void printFinalSummary(
            List<ProducerStats> allStats,
            long startTime
    ) {
        long sent = 0;
        long accepted = 0;
        long dropped = 0;
        long duplicates = 0;
        long failed = 0;
        long bytesSent = 0;

        System.out.println();
        System.out.println("==================================");
        System.out.println("         FINAL SUMMARY");
        System.out.println("==================================");

        for (int i = 0; i < allStats.size(); i++) {
            ProducerStats stats = allStats.get(i);

            System.out.println("P" + (i + 1) + ": " + stats.summary());

            sent += stats.getSent();
            accepted += stats.getAccepted();
            dropped += stats.getDropped();
            duplicates += stats.getDuplicates();
            failed += stats.getFailed();
            bytesSent += stats.getBytesSent();
        }

        long elapsed = System.currentTimeMillis() - startTime;

        System.out.println("----------------------------------");
        System.out.println("TOTAL PRODUCERS: " + allStats.size());
        System.out.println("Files sent: " + sent);
        System.out.println("Accepted: " + accepted);
        System.out.println("Dropped: " + dropped);
        System.out.println("Duplicates: " + duplicates);
        System.out.println("Failed: " + failed);
        System.out.println("Bytes sent: " + bytesSent);
        System.out.println("Elapsed: " + elapsed + " ms");
        System.out.println("==================================");
    }

    private static void printUsage() {
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  mvn -pl producer exec:java "
                + "\"-Dexec.args=--p 2 --root sample_videos\"");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --p N       Number of producer workers");
        System.out.println("  --root PATH Root directory containing producer folders");
        System.out.println("  --host HOST Consumer server hostname (default: localhost)");
        System.out.println("  --port PORT Consumer server port (default: 50051)");
        System.out.println("  --on-drop MODE  Queue-full behavior: retry or skip (default: retry)");
        System.out.println("  --respect-backpressure  Check Consumer queue before uploading");
        System.out.println("  --help      Show this help message");
    }
}
