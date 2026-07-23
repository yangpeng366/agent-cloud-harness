package com.agentcloud.worker;

import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodeBuddyProtocol focused test：
 * <ul>
 *   <li>buildPlan 命令含全部固定参数 + prompt</li>
 *   <li>parseOutput 用第 2.1 节真实 stream-json 样本断言 status/session/outputText/model</li>
 * </ul>
 * 样本来源：{@code .tmp/codebuddy-sample.jsonl}（codebuddy v2.107.0 真实 prompt 实跑）。
 */
class CodeBuddyProtocolTest {

    @Test
    void buildPlanIncludesAllRequiredFlagsAndPrompt() {
        CodeBuddyProtocol protocol = new CodeBuddyProtocol();
        LocalCliProviderConfig.ResolvedConfig config =
            new LocalCliProviderConfig("codebuddy", "codebuddy", null, null).resolve();

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            config,
            runtimeContext("reply PONG"),
            null,
            null
        );

        String command = String.join(" ", plan.command());
        // 固定参数序列（第 3.2 节）
        assertTrue(plan.command().contains("-y"), "command must include -y: " + command);
        assertTrue(plan.command().contains("--print"), "command must include --print: " + command);
        assertTrue(plan.command().contains("--output-format"), "command must include --output-format: " + command);
        assertTrue(plan.command().contains("stream-json"), "command must include stream-json: " + command);
        assertTrue(plan.command().contains("--permission-mode"), "command must include --permission-mode: " + command);
        assertTrue(plan.command().contains("bypassPermissions"), "command must include bypassPermissions: " + command);
        assertTrue(plan.command().contains("--subagent-permission-mode"),
            "command must include --subagent-permission-mode: " + command);
        assertTrue(plan.command().contains("--tools"), "command must include --tools: " + command);
        assertTrue(plan.command().contains("default"), "command must include default: " + command);
        // prompt 作为最后位置参数（argv 交付）
        assertTrue(command.contains("reply PONG"), "command must include prompt intent: " + command);
    }

    @Test
    void buildPlanInjectsModelWhenConfigured() {
        CodeBuddyProtocol protocol = new CodeBuddyProtocol();
        LocalCliProviderConfig.ResolvedConfig config =
            new LocalCliProviderConfig("codebuddy", "codebuddy", null, "MULTICA_CODEBUDDY_MODEL").resolve();

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            config,
            runtimeContextWithModel("reply PONG", "glm-5.1"),
            null,
            null
        );

        assertTrue(plan.command().contains("--model"), "command must include --model: " + String.join(" ", plan.command()));
        int modelIndex = plan.command().indexOf("--model");
        assertEquals("glm-5.1", plan.command().get(modelIndex + 1));
    }

    @Test
    void buildPlanInjectsResumeIdWhenSessionIdPresent() {
        CodeBuddyProtocol protocol = new CodeBuddyProtocol();
        LocalCliProviderConfig.ResolvedConfig config =
            new LocalCliProviderConfig("codebuddy", "codebuddy", null, null).resolve();

        ProviderProtocol.ProviderCliPlan plan = protocol.buildPlan(
            config,
            runtimeContextWithResume("reply PONG", "ses_existing_123"),
            null,
            null
        );

        int resumeIndex = plan.command().indexOf("-r");
        assertTrue(resumeIndex >= 0, "command must include -r for resume: " + String.join(" ", plan.command()));
        assertEquals("ses_existing_123", plan.command().get(resumeIndex + 1));
    }

    @Test
    void parseOutputExtractsSessionModelAndPongFromRealStreamJsonSample() {
        CodeBuddyProtocol protocol = new CodeBuddyProtocol();
        // 真实样本（.tmp/codebuddy-sample.jsonl 精简版，保留 system init / assistant / result 关键事件）
        String streamJson = """
            {"type":"system","subtype":"init","session_id":"6e78af96-980b-4349-96db-9cdae1d83c02","model":"glm-5.1","permissionMode":"bypassPermissions","tools":["Agent","Read","Write","Edit","Bash"]}
            {"type":"system","subtype":"status","session_id":"6e78af96-980b-4349-96db-9cdae1d83c02"}
            {"type":"assistant","session_id":"6e78af96-980b-4349-96db-9cdae1d83c02","message":{"content":[{"type":"text","text":"PONG ; echo EXIT=0"}],"model":"glm-5.1","usage":{"input_tokens":31868,"output_tokens":9}}}
            {"type":"result","subtype":"success","is_error":false,"result":"PONG ; echo EXIT=0","session_id":"6e78af96-980b-4349-96db-9cdae1d83c02","duration_ms":6104,"num_turns":2,"usage":{"input_tokens":31868,"output_tokens":9}}
            """;

        WorkerExecutionResult result = protocol.parseOutput(
            streamJson.getBytes(StandardCharsets.UTF_8),
            new ProviderProtocol.ProviderCliPlan(List.of("codebuddy"), "", ""),
            6104,
            Map.of()
        );

        assertEquals("completed", result.executionStatus());
        assertEquals("codebuddy_stream_json", result.metadata().get("provider_output_parser"));
        assertEquals("6e78af96-980b-4349-96db-9cdae1d83c02", result.metadata().get("provider_session_id"));
        assertEquals("glm-5.1", result.metadata().get("provider_active_model"));
        assertNotNull(result.outputText());
        assertTrue(result.outputText().contains("PONG"), "outputText must contain PONG: " + result.outputText());
        assertEquals(ExecutionOutcome.COMPLETED, result.outcome());
    }

    @Test
    void parseOutputMarksFailedWhenResultIsError() {
        CodeBuddyProtocol protocol = new CodeBuddyProtocol();
        String streamJson = """
            {"type":"system","subtype":"init","session_id":"ses_err","model":"glm-5.1"}
            {"type":"result","subtype":"success","is_error":true,"result":"boom","session_id":"ses_err"}
            """;

        WorkerExecutionResult result = protocol.parseOutput(
            streamJson.getBytes(StandardCharsets.UTF_8),
            new ProviderProtocol.ProviderCliPlan(List.of("codebuddy"), "", ""),
            100,
            Map.of()
        );

        assertEquals("failed", result.executionStatus());
        assertEquals(ExecutionOutcome.FAILED, result.outcome());
        assertEquals("ses_err", result.metadata().get("provider_session_id"));
        assertNotNull(result.metadata().get("provider_error"));
        assertFalse(result.unfinishedItems().isEmpty());
    }

    private TaskRuntimeContext runtimeContext(String intent) {
        Task task = Task.create(
            "task_codebuddy_test",
            "session_codebuddy_test",
            "CodeBuddy smoke",
            "active",
            "normal"
        ).withMetadata(Map.of("intent", intent));
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(),
            (com.agentcloud.runtime.ActiveContext) null);
    }

    private TaskRuntimeContext runtimeContextWithModel(String intent, String model) {
        Task task = Task.create(
            "task_codebuddy_test",
            "session_codebuddy_test",
            "CodeBuddy smoke",
            "active",
            "normal"
        ).withMetadata(Map.of("intent", intent, "provider_model", model));
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(),
            (com.agentcloud.runtime.ActiveContext) null);
    }

    private TaskRuntimeContext runtimeContextWithResume(String intent, String sessionId) {
        Task task = Task.create(
            "task_codebuddy_test",
            "session_codebuddy_test",
            "CodeBuddy smoke",
            "active",
            "normal"
        ).withMetadata(Map.of("intent", intent, "provider_session_id", sessionId));
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(),
            (com.agentcloud.runtime.ActiveContext) null);
    }
}
