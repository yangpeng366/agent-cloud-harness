package com.agentcloud.worker;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.providers.BuiltinAgentProviders;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAppServerWorkerExecutorTest {

    @Test
    void supportsCodexOnly() {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        CodexAppServerWorkerExecutor executor = new CodexAppServerWorkerExecutor(registry, null);

        assertTrue(executor.supports("codex", null));
        assertEquals(false, executor.supports("cursor", null));
    }

    @Test
    void missingBinaryReturnsFailedMetadataWithoutThrowing() {
        String propertyKey = "agentcloud.providers.codex.path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, "definitely-missing-codex-binary-for-test");
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            CodexAppServerWorkerExecutor executor = new CodexAppServerWorkerExecutor(registry, null);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("codex"), "codex");

            assertEquals("failed", result.executionStatus());
            assertEquals("provider_app_server", result.metadata().get("execution_backend"));
            assertEquals("codex", result.metadata().get("provider_id"));
            assertEquals("definitely-missing-codex-binary-for-test", result.metadata().get("cli_binary"));
            assertEquals("codex_json_rpc", result.metadata().get("provider_output_parser"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    @Test
    void resumeThreadIdPrefersCodexSpecificMetadataBeforeGenericProviderSession() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        CodexAppServerWorkerExecutor executor = new CodexAppServerWorkerExecutor(registry, null);
        TaskRuntimeContext context = runtimeContext("codex", new LinkedHashMap<>(java.util.Map.of(
            "task_type", "coding",
            "intent", "Verify codex resume thread precedence.",
            "workspace", "D:\\gitAll\\agent-cloud-harness",
            "provider_session_id", "generic-provider-session",
            "provider_thread_id", "generic-provider-thread",
            "codex_thread_id", "codex-thread-specific"
        )));

        Method method = CodexAppServerWorkerExecutor.class.getDeclaredMethod("resumeThreadId", TaskRuntimeContext.class);
        method.setAccessible(true);

        assertEquals("codex-thread-specific", method.invoke(executor, context));
    }

    @Test
    void systemPromptDefaultsToThinControlPlaneAutonomyContract() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        CodexAppServerWorkerExecutor executor = new CodexAppServerWorkerExecutor(registry, null);

        Method method = CodexAppServerWorkerExecutor.class.getDeclaredMethod("systemPrompt", TaskRuntimeContext.class);
        method.setAccessible(true);
        String systemPrompt = (String) method.invoke(executor, runtimeContext("codex"));

        assertTrue(systemPrompt.contains("thin control plane"));
        assertTrue(systemPrompt.contains("broad autonomy"));
    }

    @Test
    void promptBuilderIncludesWorkspaceReferencesAndChecks() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        CodexAppServerWorkerExecutor executor = new CodexAppServerWorkerExecutor(registry, null);
        TaskRuntimeContext context = runtimeContext("codex", new LinkedHashMap<>(Map.of(
            "task_type", "documentation",
            "intent", "补全文档并摸清 iOS 与鸿蒙维护流程。",
            "workspace", "D:\\gitAll\\MetaClip_iOS",
            "workspace_paths", List.of(
                "D:\\gitAll\\MetaClip_iOS",
                "D:\\gitAll\\MetaClip_Harmony"
            ),
            "reference_docs", List.of(
                "D:\\BaiduSyncdisk\\Obsidian Vault\\当前项目\\04_学习沉淀\\2026_app_ios_鸿蒙_扩展技术方向与概念清单.md"
            ),
            "deliverables", List.of(
                "docs/ARCHITECTURE.md",
                "docs/SPEC.md"
            ),
            "required_checks", List.of(
                "确认编译环境",
                "确认发布上架步骤",
                "确认调试排查方式"
            )
        )));

        Method method = CodexAppServerWorkerExecutor.class.getDeclaredMethod(
            "buildPlan",
            com.agentcloud.agent.providers.LocalCliProviderConfig.ResolvedConfig.class,
            TaskRuntimeContext.class,
            String.class
        );
        method.setAccessible(true);
        Object plan = method.invoke(
            executor,
            new com.agentcloud.agent.providers.LocalCliProviderConfig("codex", "codex", "X", "Y").resolve(),
            context,
            "D:\\gitAll\\MetaClip_iOS"
        );
        Method promptGetter = plan.getClass().getDeclaredMethod("prompt");
        promptGetter.setAccessible(true);
        String prompt = (String) promptGetter.invoke(plan);

        assertTrue(prompt.contains("Workspaces:"));
        assertTrue(prompt.contains("D:\\gitAll\\MetaClip_iOS"));
        assertTrue(prompt.contains("Reference Inputs:"));
        assertTrue(prompt.contains("Expected Deliverables:"));
        assertTrue(prompt.contains("docs/SPEC.md"));
        assertTrue(prompt.contains("Required Checks:"));
        assertTrue(prompt.contains("确认发布上架步骤"));
    }

    private TaskRuntimeContext runtimeContext(String workerId) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        metadata.put("intent", "Verify codex app-server execution routing.");
        metadata.put("workspace", "D:\\gitAll\\agent-cloud-harness");
        return runtimeContext(workerId, metadata);
    }

    private TaskRuntimeContext runtimeContext(String workerId, LinkedHashMap<String, Object> metadata) {
        Task task = new Task(
            "task_codex_executor",
            "session_codex_executor",
            null,
            "codex app-server",
            "active",
            "high",
            Instant.parse("2026-05-07T02:00:00Z"),
            Instant.parse("2026-05-07T02:00:00Z"),
            null,
            null,
            null,
            "summary",
            "exercise codex app-server executor",
            "run a single round",
            workerId,
            "continue",
            null,
            metadata
        );
        ActiveContext activeContext = new ActiveContext(
            "codex app-server context",
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
            "Task Focus: codex app-server",
            12
        );
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), List.of(), activeContext, null);
    }
}
