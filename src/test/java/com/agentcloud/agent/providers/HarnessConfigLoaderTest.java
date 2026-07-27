package com.agentcloud.agent.providers;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadReturnsEmptyWhenNoConfigFileExists() {
        Optional<HarnessConfig> config = HarnessConfigLoader.load(List.of(tempDir.resolve("nonexistent.yml")));
        assertTrue(config.isEmpty());
    }

    @Test
    void loadParsesMinimalConfig() throws Exception {
        Path config = tempDir.resolve("harness-config.yml");
        Files.writeString(config, """
            harness:
              workers: []
            """);

        Optional<HarnessConfig> result = HarnessConfigLoader.load(List.of(config));
        assertTrue(result.isPresent());
        HarnessConfig cfg = result.get();
        assertNotNull(cfg.defaults());
        assertNotNull(cfg.ccx());
        assertTrue(cfg.workers().isEmpty());
    }

    @Test
    void loadParsesDefaults() throws Exception {
        Path config = tempDir.resolve("harness-config.yml");
        Files.writeString(config, """
            harness:
              defaults:
                provider_model_provider: ccx
                provider_base_url: http://127.0.0.1:3688/v1
                provider_wire_api: chat_completions
                provider_bearer_token: ccx-test-key
              workers: []
            """);

        HarnessConfig cfg = HarnessConfigLoader.load(List.of(config)).orElseThrow();
        assertEquals("ccx", cfg.defaults().providerModelProvider());
        assertEquals("http://127.0.0.1:3688/v1", cfg.defaults().providerBaseUrl());
        assertEquals("chat_completions", cfg.defaults().providerWireApi());
        assertEquals("ccx-test-key", cfg.defaults().providerBearerToken());
    }

    @Test
    void loadParsesCcxConfig() throws Exception {
        Path config = tempDir.resolve("harness-config.yml");
        Files.writeString(config, """
            harness:
              ccx:
                base_url: http://127.0.0.1:3688
                admin_key: ccx-admin-2026
                health_check_on_startup: true
                channel_sync_on_startup: false
              workers: []
            """);

        HarnessConfig cfg = HarnessConfigLoader.load(List.of(config)).orElseThrow();
        assertEquals("http://127.0.0.1:3688", cfg.ccx().baseUrl());
        assertEquals("ccx-admin-2026", cfg.ccx().adminKey());
        assertTrue(cfg.ccx().healthCheckOnStartup());
    }

    @Test
    void loadParsesWorkerLanes() throws Exception {
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
                  metadata:
                    primary_role: planner_executor
                    local_workspace_access: true
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
        assertEquals(2, cfg.workers().size());

        WorkerLaneConfig main = cfg.workers().get(0);
        assertEquals("codex-main", main.id());
        assertEquals("codex", main.provider());
        assertEquals("strong", main.modelTier());
        assertEquals("paid_auto", main.costClass());
        assertEquals(100, main.selectionPriority());
        assertEquals(List.of("chat", "code", "patch", "session"), main.capabilities());
        assertEquals("codex", main.profile().model());
        assertEquals("ccx", main.profile().modelProvider());

        WorkerLaneConfig free = cfg.workers().get(1);
        assertEquals("codex-free-9b", free.id());
        assertEquals("small", free.modelTier());
        assertEquals("free_auto", free.costClass());
        assertEquals(70, free.selectionPriority());
    }

    @Test
    void workerLaneMetadataIncludesProfileAndRoutingFields() throws Exception {
        Path config = tempDir.resolve("harness-config.yml");
        Files.writeString(config, """
            harness:
              workers:
                - id: codex-free-flash
                  provider: codex
                  model_tier: small
                  cost_class: free_auto
                  selection_priority: 66
                  capabilities: [chat, code, session]
                  profile:
                    model: zhipu-flash
                    model_provider: ccx
                  metadata:
                    primary_role: chinese_completion
            """);

        HarnessConfig cfg = HarnessConfigLoader.load(List.of(config)).orElseThrow();
        WorkerLaneConfig lane = cfg.workers().get(0);
        Map<String, Object> meta = lane.metadata();

        // profile fields propagated to metadata
        assertEquals("zhipu-flash", meta.get("provider_model"));
        assertEquals("ccx", meta.get("provider_model_provider"));
        // routing fields propagated to metadata
        assertEquals(66, meta.get("selection_priority"));
        assertEquals("small", meta.get("model_tier"));
        assertEquals("free_auto", meta.get("provider_cost_class"));
        // custom metadata preserved
        assertEquals("chinese_completion", meta.get("primary_role"));
    }

    @Test
    void loadParsesAllFourFreeLanes() throws Exception {
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
                - id: codex-free-coding
                  provider: codex
                  model_tier: small
                  cost_class: free_auto
                  selection_priority: 68
                  capabilities: [chat, code, session]
                  profile:
                    model: openrouter-free
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
                - id: codex-free-gpt
                  provider: codex
                  model_tier: small
                  cost_class: free_auto
                  selection_priority: 64
                  capabilities: [chat, code, session]
                  profile:
                    model: github-gpt4o-mini
                    model_provider: ccx
            """);

        HarnessConfig cfg = HarnessConfigLoader.load(List.of(config)).orElseThrow();
        assertEquals(5, cfg.workers().size());
        assertEquals("codex-main", cfg.workers().get(0).id());
        assertEquals("codex-free-9b", cfg.workers().get(1).id());
        assertEquals("codex-free-coding", cfg.workers().get(2).id());
        assertEquals("codex-free-flash", cfg.workers().get(3).id());
        assertEquals("codex-free-gpt", cfg.workers().get(4).id());
    }
}