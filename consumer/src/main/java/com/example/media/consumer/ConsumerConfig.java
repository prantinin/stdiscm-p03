package com.example.media.consumer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable runtime configuration for the consumer core.
 *
 * <p>Command-line values take precedence over environment variables, which in
 * turn take precedence over the built-in defaults.</p>
 */
public final class ConsumerConfig {
    private static final Map<String, String> DEFAULTS = Map.of(
            "c", "2",
            "q", "5",
            "grpc-port", "50051",
            "upload-dir", "./uploads",
            "preview-dir", "./previews",
            "max-upload-size-mb", "500",
            "enable-dedup", "true",
            "enable-compress", "false",
            "worker-delay-ms", "0");

    private static final Map<String, String> ENVIRONMENT_KEYS = Map.of(
            "c", "C",
            "q", "Q",
            "grpc-port", "GRPC_PORT",
            "upload-dir", "UPLOAD_DIR",
            "preview-dir", "PREVIEW_DIR",
            "max-upload-size-mb", "MAX_UPLOAD_SIZE_MB",
            "enable-dedup", "ENABLE_DEDUP",
            "enable-compress", "ENABLE_COMPRESS",
            "worker-delay-ms", "WORKER_DELAY_MS");

    private final int c;
    private final int q;
    private final int grpcPort;
    private final Path uploadDir;
    private final Path previewDir;
    private final long maxUploadSizeMb;
    private final boolean enableDedup;
    private final boolean enableCompress;
    private final long workerDelayMs;

    private ConsumerConfig(Map<String, String> values) {
        c = positiveInt(values, "c");
        q = positiveInt(values, "q");
        grpcPort = positiveInt(values, "grpc-port");
        if (grpcPort > 65535) {
            throw new IllegalArgumentException("--grpc-port must be between 1 and 65535");
        }
        uploadDir = Path.of(values.get("upload-dir"));
        previewDir = Path.of(values.get("preview-dir"));
        maxUploadSizeMb = positiveLong(values, "max-upload-size-mb");
        enableDedup = parseBoolean(values, "enable-dedup");
        enableCompress = parseBoolean(values, "enable-compress");
        workerDelayMs = nonNegativeLong(values, "worker-delay-ms");
    }

    /**
     * Parses options, applies environment-variable fallbacks, and creates the
     * upload temporary and preview directories.
     *
     * @throws IllegalArgumentException if an option is unknown or invalid
     * @throws IOException if the required directories cannot be created
     */
    public static ConsumerConfig parse(String[] args) throws IOException {
        Objects.requireNonNull(args, "args must not be null");
        Map<String, String> values = new HashMap<>(DEFAULTS);

        for (String option : DEFAULTS.keySet()) {
            String environmentValue = System.getenv(ENVIRONMENT_KEYS.get(option));
            if (environmentValue != null && !environmentValue.isBlank()) {
                values.put(option, environmentValue);
            }
        }

        for (int i = 0; i < args.length; i++) {
            String argument = Objects.requireNonNull(args[i], "arguments must not contain null");
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("Expected an option beginning with --: " + argument);
            }

            int equalsIndex = argument.indexOf('=');
            String option = equalsIndex >= 0 ? argument.substring(2, equalsIndex) : argument.substring(2);
            if (!DEFAULTS.containsKey(option)) {
                throw new IllegalArgumentException("Unknown option: --" + option);
            }

            String value;
            if (equalsIndex >= 0) {
                value = argument.substring(equalsIndex + 1);
            } else if (isBooleanOption(option)
                    && (i + 1 >= args.length || args[i + 1].startsWith("--"))) {
                value = "true";
            } else {
                if (++i >= args.length) {
                    throw new IllegalArgumentException("Missing value for --" + option);
                }
                value = args[i];
            }
            values.put(option, value);
        }

        ConsumerConfig config = new ConsumerConfig(values);
        Files.createDirectories(config.uploadDir.resolve("tmp"));
        Files.createDirectories(config.previewDir);
        return config;
    }

    private static boolean isBooleanOption(String option) {
        return "enable-dedup".equals(option) || "enable-compress".equals(option);
    }

    private static int positiveInt(Map<String, String> values, String option) {
        int value = parseInt(values, option);
        if (value <= 0) {
            throw new IllegalArgumentException("--" + option + " must be greater than zero");
        }
        return value;
    }

    private static int parseInt(Map<String, String> values, String option) {
        try {
            return Integer.parseInt(values.get(option));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + option + " must be an integer", e);
        }
    }

    private static long positiveLong(Map<String, String> values, String option) {
        long value = parseLong(values, option);
        if (value <= 0) {
            throw new IllegalArgumentException("--" + option + " must be greater than zero");
        }
        return value;
    }

    private static long nonNegativeLong(Map<String, String> values, String option) {
        long value = parseLong(values, option);
        if (value < 0) {
            throw new IllegalArgumentException("--" + option + " must not be negative");
        }
        return value;
    }

    private static long parseLong(Map<String, String> values, String option) {
        try {
            return Long.parseLong(values.get(option));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + option + " must be an integer", e);
        }
    }

    private static boolean parseBoolean(Map<String, String> values, String option) {
        String value = values.get(option);
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("--" + option + " must be true or false");
    }

    public int getC() {
        return c;
    }

    public int getQ() {
        return q;
    }

    public int getGrpcPort() {
        return grpcPort;
    }

    public Path getUploadDir() {
        return uploadDir;
    }

    public Path getPreviewDir() {
        return previewDir;
    }

    public long getMaxUploadSizeMb() {
        return maxUploadSizeMb;
    }

    public boolean isEnableDedup() {
        return enableDedup;
    }

    public boolean isEnableCompress() {
        return enableCompress;
    }

    public long getWorkerDelayMs() {
        return workerDelayMs;
    }
}