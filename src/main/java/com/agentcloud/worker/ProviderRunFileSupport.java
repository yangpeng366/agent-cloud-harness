package com.agentcloud.worker;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

final class ProviderRunFileSupport {
    private static final int DEFAULT_MAX_RUNS_PER_TASK = 20;
    private static final Duration DEFAULT_MAX_AGE = Duration.ofDays(7);

    private ProviderRunFileSupport() {
    }

    static Path providerRunRoot() {
        String configured = System.getProperty("agentcloud.provider_runs.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("AGENTCLOUD_PROVIDER_RUNS_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = ".tmp/provider-runs";
        }
        return Path.of(configured);
    }

    static void cleanupTaskRuns(Path taskRunDir, Logger log) {
        if (taskRunDir == null || !Files.isDirectory(taskRunDir)) {
            return;
        }
        int maxRuns = configuredInt("agentcloud.provider_runs.max_per_task", "AGENTCLOUD_PROVIDER_RUNS_MAX_PER_TASK", DEFAULT_MAX_RUNS_PER_TASK);
        long maxAgeHours = configuredLong("agentcloud.provider_runs.max_age_hours", "AGENTCLOUD_PROVIDER_RUNS_MAX_AGE_HOURS", DEFAULT_MAX_AGE.toHours());
        Instant cutoff = Instant.now().minus(Duration.ofHours(Math.max(1L, maxAgeHours)));
        try (Stream<Path> children = Files.list(taskRunDir)) {
            children
                .filter(Files::isDirectory)
                .map(path -> new RunDir(path, lastModified(path)))
                .sorted(Comparator.comparing(RunDir::lastModified).reversed())
                .skip(Math.max(0, maxRuns))
                .forEach(run -> deleteRecursively(run.path(), log));
        } catch (IOException e) {
            if (log != null) {
                log.warn("Provider run cleanup failed. dir={} reason={}", taskRunDir, e.getMessage());
            }
        }
        try (Stream<Path> children = Files.list(taskRunDir)) {
            children
                .filter(Files::isDirectory)
                .map(path -> new RunDir(path, lastModified(path)))
                .filter(run -> run.lastModified().isBefore(cutoff))
                .forEach(run -> deleteRecursively(run.path(), log));
        } catch (IOException e) {
            if (log != null) {
                log.warn("Provider run age cleanup failed. dir={} reason={}", taskRunDir, e.getMessage());
            }
        }
    }

    static String sanitizePathSegment(String value) {
        String normalized = value == null || value.isBlank() ? "unknown" : value.trim();
        return normalized.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private static void deleteRecursively(Path path, Logger log) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException e) {
                    if (log != null) {
                        log.warn("Failed to delete provider run path. path={} reason={}", item, e.getMessage());
                    }
                }
            });
        } catch (IOException e) {
            if (log != null) {
                log.warn("Failed to walk provider run path. path={} reason={}", path, e.getMessage());
            }
        }
    }

    private static int configuredInt(String property, String env, int fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        try {
            return value == null || value.isBlank() ? fallback : Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long configuredLong(String property, String env, long fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        try {
            return value == null || value.isBlank() ? fallback : Math.max(1L, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private record RunDir(Path path, Instant lastModified) {
    }
}
