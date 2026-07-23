package com.agentcloud.worker;

import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 Trae protocol 注册测试。
 */
class TraeProtocolTest {

    private final TraeProtocol protocol = new TraeProtocol();

    @Test
    void providerIdReturnsTrae() {
        assertEquals("trae", protocol.providerId());
    }

    @Test
    void buildPlanIncludesChatModeAgent() {
        LocalCliProviderConfig.ResolvedConfig config =
            new LocalCliProviderConfig("trae", "trae", null, null).resolve();

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            config,
            runtimeContext("review this code"),
            null,
            null
        );

        List<String> command = plan.command();
        assertTrue(command.stream().anyMatch(s -> s.contains("chat")),
            "command should contain 'chat'");
        assertTrue(command.contains("--mode"), "command should contain --mode");
        assertTrue(command.contains("agent"), "command should contain 'agent'");
        assertEquals("app_server", plan.launchMode(), "launchMode should be app_server");
    }

    @Test
    void buildPlanIncludesPromptAsLastArg() {
        LocalCliProviderConfig.ResolvedConfig config =
            new LocalCliProviderConfig("trae", "trae", null, null).resolve();

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            config,
            runtimeContext("review this code"),
            null,
            null
        );

        String lastArg = plan.command().get(plan.command().size() - 1);
        assertNotNull(lastArg);
        assertFalse(lastArg.isBlank(), "last arg should be the prompt");
    }

    @Test
    void parseOutputHandlesEmptyOutput() {
        byte[] raw = "".getBytes(StandardCharsets.UTF_8);
        ProviderProtocol.ProviderCliPlan plan = plan();
        WorkerExecutionResult result = protocol.parseOutput(raw, plan, 1000L, Map.of());

        assertEquals("completed", result.executionStatus());
        assertEquals("", result.outputText());
        assertEquals("trae_text", result.metadata().get("provider_output_parser"));
        assertEquals("app_server", result.metadata().get("provider_launch_mode"));
    }

    @Test
    void parseOutputMarksFailedOnErrorText() {
        byte[] raw = "Error: something went wrong".getBytes(StandardCharsets.UTF_8);
        ProviderProtocol.ProviderCliPlan plan = plan();
        WorkerExecutionResult result = protocol.parseOutput(raw, plan, 1000L, Map.of());

        assertEquals("failed", result.executionStatus());
        assertTrue(result.outputText().contains("Error"));
    }

    @Test
    void protocolIsRegisteredInDefaultRegistry() {
        ProviderProtocolRegistry registry = ProviderProtocolRegistry.defaultRegistry();
        ProviderProtocol trae = registry.get("trae");
        assertNotNull(trae, "TraeProtocol should be registered in default registry");
        assertEquals("trae", trae.providerId());
    }

    private TaskRuntimeContext runtimeContext(String intent) {
        Task task = Task.create(
            "task_trae_test",
            "session_trae_test",
            "Trae smoke",
            "active",
            "normal"
        ).withMetadata(Map.of("intent", intent));
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(),
            (ActiveContext) null);
    }

    private ProviderProtocol.ProviderCliPlan plan() {
        return new ProviderProtocol.ProviderCliPlan(
            List.of("trae", "chat", "--mode", "agent", "test prompt"),
            "test prompt",
            null,
            null,
            Map.of(),
            "trae",
            "trae",
            "app_server",
            null,
            List.of()
        );
    }
}