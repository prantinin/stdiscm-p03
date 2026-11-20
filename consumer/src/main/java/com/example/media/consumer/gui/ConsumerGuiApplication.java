package com.example.media.consumer.gui;

import com.example.media.consumer.ConsumerConfig;
import com.example.media.consumer.catalog.DefaultVideoCatalog;
import com.example.media.consumer.catalog.FakeVideoCatalog;
import com.example.media.consumer.dedupe.DedupeRegistry;
import com.example.media.consumer.media.MediaProcessor;
import com.example.media.consumer.queue.LeakyBucket;
import com.example.media.consumer.server.ConsumerRuntime;
import com.example.media.consumer.server.MediaUploadServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot application for the consumer GUI, upload service, and workers.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.example.media.consumer")
public class ConsumerGuiApplication {
    private static final Logger log = LoggerFactory.getLogger(ConsumerGuiApplication.class);

    public static void main(String[] args) {
        // Read port from environment GUI_PORT if set
        String guiPort = System.getenv("GUI_PORT");
        if (guiPort != null && !guiPort.isEmpty() && System.getProperty("server.port") == null) {
            System.setProperty("server.port", guiPort);
        }

        // Read USE_FAKE from environment if set
        String useFake = System.getenv("USE_FAKE");
        if (useFake != null && !useFake.isEmpty() && System.getProperty("media.use-fake") == null) {
            System.setProperty("media.use-fake", useFake);
        }

        SpringApplication.run(ConsumerGuiApplication.class, args);
    }

    @Bean
    public ConsumerConfig consumerConfig(Environment environment) throws IOException {
        return ConsumerConfig.parse(consumerArguments(environment));
    }

    @Bean
    public DedupeRegistry dedupeRegistry(ConsumerConfig config) {
        DedupeRegistry registry = new DedupeRegistry();
        registry.rebuildFromFolder(config.getUploadDir());
        return registry;
    }

    @Bean
    public LeakyBucket leakyBucket(ConsumerConfig config) {
        return new LeakyBucket(config.getQ());
    }

    @Bean
    public MediaProcessor mediaProcessor() {
        return new MediaProcessor();
    }

    @Bean
    public MediaUploadServiceImpl mediaUploadService(
            ConsumerConfig config,
            DedupeRegistry dedupeRegistry,
            LeakyBucket leakyBucket) {
        return new MediaUploadServiceImpl(config, dedupeRegistry, leakyBucket);
    }

    @Bean
    @Primary
    public DefaultVideoCatalog videoCatalog(
            ConsumerConfig config,
            @Value("${media.use-fake:true}") boolean useFake) {

        if (useFake) {
            log.info(">>> Initializing FakeVideoCatalog (USE_FAKE=true) for GUI development & testing per §6.4");
            return new FakeVideoCatalog();
        }
        log.info(">>> Initializing DefaultVideoCatalog (USE_FAKE=false) using uploadsDir={}",
                config.getUploadDir());
        DefaultVideoCatalog catalog = new DefaultVideoCatalog();
        catalog.scanAndRegisterExisting(
                config.getUploadDir().toString(),
                config.getPreviewDir().toString());
        return catalog;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "media.grpc.enabled", havingValue = "true", matchIfMissing = true)
    public ConsumerRuntime consumerRuntime(
            ConsumerConfig config,
            LeakyBucket leakyBucket,
            DedupeRegistry dedupeRegistry,
            DefaultVideoCatalog videoCatalog,
            MediaProcessor mediaProcessor,
            MediaUploadServiceImpl mediaUploadService) throws IOException {
        return new ConsumerRuntime(
                config,
                leakyBucket,
                dedupeRegistry,
                videoCatalog,
                mediaProcessor,
                mediaUploadService).start();
    }

    private static String[] consumerArguments(Environment environment) {
        String[] options = {
                "c",
                "q",
                "grpc-port",
                "upload-dir",
                "preview-dir",
                "max-upload-size-mb",
                "enable-dedup",
                "enable-compress",
                "worker-delay-ms"
        };
        List<String> arguments = new ArrayList<>();
        for (String option : options) {
            String alias = switch (option) {
                case "c" -> "media.c";
                case "q" -> "media.q";
                case "upload-dir" -> "media.uploads-dir";
                case "preview-dir" -> "media.previews-dir";
                default -> null;
            };
            boolean present = environment.containsProperty(option);
            String value = present ? environment.getProperty(option) : null;
            if (!present && alias != null) {
                present = environment.containsProperty(alias);
                value = present ? environment.getProperty(alias) : null;
            }
            if (!present) {
                continue;
            }
            if (value == null || value.isBlank()) {
                arguments.add("--" + option);
            } else {
                arguments.add("--" + option + "=" + value);
            }
        }
        return arguments.toArray(String[]::new);
    }
}
