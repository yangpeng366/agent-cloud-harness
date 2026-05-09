package com.agentcloud.worker;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.providers.BuiltinAgentProviders;
import com.agentcloud.agent.providers.OpenClawProvider;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCliWorkerExecutorTest {

    @Test
    void supportsProviderNativeCliCatalogIncludingDeepSeekButNotCodex() {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

        assertTrue(executor.supports("cursor", null));
        assertTrue(executor.supports("openclaw-native", null));
        assertTrue(executor.supports("claude", null));
        assertTrue(executor.supports("gemini", null));
        assertTrue(executor.supports("deepseek", null));
        assertTrue(executor.supports("copilot", null));
        assertTrue(executor.supports("opencode", null));
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

    @Test
    void claudeMissingBinaryReturnsFailedMetadataWithoutThrowing() {
        String propertyKey = "agentcloud.providers.claude.path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, "definitely-missing-claude-binary-for-test");
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("claude"), "claude");

            assertEquals("failed", result.executionStatus());
            assertEquals("provider_native_cli", result.metadata().get("execution_backend"));
            assertEquals("claude", result.metadata().get("provider_id"));
            assertEquals("definitely-missing-claude-binary-for-test", result.metadata().get("cli_binary"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    @Test
    void copilotMissingBinaryReturnsFailedMetadataWithoutThrowing() {
        String propertyKey = "agentcloud.providers.copilot.path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, "definitely-missing-copilot-binary-for-test");
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("copilot"), "copilot");

            assertEquals("failed", result.executionStatus());
            assertEquals("provider_native_cli", result.metadata().get("execution_backend"));
            assertEquals("copilot", result.metadata().get("provider_id"));
            assertEquals("definitely-missing-copilot-binary-for-test", result.metadata().get("cli_binary"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    @Test
    void deepSeekMissingBinaryReturnsFailedMetadataWithoutThrowing() {
        String propertyKey = "agentcloud.providers.deepseek.path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, "definitely-missing-deepseek-binary-for-test");
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("deepseek"), "deepseek");

            assertEquals("failed", result.executionStatus());
            assertEquals("provider_native_cli", result.metadata().get("execution_backend"));
            assertEquals("deepseek", result.metadata().get("provider_id"));
            assertEquals("definitely-missing-deepseek-binary-for-test", result.metadata().get("cli_binary"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    @Test
    void promptBuilderIncludesWorkspaceReferenceAndDeliverables() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);
        TaskRuntimeContext context = runtimeContext("claude", new LinkedHashMap<>(Map.of(
            "task_type", "documentation",
            "intent", "补全文档并摸清移动端项目维护流程。",
            "workspace", "D:\\gitAll\\MetaClip_Harmony",
            "reference_docs", List.of(
                "D:\\BaiduSyncdisk\\Obsidian Vault\\当前项目\\04_学习沉淀\\2026_app_ios_鸿蒙_概念学习规划.md",
                "D:\\BaiduSyncdisk\\Obsidian Vault\\当前项目\\04_学习沉淀\\2026-04-24_移动端维护总览_第一版.md"
            ),
            "deliverables", List.of(
                "docs/ARCHITECTURE.md",
                "docs/TROUBLESHOOT.md"
            ),
            "required_checks", List.of(
                "梳理编译环境",
                "梳理上架发布步骤",
                "梳理调试方法"
            )
        )));

        Method method = ProviderCliWorkerExecutor.class.getDeclaredMethod(
            "buildPlan",
            String.class,
            com.agentcloud.agent.providers.LocalCliProviderConfig.ResolvedConfig.class,
            TaskRuntimeContext.class,
            String.class
        );
        method.setAccessible(true);
        Object plan = method.invoke(
            executor,
            "claude",
            new com.agentcloud.agent.providers.LocalCliProviderConfig("claude", "claude", "X", "Y").resolve(),
            context,
            "D:\\gitAll\\MetaClip_Harmony"
        );
        Method stdinPrompt = plan.getClass().getDeclaredMethod("stdinPrompt");
        stdinPrompt.setAccessible(true);
        String prompt = (String) stdinPrompt.invoke(plan);
        com.fasterxml.jackson.databind.JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(prompt);
        String promptText = payload.path("message").path("content").get(0).path("text").asText();

        assertTrue(promptText.contains("Workspaces:"));
        assertTrue(promptText.contains("D:\\gitAll\\MetaClip_Harmony"));
        assertTrue(promptText.contains("Reference Inputs:"));
        assertTrue(promptText.contains("Expected Deliverables:"));
        assertTrue(promptText.contains("docs/ARCHITECTURE.md"));
        assertTrue(promptText.contains("Required Checks:"));
        assertTrue(promptText.contains("梳理编译环境"));
    }

    @Test
    void deepSeekPlanUsesFacadeProviderFlagsAndModelOverride() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);
        TaskRuntimeContext context = runtimeContext("deepseek", new LinkedHashMap<>(Map.of(
            "task_type", "coding",
            "intent", "用 deepseek 非交互执行单轮任务。",
            "provider_model", "deepseek-v4-flash",
            "workspace", "D:\\gitAll\\agent-cloud-harness"
        )));

        Method method = ProviderCliWorkerExecutor.class.getDeclaredMethod(
            "buildPlan",
            String.class,
            com.agentcloud.agent.providers.LocalCliProviderConfig.ResolvedConfig.class,
            TaskRuntimeContext.class,
            String.class
        );
        method.setAccessible(true);
        Object plan = method.invoke(
            executor,
            "deepseek",
            new com.agentcloud.agent.providers.LocalCliProviderConfig("deepseek", "deepseek", "X", "Y").resolve(),
            context,
            "D:\\gitAll\\agent-cloud-harness"
        );
        Method commandMethod = plan.getClass().getDeclaredMethod("command");
        commandMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) commandMethod.invoke(plan);

        assertEquals("deepseek", command.get(0));
        assertEquals("--provider", command.get(1));
        assertEquals("deepseek", command.get(2));
        assertEquals("--model", command.get(3));
        assertEquals("deepseek-v4-flash", command.get(4));
        assertEquals("exec", command.get(5));
        assertTrue(command.get(6).contains("Workspaces:"));
        assertTrue(command.get(6).contains("D:\\gitAll\\agent-cloud-harness"));
    }

    private TaskRuntimeContext runtimeContext(String workerId) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        metadata.put("intent", "Verify provider-native execution routing.");
        metadata.put("workspace", "D:\\gitAll\\agent-cloud-harness");
        return runtimeContext(workerId, metadata);
    }

    private TaskRuntimeContext runtimeContext(String workerId, LinkedHashMap<String, Object> metadata) {
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
