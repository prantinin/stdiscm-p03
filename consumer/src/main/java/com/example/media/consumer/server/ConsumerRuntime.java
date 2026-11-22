package com.example.media.consumer.server;

import com.example.media.consumer.ConsumerConfig;
import com.example.media.consumer.catalog.VideoCatalog;
import com.example.media.consumer.dedupe.DedupeRegistry;
import com.example.media.consumer.media.MediaProcessor;
import com.example.media.consumer.queue.Job;
import com.example.media.consumer.queue.LeakyBucket;
import com.example.media.consumer.worker.ConsumerWorker;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Owns the consumer worker pool and gRPC server for one Spring application.
 */
public final class ConsumerRuntime implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ConsumerRuntime.class);
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final ConsumerConfig config;
    private final LeakyBucket leakyBucket;
    private final DedupeRegistry dedupeRegistry;
    private final VideoCatalog videoCatalog;
    private final MediaProcessor mediaProcessor;
    private final MediaUploadServiceImpl uploadService;

    private ExecutorService workerExecutor;
    private ExecutorService grpcExecutor;
    private Server grpcServer;

    public ConsumerRuntime(
            ConsumerConfig config,
            LeakyBucket leakyBucket,
            DedupeRegistry dedupeRegistry,
            VideoCatalog videoCatalog,
            MediaProcessor mediaProcessor,
            MediaUploadServiceImpl uploadService) {
        this.config = config;
        this.leakyBucket = leakyBucket;
        this.dedupeRegistry = dedupeRegistry;
        this.videoCatalog = videoCatalog;
        this.mediaProcessor = mediaProcessor;
        this.uploadService = uploadService;
    }

    public synchronized ConsumerRuntime start() throws IOException {
        if (grpcServer != null) {
            throw new IllegalStateException("Consumer runtime is already started");
        }

        int workerCount = config.getC();
        workerExecutor = Executors.newFixedThreadPool(workerCount);
        grpcExecutor = Executors.newFixedThreadPool(Math.max(16, workerCount + 8));
        try {
            for (int i = 0; i < workerCount; i++) {
                workerExecutor.execute(new ConsumerWorker(
                        leakyBucket,
                        config,
                        dedupeRegistry,
                        videoCatalog,
                        mediaProcessor));
            }
            grpcServer = NettyServerBuilder.forPort(config.getGrpcPort())
                    .addService(uploadService)
                    .executor(grpcExecutor)
                    .build()
                    .start();
            log.info("Consumer gRPC server listening on port {} with {} workers",
                    config.getGrpcPort(), workerCount);
            return this;
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        if (grpcServer != null) {
            grpcServer.shutdown();
            if (!awaitTermination(grpcServer)) {
                grpcServer.shutdownNow();
                awaitTermination(grpcServer);
            }
            grpcServer = null;
        }
        stopExecutor(workerExecutor);
        workerExecutor = null;
        discardQueuedJobs();
        stopExecutor(grpcExecutor);
        grpcExecutor = null;
        log.info("Consumer runtime stopped");
    }

    private static boolean awaitTermination(Server server) {
        try {
            return server.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void stopExecutor(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Executor did not stop within {} seconds", SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void discardQueuedJobs() {
        Job job;
        while ((job = leakyBucket.pollJob()) != null) {
            try {
                Files.deleteIfExists(job.getTempPartPath());
            } catch (IOException e) {
                log.error("Failed to remove queued upload during shutdown: {}",
                        job.getTempPartPath(), e);
            }
            dedupeRegistry.releasePending(job.getMeta().getSha256());
        }
    }
}
