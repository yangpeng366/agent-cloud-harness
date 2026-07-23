package com.agentcloud.worker;

import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiProtocolTest {

    @Test
    void buildPlanIncludesRunSubcommandAndPrompt() {
        PiProtocol protocol = new PiProtocol();
        LocalCliProviderConfig.ResolvedConfig config =
            new LocalCliProviderConfig("pi", "pi", null, null).resolve();

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            config,
            runtimeContext("check service health"),
            null,
            null
        );

        String command = String.join(" ", plan.command());
        assertTrue(plan.command().contains("run"), "command must include run: " + command);
        assertTrue(command.contains("check service health"), "command must include prompt: " + command);
    }

    @Test
    void buildPlanInjectsModelAndCwdWhenProvided() {
        PiProtocol protocol = new PiProtocol();
        LocalCliProviderConfig.ResolvedConfig config =
            new LocalCliProviderConfig("pi", "pi", null, null).resolve();

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            config,
            runtimeContextWithModel("deploy", "glm-5.1"),
            "/workspace/project",
            null
        );

        String command = String.join(" ", plan.command());
        assertTrue(plan.command().contains("--model"), "must include --model: " + command);
        assertTrue(plan.command().contains("--cwd"), "must include --cwd: " + command);
        assertTrue(plan.command().contains("glm-5.1"), "must include model value: " + command);
    }

    @Test
    void parseOutputExtractsSessionAndTextFromEventStream() {
        PiProtocol protocol = new PiProtocol();
        String eventStream = """
            {"type":"agent_start","session_id":"ses_pi_001"}
            {"type":"turn_start","session_id":"ses_pi_001"}
            {"type":"message_update","session_id":"ses_pi_001","content":[{"type":"text","text":"Service is healthy."}]}
            {"type":"turn_end","session_id":"ses_pi_001"}
            {"type":"agent_end","session_id":"ses_pi_001"}
            """;

        WorkerExecutionResult result = protocol.parseOutput(
            eventStream.getBytes(StandardCharsets.UTF_8),
            new ProviderProtocol.ProviderCliPlan(List.of("pi"), "", ""),
            3200,
            Map.of()
        );

        assertEquals("completed", result.executionStatus());
        assertEquals("pi_event_stream", result.metadata().get("provider_output_parser"));
        assertEquals("ses_pi_001", result.metadata().get("provider_session_id"));
        assertNotNull(result.outputText());
        assertTrue(result.outputText().contains("Service is healthy."), "outputText must contain response: " + result.outputText());
        assertEquals(ExecutionOutcome.COMPLETED, result.outcome());
    }

    @Test
    void parseOutputMarksFailedOnErrorContent() {
        PiProtocol protocol = new PiProtocol();
        String eventStream = """
            {"type":"agent_start","session_id":"ses_pi_err"}
            {"type":"message_update","content":[{"type":"error","text":"connection refused"}]}
            {"type":"agent_end","session_id":"ses_pi_err"}
            """;

        WorkerExecutionResult result = protocol.parseOutput(
            eventStream.getBytes(StandardCharsets.UTF_8),
            new ProviderProtocol.ProviderCliPlan(List.of("pi"), "", ""),
            100,
            Map.of()
        );

        assertEquals("failed", result.executionStatus());
        assertEquals(ExecutionOutcome.FAILED, result.outcome());
    }

    @Test
    void piProtocolIsRegisteredInDefaultRegistry() {
        ProviderProtocol pi = ProviderProtocolRegistry.defaultRegistry().get("pi");
        assertNotNull(pi, "Pi protocol must be registered in default registry");
        assertEquals("pi", pi.providerId());
    }

    private TaskRuntimeContext runtimeContext(String intent) {
        Task task = Task.create(
            "task_pi_test",
            "session_pi_test",
            "Pi smoke",
            "active",
            "normal"
        ).withMetadata(Map.of("intent", intent));
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(),
            (com.agentcloud.runtime.ActiveContext) null);
    }

    private TaskRuntimeContext runtimeContextWithModel(String intent, String model) {
        Task task = Task.create(
            "task_pi_test",
            "session_pi_test",
            "Pi smoke",
            "active",
            "normal"
        ).withMetadata(Map.of("intent", intent, "provider_model", model));
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(),
            (com.agentcloud.runtime.ActiveContext) null);
    }
}