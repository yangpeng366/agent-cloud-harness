package com.agentcloud.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.TaskRuntimeContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderProtocolDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversGenericProviderFromYamlConfig() throws Exception {
        Path config = tempDir.resolve("providers.yaml");
        Files.writeString(config, """
            providers:
              - id: local_echo
                type: generic
                command:
                  - echo
                  - "{{prompt}}"
                output_parser: text
            """);

        ProviderProtocolRegistry registry = new ProviderProtocolDiscovery(List.of(config)).discover();

        ProviderProtocol protocol = registry.get("local_echo");
        assertNotNull(protocol);
        assertEquals("local_echo", protocol.providerId());

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            new LocalCliProviderConfig("local_echo", "local_echo", null, null).resolve(),
            runtimeContext("echo command shape"),
            tempDir.toString(),
            null
        );

        assertEquals("echo", plan.command().get(0));
        assertEquals(1, plan.command().stream().filter("echo"::equals).count());
    }

    @Test
    void discoversYamlConfigWithUtf8Bom() throws Exception {
        Path config = tempDir.resolve("providers.yaml");
        Files.writeString(config, "\uFEFF" + """
            providers:
              - id: bom_agent
                protocol: native_cli_text
                binary: bom-agent
            """);

        ProviderProtocolDiscovery.DiscoveryResult result =
            new ProviderProtocolDiscovery(List.of(config)).discoverDetailed();

        assertNotNull(result.registry().get("bom_agent"));
        assertEquals(1, result.providers().size());
        assertEquals("bom_agent", result.providers().get(0).id());
    }

    @Test
    void discoversGenericProviderFromDocumentedProtocolBinaryArgsYamlShape() throws Exception {
        Path config = tempDir.resolve("providers.yaml");
        Files.writeString(config, """
            providers:
              - id: trae
                binary: trae
                args: ["chat", "--mode", "agent"]
                protocol: native_cli_text
                env:
                  TRAE_MODE: local
            """);

        ProviderProtocolRegistry registry = new ProviderProtocolDiscovery(List.of(config)).discover();

        ProviderProtocol protocol = registry.get("trae");
        assertNotNull(protocol);

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            new LocalCliProviderConfig("trae", "trae", null, null).resolve(),
            runtimeContext("use provider config shape"),
            tempDir.toString(),
            null
        );

        assertEquals("trae", protocol.providerId());
        assertEquals("trae", plan.configuredBinary());
        assertTrue(plan.command().stream().filter("trae"::equals).count() <= 1);
        assertTrue(plan.command().contains("chat"));
        assertTrue(plan.command().contains("--mode"));
        assertTrue(plan.command().contains("agent"));
        assertEquals("local", plan.environment().get("TRAE_MODE"));
        assertTrue(String.join(" ", plan.command()).contains("use provider config shape"));
    }

    @Test
    void discoversGenericProviderFromJsonConfigWithProtocolAliases() throws Exception {
        Path config = tempDir.resolve("providers.json");
        Files.writeString(config, """
            {
              "providers": [
                {
                  "id": "json_agent",
                  "protocol": "native_cli_text",
                  "binary": "json-agent",
                  "args": ["run"],
                  "env": {
                    "JSON_AGENT_MODE": "local"
                  }
                }
              ]
            }
            """);

        ProviderProtocolRegistry registry = new ProviderProtocolDiscovery(List.of(config)).discover();

        ProviderProtocol protocol = registry.get("json_agent");
        assertNotNull(protocol);

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            new LocalCliProviderConfig("json_agent", "json-agent", null, null).resolve(),
            runtimeContext("json provider config"),
            tempDir.toString(),
            null
        );

        assertTrue(plan.command().contains("run"));
        assertEquals("json-agent", plan.configuredBinary());
        assertEquals("local", plan.environment().get("JSON_AGENT_MODE"));
    }

    @Test
    void discoveryResultExposesProviderInventoryConfig() throws Exception {
        Path config = tempDir.resolve("providers.yaml");
        Files.writeString(config, """
            providers:
              - id: local_agent
                display_name: Local Agent
                protocol: native_cli_text
                binary: local-agent
                capabilities: ["coding", "research"]
                model_tier: small
                selection_priority: 61
            """);

        ProviderProtocolDiscovery.DiscoveryResult result =
            new ProviderProtocolDiscovery(List.of(config)).discoverDetailed();

        assertNotNull(result.registry().get("local_agent"));
        assertEquals(1, result.providers().size());
        ProviderProtocolDiscovery.DiscoveredProvider provider = result.providers().get(0);
        assertEquals("local_agent", provider.id());
        assertEquals("Local Agent", provider.displayName());
        assertEquals("local-agent", provider.binary());
        assertEquals(List.of("coding", "research"), provider.capabilities());
        assertEquals("small", provider.metadata().get("model_tier"));
        assertEquals(61, provider.metadata().get("selection_priority"));
    }

    @Test
    void discoversNativeCliStreamJsonProviderAsStreamJsonParser() throws Exception {
        Path config = tempDir.resolve("providers.yaml");
        Files.writeString(config, """
            providers:
              - id: stream_agent
                protocol: native_cli_stream_json
                binary: stream-agent
                args:
                  - run
            """);

        ProviderProtocolRegistry registry = new ProviderProtocolDiscovery(List.of(config)).discover();

        ProviderProtocol protocol = registry.get("stream_agent");
        assertNotNull(protocol);

        WorkerExecutionResult result = protocol.parseOutput(
            """
            {"type":"message","content":"first"}
            {"type":"result","content":"done"}
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            new ProviderProtocol.ProviderCliPlan(List.of("stream-agent"), "", ""),
            12,
            Map.of()
        );

        assertEquals("completed", result.executionStatus());
        assertEquals("STREAM_JSON", result.metadata().get("provider_output_parser"));
        assertEquals("first\ndone", result.outputText());
        assertEquals(2, result.metadata().get("stream_json_event_count"));
        assertEquals(2, result.metadata().get("stream_json_parsed_event_count"));
    }

    @Test
    void nativeCliStreamJsonParserMarksErrorEventsAsFailed() throws Exception {
        Path config = tempDir.resolve("providers.yaml");
        Files.writeString(config, """
            providers:
              - id: stream_error_agent
                protocol: native_cli_stream_json
                binary: stream-agent
            """);

        ProviderProtocol protocol = new ProviderProtocolDiscovery(List.of(config)).discover().get("stream_error_agent");
        assertNotNull(protocol);

        WorkerExecutionResult result = protocol.parseOutput(
            """
            {"type":"assistant","message":{"content":[{"type":"text","text":"partial"}]}}
            {"type":"result","status":"error","message":"provider failed"}
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            new ProviderProtocol.ProviderCliPlan(List.of("stream-agent"), "", ""),
            12,
            Map.of()
        );

        assertEquals("failed", result.executionStatus());
        assertEquals(ExecutionOutcome.FAILED, result.outcome());
        assertEquals("partial\nprovider failed", result.outputText());
        assertEquals("provider failed", result.metadata().get("stream_json_error_text"));
    }

    @Test
    void infersNativeCliTextProtocolWhenBinaryIsConfiguredWithoutProtocol() throws Exception {
        Path config = tempDir.resolve("providers.yaml");
        Files.writeString(config, """
            providers:
              - id: inferred_binary_agent
                binary: inferred-agent
                args: ["run"]
            """);

        ProviderProtocolDiscovery.DiscoveryResult result =
            new ProviderProtocolDiscovery(List.of(config)).discoverDetailed();

        ProviderProtocol protocol = result.registry().get("inferred_binary_agent");
        assertNotNull(protocol);
        assertEquals(1, result.providers().size());
        assertEquals("native_cli_text", result.providers().get(0).protocol());
        assertEquals(true, result.providers().get(0).metadata().get("provider_protocol_inferred"));

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            new LocalCliProviderConfig("inferred_binary_agent", "inferred-agent", null, null).resolve(),
            runtimeContext("inferred protocol prompt"),
            tempDir.toString(),
            null
        );

        assertEquals("inferred-agent", plan.configuredBinary());
        assertTrue(plan.command().contains("run"));
        assertTrue(String.join(" ", plan.command()).contains("inferred protocol prompt"));
    }

    @Test
    void infersNativeCliTextProtocolWhenCommandIsConfiguredWithoutProtocol() throws Exception {
        Path config = tempDir.resolve("providers.json");
        Files.writeString(config, """
            {
              "providers": [
                {
                  "id": "inferred_command_agent",
                  "command": ["echo", "{{prompt}}"]
                }
              ]
            }
            """);

        ProviderProtocolDiscovery.DiscoveryResult result =
            new ProviderProtocolDiscovery(List.of(config)).discoverDetailed();

        ProviderProtocol protocol = result.registry().get("inferred_command_agent");
        assertNotNull(protocol);
        assertEquals("native_cli_text", result.providers().get(0).protocol());
        assertEquals(true, result.providers().get(0).metadata().get("provider_protocol_inferred"));

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            new LocalCliProviderConfig("inferred_command_agent", "echo", null, null).resolve(),
            runtimeContext("command protocol prompt"),
            tempDir.toString(),
            null
        );

        assertEquals("echo", plan.command().get(0));
        assertTrue(String.join(" ", plan.command()).contains("command protocol prompt"));
    }

    @Test
    void recordsUnsupportedAppServerAndMcpProvidersWithoutRegisteringRunnableProtocols() throws Exception {
        Path config = tempDir.resolve("providers.yaml");
        Files.writeString(config, """
            providers:
              - id: codex_dynamic
                display_name: Dynamic Codex
                protocol: app_server_json_rpc
                binary: codex
              - id: mcp_agent
                protocol: mcp
                binary: mcp-agent
                capabilities: ["tool_use"]
            """);

        ProviderProtocolDiscovery.DiscoveryResult result =
            new ProviderProtocolDiscovery(List.of(config)).discoverDetailed();

        assertTrue(result.providers().isEmpty());
        assertEquals(2, result.unsupportedProviders().size());
        assertEquals(null, result.registry().get("codex_dynamic"));
        assertEquals(null, result.registry().get("mcp_agent"));

        ProviderProtocolDiscovery.UnsupportedProvider codex = result.unsupportedProviders().get(0);
        assertEquals("codex_dynamic", codex.id());
        assertEquals("Dynamic Codex", codex.displayName());
        assertTrue(codex.capabilities().contains("coding"));
        assertEquals("app_server_json_rpc", codex.protocol());
        assertTrue(codex.reason().contains("built-in codex app-server"));
        assertEquals(false, codex.metadata().get("provider_discovery_supported"));

        ProviderProtocolDiscovery.UnsupportedProvider mcp = result.unsupportedProviders().get(1);
        assertEquals("mcp_agent", mcp.id());
        assertEquals(List.of("tool_use"), mcp.capabilities());
        assertEquals("mcp", mcp.protocol());
        assertTrue(mcp.reason().contains("not implemented"));
        assertEquals(false, mcp.metadata().get("provider_discovery_supported"));
    }

    private TaskRuntimeContext runtimeContext(String intent) {
        Task task = Task.create(
            "task_discovery_test",
            "session_discovery_test",
            "Discovery task",
            "active",
            "normal"
        ).withMetadata(Map.of("intent", intent));
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), (com.agentcloud.runtime.ActiveContext) null);
    }
}
