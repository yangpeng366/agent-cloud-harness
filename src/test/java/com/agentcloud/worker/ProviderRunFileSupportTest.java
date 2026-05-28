package com.agentcloud.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProviderRunFileSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void cleanupTaskRunsKeepsNewestConfiguredRunDirectories() throws Exception {
        String maxKey = "agentcloud.provider_runs.max_per_task";
        String ageKey = "agentcloud.provider_runs.max_age_hours";
        String originalMax = System.getProperty(maxKey);
        String originalAge = System.getProperty(ageKey);
        System.setProperty(maxKey, "2");
        System.setProperty(ageKey, "9999");
        try {
            Path taskRunDir = tempDir.resolve("provider-runs").resolve("codex").resolve("task-a");
            Files.createDirectories(taskRunDir);
            Path oldRun = createRun(taskRunDir, "run-001-old", Instant.parse("2026-05-20T00:00:00Z"));
            Path middleRun = createRun(taskRunDir, "run-002-middle", Instant.parse("2026-05-21T00:00:00Z"));
            Path newRun = createRun(taskRunDir, "run-003-new", Instant.parse("2026-05-22T00:00:00Z"));

            ProviderRunFileSupport.cleanupTaskRuns(taskRunDir, LoggerFactory.getLogger(ProviderRunFileSupportTest.class));

            assertFalse(Files.exists(oldRun));
            assertEquals(List.of("run-002-middle", "run-003-new"), listRunNames(taskRunDir));
            assertEquals(true, Files.exists(middleRun));
            assertEquals(true, Files.exists(newRun));
        } finally {
            restore(maxKey, originalMax);
            restore(ageKey, originalAge);
        }
    }

    private Path createRun(Path taskRunDir, String name, Instant lastModified) throws Exception {
        Path run = taskRunDir.resolve(name);
        Files.createDirectories(run);
        Files.writeString(run.resolve("metadata.json"), "{}");
        Files.setLastModifiedTime(run, java.nio.file.attribute.FileTime.from(lastModified));
        return run;
    }

    private List<String> listRunNames(Path taskRunDir) throws Exception {
        try (Stream<Path> children = Files.list(taskRunDir)) {
            return children
                .filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .sorted(Comparator.naturalOrder())
                .toList();
        }
    }

    private void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
