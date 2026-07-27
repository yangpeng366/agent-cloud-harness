package com.agentcloud.engine.router;

import com.agentcloud.agent.providers.HarnessConfig;
import com.agentcloud.agent.providers.HarnessConfigLoader;
import com.agentcloud.agent.providers.WorkerLaneConfig;
import com.agentcloud.agent.providers.WorkerLaneProfileConfig;
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

class WorkerRegistryConfigRegistrationTest {

    @TempDir
    Path tempDir;

    @Test
    void configWorkersRegisteredIntoRegistry() throws Exception {
        Path config = tempDir.resolve("harness-config.yml");
        Files.writeString(config, """
            harness:
              workers:
                - id: codex-free-9b
                  provider: codex
                  model_tier: small
                  cost_class: free_auto
                  selection_priority: 70
                  capabilities: [chat, code, session]
                  profile:
                    model: siliconflow-9b
                    model_provider: ccx
                  metadata:
                    primary_role: cannon_fodder
            """);

        HarnessConfig cfg = HarnessConfigLoader.load(List.of(config)).orElseThrow();
        WorkerRegistry registry = new WorkerRegistry(null);
        registry.registerFromConfig(cfg);

        Worker worker = registry.get("codex-free-9b");
        assertNotNull(worker);
        assertEquals("codex", worker.workerType());
        assertEquals("small", worker.metadata().get("model_tier"));
        assertEquals("free_auto", worker.metadata().get("provider_cost_class"));
        assertEquals(70, worker.metadata().get("selection_priority"));
        assertEquals("siliconflow-9b", worker.metadata().get("provider_model"));
        assertEquals("ccx", worker.metadata().get("provider_model_provider"));
        assertEquals("cannon_fodder", worker.metadata().get("primary_role"));
    }

    @Test
    void configOverridesBuiltinWorkerWithSameId() throws Exception {
        Path config = tempDir.resolve("harness-config.yml");
        Files.writeString(config, """
            harness:
              workers:
                - id: codex
                  provider: codex
                  model_tier: strong
                  cost_class: paid_auto
                  selection_priority: 150
                  capabilities: [chat, code, patch, session]
                  profile:
                    model: codex
                    model_provider: ccx
                  metadata:
                    primary_role: planner_executor
            """);

        HarnessConfig cfg = HarnessConfigLoader.load(List.of(config)).orElseThrow();
        WorkerRegistry registry = new WorkerRegistry(null);
        // builtin codex already registered with selection_priority=100
        Worker builtin = registry.get("codex");
        assertNotNull(builtin);

        registry.registerFromConfig(cfg);

        Worker overridden = registry.get("codex");
        assertNotNull(overridden);
        assertEquals(150, overridden.metadata().get("selection_priority"));
    }

    @Test
    void configAbsentFallsBackToBuiltinDefaults() {
        // No config file -> load returns empty -> no registerFromConfig call
        WorkerRegistry registry = new WorkerRegistry(null);
        Worker codex = registry.get("codex");
        assertNotNull(codex);
        assertEquals("strong", codex.metadata().get("model_tier"));
    }

    @Test
    void multipleConfigWorkersAllRegistered() throws Exception {
        Path config = tempDir.resolve("harness-config.yml");
        Files.writeString(config, """
            harness:
              workers:
                - id: codex-main
                  provider: codex
                  model_tier: strong
                  cost_class: paid_auto
                  selection_priority: 100
                  capabilities: [chat, code, patch, session]
                  profile:
                    model: codex
                    model_provider: ccx
                - id: codex-free-9b
                  provider: codex
                  model_tier: small
                  cost_class: free_auto
                  selection_priority: 70
                  capabilities: [chat, code, session]
                  profile:
                    model: siliconflow-9b
                    model_provider: ccx
                - id: codex-free-flash
                  provider: codex
                  model_tier: small
                  cost_class: free_auto
                  selection_priority: 66
                  capabilities: [chat, code, session]
                  profile:
                    model: zhipu-flash
                    model_provider: ccx
            """);

        HarnessConfig cfg = HarnessConfigLoader.load(List.of(config)).orElseThrow();
        WorkerRegistry registry = new WorkerRegistry(null);
        registry.registerFromConfig(cfg);

        assertNotNull(registry.get("codex-main"));
        assertNotNull(registry.get("codex-free-9b"));
        assertNotNull(registry.get("codex-free-flash"));

        // builtin workers still present
        assertNotNull(registry.get("codex"));
        assertNotNull(registry.get("claude"));
    }

    @Test
    void freeWorkerLaneRoutedByFreeFirstStrategy() throws Exception {
        Path config = tempDir.resolve("harness-config.yml");
        Files.writeString(config, """
            harness:
              workers:
                - id: codex-free-9b
                  provider: codex
                  model_tier: small
                  cost_class: free_auto
                  selection_priority: 70
                  capabilities: [chat, code, session]
                  profile:
                    model: siliconflow-9b
                    model_provider: ccx
                  metadata:
                    primary_role: cannon_fodder
                    local_workspace_access: true
            """);

        HarnessConfig cfg = HarnessConfigLoader.load(List.of(config)).orElseThrow();
        WorkerRegistry registry = new WorkerRegistry(null);
        registry.registerFromConfig(cfg);

        WorkerRouter router = new WorkerRouter(registry);
        // Create a task with free_first routing
        Map<String, Object> metadata = Map.of(
            "task_type", "coding",
            "provider_routing_policy", "free_first"
        );
        WorkerRouter.RouteResult route = router.selectWorker(TestTasks.task("coding", metadata));

        // Should have free candidates populated
        assertNotNull(route);
        assertTrue(route.freeFirstRouting());
    }

    @Test
    void emptyWorkersListDoesNotFail() throws Exception {
        Path config = tempDir.resolve("harness-config.yml");
        Files.writeString(config, """
            harness:
              workers: []
            """);

        HarnessConfig cfg = HarnessConfigLoader.load(List.of(config)).orElseThrow();
        WorkerRegistry registry = new WorkerRegistry(null);
        registry.registerFromConfig(cfg);

        // builtin workers still present
        assertNotNull(registry.get("codex"));
    }
}