package com.agentcloud.engine.router;

import com.agentcloud.model.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerRegistryPriorityOverrideTest {

    @TempDir
    Path tempDir;

    @Test
    void workersYamlOverrideReplacesSelectionPriorityAndKeepsMetadataTrace() throws Exception {
        Path config = tempDir.resolve("workers.yml");
        Files.writeString(config, """
            workers:
              - codex: 135
              - kimi: 79
            """);

        WorkerRegistry registry = new WorkerRegistry(null, List.of(config));
        Worker codex = registry.get("codex");
        Worker kimi = registry.get("kimi");

        assertNotNull(codex);
        assertNotNull(kimi);
        assertEquals("135", String.valueOf(codex.metadata().get("selection_priority")));
        assertEquals("100", String.valueOf(codex.metadata().get("selection_priority_original")));
        assertEquals(Boolean.TRUE, codex.metadata().get("selection_priority_overridden"));
        assertEquals("79", String.valueOf(kimi.metadata().get("selection_priority")));
        assertEquals("80", String.valueOf(kimi.metadata().get("selection_priority_original")));
        assertEquals(Boolean.TRUE, kimi.metadata().get("selection_priority_overridden"));
    }

    @Test
    void workersYamlOverrideCanChangeRouteOrderForSameCapabilityWorkers() throws Exception {
        Path config = tempDir.resolve("workers.yml");
        Files.writeString(config, """
            workers:
              - alpha-worker: 120
              - beta-worker: 50
            """);

        WorkerRegistry registry = new WorkerRegistry(null, List.of(config));
        registry.register(new Worker(
            "alpha-worker",
            "test",
            List.of("continuation"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "small",
                "primary_role", "executor",
                "selection_priority", 10
            ),
            false,
            true
        ));
        registry.register(new Worker(
            "beta-worker",
            "test",
            List.of("continuation"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "small",
                "primary_role", "executor",
                "selection_priority", 90
            ),
            false,
            true
        ));

        Worker alpha = registry.get("alpha-worker");
        Worker beta = registry.get("beta-worker");
        assertEquals("120", String.valueOf(alpha.metadata().get("selection_priority")));
        assertEquals("10", String.valueOf(alpha.metadata().get("selection_priority_original")));
        assertEquals("50", String.valueOf(beta.metadata().get("selection_priority")));
        assertEquals("90", String.valueOf(beta.metadata().get("selection_priority_original")));

        WorkerRouter router = new WorkerRouter(registry);
        WorkerRouter.RouteResult route = router.selectWorker(TestTasks.task("continuation"));

        assertEquals("alpha-worker", route.selectedWorker());
        assertTrue(route.candidateWorkers().contains("alpha-worker"));
        assertTrue(route.candidateWorkers().contains("beta-worker"));
    }
}
