package com.agentcloud.judgment;

import com.agentcloud.judgment.model.CompletionDecision;
import com.agentcloud.judgment.model.ExecutionDecision;
import com.agentcloud.llm.LlmClient;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.context.ContextObject;
import com.agentcloud.runtime.context.ContextObjectType;
import com.agentcloud.runtime.context.ContextRetentionState;
import com.agentcloud.runtime.context.MountedContextPanel;
import com.agentcloud.runtime.context.MountedContextPanelName;
import com.agentcloud.runtime.context.MountedContextView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBasedJudgmentServiceTest {

    @Test
    void judgeExecutionUsesReviewChannel() {
        RecordingLlmClient llmClient = new RecordingLlmClient("""
            {"action":"done","reason":"reviewed","next_step":"close","needs_checkpoint":false,"needs_human":false,"target_worker":""}
            """);
        PromptBasedJudgmentService service = new PromptBasedJudgmentService(llmClient);

        ExecutionDecision decision = service.judgeExecution(new JudgmentContext(
            task(null),
            null,
            "worker output",
            "",
            Map.of()
        ));

        assertEquals("done", decision.action());
        assertEquals(0, llmClient.chatCalls);
        assertEquals(1, llmClient.reviewCalls);
    }

    @Test
    void judgeCompletionUsesReviewChannel() {
        RecordingLlmClient llmClient = new RecordingLlmClient("""
            {"status":"done","alignment_level":"high","reason":"reviewed","suggested_next_action":"close"}
            """);
        PromptBasedJudgmentService service = new PromptBasedJudgmentService(llmClient);

        CompletionDecision decision = service.judgeCompletion(new JudgmentContext(
            task(null),
            null,
            "worker output",
            "",
            Map.of()
        ));

        assertEquals("done", decision.status());
        assertEquals("high", decision.alignmentLevel());
        assertEquals(0, llmClient.chatCalls);
        assertEquals(1, llmClient.reviewCalls);
    }

    @Test
    void judgmentPromptsDefaultToActiveContextOnlyMode() {
        RecordingLlmClient llmClient = new RecordingLlmClient("""
            {"action":"continue","reason":"reviewed","next_step":"next","needs_checkpoint":false,"needs_human":false,"target_worker":""}
            """);
        llmClient.completionResponse = """
            {"status":"partially_done","alignment_level":"medium","reason":"reviewed","suggested_next_action":"next"}
            """.strip();
        PromptBasedJudgmentService service = new PromptBasedJudgmentService(llmClient);

        JudgmentContext context = new JudgmentContext(
            task(null),
            runtimeContext(null),
            "worker output",
            "",
            Map.of()
        );
        service.judgeExecution(context);
        service.judgeCompletion(context);

        assertFalse(llmClient.executionPrompt.contains("Mounted Context:"));
        assertTrue(llmClient.executionPrompt.contains("Active Context:"));
        assertFalse(llmClient.completionPrompt.contains("Mounted Context Selection Trace:"));
        assertTrue(llmClient.completionPrompt.contains("Active Context:"));
    }

    @Test
    void judgmentPromptsIncludeMountedContextWhenPrimaryModeIsEnabled() {
        RecordingLlmClient llmClient = new RecordingLlmClient("""
            {"action":"continue","reason":"reviewed","next_step":"next","needs_checkpoint":false,"needs_human":false,"target_worker":""}
            """);
        llmClient.completionResponse = """
            {"status":"partially_done","alignment_level":"medium","reason":"reviewed","suggested_next_action":"next"}
            """.strip();
        PromptBasedJudgmentService service = new PromptBasedJudgmentService(llmClient);

        JudgmentContext context = new JudgmentContext(
            task("mounted_context_primary"),
            runtimeContext("mounted_context_primary"),
            "worker output",
            "",
            Map.of()
        );
        service.judgeExecution(context);
        service.judgeCompletion(context);

        assertTrue(llmClient.executionPrompt.contains("Mounted Context:"));
        assertTrue(llmClient.executionPrompt.contains("Pinned (1)"));
        assertTrue(llmClient.executionPrompt.contains("task/pinned/Task Goal"));
        assertTrue(llmClient.completionPrompt.contains("Mounted Context Selection Trace:"));
        assertTrue(llmClient.completionPrompt.contains("compat_mode=task_runtime_context_preserved"));
    }

    private Task task(String promptRenderingMode) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        if (promptRenderingMode != null) {
            metadata.put("prompt_rendering_mode", promptRenderingMode);
        }
        return new Task(
            "task-review",
            "session-review",
            null,
            "review task",
            "active",
            "high",
            Instant.now(),
            Instant.now(),
            Instant.now(),
            null,
            null,
            null,
            "evaluate output",
            null,
            null,
            "continue",
            null,
            metadata
        );
    }

    private TaskRuntimeContext runtimeContext(String promptRenderingMode) {
        ActiveContext activeContext = new ActiveContext(
            "Review task",
            List.of("priority=high"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "",
            "Task Focus: review task",
            12
        );
        MountedContextView mountedContextView = new MountedContextView(
            null,
            "task-review",
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.PINNED,
                    "Pinned",
                    List.of(new ContextObject(
                        "goal",
                        "/sessions/session-review/tasks/task-review",
                        ContextObjectType.TASK,
                        "",
                        "Task Goal",
                        "评估 mounted context 是否进入 judgment prompt",
                        "",
                        Instant.parse("2026-05-06T06:40:00Z"),
                        ContextRetentionState.PINNED,
                        List.of(),
                        List.of(),
                        Map.of()
                    ))
                )
            ),
            List.of("compat_mode=task_runtime_context_preserved")
        );
        return new TaskRuntimeContext(
            task(promptRenderingMode),
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            activeContext,
            mountedContextView
        );
    }

    private static final class RecordingLlmClient implements LlmClient {
        private final String reviewResponse;
        private int chatCalls;
        private int reviewCalls;
        private String completionResponse;
        private String executionPrompt = "";
        private String completionPrompt = "";

        private RecordingLlmClient(String reviewResponse) {
            this.reviewResponse = reviewResponse.strip();
            this.completionResponse = this.reviewResponse;
        }

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            chatCalls++;
            return "{\"status\":\"unexpected-chat\"}";
        }

        @Override
        public String review(String systemPrompt, String userPrompt) {
            reviewCalls++;
            if (systemPrompt.contains("completion evaluator")) {
                completionPrompt = userPrompt;
                return completionResponse;
            }
            executionPrompt = userPrompt;
            return reviewResponse;
        }
    }
}
