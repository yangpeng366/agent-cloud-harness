package com.agentcloud.engine.router;

import com.agentcloud.model.Worker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CodexProfileWorkerRegistryTest {

    @Test
    void codexProfileWorkersAreRegistered() {
        WorkerRegistry registry = new WorkerRegistry();
        Worker codexOpenai = registry.get("codex-openai");
        Worker codexXfyun = registry.get("codex-xfyun");
        Worker codexDeepseek = registry.get("codex-deepseek");
        assertNotNull(codexOpenai, "codex-openai worker should be registered");
        assertNotNull(codexXfyun, "codex-xfyun worker should be registered");
        assertNotNull(codexDeepseek, "codex-deepseek worker should be registered");
    }

    @Test
    void legacyCodexWorkerStillExists() {
        WorkerRegistry registry = new WorkerRegistry();
        Worker codex = registry.get("codex");
        assertNotNull(codex, "legacy codex worker should still be registered");
    }

    @Test
    void codexProfileWorkersHaveCodexWorkerType() {
        WorkerRegistry registry = new WorkerRegistry();
        assertEquals("codex", registry.get("codex-openai").workerType());
        assertEquals("codex", registry.get("codex-xfyun").workerType());
        assertEquals("codex", registry.get("codex-deepseek").workerType());
    }

    @Test
    void codexOpenaiHasExpectedProfileMetadata() {
        WorkerRegistry registry = new WorkerRegistry();
        Worker worker = registry.get("codex-openai");
        Map<String, Object> metadata = worker.metadata();
        assertEquals("codex_openai_strong", metadata.get("provider_profile_id"));
        assertEquals("OpenAI", metadata.get("provider_model_provider"));
        assertEquals("gpt-5.4", metadata.get("provider_model"));
        assertEquals("premium_usage", metadata.get("provider_billing_class"));
        assertEquals("codex", metadata.get("codex_profile_family"));
        assertEquals("strong_design", metadata.get("provider_profile_role"));
    }

    @Test
    void codexXfyunHasExpectedProfileMetadata() {
        WorkerRegistry registry = new WorkerRegistry();
        Worker worker = registry.get("codex-xfyun");
        Map<String, Object> metadata = worker.metadata();
        assertEquals("codex_xfyun_execute", metadata.get("provider_profile_id"));
        assertEquals("xfyun", metadata.get("provider_model_provider"));
        assertEquals("xopglm51", metadata.get("provider_model"));
        assertEquals("monthly_prepaid", metadata.get("provider_billing_class"));
        assertEquals("codex", metadata.get("codex_profile_family"));
    }

    @Test
    void codexDeepseekHasExpectedProfileMetadata() {
        WorkerRegistry registry = new WorkerRegistry();
        Worker worker = registry.get("codex-deepseek");
        Map<String, Object> metadata = worker.metadata();
        assertEquals("codex_deepseek_fallback", metadata.get("provider_profile_id"));
        assertEquals("deepseek", metadata.get("provider_model_provider"));
        assertEquals("deepseek-v4-pro", metadata.get("provider_model"));
        assertEquals("usage_metered", metadata.get("provider_billing_class"));
        assertEquals("codex", metadata.get("codex_profile_family"));
    }

    @Test
    void codexProfileWorkersHaveAppServerBackend() {
        WorkerRegistry registry = new WorkerRegistry();
        assertEquals("provider_app_server", registry.get("codex-openai").metadata().get("execution_backend"));
        assertEquals("provider_app_server", registry.get("codex-xfyun").metadata().get("execution_backend"));
        assertEquals("provider_app_server", registry.get("codex-deepseek").metadata().get("execution_backend"));
    }

    @Test
    void codexOpenaiHasDesignVerifyStageAffinity() {
        WorkerRegistry registry = new WorkerRegistry();
        @SuppressWarnings("unchecked")
        List<String> affinity = (List<String>) registry.get("codex-openai").metadata().get("workflow_stage_affinity");
        assertTrue(affinity.contains("design"));
        assertTrue(affinity.contains("verify"));
    }

    @Test
    void codexXfyunHasImplementStageAffinity() {
        WorkerRegistry registry = new WorkerRegistry();
        @SuppressWarnings("unchecked")
        List<String> affinity = (List<String>) registry.get("codex-xfyun").metadata().get("workflow_stage_affinity");
        assertTrue(affinity.contains("implement"));
    }

    @Test
    void codexProfileWorkersHaveLowerPriorityThanLegacyCodex() {
        WorkerRegistry registry = new WorkerRegistry();
        int codexPriority = (int) registry.get("codex").metadata().get("selection_priority");
        int openaiPriority = (int) registry.get("codex-openai").metadata().get("selection_priority");
        int xfyunPriority = (int) registry.get("codex-xfyun").metadata().get("selection_priority");
        int deepseekPriority = (int) registry.get("codex-deepseek").metadata().get("selection_priority");
        assertTrue(openaiPriority < codexPriority, "codex-openai should have lower selection_priority than codex");
        assertTrue(xfyunPriority < codexPriority, "codex-xfyun should have lower selection_priority than codex");
        assertTrue(deepseekPriority < codexPriority, "codex-deepseek should have lower selection_priority than codex");
    }
}
