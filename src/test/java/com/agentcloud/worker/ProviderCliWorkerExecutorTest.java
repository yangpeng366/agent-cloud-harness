package com.agentcloud.worker;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.providers.BuiltinAgentProviders;
import com.agentcloud.agent.providers.OpenClawProvider;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCliWorkerExecutorTest {

    @Test
    void supportsCursorAndOpenclawButNotCodex() {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

        assertTrue(executor.supports("cursor", null));
        assertTrue(executor.supports("openclaw-native", null));
        assertEquals(false, executor.supports("codex", null));
    }

    @Test
    void openclawMissingBinaryReturnsFailedMetadataWithoutThrowing() {
        String propertyKey = "agentcloud.providers.openclaw.path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, "definitely-missing-openclaw-binary-for-test");
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry()
                .register(new OpenClawProvider());
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("openclaw-native"), "openclaw-native");

            assertEquals("failed", result.executionStatus());
            assertEquals("provider_native_cli", result.metadata().get("execution_backend"));
            assertEquals("openclaw", result.metadata().get("provider_id"));
            assertEquals("definitely-missing-openclaw-binary-for-test", result.metadata().get("cli_binary"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    private TaskRuntimeContext runtimeContext(String workerId) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        metadata.put("intent", "Verify provider-native execution routing.");
        metadata.put("workspace", "D:\\gitAll\\agent-cloud-harness");
        Task task = new Task(
            "task_provider_cli",
            "session_provider_cli",
            null,
            "provider native cli",
            "active",
            "high",
            Instant.parse("2026-05-07T02:00:00Z"),
            Instant.parse("2026-05-07T02:00:00Z"),
            null,
            null,
            null,
            "summary",
            "exercise provider native cli",
            "run a single round",
            workerId,
            "continue",
            null,
            metadata
        );
        ActiveContext activeContext = new ActiveContext(
            "provider cli context",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "",
            "Task Focus: provider-native cli",
            12
        );
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), List.of(), activeContext, null);
    }
}
