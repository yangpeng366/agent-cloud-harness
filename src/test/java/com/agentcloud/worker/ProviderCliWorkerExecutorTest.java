package com.agentcloud.worker;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.providers.BuiltinAgentProviders;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCliWorkerExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void supportsProviderNativeCliCatalogIncludingDeepSeekButNotCodex() {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

        assertTrue(executor.supports("cursor", null));
        assertTrue(executor.supports("claude", null));
        assertTrue(executor.supports("gemini", null));
        assertTrue(executor.supports("deepseek", null));
        assertTrue(executor.supports("kimi", null));
        assertTrue(executor.supports("copilot", null));
        assertTrue(executor.supports("opencode", null));
        assertEquals(false, executor.supports("codex", null));
    }

    @Test
    void supportsDynamicallyRegisteredNativeCliProvider() {
        ProviderExecutionSupport.registerProviderNativeCli("local_agent");

        AgentProviderRegistry registry = new AgentProviderRegistry();
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

        assertTrue(executor.supports("local_agent", null));
    }

    @Test
    void providerProtocolParseOutputIsUsedForProtocolBackedProvider() throws Exception {
        Path script = tempDir.resolve("protocol-backed-provider.cmd");
        Files.writeString(script, "@echo protocol raw output\r\n");
        System.setProperty("agentcloud.providers.deepseek.path", script.toString());
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderProtocolRegistry protocols = new ProviderProtocolRegistry()
                .register(new StubProtocol("deepseek"));
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry, null, protocols);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("deepseek"), "deepseek");

            assertEquals("completed", result.executionStatus());
            assertEquals("parsed by protocol", result.outputText());
            assertEquals(true, result.metadata().get("provider_protocol_parser_used"));
            assertEquals("stub_protocol", result.metadata().get("provider_output_parser"));
        } finally {
            System.clearProperty("agentcloud.providers.deepseek.path");
        }
    }

    @Test
    void openCodeUsesDefaultProviderProtocolOnExecutePath() throws Exception {
        Path script = tempDir.resolve("opencode-protocol-provider.cmd");
        Files.writeString(script, """
            @echo off
            echo {"type":"text","part":{"text":"opencode protocol ok","sessionID":"opencode-session-2"}}
            """);
        String propertyKey = "agentcloud.providers.opencode.path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, script.toString());
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("opencode"), "opencode");

            assertEquals("completed", result.executionStatus());
            assertEquals("opencode protocol ok", result.outputText());
            assertEquals("opencode-session-2", result.metadata().get("provider_session_id"));
            assertEquals(true, result.metadata().get("provider_protocol_parser_used"));
            assertEquals("opencode_json", result.metadata().get("provider_output_parser"));
            assertEquals("opencode", result.metadata().get("provider_protocol_id"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    @Test
    void copilotUsesDefaultProviderProtocolOnExecutePath() throws Exception {
        Path script = tempDir.resolve("copilot-protocol-provider.cmd");
        Files.writeString(script, """
            @echo off
            echo {"type":"session.start","data":{"sessionId":"copilot-session-2","selectedModel":"gpt-test"}}
            echo {"type":"assistant.message_delta","data":{"deltaContent":"copilot "}}
            echo {"type":"assistant.message_delta","data":{"deltaContent":"protocol ok"}}
            echo {"type":"result","sessionId":"copilot-session-2","exitCode":0}
            """);
        String propertyKey = "agentcloud.providers.copilot.path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, script.toString());
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("copilot"), "copilot");

            assertEquals("completed", result.executionStatus());
            assertEquals("copilot protocol ok", result.outputText());
            assertEquals("copilot-session-2", result.metadata().get("provider_session_id"));
            assertEquals("gpt-test", result.metadata().get("provider_active_model"));
            assertEquals(true, result.metadata().get("provider_protocol_parser_used"));
            assertEquals("copilot_jsonl", result.metadata().get("provider_output_parser"));
            assertEquals("copilot", result.metadata().get("provider_protocol_id"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    @Test
    void kimiUsesDefaultProviderProtocolOnExecutePath() throws Exception {
        Path script = tempDir.resolve("kimi-protocol-provider.cmd");
        Files.writeString(script, """
            @echo off
            echo To resume this session: kimi --resume kimi-session-2
            echo {"role":"assistant","content":[{"type":"text","text":"kimi protocol ok"}]}
            """);
        String propertyKey = "agentcloud.providers.kimi.path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, script.toString());
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("kimi"), "kimi");

            assertEquals("completed", result.executionStatus());
            assertEquals("kimi protocol ok", result.outputText());
            assertEquals("kimi-session-2", result.metadata().get("provider_session_id"));
            assertEquals(true, result.metadata().get("provider_protocol_parser_used"));
            assertEquals("kimi_stream_json", result.metadata().get("provider_output_parser"));
            assertEquals("kimi", result.metadata().get("provider_protocol_id"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    @Test
    void geminiUsesDefaultProviderProtocolOnExecutePath() throws Exception {
        Path script = tempDir.resolve("gemini-protocol-provider.cmd");
        Files.writeString(script, """
            @echo off
            echo {"type":"message","role":"assistant","content":"gemini protocol ok","session_id":"gemini-session-2"}
            """);
        String propertyKey = "agentcloud.providers.gemini.path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, script.toString());
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("gemini"), "gemini");

            assertEquals("completed", result.executionStatus());
            assertEquals("gemini protocol ok", result.outputText());
            assertEquals("gemini-session-2", result.metadata().get("provider_session_id"));
            assertEquals(true, result.metadata().get("provider_protocol_parser_used"));
            assertEquals("gemini_stream_json", result.metadata().get("provider_output_parser"));
            assertEquals("gemini", result.metadata().get("provider_protocol_id"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    @Test
    void cursorUsesDefaultProviderProtocolOnExecutePath() throws Exception {
        Path script = tempDir.resolve("cursor-protocol-provider.cmd");
        Files.writeString(script, """
            @echo off
            echo stdout: {"type":"assistant","message":{"content":[{"type":"text","text":"cursor protocol ok"}]},"session_id":"cursor-session-2"}
            """);
        String propertyKey = "agentcloud.providers.cursor.path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, script.toString());
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("cursor"), "cursor");

            assertEquals("completed", result.executionStatus());
            assertEquals("cursor protocol ok", result.outputText());
            assertEquals("cursor-session-2", result.metadata().get("provider_session_id"));
            assertEquals(true, result.metadata().get("provider_protocol_parser_used"));
            assertEquals("cursor_stream_json", result.metadata().get("provider_output_parser"));
            assertEquals("cursor", result.metadata().get("provider_protocol_id"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    @Test
    void claudeUsesDefaultProviderProtocolOnExecutePath() throws Exception {
        Path script = tempDir.resolve("claude-protocol-provider.cmd");
        Files.writeString(script, """
            @echo off
            more > nul
            echo {"type":"assistant","message":{"content":[{"type":"text","text":"claude protocol ok"}]},"session_id":"claude-session-2"}
            """);
        String propertyKey = "agentcloud.providers.claude.path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, script.toString());
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("claude"), "claude");

            assertEquals("completed", result.executionStatus());
            assertEquals("claude protocol ok", result.outputText());
            assertEquals("claude-session-2", result.metadata().get("provider_session_id"));
            assertEquals(true, result.metadata().get("provider_protocol_parser_used"));
            assertEquals("claude_stream_json", result.metadata().get("provider_output_parser"));
            assertEquals("claude", result.metadata().get("provider_protocol_id"));
            assertEquals("stdin_jsonl", result.metadata().get("cli_prompt_delivery"));
            assertEquals(true, result.metadata().get("cli_uses_stdin"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    @Test
    void outputCaptureCapsHugeProviderOutput() throws Exception {
        Class<?> captureClass = Class.forName("com.agentcloud.worker.ProviderCliWorkerExecutor$OutputCapture");
        Constructor<?> constructor = captureClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object capture = constructor.newInstance();
        byte[] oversizedOutput = new byte[1_050_000];
        java.util.Arrays.fill(oversizedOutput, (byte) 'x');

        Method drain = captureClass.getDeclaredMethod("drain", java.io.InputStream.class, java.io.OutputStream.class);
        Method bytes = captureClass.getDeclaredMethod("bytes");
        Method truncated = captureClass.getDeclaredMethod("truncated");
        Method totalBytes = captureClass.getDeclaredMethod("totalBytes");
        drain.setAccessible(true);
        bytes.setAccessible(true);
        truncated.setAccessible(true);
        totalBytes.setAccessible(true);

        drain.invoke(capture, new ByteArrayInputStream(oversizedOutput), java.io.OutputStream.nullOutputStream());

        assertEquals(1_050_000L, totalBytes.invoke(capture));
        assertEquals(true, truncated.invoke(capture));
        assertTrue(((byte[]) bytes.invoke(capture)).length <= 1_048_576);
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
            assertEquals("provider_not_installed", result.metadata().get("provider_failure_class"));
            assertTrue(result.metadata().get("provider_failure_reason").toString()
                .contains("definitely-missing-claude-binary-for-test"));
            assertEquals(false, result.metadata().get("provider_retryable"));
            assertEquals("stdin_jsonl", result.metadata().get("cli_prompt_delivery"));
            assertEquals(true, result.metadata().get("cli_uses_stdin"));
            assertEquals(false, result.metadata().get("cli_uses_resume"));
            assertEquals("stream_json", result.metadata().get("provider_expected_output_mode"));
            assertEquals("claude_stream_json", result.metadata().get("provider_expected_parser"));
            assertTrue(((List<?>) result.metadata().get("cli_command_shape")).contains("-p"));
            assertTrue(((Number) result.metadata().get("cli_command_arg_count")).intValue() >= 1);
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
            assertEquals("argv_prompt", result.metadata().get("cli_prompt_delivery"));
            assertEquals(false, result.metadata().get("cli_uses_stdin"));
            assertEquals(false, result.metadata().get("cli_uses_resume"));
            assertEquals("jsonl", result.metadata().get("provider_expected_output_mode"));
            assertEquals("copilot_jsonl", result.metadata().get("provider_expected_parser"));
            assertTrue(((List<?>) result.metadata().get("cli_command_shape")).contains("-p"));
            assertTrue(((List<?>) result.metadata().get("cli_command_shape")).contains("<prompt>"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    @Test
    void deepSeekDelegatedReasonixMissingBinaryReturnsFailedMetadataWithoutThrowing() {
        String propertyKey = "agentcloud.providers.reasonix.path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, "definitely-missing-reasonix-binary-for-deepseek-test");
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("deepseek"), "deepseek");

            assertEquals("failed", result.executionStatus());
            assertEquals("provider_native_cli", result.metadata().get("execution_backend"));
            assertEquals("deepseek", result.metadata().get("provider_id"));
            assertEquals("definitely-missing-reasonix-binary-for-deepseek-test", result.metadata().get("cli_binary"));
            assertEquals("argv_prompt", result.metadata().get("cli_prompt_delivery"));
            assertEquals(false, result.metadata().get("cli_uses_stdin"));
            assertEquals(false, result.metadata().get("cli_uses_resume"));
            assertEquals("text", result.metadata().get("provider_expected_output_mode"));
            assertEquals("deepseek_reasonix_text", result.metadata().get("provider_expected_parser"));
            assertEquals("reasonix", result.metadata().get("execution_runtime"));
            assertTrue(((List<?>) result.metadata().get("cli_command_shape")).contains("run"));
            assertTrue(((List<?>) result.metadata().get("cli_command_shape")).contains("--model"));
            assertTrue(((List<?>) result.metadata().get("cli_command_shape")).contains("deepseek-v4-flash"));
            assertTrue(((List<?>) result.metadata().get("cli_command_shape")).contains("<prompt>"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    @Test
    void providerParsersConsumeMinimalSuccessOutputs() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

        assertProviderOutput(
            executor,
            "cursor",
            """
            {"type":"assistant","message":{"content":[{"type":"text","text":"cursor ok"}]},"session_id":"cursor-session-1"}
            """,
            "cursor_stream_json",
            "cursor ok",
            "cursor-session-1"
        );
        assertProviderOutput(
            executor,
            "openclaw",
            """
            {"type":"text","text":"openclaw ok","sessionId":"openclaw-session-1"}
            """,
            "openclaw_json",
            "openclaw ok",
            "openclaw-session-1"
        );
        assertProviderOutput(
            executor,
            "gemini",
            """
            {"type":"message","role":"assistant","content":"gemini ok","session_id":"gemini-session-1"}
            """,
            "gemini_stream_json",
            "gemini ok",
            "gemini-session-1"
        );
        assertProviderOutput(
            executor,
            "deepseek",
            "deepseek ok\n",
            "deepseek_exec_text",
            "deepseek ok",
            null
        );
        assertProviderOutput(
            executor,
            "copilot",
            """
            {"type":"session.start","data":{"sessionId":"copilot-session-1","selectedModel":"gpt-test"}}
            {"type":"assistant.message","data":{"content":"copilot ok","selectedModel":"gpt-test"}}
            """,
            "copilot_jsonl",
            "copilot ok",
            "copilot-session-1"
        );
        assertProviderOutput(
            executor,
            "opencode",
            """
            {"type":"text","part":{"text":"opencode ok","sessionID":"opencode-session-1"}}
            """,
            "opencode_json",
            "opencode ok",
            "opencode-session-1"
        );
    }

    @Test
    void deepSeekUnexpectedArgumentStdoutIsClassifiedAsProtocolError() throws Exception {
        String pathProperty = "agentcloud.providers.reasonix.path";
        String originalPath = System.getProperty(pathProperty);
        Path cli = fakeCli("reasonix-bad-args", unexpectedArgumentCliBody());
        System.setProperty(pathProperty, cli.toString());
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("deepseek"), "deepseek");

            assertEquals("failed", result.executionStatus());
            assertEquals("provider_protocol_error", result.metadata().get("provider_failure_class"));
            assertEquals(true, result.metadata().get("provider_retryable"));
            assertTrue(String.valueOf(result.metadata().get("provider_error")).contains("unexpected argument"));
        } finally {
            restoreProperty(pathProperty, originalPath);
        }
    }

    @Test
    void cursorGeminiOpenClawAndOpenCodeMissingBinaryExposeCommandPlanMetadata() {
        assertMissingBinaryPlanMetadata(
            "cursor",
            "definitely-missing-cursor-binary-for-test",
            "argv_prompt",
            false,
            "stream_json",
            "cursor_stream_json",
            List.of("chat", "-p", "<prompt>", "--output-format", "stream-json", "--workspace")
        );
        assertMissingBinaryPlanMetadata(
            "gemini",
            "definitely-missing-gemini-binary-for-test",
            "argv_prompt",
            false,
            "stream_json",
            "gemini_stream_json",
            List.of("-p", "<prompt>", "--yolo", "-o", "stream-json")
        );
        assertMissingBinaryPlanMetadata(
            "openclaw",
            "definitely-missing-openclaw-binary-for-test",
            "argv_prompt",
            false,
            "stream_json",
            "openclaw_json",
            List.of("agent", "--local", "--json", "--message", "<prompt>")
        );
        assertMissingBinaryPlanMetadata(
            "opencode",
            "definitely-missing-opencode-binary-for-test",
            "argv_prompt",
            false,
            "json",
            "opencode_json",
            List.of("run", "--format", "json", "<prompt>")
        );
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
    void deepSeekPlanDelegatesToReasonixRunWithDeepSeekModel() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);
        TaskRuntimeContext context = runtimeContext("deepseek", new LinkedHashMap<>(Map.of(
            "task_type", "coding",
            "intent", "用 deepseek 非交互执行单轮任务。",
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
        Method configuredBinaryMethod = plan.getClass().getDeclaredMethod("configuredBinary");
        Method launchModeMethod = plan.getClass().getDeclaredMethod("launchMode");
        commandMethod.setAccessible(true);
        configuredBinaryMethod.setAccessible(true);
        launchModeMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) commandMethod.invoke(plan);

        assertEquals("reasonix", configuredBinaryMethod.invoke(plan));
        assertTrue(List.of("direct", "cmd_file").contains(String.valueOf(launchModeMethod.invoke(plan))));
        assertEquals(false, command.contains("--skip-onboarding"));
        assertEquals(false, command.contains("--yolo"));
        assertEquals(false, command.contains("--provider"));
        assertTrue(command.contains("run"));
        assertTrue(command.contains("--no-config"));
        assertTrue(command.contains("--no-proxy"));
        assertTrue(command.contains("--model"));
        assertTrue(command.contains("deepseek-v4-flash"));
        int promptIndex = command.indexOf("deepseek-v4-flash") + 1;
        assertTrue(promptIndex > 0);
        assertTrue(command.get(promptIndex).contains("Workspaces:"));
        assertTrue(command.get(promptIndex).contains("D:\\gitAll\\agent-cloud-harness"));
    }

    @Test
    void providerCliPlanDropsYoloWhenCliProfileShowsUnsupportedFlag() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);
        TaskRuntimeContext context = runtimeContext("gemini", new LinkedHashMap<>(Map.of(
            "task_type", "coding",
            "intent", "用 gemini 执行本地代码任务。",
            "provider_model", "gemini-pro",
            "workspace", "D:\\gitAll\\agent-cloud-harness"
        )));

        Method method = ProviderCliWorkerExecutor.class.getDeclaredMethod(
            "buildPlan",
            String.class,
            com.agentcloud.agent.providers.LocalCliProviderConfig.ResolvedConfig.class,
            TaskRuntimeContext.class,
            String.class,
            com.agentcloud.agent.providers.CliCapabilityProfile.class
        );
        method.setAccessible(true);
        Object plan = method.invoke(
            executor,
            "gemini",
            new com.agentcloud.agent.providers.LocalCliProviderConfig("gemini", "gemini", "X", "Y").resolve(),
            context,
            "D:\\gitAll\\agent-cloud-harness",
            new com.agentcloud.agent.providers.CliCapabilityProfile(true, false, true, true, true, null, null, null)
        );
        Method commandMethod = plan.getClass().getDeclaredMethod("command");
        Method adjustmentsMethod = plan.getClass().getDeclaredMethod("cliProfileAdjustments");
        commandMethod.setAccessible(true);
        adjustmentsMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) commandMethod.invoke(plan);
        @SuppressWarnings("unchecked")
        List<String> adjustments = (List<String>) adjustmentsMethod.invoke(plan);

        assertEquals(false, command.contains("--yolo"));
        assertTrue(command.contains("-m"));
        assertTrue(adjustments.contains("dropped --yolo"));
    }

    @Test
    void deepSeekResolvesWorkingDirectoryFromGoalPathWhenWorkspaceMetadataMissing() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);
        TaskRuntimeContext context = runtimeContext("deepseek", new LinkedHashMap<>(Map.of(
            "task_type", "coding",
            "intent", "按文档计划执行"
        )), "按文档计划 D:\\gitAll\\articleeditor\\docs\\XINHUA_CNML_ADAPTER_IMPLEMENTATION_PLAN_2026-05-15.md 修改代码");

        Method method = ProviderCliWorkerExecutor.class.getDeclaredMethod(
            "resolveWorkingDirectory",
            TaskRuntimeContext.class,
            com.agentcloud.model.Worker.class
        );
        method.setAccessible(true);

        assertEquals("D:\\gitAll\\articleeditor", method.invoke(executor, context, null));
    }

    @Test
    void deepSeekResolvesWorkingDirectoryFromSingleWorkspaceRootsWhenExplicitCwdMissing() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);
        TaskRuntimeContext context = runtimeContext("deepseek", new LinkedHashMap<>(Map.of(
            "task_type", "coding",
            "intent", "按 workspace_roots 执行本地代码任务。",
            "workspace_roots", List.of("D:\\gitAll\\articleeditor")
        )));

        Method method = ProviderCliWorkerExecutor.class.getDeclaredMethod(
            "resolveWorkingDirectory",
            TaskRuntimeContext.class,
            com.agentcloud.model.Worker.class
        );
        method.setAccessible(true);

        assertEquals("D:\\gitAll\\articleeditor", method.invoke(executor, context, null));
    }

    @Test
    void deepSeekDoesNotCollapseMultipleWorkspaceRootsIntoArbitraryCwd() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);
        TaskRuntimeContext context = runtimeContext("deepseek", new LinkedHashMap<>(Map.of(
            "task_type", "coding",
            "intent", "多仓库任务应由 ChatFacade 拆成子任务。",
            "workspace_roots", List.of("D:\\gitAll\\articleeditor", "D:\\gitAll\\agent-cloud-harness")
        )));

        Method method = ProviderCliWorkerExecutor.class.getDeclaredMethod(
            "resolveWorkingDirectory",
            TaskRuntimeContext.class,
            com.agentcloud.model.Worker.class
        );
        method.setAccessible(true);

        assertEquals(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().toString(),
            method.invoke(executor, context, null));
    }

    @Test
    void kimiPlanUsesPrintModeWorkdirAndSessionMetadata() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);
        TaskRuntimeContext context = runtimeContext("kimi", new LinkedHashMap<>(Map.of(
            "task_type", "coding",
            "intent", "用 kimi 非交互执行单轮任务。",
            "provider_model", "kimi-k2-turbo-preview",
            "workspace", "D:\\gitAll\\agent-cloud-harness",
            "provider_session_id", "kimi-session-001"
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
            "kimi",
            new com.agentcloud.agent.providers.LocalCliProviderConfig("kimi", "kimi", "X", "Y").resolve(),
            context,
            "D:\\gitAll\\agent-cloud-harness"
        );
        Method commandMethod = plan.getClass().getDeclaredMethod("command");
        Method launchModeMethod = plan.getClass().getDeclaredMethod("launchMode");
        commandMethod.setAccessible(true);
        launchModeMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) commandMethod.invoke(plan);

        assertTrue(List.of("direct", "cmd_file", "powershell_file").contains(String.valueOf(launchModeMethod.invoke(plan))));
        assertTrue(command.contains("--print"));
        assertTrue(command.contains("--output-format"));
        assertTrue(command.contains("stream-json"));
        assertTrue(command.contains("--work-dir"));
        assertTrue(command.contains("D:\\gitAll\\agent-cloud-harness"));
        assertTrue(command.contains("--session"));
        assertTrue(command.contains("kimi-session-001"));
        assertTrue(command.contains("--model"));
        assertTrue(command.contains("kimi-k2-turbo-preview"));
        assertTrue(command.contains("--prompt"));
    }

    @Test
    void resumeIdUsesContinuationMetadataInsteadOfSessionIdAndSkipsRecoveryColdStart() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);
        Method method = ProviderCliWorkerExecutor.class.getDeclaredMethod("resumeId", TaskRuntimeContext.class);
        method.setAccessible(true);

        TaskRuntimeContext normal = runtimeContext("claude", new LinkedHashMap<>(Map.of(
            "task_type", "coding",
            "intent", "Verify provider-native resume metadata precedence.",
            "workspace", "D:\\gitAll\\agent-cloud-harness",
            "provider_session_id", "provider-session-001",
            "provider_thread_id", "provider-thread-001"
        )));
        TaskRuntimeContext sameWorkerRetry = runtimeContext("claude", new LinkedHashMap<>(Map.of(
            "task_type", "coding",
            "intent", "Verify same-worker retry skips provider resume.",
            "workspace", "D:\\gitAll\\agent-cloud-harness",
            "recovery_stage", "same_worker_retry_scheduled",
            "provider_session_id", "provider-session-001",
            "provider_thread_id", "provider-thread-001"
        )));
        TaskRuntimeContext autoHandoff = runtimeContext("claude", new LinkedHashMap<>(Map.of(
            "task_type", "coding",
            "intent", "Verify auto handoff skips provider resume.",
            "workspace", "D:\\gitAll\\agent-cloud-harness",
            "recovery_stage", "auto_handoff_scheduled",
            "provider_session_id", "provider-session-001",
            "provider_thread_id", "provider-thread-001"
        )));

        assertEquals("provider-session-001", method.invoke(executor, normal));
        assertEquals(null, method.invoke(executor, sameWorkerRetry));
        assertEquals(null, method.invoke(executor, autoHandoff));
    }

    @Test
    void kimiMissingBinaryReturnsFailedMetadataWithoutThrowing() {
        String propertyKey = "agentcloud.providers.kimi.path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, "definitely-missing-kimi-binary-for-test");
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("kimi"), "kimi");

            assertEquals("failed", result.executionStatus());
            assertEquals("provider_native_cli", result.metadata().get("execution_backend"));
            assertEquals("kimi", result.metadata().get("provider_id"));
            assertEquals("definitely-missing-kimi-binary-for-test", result.metadata().get("cli_binary"));
            assertEquals("argv_prompt", result.metadata().get("cli_prompt_delivery"));
            assertEquals(false, result.metadata().get("cli_uses_stdin"));
            assertEquals(false, result.metadata().get("cli_uses_resume"));
            assertEquals("stream_json", result.metadata().get("provider_expected_output_mode"));
            assertEquals("kimi_stream_json", result.metadata().get("provider_expected_parser"));
            assertTrue(((List<?>) result.metadata().get("cli_command_shape")).contains("--prompt"));
            assertTrue(((List<?>) result.metadata().get("cli_command_shape")).contains("<prompt>"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    @Test
    void kimiMissingBinaryReportsResumeCommandPlanMetadata() {
        String propertyKey = "agentcloud.providers.kimi.path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, "definitely-missing-kimi-binary-for-test");
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);
            TaskRuntimeContext context = runtimeContext("kimi", new LinkedHashMap<>(Map.of(
                "task_type", "coding",
                "intent", "verify kimi resume metadata",
                "workspace", "D:\\gitAll\\agent-cloud-harness",
                "provider_session_id", "kimi-session-001"
            )));

            WorkerExecutionResult result = executor.executeOneRound(context, "kimi");

            assertEquals("failed", result.executionStatus());
            assertEquals(true, result.metadata().get("cli_uses_resume"));
            assertEquals("--session", result.metadata().get("cli_resume_arg_name"));
            assertTrue(((List<?>) result.metadata().get("cli_command_shape")).contains("--session"));
            assertTrue(((List<?>) result.metadata().get("cli_command_shape")).contains("kimi-session-001"));
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    @Test
    void nativeCliProvidersExposeResumeCommandPlanMetadata() {
        assertMissingBinaryResumeMetadata("claude", "--resume");
        assertMissingBinaryResumeMetadata("cursor", "--resume");
        assertMissingBinaryResumeMetadata("gemini", "-r");
        assertMissingBinaryResumeMetadata("openclaw", "--session-id");
        assertMissingBinaryResumeMetadata("copilot", "--resume");
        assertMissingBinaryResumeMetadata("opencode", "--session");
    }

    @Test
    void providerNativeCliWritesRunFilesAndTruncatesSqliteOutputText() throws Exception {
        String pathProperty = "agentcloud.providers.reasonix.path";
        String runsProperty = "agentcloud.provider_runs.dir";
        String originalPath = System.getProperty(pathProperty);
        String originalRunsDir = System.getProperty(runsProperty);
        Path cli = fakeCli("reasonix-run-files", oversizedCliBody());
        Path runRoot = tempDir.resolve("provider-runs");
        System.setProperty(pathProperty, cli.toString());
        System.setProperty(runsProperty, runRoot.toString());
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext("deepseek"), "deepseek");

            assertEquals("failed", result.executionStatus());
            assertEquals(true, result.metadata().get("provider_output_truncated"));
            assertTrue(result.outputText().length() <= 16_384);
            Path stdoutPath = Path.of(result.metadata().get("provider_stdout_path").toString());
            Path lastMessagePath = Path.of(result.metadata().get("provider_last_message_path").toString());
            Path metadataPath = Path.of(result.metadata().get("provider_run_metadata_path").toString());
            assertTrue(Files.exists(stdoutPath));
            assertTrue(Files.size(stdoutPath) > 1_048_576L);
            assertTrue(Files.exists(lastMessagePath));
            assertTrue(Files.exists(metadataPath));
            assertTrue(Files.readString(metadataPath).contains("provider_output_truncated"));
        } finally {
            restoreProperty(pathProperty, originalPath);
            restoreProperty(runsProperty, originalRunsDir);
        }
    }

    @Test
    void claudePlanUsesWindowsCmdWrapperLaunchMetadata() throws Exception {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);
        ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);
        Path cmdShim = Files.writeString(tempDir.resolve("claude.cmd"), "@echo off\r\necho claude\r\n");

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
            new com.agentcloud.agent.providers.LocalCliProviderConfig("claude", cmdShim.toString(), "X", "Y").resolve(),
            runtimeContext("claude"),
            "D:\\gitAll\\agent-cloud-harness"
        );

        Method commandMethod = plan.getClass().getDeclaredMethod("command");
        Method configuredBinaryMethod = plan.getClass().getDeclaredMethod("configuredBinary");
        Method executableTargetMethod = plan.getClass().getDeclaredMethod("executableTarget");
        Method launchModeMethod = plan.getClass().getDeclaredMethod("launchMode");
        commandMethod.setAccessible(true);
        configuredBinaryMethod.setAccessible(true);
        executableTargetMethod.setAccessible(true);
        launchModeMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) commandMethod.invoke(plan);
        assertEquals("cmd.exe", command.get(0));
        assertEquals("/c", command.get(1));
        assertEquals(cmdShim.toString(), command.get(2));
        assertEquals("-p", command.get(3));
        assertEquals(cmdShim.toString(), configuredBinaryMethod.invoke(plan));
        assertEquals(cmdShim.toString(), executableTargetMethod.invoke(plan));
        assertEquals("cmd_file", launchModeMethod.invoke(plan));
    }

    private TaskRuntimeContext runtimeContext(String workerId) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        metadata.put("intent", "Verify provider-native execution routing.");
        metadata.put("workspace", "D:\\gitAll\\agent-cloud-harness");
        return runtimeContext(workerId, metadata);
    }

    private TaskRuntimeContext runtimeContext(String workerId, LinkedHashMap<String, Object> metadata) {
        return runtimeContext(workerId, metadata, "run a single round");
    }

    private TaskRuntimeContext runtimeContext(String workerId, LinkedHashMap<String, Object> metadata, String goal) {
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
            goal,
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

    private void assertProviderOutput(ProviderCliWorkerExecutor executor,
                                      String providerId,
                                      String rawOutput,
                                      String expectedParser,
                                      String expectedOutput,
                                      String expectedSessionId) throws Exception {
        Method consume = ProviderCliWorkerExecutor.class.getDeclaredMethod("consume", byte[].class, String.class);
        consume.setAccessible(true);
        Object output = consume.invoke(executor, rawOutput.getBytes(java.nio.charset.StandardCharsets.UTF_8), providerId);
        Method status = output.getClass().getDeclaredMethod("status");
        Method outputText = output.getClass().getDeclaredMethod("outputText");
        Method sessionId = output.getClass().getDeclaredMethod("sessionId");
        Method parser = output.getClass().getDeclaredMethod("parser");
        status.setAccessible(true);
        outputText.setAccessible(true);
        sessionId.setAccessible(true);
        parser.setAccessible(true);

        assertEquals("completed", status.invoke(output));
        assertEquals(expectedParser, parser.invoke(output));
        assertEquals(expectedOutput, outputText.invoke(output));
        assertEquals(expectedSessionId, sessionId.invoke(output));
    }

    private void assertMissingBinaryPlanMetadata(String providerId,
                                                 String missingBinary,
                                                 String expectedPromptDelivery,
                                                 boolean expectedStdin,
                                                 String expectedOutputMode,
                                                 String expectedParser,
                                                 List<String> expectedShapeEntries) {
        String propertyKey = "agentcloud.providers." + providerId + ".path";
        String original = System.getProperty(propertyKey);
        System.setProperty(propertyKey, missingBinary);
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);

            WorkerExecutionResult result = executor.executeOneRound(runtimeContext(providerId), providerId);

            assertEquals("failed", result.executionStatus());
            assertEquals("provider_native_cli", result.metadata().get("execution_backend"));
            assertEquals(providerId, result.metadata().get("provider_id"));
            assertEquals(missingBinary, result.metadata().get("cli_binary"));
            assertEquals(expectedPromptDelivery, result.metadata().get("cli_prompt_delivery"));
            assertEquals(expectedStdin, result.metadata().get("cli_uses_stdin"));
            assertEquals(false, result.metadata().get("cli_uses_resume"));
            assertEquals(expectedOutputMode, result.metadata().get("provider_expected_output_mode"));
            assertEquals(expectedParser, result.metadata().get("provider_expected_parser"));
            List<?> shape = (List<?>) result.metadata().get("cli_command_shape");
            for (String expectedEntry : expectedShapeEntries) {
                assertTrue(shape.contains(expectedEntry), () -> providerId + " command shape should contain " + expectedEntry + ": " + shape);
            }
            assertTrue(((Number) result.metadata().get("cli_command_arg_count")).intValue() >= expectedShapeEntries.size());
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    private void assertMissingBinaryResumeMetadata(String providerId, String expectedResumeArgName) {
        String propertyKey = "agentcloud.providers." + providerId + ".path";
        String original = System.getProperty(propertyKey);
        String missingBinary = "definitely-missing-" + providerId + "-binary-for-test";
        String sessionId = providerId + "-session-001";
        System.setProperty(propertyKey, missingBinary);
        try {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            ProviderCliWorkerExecutor executor = new ProviderCliWorkerExecutor(registry);
            TaskRuntimeContext context = runtimeContext(providerId, new LinkedHashMap<>(Map.of(
                "task_type", "coding",
                "intent", "verify provider resume metadata",
                "workspace", "D:\\gitAll\\agent-cloud-harness",
                "provider_session_id", sessionId
            )));

            WorkerExecutionResult result = executor.executeOneRound(context, providerId);

            assertEquals("failed", result.executionStatus());
            assertEquals(true, result.metadata().get("cli_uses_resume"));
            assertEquals(expectedResumeArgName, result.metadata().get("cli_resume_arg_name"));
            List<?> shape = (List<?>) result.metadata().get("cli_command_shape");
            assertTrue(shape.contains(expectedResumeArgName), () -> providerId + " command shape should contain resume arg: " + shape);
            assertTrue(shape.contains(sessionId), () -> providerId + " command shape should contain resume session id: " + shape);
        } finally {
            if (original == null) {
                System.clearProperty(propertyKey);
            } else {
                System.setProperty(propertyKey, original);
            }
        }
    }

    private static final class StubProtocol implements ProviderProtocol {
        private final String providerId;

        private StubProtocol(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public ProviderStatus detect(com.agentcloud.agent.providers.LocalCliProviderConfig.ResolvedConfig config) {
            return new ProviderStatus(true, null, Map.of());
        }

        @Override
        public ProviderCliPlan buildPlan(com.agentcloud.agent.providers.LocalCliProviderConfig.ResolvedConfig config,
                                         TaskRuntimeContext context,
                                         String cwd,
                                         com.agentcloud.agent.providers.CliCapabilityProfile profile) {
            return new ProviderCliPlan(
                config.launchSpec().command(List.of()),
                "stub",
                "",
                null,
                Map.of(),
                config.launchSpec().configuredBinary(),
                config.launchSpec().executableTarget(),
                config.launchSpec().launchMode(),
                profile,
                List.of()
            );
        }

        @Override
        public WorkerExecutionResult parseOutput(byte[] raw,
                                                 ProviderCliPlan plan,
                                                 long durationMs,
                                                 Map<String, Object> baseMetadata) {
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
            metadata.put("provider_output_parser", "stub_protocol");
            metadata.put("raw_seen", raw != null && raw.length > 0);
            return new WorkerExecutionResult(
                "protocol summary",
                "parsed by protocol",
                false,
                "",
                "",
                "",
                "medium",
                "completed",
                List.of(),
                List.of(),
                0,
                durationMs,
                metadata,
                ExecutionOutcome.COMPLETED
            );
        }
    }

    private Path fakeCli(String baseName, String body) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path path = tempDir.resolve(baseName + (windows ? ".cmd" : ".sh"));
        String content = windows
            ? "@echo off\r\n" + body + "\r\n"
            : "#!/usr/bin/env sh\n" + body + "\n";
        Files.writeString(path, content);
        if (!windows) {
            path.toFile().setExecutable(true);
        }
        return path;
    }

    private String oversizedCliBody() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows
            ? "powershell -NoProfile -Command \"[Console]::Out.Write(('x' * 1050000))\""
            : "python3 - <<'PY'\nprint('x' * 1050000, end='')\nPY";
    }

    private String unexpectedArgumentCliBody() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows
            ? "echo error: unexpected argument '--yolo' found\r\necho Usage: deepseek [OPTIONS] [PROMPT]\r\nexit /b 2"
            : "echo \"error: unexpected argument '--yolo' found\"\necho \"Usage: deepseek [OPTIONS] [PROMPT]\"\nexit 2";
    }

    private void restoreProperty(String key, String original) {
        if (original == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, original);
        }
    }
}
