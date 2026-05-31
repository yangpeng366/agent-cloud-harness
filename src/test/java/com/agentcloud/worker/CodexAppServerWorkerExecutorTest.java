package com.agentcloud.worker;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.agent.providers.BuiltinAgentProviders;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAppServerWorkerExecutorTest {

    @TempDir
    Path tempDir;

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
        String runDirKey = "agentcloud.provider_runs.dir";
        String modeKey = "agentcloud.providers.codex.execution_mode";
        String original = System.getProperty(propertyKey);
        String originalRunDir = System.getProperty(runDirKey);
        String originalMode = System.getProperty(modeKey);
        System.setProperty(propertyKey, "definitely-missing-codex-binary-for-test");
        System.setProperty(runDirKey, tempDir.resolve("provider-runs").toString());
        System.clearProperty(modeKey);
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
            assertEquals("provider_not_installed", result.metadata().get("provider_failure_class"));
            assertTrue(result.metadata().get("provider_failure_reason").toString()
                .contains("definitely-missing-codex-binary-for-test"));
            assertEquals(false, result.metadata().get("provider_retryable"));
            assertTrue(Files.exists(Path.of(result.metadata().get("provider_run_dir").toString())));
            assertTrue(Files.exists(Path.of(result.metadata().get("provider_prompt_path").toString())));
            assertTrue(Files.exists(Path.of(result.metadata().get("provider_event_log_path").toString())));
            assertTrue(Files.exists(Path.of(result.metadata().get("provider_last_message_path").toString())));
            assertTrue(Files.exists(Path.of(result.metadata().get("provider_run_metadata_path").toString())));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
            if (originalRunDir == null) {
                System.clearProperty(runDirKey);
            } else {
                System.setProperty(runDirKey, originalRunDir);
            }
            if (originalMode == null) {
                System.clearProperty(modeKey);
            } else {
                System.setProperty(modeKey, originalMode);
            }
        }
    }

    @Test
    void execJsonModeRunsCodexExecAndReadsLastMessageRunFiles() throws Exception {
        String propertyKey = "agentcloud.providers.codex.path";
        String runDirKey = "agentcloud.provider_runs.dir";
        String modeKey = "agentcloud.providers.codex.execution_mode";
        String original = System.getProperty(propertyKey);
        String originalRunDir = System.getProperty(runDirKey);
        String originalMode = System.getProperty(modeKey);
        Path cli = fakeCodexExecJsonCli();
        System.setProperty(propertyKey, cli.toString());
        System.setProperty(runDirKey, tempDir.resolve("codex-exec-json-runs").toString());
        System.setProperty(modeKey, "exec_json");
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            CodexAppServerWorkerExecutor executor = new CodexAppServerWorkerExecutor(registry, null);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("codex"), "codex");

            assertEquals("completed", result.executionStatus());
            assertEquals("provider_native_cli_json", result.metadata().get("execution_backend"));
            assertEquals("codex_exec_json", result.metadata().get("provider_output_parser"));
            assertEquals("session_exec_json_test", result.metadata().get("provider_session_id"));
            assertEquals("codex exec json result", result.outputText().trim());
            assertTrue(Path.of(result.metadata().get("provider_prompt_path").toString()).toFile().isFile());
            assertTrue(Files.readString(Path.of(result.metadata().get("provider_event_log_path").toString()))
                .contains("session_exec_json_test"));
            assertEquals("codex exec json result",
                Files.readString(Path.of(result.metadata().get("provider_last_message_path").toString())).trim());
            assertTrue(result.metadata().get("cli_command_preview").toString().contains("exec --json -o"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
            if (originalRunDir == null) {
                System.clearProperty(runDirKey);
            } else {
                System.setProperty(runDirKey, originalRunDir);
            }
            if (originalMode == null) {
                System.clearProperty(modeKey);
            } else {
                System.setProperty(modeKey, originalMode);
            }
        }
    }

    @Test
    void codexRunFileMetadataIsAttachedToFailureResult() throws Exception {
        String runDirKey = "agentcloud.provider_runs.dir";
        String originalRunDir = System.getProperty(runDirKey);
        System.setProperty(runDirKey, tempDir.resolve("codex-runs").toString());
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            CodexAppServerWorkerExecutor executor = new CodexAppServerWorkerExecutor(registry, null);

            Method buildPlan = CodexAppServerWorkerExecutor.class.getDeclaredMethod(
                "buildPlan",
                LocalCliProviderConfig.ResolvedConfig.class,
                TaskRuntimeContext.class,
                String.class
            );
            buildPlan.setAccessible(true);
            Object plan = buildPlan.invoke(
                executor,
                new LocalCliProviderConfig("codex", "codex", "X", "Y").resolve(),
                runtimeContext("codex"),
                "D:\\gitAll\\agent-cloud-harness"
            );
            Class<?> runFilesClass = Class.forName("com.agentcloud.worker.CodexAppServerWorkerExecutor$ProviderRunFiles");
            Method createRunFiles = runFilesClass.getDeclaredMethod(
                "create",
                String.class,
                String.class,
                String.class,
                plan.getClass()
            );
            createRunFiles.setAccessible(true);
            Object runFiles = createRunFiles.invoke(null, "codex", "task_codex_executor", "codex", plan);

            Method failureResult = CodexAppServerWorkerExecutor.class.getDeclaredMethod(
                "failureResult",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                plan.getClass(),
                com.agentcloud.agent.AgentProviderStatus.class,
                long.class,
                Integer.class,
                String.class,
                runFilesClass
            );
            failureResult.setAccessible(true);
            WorkerExecutionResult result = (WorkerExecutionResult) failureResult.invoke(
                executor,
                "failed",
                "codex app-server test failure",
                "codex",
                "codex",
                "D:\\gitAll\\agent-cloud-harness",
                plan,
                null,
                12L,
                null,
                null,
                runFiles
            );

            Path promptPath = Path.of(result.metadata().get("provider_prompt_path").toString());
            Path metadataPath = Path.of(result.metadata().get("provider_run_metadata_path").toString());
            assertTrue(Files.exists(promptPath));
            assertTrue(Files.exists(metadataPath));
            assertTrue(Files.readString(promptPath).contains("Task Focus:"));
            assertTrue(Files.readString(metadataPath).contains("codex app-server test failure"));
        } finally {
            if (originalRunDir == null) {
                System.clearProperty(runDirKey);
            } else {
                System.setProperty(runDirKey, originalRunDir);
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
    void resumeThreadIdReturnsNullDuringRecoveryColdStartStages() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        CodexAppServerWorkerExecutor executor = new CodexAppServerWorkerExecutor(registry, null);
        Method method = CodexAppServerWorkerExecutor.class.getDeclaredMethod("resumeThreadId", TaskRuntimeContext.class);
        method.setAccessible(true);

        TaskRuntimeContext sameWorkerRetry = runtimeContext("codex", new LinkedHashMap<>(java.util.Map.of(
            "task_type", "coding",
            "intent", "Verify codex recovery cold-start retry skips resume.",
            "workspace", "D:\\gitAll\\agent-cloud-harness",
            "recovery_stage", "same_worker_retry_scheduled",
            "provider_session_id", "generic-provider-session",
            "provider_thread_id", "generic-provider-thread",
            "codex_thread_id", "codex-thread-specific"
        )));
        TaskRuntimeContext autoHandoff = runtimeContext("codex", new LinkedHashMap<>(java.util.Map.of(
            "task_type", "coding",
            "intent", "Verify codex recovery handoff skips resume.",
            "workspace", "D:\\gitAll\\agent-cloud-harness",
            "recovery_stage", "auto_handoff_scheduled",
            "provider_session_id", "generic-provider-session",
            "provider_thread_id", "generic-provider-thread",
            "codex_thread_id", "codex-thread-specific"
        )));

        assertEquals(null, method.invoke(executor, sameWorkerRetry));
        assertEquals(null, method.invoke(executor, autoHandoff));
    }

    @Test
    void legacyTurnAbortedWithOutputPreservesAbortReasonAsPartialTimeout() throws Exception {
        Class<?> sessionClass = Class.forName("com.agentcloud.worker.CodexAppServerWorkerExecutor$JsonRpcSession");
        Constructor<?> constructor = sessionClass.getDeclaredConstructor(
            java.io.Writer.class,
            BufferedReader.class,
            java.io.OutputStream.class
        );
        constructor.setAccessible(true);
        String output = "x".repeat(140);
        StringReader input = new StringReader(
            "{\"jsonrpc\":\"2.0\",\"method\":\"codex/event\",\"params\":{\"msg\":{\"type\":\"agent_message\",\"message\":\"" + output + "\"}}}\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"codex/event\",\"params\":{\"msg\":{\"type\":\"turn_aborted\",\"reason\":\"user_interrupted\"}}}\n"
        );
        Object session = constructor.newInstance(
            new StringWriter(),
            new BufferedReader(input),
            new ByteArrayOutputStream()
        );

        Method nextEnvelope = sessionClass.getDeclaredMethod("nextEnvelope", long.class);
        Method handleEnvelope = sessionClass.getDeclaredMethod("handleEnvelope", com.fasterxml.jackson.databind.JsonNode.class);
        Method toOutput = sessionClass.getDeclaredMethod("toOutput", String.class, String.class);
        nextEnvelope.setAccessible(true);
        handleEnvelope.setAccessible(true);
        toOutput.setAccessible(true);

        Object first = nextEnvelope.invoke(session, System.currentTimeMillis() + 1_000L);
        handleEnvelope.invoke(session, first);
        Object second = nextEnvelope.invoke(session, System.currentTimeMillis() + 1_000L);
        handleEnvelope.invoke(session, second);
        Object result = toOutput.invoke(session, "thread_abort_1", null);

        Method status = result.getClass().getDeclaredMethod("status");
        Method turnStatus = result.getClass().getDeclaredMethod("turnStatus");
        Method abortReason = result.getClass().getDeclaredMethod("abortReason");
        status.setAccessible(true);
        turnStatus.setAccessible(true);
        abortReason.setAccessible(true);

        assertEquals("partial_timeout", status.invoke(result));
        assertEquals("partial_timeout", turnStatus.invoke(result));
        assertEquals("user_interrupted", abortReason.invoke(result));
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

    @Test
    void promptBuilderIncludesWorkspaceRootsAsWorkspaceReferences() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        CodexAppServerWorkerExecutor executor = new CodexAppServerWorkerExecutor(registry, null);
        TaskRuntimeContext context = runtimeContext("codex", new LinkedHashMap<>(Map.of(
            "task_type", "coding",
            "intent", "检查本地仓库并补测试。",
            "workspace_roots", List.of("D:\\gitAll\\articleeditor")
        )));

        Method method = CodexAppServerWorkerExecutor.class.getDeclaredMethod(
            "buildPlan",
            LocalCliProviderConfig.ResolvedConfig.class,
            TaskRuntimeContext.class,
            String.class
        );
        method.setAccessible(true);
        Object plan = method.invoke(
            executor,
            new LocalCliProviderConfig("codex", "codex", "X", "Y").resolve(),
            context,
            "D:\\gitAll\\articleeditor"
        );
        Method promptGetter = plan.getClass().getDeclaredMethod("prompt");
        promptGetter.setAccessible(true);
        String prompt = (String) promptGetter.invoke(plan);

        assertTrue(prompt.contains("Workspaces:"));
        assertTrue(prompt.contains("D:\\gitAll\\articleeditor"));
    }

    @Test
    void codexResolvesWorkingDirectoryFromSingleWorkspaceRootsWhenExplicitCwdMissing() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        CodexAppServerWorkerExecutor executor = new CodexAppServerWorkerExecutor(registry, null);
        TaskRuntimeContext context = runtimeContext("codex", new LinkedHashMap<>(Map.of(
            "task_type", "coding",
            "intent", "按 workspace_roots 执行本地代码任务。",
            "workspace_roots", List.of("D:\\gitAll\\articleeditor")
        )));

        Method method = CodexAppServerWorkerExecutor.class.getDeclaredMethod(
            "resolveWorkingDirectory",
            TaskRuntimeContext.class,
            com.agentcloud.model.Worker.class
        );
        method.setAccessible(true);

        assertEquals("D:\\gitAll\\articleeditor", method.invoke(executor, context, null));
    }

    @Test
    void codexDoesNotCollapseMultipleWorkspaceRootsIntoArbitraryCwd() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        CodexAppServerWorkerExecutor executor = new CodexAppServerWorkerExecutor(registry, null);
        TaskRuntimeContext context = runtimeContext("codex", new LinkedHashMap<>(Map.of(
            "task_type", "coding",
            "intent", "多仓库任务应由 ChatFacade 拆成子任务。",
            "workspace_roots", List.of("D:\\gitAll\\articleeditor", "D:\\gitAll\\agent-cloud-harness")
        )));

        Method method = CodexAppServerWorkerExecutor.class.getDeclaredMethod(
            "resolveWorkingDirectory",
            TaskRuntimeContext.class,
            com.agentcloud.model.Worker.class
        );
        method.setAccessible(true);

        assertEquals(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().toString(),
            method.invoke(executor, context, null));
    }

    @Test
    void codexPlanUsesResolvedWindowsLaunchWrapperMetadata() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        CodexAppServerWorkerExecutor executor = new CodexAppServerWorkerExecutor(registry, null);
        Path cmdShim = Files.writeString(tempDir.resolve("codex.cmd"), "@echo off\r\necho codex\r\n");

        Method method = CodexAppServerWorkerExecutor.class.getDeclaredMethod(
            "buildPlan",
            LocalCliProviderConfig.ResolvedConfig.class,
            TaskRuntimeContext.class,
            String.class
        );
        method.setAccessible(true);

        Object plan = method.invoke(
            executor,
            new LocalCliProviderConfig("codex", cmdShim.toString(), "X", "Y").resolve(),
            runtimeContext("codex"),
            "D:\\gitAll\\agent-cloud-harness"
        );

        Method commandGetter = plan.getClass().getDeclaredMethod("command");
        Method configuredBinaryGetter = plan.getClass().getDeclaredMethod("configuredBinary");
        Method executableTargetGetter = plan.getClass().getDeclaredMethod("executableTarget");
        Method launchModeGetter = plan.getClass().getDeclaredMethod("launchMode");
        commandGetter.setAccessible(true);
        configuredBinaryGetter.setAccessible(true);
        executableTargetGetter.setAccessible(true);
        launchModeGetter.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) commandGetter.invoke(plan);
        assertEquals(List.of("cmd.exe", "/c", cmdShim.toString(), "app-server", "--listen", "stdio://"), command);
        assertEquals(cmdShim.toString(), configuredBinaryGetter.invoke(plan));
        assertEquals(cmdShim.toString(), executableTargetGetter.invoke(plan));
        assertEquals("cmd_file", launchModeGetter.invoke(plan));
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

    private Path fakeCodexExecJsonCli() throws Exception {
        String body = """
            @echo off
            set OUT=
            :loop
            if "%1"=="" goto done
            if "%1"=="-o" (
              set OUT=%2
              shift
              shift
              goto loop
            )
            shift
            goto loop
            :done
            echo {"type":"session.created","session_id":"session_exec_json_test"}
            echo {"type":"message","text":"fallback output"}
            echo codex exec json result> "%OUT%"
            exit /b 0
            """;
        return Files.writeString(tempDir.resolve("codex-exec-json.cmd"), body);
    }
}
