package com.example.media.consumer.gui;

import com.example.media.consumer.catalog.DefaultVideoCatalog;
import com.example.media.consumer.catalog.FakeVideoCatalog;
import com.example.media.consumer.catalog.VideoCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;

/**
 * Spring Boot Application for Member 3: Consumer Web GUI.
 * Serves the browser-based gallery, hover previews, video streaming, and leaky-bucket metrics.
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
    @Primary
    public VideoCatalog videoCatalog(
            @Value("${media.use-fake:true}") boolean useFake,
            @Value("${media.uploads-dir:./uploads}") String uploadsDir,
            @Value("${media.previews-dir:./previews}") String previewsDir) {

        if (useFake) {
            log.info(">>> Initializing FakeVideoCatalog (USE_FAKE=true) for GUI development & testing per §6.4");
            return new FakeVideoCatalog();
        } else {
            log.info(">>> Initializing DefaultVideoCatalog (USE_FAKE=false) using uploadsDir={}", uploadsDir);
            DefaultVideoCatalog catalog = new DefaultVideoCatalog();
            catalog.scanAndRegisterExisting(uploadsDir, previewsDir);
            return catalog;
        }
    }
}
