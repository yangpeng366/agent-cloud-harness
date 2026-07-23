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

/**
 * DevecoProtocol focused test：
 * <ul>
 *   <li>buildPlan 命令含 run/--skip-agreement/--format json + message</li>
 *   <li>parseOutput 用第 2.2 节真实 opencode 事件流样本断言 outputText/session</li>
 * </ul>
 * 样本来源：{@code .tmp/deveco-sample.json}（deveco v0.1.0 真实 prompt 实跑）。
 */
class DevecoProtocolTest {

    @Test
    void buildPlanIncludesAllRequiredFlagsAndMessage() {
        DevecoProtocol protocol = new DevecoProtocol();
        LocalCliProviderConfig.ResolvedConfig config =
            new LocalCliProviderConfig("deveco", "deveco", null, null).resolve();

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            config,
            runtimeContext("reply PONG"),
            "/tmp/workdir",
            null
        );

        String command = String.join(" ", plan.command());
        // 固定参数序列（第 3.3 节）
        assertTrue(plan.command().contains("run"), "command must include run: " + command);
        assertTrue(plan.command().contains("--skip-agreement"), "command must include --skip-agreement: " + command);
        assertTrue(plan.command().contains("--format"), "command must include --format: " + command);
        assertTrue(plan.command().contains("json"), "command must include json: " + command);
        // cwd 传递
        assertTrue(plan.command().contains("--dir"), "command must include --dir: " + command);
        assertTrue(plan.command().contains("/tmp/workdir"), "command must include cwd: " + command);
        // message 作为最后位置参数
        assertTrue(command.contains("reply PONG"), "command must include message intent: " + command);
    }

    @Test
    void buildPlanInjectsModelWhenConfigured() {
        DevecoProtocol protocol = new DevecoProtocol();
        LocalCliProviderConfig.ResolvedConfig config =
            new LocalCliProviderConfig("deveco", "deveco", null, "MULTICA_DEVECO_MODEL").resolve();

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            config,
            runtimeContextWithModel("reply PONG", "glm-5.1"),
            null,
            null
        );

        int modelIndex = plan.command().indexOf("-m");
        assertTrue(modelIndex >= 0, "command must include -m: " + String.join(" ", plan.command()));
        assertEquals("glm-5.1", plan.command().get(modelIndex + 1));
    }

    @Test
    void buildPlanInjectsSessionWhenResumeIdPresent() {
        DevecoProtocol protocol = new DevecoProtocol();
        LocalCliProviderConfig.ResolvedConfig config =
            new LocalCliProviderConfig("deveco", "deveco", null, null).resolve();

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            config,
            runtimeContextWithResume("reply PONG", "ses_existing_456"),
            null,
            null
        );

        int sessionIndex = plan.command().indexOf("-s");
        assertTrue(sessionIndex >= 0, "command must include -s: " + String.join(" ", plan.command()));
        assertEquals("ses_existing_456", plan.command().get(sessionIndex + 1));
    }

    @Test
    void parseOutputExtractsPongAndSessionFromRealOpencodeSample() {
        DevecoProtocol protocol = new DevecoProtocol();
        // 真实样本（.tmp/deveco-sample.json，opencode 事件流）
        String eventStream = """
            {"type":"step_start","timestamp":1781750543303,"sessionID":"ses_127648ee6ffeaCeRAJxpcE2ChB","part":{"messageID":"msg_ed89b7505001pYcOXZOyXQoVti","sessionID":"ses_127648ee6ffeaCeRAJxpcE2ChB","type":"step-start"}}
            {"type":"text","timestamp":1781750543477,"sessionID":"ses_127648ee6ffeaCeRAJxpcE2ChB","part":{"type":"text","text":"PONG","time":{"start":1781750543305,"end":1781750543471}}}
            {"type":"step_finish","timestamp":1781750543857,"sessionID":"ses_127648ee6ffeaCeRAJxpcE2ChB","part":{"reason":"stop","type":"step-finish","tokens":{"total":18735,"input":18732,"output":3,"reasoning":0},"cost":0}}
            """;

        WorkerExecutionResult result = protocol.parseOutput(
            eventStream.getBytes(StandardCharsets.UTF_8),
            new ProviderProtocol.ProviderCliPlan(List.of("deveco"), "", ""),
            554,
            Map.of()
        );

        assertEquals("completed", result.executionStatus());
        assertEquals("deveco_opencode_json", result.metadata().get("provider_output_parser"));
        assertEquals("ses_127648ee6ffeaCeRAJxpcE2ChB", result.metadata().get("provider_session_id"));
        assertNotNull(result.outputText());
        assertTrue(result.outputText().contains("PONG"), "outputText must contain PONG: " + result.outputText());
        // step_finish 抽取的 tokens / cost
        assertEquals(18735L, result.metadata().get("provider_total_tokens"));
        assertEquals(18732L, result.metadata().get("provider_input_tokens"));
        assertEquals(3L, result.metadata().get("provider_output_tokens"));
        assertEquals(0.0, result.metadata().get("provider_cost"));
        assertEquals("stop", result.metadata().get("provider_stop_reason"));
        assertEquals(ExecutionOutcome.COMPLETED, result.outcome());
    }

    private TaskRuntimeContext runtimeContext(String intent) {
        Task task = Task.create(
            "task_deveco_test",
            "session_deveco_test",
            "DevEco smoke",
            "active",
            "normal"
        ).withMetadata(Map.of("intent", intent));
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(),
            (com.agentcloud.runtime.ActiveContext) null);
    }

    private TaskRuntimeContext runtimeContextWithModel(String intent, String model) {
        Task task = Task.create(
            "task_deveco_test",
            "session_deveco_test",
            "DevEco smoke",
            "active",
            "normal"
        ).withMetadata(Map.of("intent", intent, "provider_model", model));
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(),
            (com.agentcloud.runtime.ActiveContext) null);
    }

    private TaskRuntimeContext runtimeContextWithResume(String intent, String sessionId) {
        Task task = Task.create(
            "task_deveco_test",
            "session_deveco_test",
            "DevEco smoke",
            "active",
            "normal"
        ).withMetadata(Map.of("intent", intent, "provider_session_id", sessionId));
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(),
            (com.agentcloud.runtime.ActiveContext) null);
    }
}
