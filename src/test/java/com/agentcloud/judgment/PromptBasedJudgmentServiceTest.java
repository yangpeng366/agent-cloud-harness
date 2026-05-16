package com.agentcloud.judgment;

import com.agentcloud.judgment.model.CompletionDecision;
import com.agentcloud.judgment.model.ExecutionDecision;
import com.agentcloud.llm.LlmClient;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.context.ContextObject;
import com.agentcloud.runtime.context.ContextObjectType;
import com.agentcloud.runtime.context.ContextRetentionState;
import com.agentcloud.runtime.context.MountedContextPromptMetrics;
import com.agentcloud.runtime.context.MountedContextPromptRenderer;
import com.agentcloud.runtime.context.MountedContextPanel;
import com.agentcloud.runtime.context.MountedContextPanelName;
import com.agentcloud.runtime.context.MountedContextView;
import com.agentcloud.runtime.context.PromptRenderingMode;
import com.agentcloud.runtime.model.RuntimeFactSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBasedJudgmentServiceTest {

    @Test
    void judgeExecutionUsesReviewChannel() {
        RecordingLlmClient llmClient = new RecordingLlmClient("""
            {"action":"done","reason":"reviewed","next_step":"close","needs_checkpoint":false,"needs_context_reopen":true,"evidence_gap_detected":true,"needs_archive_retrieval":true,"needs_external_fact_refresh":true,"needs_human":false,"target_worker":""}
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
        assertTrue(decision.needsContextReopen());
        assertTrue(decision.evidenceGapDetected());
        assertTrue(decision.needsArchiveRetrieval());
        assertTrue(decision.needsExternalFactRefresh());
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

    @Test
    void judgmentPromptsKeepMountedContextOutWhenShadowModeIsEnabled() {
        RecordingLlmClient llmClient = new RecordingLlmClient("""
            {"action":"continue","reason":"reviewed","next_step":"next","needs_checkpoint":false,"needs_human":false,"target_worker":""}
            """);
        llmClient.completionResponse = """
            {"status":"partially_done","alignment_level":"medium","reason":"reviewed","suggested_next_action":"next"}
            """.strip();
        PromptBasedJudgmentService service = new PromptBasedJudgmentService(llmClient);

        JudgmentContext context = new JudgmentContext(
            task("mounted_context_shadow"),
            runtimeContext("mounted_context_shadow"),
            "worker output",
            "",
            Map.of()
        );
        service.judgeExecution(context);
        service.judgeCompletion(context);

        assertFalse(llmClient.executionPrompt.contains("Mounted Context:"));
        assertFalse(llmClient.completionPrompt.contains("Mounted Context Selection Trace:"));
        assertTrue(llmClient.executionPrompt.contains("Active Context:"));
        assertTrue(llmClient.completionPrompt.contains("Active Context:"));
    }

    @Test
    void judgmentShadowModeStillUsesMountedRenderResultForMetricsButDoesNotInjectPrompt() {
        RecordingLlmClient llmClient = new RecordingLlmClient("""
            {"action":"continue","reason":"reviewed","next_step":"next","needs_checkpoint":false,"needs_human":false,"target_worker":""}
            """);
        llmClient.completionResponse = """
            {"status":"partially_done","alignment_level":"medium","reason":"reviewed","suggested_next_action":"next"}
            """.strip();
        PromptBasedJudgmentService service = new PromptBasedJudgmentService(llmClient);

        TaskRuntimeContext runtimeContext = runtimeContext("mounted_context_shadow");
        JudgmentContext context = new JudgmentContext(
            task("mounted_context_shadow"),
            runtimeContext,
            "worker output",
            "",
            Map.of()
        );

        service.judgeExecution(context);
        service.judgeCompletion(context);

        MountedContextPromptRenderer renderer = new MountedContextPromptRenderer();
        var renderResult = renderer.renderResult(runtimeContext);
        MountedContextPromptMetrics metrics = MountedContextPromptMetrics.from(
            runtimeContext,
            PromptRenderingMode.MOUNTED_CONTEXT_SHADOW,
            renderResult
        );

        assertTrue(renderResult.hasPrompt());
        assertTrue(metrics.mountedRendered());
        assertTrue(metrics.mountedRenderUsed());
        assertFalse(metrics.mountedInjected());
        assertFalse(llmClient.executionPrompt.contains("Mounted Context:"));
        assertFalse(llmClient.completionPrompt.contains("Mounted Context Selection Trace:"));
    }

    @Test
    void judgmentPrimaryModeCanBeResolvedFromTaskMetadata() {
        Task task = task("mounted_context_primary");
        assertEquals("mounted_context_primary",
            com.agentcloud.runtime.context.PromptRenderingMode.resolve(task).wireName());
    }

    @Test
    void judgmentPrimaryModeCanBeResolvedFromPromptModeAlias() {
        Task task = taskWithPromptModeAlias("mounted_context_primary");
        assertEquals("mounted_context_primary",
            com.agentcloud.runtime.context.PromptRenderingMode.resolve(task).wireName());
    }

    @Test
    void judgmentPromptsIncludeMountedContextWhenPromptModeAliasIsEnabled() {
        RecordingLlmClient llmClient = new RecordingLlmClient("""
            {"action":"continue","reason":"reviewed","next_step":"next","needs_checkpoint":false,"needs_human":false,"target_worker":""}
            """);
        llmClient.completionResponse = """
            {"status":"partially_done","alignment_level":"medium","reason":"reviewed","suggested_next_action":"next"}
            """.strip();
        PromptBasedJudgmentService service = new PromptBasedJudgmentService(llmClient);

        JudgmentContext context = new JudgmentContext(
            taskWithPromptModeAlias("mounted_context_primary"),
            runtimeContextWithPromptModeAlias("mounted_context_primary"),
            "worker output",
            "",
            Map.of()
        );
        service.judgeExecution(context);
        service.judgeCompletion(context);

        assertTrue(llmClient.executionPrompt.contains("Mounted Context:"));
        assertTrue(llmClient.completionPrompt.contains("Mounted Context Selection Trace:"));
    }

    @Test
    void judgmentPromptsIncludeMountedContextWhenLatestPacketAliasIsEnabled() {
        RecordingLlmClient llmClient = new RecordingLlmClient("""
            {"action":"continue","reason":"reviewed","next_step":"next","needs_checkpoint":false,"needs_human":false,"target_worker":""}
            """);
        llmClient.completionResponse = """
            {"status":"partially_done","alignment_level":"medium","reason":"reviewed","suggested_next_action":"next"}
            """.strip();
        PromptBasedJudgmentService service = new PromptBasedJudgmentService(llmClient);

        JudgmentContext context = new JudgmentContext(
            task(null),
            runtimeContextWithLatestPacketPromptModeAlias("mounted_context_primary"),
            "worker output",
            "",
            Map.of()
        );
        service.judgeExecution(context);
        service.judgeCompletion(context);

        assertTrue(llmClient.executionPrompt.contains("Mounted Context:"));
        assertTrue(llmClient.completionPrompt.contains("Mounted Context Selection Trace:"));
    }

    @Test
    void judgmentPrimaryModeHandlesEmptyMountedViewSafely() {
        RecordingLlmClient llmClient = new RecordingLlmClient("""
            {"action":"continue","reason":"reviewed","next_step":"next","needs_checkpoint":false,"needs_human":false,"target_worker":""}
            """);
        llmClient.completionResponse = """
            {"status":"partially_done","alignment_level":"medium","reason":"reviewed","suggested_next_action":"next"}
            """.strip();
        PromptBasedJudgmentService service = new PromptBasedJudgmentService(llmClient);

        JudgmentContext context = new JudgmentContext(
            task("mounted_context_primary"),
            runtimeContextWithEmptyMountedView("mounted_context_primary"),
            "worker output",
            "",
            Map.of()
        );
        service.judgeExecution(context);
        service.judgeCompletion(context);

        assertFalse(llmClient.executionPrompt.contains("Mounted Context:"));
        assertFalse(llmClient.completionPrompt.contains("Mounted Context Selection Trace:"));
        assertTrue(llmClient.executionPrompt.contains("Active Context:"));
        assertTrue(llmClient.completionPrompt.contains("Active Context:"));
    }

    @Test
    void judgmentPromptsIncludeRuntimeFactSetEvidence() {
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
            Map.of(
                "selected_worker", "kimi",
                "selected_model_tier", "small",
                "route_source", "learning_memory"
            ),
            runtimeFactSet()
        );

        service.judgeExecution(context);
        service.judgeCompletion(context);

        assertTrue(llmClient.executionPrompt.contains("Runtime Facts:"));
        assertTrue(llmClient.executionPrompt.contains("Runtime Cognition Surface:"));
        assertTrue(llmClient.executionPrompt.contains("Route Surface:"));
        assertTrue(llmClient.executionPrompt.contains("Execution Surface:"));
        assertTrue(llmClient.executionPrompt.contains("Execution Judgment Surface:"));
        assertTrue(llmClient.executionPrompt.contains("Completion Judgment Surface:"));
        assertTrue(llmClient.executionPrompt.contains("Alignment Surface:"));
        assertTrue(llmClient.executionPrompt.contains("Route Preview:"));
        assertTrue(llmClient.executionPrompt.contains("- selected_worker: kimi"));
        assertTrue(llmClient.executionPrompt.contains("Execution Boundary:"));
        assertTrue(llmClient.executionPrompt.contains("- execution_status: blocked"));
        assertTrue(llmClient.executionPrompt.contains("- trace_summary: 2 steps"));
        assertTrue(llmClient.executionPrompt.contains("- proof_summary: proof=tool:tool-1, tool:tool-2"));
        assertTrue(llmClient.executionPrompt.contains("- mounted_context_budget: 3/2 objects"));
        assertTrue(llmClient.executionPrompt.contains("- route_worker_matches_execution_worker: true"));
        assertTrue(llmClient.executionPrompt.contains("- execution_and_execution_judgment_prompt_mode_aligned: true"));
        assertTrue(llmClient.executionPrompt.contains("- mounted_context_panel_count: 7"));
        assertTrue(llmClient.executionPrompt.contains("- mounted_context_non_empty_panel_count: 3"));
        assertTrue(llmClient.executionPrompt.contains("- mounted_active_count: 4"));
        assertTrue(llmClient.executionPrompt.contains("- mounted_evidence_count: 2"));
        assertTrue(llmClient.executionPrompt.contains("- mounted_context_hidden_object_count: 2"));
        assertTrue(llmClient.executionPrompt.contains("- mounted_context_budget_truncated: true"));
        assertTrue(llmClient.executionPrompt.contains("- evidence_refs: [tool:read_file:input.txt, tool:write_file:draft.txt]"));
        assertTrue(llmClient.completionPrompt.contains("Runtime Cognition Surface:"));
        assertTrue(llmClient.completionPrompt.contains("Alignment Surface:"));
        assertTrue(llmClient.completionPrompt.contains("- has_route_preview: true"));
        assertTrue(llmClient.completionPrompt.contains("- mounted_context_selection_trace_count: 4"));
        assertTrue(llmClient.completionPrompt.contains("- mounted_archive_count: 1"));
        assertTrue(llmClient.completionPrompt.contains("- mounted_context_hidden_selection_trace_count: 1"));
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

    private Task taskWithPromptModeAlias(String promptMode) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        if (promptMode != null) {
            metadata.put("prompt_mode", promptMode);
        }
        return new Task(
            "task-review-alias",
            "session-review",
            null,
            "review task alias",
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

    private TaskRuntimeContext runtimeContextWithPromptModeAlias(String promptMode) {
        ActiveContext activeContext = new ActiveContext(
            "Review task alias",
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
            "Task Focus: review task alias",
            12
        );
        MountedContextView mountedContextView = new MountedContextView(
            null,
            "task-review-alias",
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.PINNED,
                    "Pinned",
                    List.of(new ContextObject(
                        "goal",
                        "/sessions/session-review/tasks/task-review-alias",
                        ContextObjectType.TASK,
                        "",
                        "Task Goal",
                        "评估 prompt_mode alias 是否进入 judgment prompt",
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
            taskWithPromptModeAlias(promptMode),
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

    private TaskRuntimeContext runtimeContextWithEmptyMountedView(String promptRenderingMode) {
        ActiveContext activeContext = new ActiveContext(
            "Review task empty mounted",
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
            "Task Focus: review task empty mounted",
            12
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
            MountedContextView.empty("task-review-empty-mounted")
        );
    }

    private TaskRuntimeContext runtimeContextWithLatestPacketPromptModeAlias(String promptMode) {
        TaskRuntimeContext base = runtimeContext(null);
        ResumePacket latestPacket = new ResumePacket(
            UUID.randomUUID().toString(),
            base.task().sessionId(),
            base.task().id(),
            Instant.parse("2026-05-06T06:42:00Z"),
            "1.1",
            "review summary",
            null,
            null,
            List.of(),
            "evaluate output",
            Map.of(
                "prompt_mode", promptMode,
                "next_step", "evaluate output"
            )
        );
        return new TaskRuntimeContext(
            base.task(),
            latestPacket,
            null,
            base.recentEvents(),
            base.recentDecisions(),
            base.recentArtifacts(),
            base.recentMessages(),
            base.activeContext(),
            base.mountedContextView()
        );
    }

    private RuntimeFactSet runtimeFactSet() {
        return new RuntimeFactSet(
            "task-review",
            "session-review",
            "active",
            "continue",
            "kimi",
            "worker output",
            "continue",
            "next",
            runtimeContext("mounted_context_primary"),
            null,
            null,
            new com.agentcloud.model.Decision(
                "dec-exec",
                "session-review",
                "task-review",
                Instant.parse("2026-05-08T01:01:00Z"),
                "execution_judgment",
                "Execution judgment: continue",
                "needs more review",
                "medium",
                null,
                Map.ofEntries(
                    Map.entry("action", "continue"),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("mounted_context_panel_count", 7),
                    Map.entry("mounted_context_non_empty_panel_count", 3),
                    Map.entry("mounted_context_selection_trace_count", 4),
                    Map.entry("mounted_pinned_count", 1),
                    Map.entry("mounted_active_count", 4),
                    Map.entry("mounted_ancestor_count", 1),
                    Map.entry("mounted_sibling_count", 0),
                    Map.entry("mounted_evidence_count", 2),
                    Map.entry("mounted_index_count", 0),
                    Map.entry("mounted_archive_count", 1),
                    Map.entry("mounted_context_rendered_object_count", 3),
                    Map.entry("mounted_context_hidden_object_count", 2),
                    Map.entry("mounted_context_rendered_selection_trace_count", 4),
                    Map.entry("mounted_context_hidden_selection_trace_count", 1),
                    Map.entry("mounted_context_budget_truncated", true),
                    Map.entry("needs_context_reopen", true),
                    Map.entry("reopen_candidate_paths", List.of(
                        "/sessions/session-review/tasks/task-review/tool_invocations",
                        "/sessions/session-review/tasks/task-review/packets/packet-review-1"
                    )),
                    Map.entry("tool_invocation_ids", List.of("tool-1", "tool-2")),
                    Map.entry("evidence_refs", List.of("tool:read_file:input.txt", "tool:write_file:draft.txt")),
                    Map.entry("unfinished_items", List.of("manual_review"))
                )
            ),
            new com.agentcloud.model.Decision(
                "dec-completion",
                "session-review",
                "task-review",
                Instant.parse("2026-05-08T01:01:10Z"),
                "completion_judgment",
                "Completion judgment: partial",
                "needs manual review",
                "medium",
                null,
                Map.ofEntries(
                    Map.entry("status", "partially_done"),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("mounted_context_panel_count", 7),
                    Map.entry("mounted_context_non_empty_panel_count", 3),
                    Map.entry("mounted_context_selection_trace_count", 4),
                    Map.entry("mounted_pinned_count", 1),
                    Map.entry("mounted_active_count", 4),
                    Map.entry("mounted_ancestor_count", 1),
                    Map.entry("mounted_sibling_count", 0),
                    Map.entry("mounted_evidence_count", 2),
                    Map.entry("mounted_index_count", 0),
                    Map.entry("mounted_archive_count", 1),
                    Map.entry("mounted_context_rendered_object_count", 3),
                    Map.entry("mounted_context_hidden_object_count", 2),
                    Map.entry("mounted_context_rendered_selection_trace_count", 4),
                    Map.entry("mounted_context_hidden_selection_trace_count", 1),
                    Map.entry("mounted_context_budget_truncated", true),
                    Map.entry("tool_invocation_ids", List.of("tool-1", "tool-2")),
                    Map.entry("evidence_refs", List.of("tool:read_file:input.txt", "tool:write_file:draft.txt")),
                    Map.entry("unfinished_items", List.of("manual_review"))
                )
            ),
            List.of(),
            new RuntimeFactSet.ExecutionBoundary(
                "exec-123",
                "blocked",
                "2026-05-08T01:00:00Z",
                "2026-05-08T01:00:10Z",
                10L,
                "kimi",
                List.of("tool-1", "tool-2"),
                2,
                "2 steps · planner_no_additional_tool · read_file -> write_file",
                Map.ofEntries(
                    Map.entry("tool_execution_mode", "multi_tool_round"),
                    Map.entry("tool_chain_step_count", 2),
                    Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("mounted_context_panel_count", 7),
                    Map.entry("mounted_context_non_empty_panel_count", 3),
                    Map.entry("mounted_context_selection_trace_count", 4),
                    Map.entry("mounted_pinned_count", 1),
                    Map.entry("mounted_active_count", 4),
                    Map.entry("mounted_ancestor_count", 1),
                    Map.entry("mounted_sibling_count", 0),
                    Map.entry("mounted_evidence_count", 2),
                    Map.entry("mounted_index_count", 0),
                    Map.entry("mounted_archive_count", 1),
                    Map.entry("mounted_context_rendered_object_count", 3),
                    Map.entry("mounted_context_hidden_object_count", 2),
                    Map.entry("mounted_context_rendered_selection_trace_count", 4),
                    Map.entry("mounted_context_hidden_selection_trace_count", 1),
                    Map.entry("mounted_context_budget_truncated", true),
                    Map.entry("evidence_refs", List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"))
                )
            ),
            new com.agentcloud.engine.router.WorkerRouter.RouteResult(
                "task-review",
                "kimi",
                List.of("codex"),
                "selected by learning memory hint",
                "learning_memory",
                "coding",
                "kimi",
                true,
                List.of("kimi", "codex"),
                "codex",
                "small",
                "executor",
                "executor",
                "selected by learning memory hint",
                null,
                null,
                null,
                null,
                null,
                null
            ),
            Map.of(
                "has_route_preview", true,
                "has_execution_boundary", true,
                "execution_trace_summary", "2 steps · planner_no_additional_tool · read_file -> write_file",
                "mounted_context_hidden_selection_trace_count", 1
            )
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
