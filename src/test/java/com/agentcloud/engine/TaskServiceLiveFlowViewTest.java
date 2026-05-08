package com.agentcloud.engine;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.providers.CodexProvider;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.AgentRunRecord;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.runtime.context.ContextObject;
import com.agentcloud.runtime.context.ContextObjectType;
import com.agentcloud.runtime.context.ContextRetentionState;
import com.agentcloud.runtime.context.MountedContextPanel;
import com.agentcloud.runtime.context.MountedContextPanelName;
import com.agentcloud.runtime.context.MountedContextView;
import com.agentcloud.store.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceLiveFlowViewTest {

    @TempDir
    Path tempDir;

    @Test
    void getJudgmentTraceWithoutToolEvidenceLeavesExecutionBoundaryNull() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("judgment-trace-no-execution-boundary.db"))) {
            TaskService service = service(db);

            Task task = service.createTask(new TaskCreateRequest(
                "judgment trace empty task", "continuation", "user", "high",
                "没有 tool evidence 时不应伪造 execution boundary", "保持 judgment trace 干净", null, null, Map.of(), false
            ));

            var trace = service.getJudgmentTrace(task.id());

            assertNull(trace.executionBoundary());
            assertNotNull(trace.runtimeFacts());
            assertEquals(task.id(), trace.runtimeFacts().taskId());
            assertEquals(0, ((Number) trace.runtimeFacts().metadata().get("tool_invocation_count")).intValue());
        }
    }

    @Test
    void getLiveFlowIncludesRelatedMessages() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("live-flow-related-messages.db"))) {
            TaskService service = service(db);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "live flow task", "continuation", "user", "high",
                "整理 continuity 相关的输入", "形成一版结构化摘要", null, null, Map.of(), false
            ));

            messageDao.insert(new SessionMessage(
                IdGenerator.newId("msg"),
                task.sessionId(),
                task.id(),
                "user",
                "task_note",
                "这条消息应被 live_flow 聚合进 related_messages",
                Instant.now(),
                Map.of("source_surface", "test")
            ));
            messageDao.insert(new SessionMessage(
                IdGenerator.newId("msg"),
                task.sessionId(),
                null,
                "user",
                "user_note",
                "这条 session 级消息不应出现在 related_messages 中",
                Instant.now(),
                Map.of("source_surface", "test")
            ));

            var flow = service.getLiveFlow(task.id(), 10);

            assertEquals(task.id(), flow.task().id());
            assertNotNull(flow.runtimeFacts());
            assertEquals(task.id(), flow.runtimeFacts().taskId());
            assertEquals(2, flow.relatedMessages().size());
            assertEquals(task.id(), flow.experimentRun().taskId());
            assertTrue(flow.relatedMessages().stream().allMatch(message -> task.id().equals(message.taskId())));
            assertTrue(flow.relatedMessages().stream().anyMatch(message -> "task_receipt".equals(message.messageType())));
            assertTrue(flow.relatedMessages().stream().anyMatch(message -> "task_note".equals(message.messageType())));
        }
    }

    @Test
    void getLiveFlowCarriesMountedContextRuntimeSurface() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("live-flow-mounted-context.db"))) {
            TaskService service = service(db);

            Task task = service.createTask(new TaskCreateRequest(
                "mounted live flow task", "continuation", "user", "high",
                "聚合 mounted context", "确认 live flow 暴露 mounted context", null, null, Map.of(), false
            ));

            var flow = service.getLiveFlow(task.id(), 10);

            assertNotNull(flow.runtimeContext());
            assertNotNull(flow.runtimeContext().mountedContextView());
            assertEquals(task.id(), flow.runtimeContext().mountedContextView().taskId());
            assertTrue(flow.runtimeContext().mountedContextView().selectionTrace()
                .contains("compat_mode=task_runtime_context_preserved"));
            assertTrue(flow.runtimeContext().mountedContextView().objects(MountedContextPanelName.PINNED).stream()
                .anyMatch(object -> object.type() == ContextObjectType.CONSTRAINT
                    && object.retentionState() == ContextRetentionState.PINNED
                    && object.summary().contains("保留 mounted context 里的关键约束")));
        }
    }

    @Test
    void getLiveFlowCarriesExperimentRunToolChainSummary() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("live-flow-tool-chain.db"))) {
            TaskService service = service(db);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "tool chain live flow task", "coding", "user", "high",
                "多步 tool chain 汇总", "给 live flow 增加 tool chain 摘要", null, null, Map.of(), false
            ));

            artifactDao.insert(new Artifact(
                IdGenerator.newId("art"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_artifact",
                "Executor result",
                null,
                null,
                "完成两步 tool chain",
                Map.of(
                    "selected_model_tier", "small",
                    "route_source", "learning_memory",
                    "preferred_worker_hint", "kimi",
                    "learning_hint_applied", true,
                    "fallback_reason", "hint survived tier filter",
                    "latest_worker_metadata", Map.of(
                        "tool_execution_mode", "multi_tool_round",
                        "tool_chain_step_count", 2,
                        "tool_chain_termination_reason", "planner_no_additional_tool",
                        "tool_chain_trace", List.of(
                            Map.of("tool_chain_step_index", 1, "tool_name", "read_file"),
                            Map.of("tool_chain_step_index", 2, "tool_name", "write_file")
                        ),
                        "execution_status", "blocked",
                        "evidence_refs", List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"),
                        "unfinished_items", List.of("manual_review")
                    )
                )
            ));
            toolInvocationDao.insert(new ToolInvocationRecord(
                IdGenerator.newId("tool"),
                task.sessionId(),
                task.id(),
                "kimi",
                "exec_write_file",
                "write_file",
                Map.of("path", "draft.txt"),
                "draft updated",
                "succeeded",
                true,
                21,
                List.of("draft.txt"),
                Instant.now(),
                Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "tool_chain_step_index", 2
                )
            ));
            toolInvocationDao.insert(new ToolInvocationRecord(
                IdGenerator.newId("tool"),
                task.sessionId(),
                task.id(),
                "kimi",
                "exec_read_file",
                "read_file",
                Map.of("path", "input.txt"),
                "input loaded",
                "succeeded",
                true,
                12,
                List.of("input.txt"),
                Instant.now(),
                Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "tool_chain_step_index", 1
                )
            ));

            var flow = service.getLiveFlow(task.id(), 10);

            assertEquals("learning_memory", flow.experimentRun().metadata().get("route_source"));
            assertNotNull(flow.runtimeFacts());
            assertEquals(flow.routePreview().selectedWorker(), flow.runtimeFacts().routePreview().selectedWorker());
            assertEquals(2, flow.runtimeFacts().toolInvocations().size());
            assertEquals(Set.of("read_file", "write_file"),
                flow.runtimeFacts().toolInvocations().stream().map(ToolInvocationRecord::toolName).collect(java.util.stream.Collectors.toSet()));
            assertEquals("blocked", flow.runtimeFacts().metadata().get("execution_status"));
            assertEquals("kimi", flow.experimentRun().metadata().get("preferred_worker_hint"));
            assertEquals(Boolean.TRUE, flow.experimentRun().metadata().get("learning_hint_applied"));
            assertEquals("hint survived tier filter", flow.experimentRun().metadata().get("fallback_reason"));
            assertNotNull(flow.executionBoundary());
            assertEquals("exec_read_file", flow.executionBoundary().executionId());
            assertEquals("kimi", flow.executionBoundary().workerId());
            assertEquals("blocked", flow.executionBoundary().executionStatus());
            assertEquals(2, flow.executionBoundary().toolInvocationCount());
            assertEquals("write_file", flow.executionBoundary().metadata().get("latest_tool_name"));
            assertEquals("2 steps · planner_no_additional_tool · read_file -> write_file", flow.executionBoundary().traceSummary());
            assertEquals("multi_tool_round", flow.experimentRun().metadata().get("tool_execution_mode"));
            assertEquals(2, ((Number) flow.experimentRun().metadata().get("tool_chain_step_count")).intValue());
            assertEquals("planner_no_additional_tool",
                flow.experimentRun().metadata().get("tool_chain_termination_reason"));
            assertEquals("2 steps · planner_no_additional_tool · read_file -> write_file",
                flow.experimentRun().metadata().get("tool_chain_trace_summary"));
            assertEquals(List.of("read_file", "write_file"), flow.experimentRun().metadata().get("tool_chain_tools"));
            assertEquals("blocked", flow.experimentRun().metadata().get("execution_status"));
            assertEquals(List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"),
                flow.experimentRun().metadata().get("evidence_refs"));
            assertEquals(List.of("manual_review"), flow.experimentRun().metadata().get("unfinished_items"));
        }
    }

    @Test
    void getJudgmentTraceCarriesExecutionBoundary() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("judgment-trace-execution-boundary.db"))) {
            TaskService service = service(db);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "judgment trace task", "coding", "user", "high",
                "把 execution boundary 带进 judgment trace", "方便 operator 对照判断与执行事实", null, null, Map.of(), false
            ));

            toolInvocationDao.insert(new ToolInvocationRecord(
                "exec_boundary_1",
                task.sessionId(),
                task.id(),
                "codex",
                "exec_boundary_1",
                "read_file",
                Map.of("path", "input.txt"),
                "input loaded",
                "succeeded",
                true,
                18,
                List.of("input.txt"),
                Instant.now(),
                Map.of(
                    "execution_status", "succeeded",
                    "execution_duration_ms", 18,
                    "tool_execution_mode", "single_tool_round"
                )
            ));

            var trace = service.getJudgmentTrace(task.id());

            assertNotNull(trace.executionBoundary());
            assertNotNull(trace.runtimeFacts());
            assertEquals("codex", trace.runtimeFacts().routePreview().selectedWorker());
            assertEquals(1, trace.runtimeFacts().toolInvocations().size());
            assertEquals("exec_boundary_1", trace.executionBoundary().executionId());
            assertEquals("codex", trace.executionBoundary().workerId());
            assertEquals("succeeded", trace.executionBoundary().executionStatus());
            assertEquals(1, trace.executionBoundary().toolInvocationCount());
            assertEquals("read_file", trace.executionBoundary().metadata().get("latest_tool_name"));
        }
    }

    @Test
    void getJudgmentTraceAndLiveFlowExposeMountedContextRuntimeFacts() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("judgment-trace-runtime-facts.db"))) {
            TaskService service = service(db);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "runtime facts task", "coding", "user", "high",
                "把 mounted context rollout 信号带进单任务诊断面", "确认 judgment trace 和 live flow 都能读到 runtime facts", null, null, Map.of(), false
            ));

            toolInvocationDao.insert(new ToolInvocationRecord(
                "exec_runtime_facts_1",
                task.sessionId(),
                task.id(),
                "codex",
                "exec_runtime_facts_1",
                "read_file",
                Map.of("path", "input.txt"),
                "input loaded",
                "succeeded",
                true,
                24,
                List.of("input.txt"),
                Instant.now(),
                Map.ofEntries(
                    Map.entry("execution_status", "succeeded"),
                    Map.entry("tool_execution_mode", "single_tool_round"),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("mounted_context_panel_count", 3),
                    Map.entry("mounted_context_non_empty_panel_count", 2),
                    Map.entry("mounted_context_selection_trace_count", 1),
                    Map.entry("mounted_context_rendered_panel_count", 2),
                    Map.entry("mounted_context_hidden_panel_count", 0),
                    Map.entry("mounted_context_rendered_object_count", 3),
                    Map.entry("mounted_context_hidden_object_count", 1),
                    Map.entry("mounted_context_rendered_selection_trace_count", 1),
                    Map.entry("mounted_context_hidden_selection_trace_count", 0),
                    Map.entry("mounted_context_budget_truncated", true),
                    Map.entry("mounted_pinned_count", 1),
                    Map.entry("mounted_active_count", 2),
                    Map.entry("mounted_ancestor_count", 1),
                    Map.entry("mounted_sibling_count", 1),
                    Map.entry("mounted_evidence_count", 1),
                    Map.entry("mounted_index_count", 1),
                    Map.entry("mounted_archive_count", 1),
                    Map.entry("candidate_workers", List.of("codex", "kimi")),
                    Map.entry("tool_invocation_ids", List.of("exec_fact_1")),
                    Map.entry("evidence_refs", List.of("tool:read_file:input.txt")),
                    Map.entry("unfinished_items", List.of("manual_review"))
                )
            ));
            decisionDao.insert(new Decision(
                IdGenerator.newId("dec"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "execution_judgment",
                "Execution judgment: continue",
                "judgment sees the same mounted context surface",
                "medium",
                null,
                Map.ofEntries(
                    Map.entry("action", "continue"),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("mounted_context_panel_count", 3),
                    Map.entry("mounted_context_non_empty_panel_count", 2),
                    Map.entry("mounted_context_selection_trace_count", 1),
                    Map.entry("mounted_context_rendered_panel_count", 2),
                    Map.entry("mounted_context_hidden_panel_count", 0),
                    Map.entry("mounted_context_rendered_object_count", 3),
                    Map.entry("mounted_context_hidden_object_count", 1),
                    Map.entry("mounted_context_rendered_selection_trace_count", 1),
                    Map.entry("mounted_context_hidden_selection_trace_count", 0),
                    Map.entry("mounted_context_budget_truncated", true),
                    Map.entry("mounted_pinned_count", 1),
                    Map.entry("mounted_active_count", 2),
                    Map.entry("mounted_ancestor_count", 1),
                    Map.entry("mounted_sibling_count", 1),
                    Map.entry("mounted_evidence_count", 1),
                    Map.entry("mounted_index_count", 1),
                    Map.entry("mounted_archive_count", 1),
                    Map.entry("candidate_workers", List.of("codex", "kimi")),
                    Map.entry("tool_invocation_ids", List.of("exec_fact_1")),
                    Map.entry("evidence_refs", List.of("tool:read_file:input.txt")),
                    Map.entry("unfinished_items", List.of("manual_review"))
                )
            ));
            decisionDao.insert(new Decision(
                IdGenerator.newId("dec"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "completion_judgment",
                "Completion judgment: partial",
                "completion judgment also sees the same mounted context surface",
                "medium",
                null,
                Map.ofEntries(
                    Map.entry("status", "partially_done"),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("mounted_context_panel_count", 3),
                    Map.entry("mounted_context_non_empty_panel_count", 2),
                    Map.entry("mounted_context_selection_trace_count", 1),
                    Map.entry("mounted_context_rendered_panel_count", 2),
                    Map.entry("mounted_context_hidden_panel_count", 0),
                    Map.entry("mounted_context_rendered_object_count", 3),
                    Map.entry("mounted_context_hidden_object_count", 1),
                    Map.entry("mounted_context_rendered_selection_trace_count", 1),
                    Map.entry("mounted_context_hidden_selection_trace_count", 0),
                    Map.entry("mounted_context_budget_truncated", true),
                    Map.entry("mounted_pinned_count", 1),
                    Map.entry("mounted_active_count", 2),
                    Map.entry("mounted_ancestor_count", 1),
                    Map.entry("mounted_sibling_count", 1),
                    Map.entry("mounted_evidence_count", 1),
                    Map.entry("mounted_index_count", 1),
                    Map.entry("mounted_archive_count", 1),
                    Map.entry("candidate_workers", List.of("codex", "kimi")),
                    Map.entry("evidence_refs", List.of("tool:read_file:input.txt")),
                    Map.entry("unfinished_items", List.of("manual_review"))
                )
            ));

            var trace = service.getJudgmentTrace(task.id());
            var flow = service.getLiveFlow(task.id(), 10);

            assertEquals("mounted_context_primary", trace.runtimeFacts().metadata().get("prompt_mode"));
            assertEquals(Boolean.TRUE, trace.runtimeFacts().metadata().get("mounted_context_rendered"));
            assertEquals(Boolean.TRUE, trace.runtimeFacts().metadata().get("mounted_context_injected"));
            assertEquals(3, ((Number) trace.runtimeFacts().metadata().get("mounted_context_panel_count")).intValue());
            assertEquals(List.of("codex", "kimi"), trace.runtimeFacts().metadata().get("candidate_workers"));
            assertEquals(List.of("tool:read_file:input.txt"), trace.runtimeFacts().metadata().get("evidence_refs"));
            assertEquals(List.of("manual_review"), trace.runtimeFacts().metadata().get("unfinished_items"));
            assertNotNull(trace.runtimeCognitionSurface());
            assertEquals("codex", trace.runtimeCognitionSurface().route().selectedWorker());
            assertEquals("codex", trace.runtimeCognitionSurface().execution().workerId());
            assertEquals("mounted_context_primary", trace.runtimeCognitionSurface().execution().promptMode());
            assertEquals(Boolean.TRUE, trace.runtimeCognitionSurface().execution().mountedRenderUsed());
            assertEquals(2, trace.runtimeCognitionSurface().execution().mountedContextNonEmptyPanelCount());
            assertEquals(1, trace.runtimeCognitionSurface().execution().mountedContextSelectionTraceCount());
            assertEquals(3, trace.runtimeCognitionSurface().execution().mountedContextRenderedObjectCount());
            assertEquals(1, trace.runtimeCognitionSurface().execution().mountedContextHiddenObjectCount());
            assertEquals(Boolean.TRUE, trace.runtimeCognitionSurface().execution().mountedContextBudgetTruncated());
            assertEquals(2, trace.runtimeCognitionSurface().execution().mountedActiveCount());
            assertEquals(1, trace.runtimeCognitionSurface().execution().mountedEvidenceCount());
            assertEquals(1, trace.runtimeCognitionSurface().execution().mountedArchiveCount());
            assertEquals(List.of("exec_fact_1"), trace.runtimeCognitionSurface().execution().toolInvocationIds());
            assertEquals(List.of("tool:read_file:input.txt"), trace.runtimeCognitionSurface().execution().evidenceRefs());
            assertEquals(Boolean.TRUE, trace.runtimeCognitionSurface().executionJudgment().mountedRenderUsed());
            assertEquals(2, trace.runtimeCognitionSurface().executionJudgment().mountedContextNonEmptyPanelCount());
            assertEquals(3, trace.runtimeCognitionSurface().executionJudgment().mountedContextRenderedObjectCount());
            assertEquals(Boolean.TRUE, trace.runtimeCognitionSurface().executionJudgment().mountedContextBudgetTruncated());
            assertEquals(List.of("exec_fact_1"), trace.runtimeCognitionSurface().executionJudgment().toolInvocationIds());
            assertEquals(2, trace.runtimeCognitionSurface().completionJudgment().mountedActiveCount());
            assertEquals(Boolean.TRUE,
                trace.runtimeCognitionSurface().alignment().routeWorkerMatchesExecutionWorker());
            assertEquals(Boolean.TRUE,
                trace.runtimeCognitionSurface().alignment().executionAndExecutionJudgmentPromptModeAligned());

            assertEquals("mounted_context_primary", flow.runtimeFacts().metadata().get("prompt_mode"));
            assertEquals(Boolean.TRUE, flow.runtimeFacts().metadata().get("mounted_context_rendered"));
            assertEquals(Boolean.TRUE, flow.runtimeFacts().metadata().get("mounted_context_injected"));
            assertEquals(3, ((Number) flow.runtimeFacts().metadata().get("mounted_context_panel_count")).intValue());
            assertEquals(List.of("codex", "kimi"), flow.runtimeFacts().metadata().get("candidate_workers"));
            assertEquals(List.of("tool:read_file:input.txt"), flow.runtimeFacts().metadata().get("evidence_refs"));
            assertEquals(List.of("manual_review"), flow.runtimeFacts().metadata().get("unfinished_items"));
            assertNotNull(flow.runtimeCognitionSurface());
            assertEquals("codex", flow.runtimeCognitionSurface().route().selectedWorker());
            assertEquals("single_tool_round", flow.executionBoundary().metadata().get("tool_execution_mode"));
            assertEquals("mounted_context_primary", flow.runtimeCognitionSurface().execution().promptMode());
            assertEquals(Boolean.TRUE, flow.runtimeCognitionSurface().execution().mountedRenderUsed());
            assertEquals(2, flow.runtimeCognitionSurface().execution().mountedContextNonEmptyPanelCount());
            assertEquals(1, flow.runtimeCognitionSurface().execution().mountedContextSelectionTraceCount());
            assertEquals(3, flow.runtimeCognitionSurface().execution().mountedContextRenderedObjectCount());
            assertEquals(1, flow.runtimeCognitionSurface().execution().mountedContextHiddenObjectCount());
            assertEquals(Boolean.TRUE, flow.runtimeCognitionSurface().execution().mountedContextBudgetTruncated());
            assertEquals(2, flow.runtimeCognitionSurface().execution().mountedActiveCount());
            assertEquals(List.of("exec_fact_1"), flow.runtimeCognitionSurface().execution().toolInvocationIds());
            assertEquals(List.of("codex", "kimi"), flow.runtimeCognitionSurface().executionJudgment().candidateWorkers());
            assertEquals(1, flow.runtimeCognitionSurface().executionJudgment().mountedPinnedCount());
            assertEquals(3, flow.runtimeCognitionSurface().executionJudgment().mountedContextRenderedObjectCount());
            assertEquals(List.of("exec_fact_1"), flow.runtimeCognitionSurface().completionJudgment().toolInvocationIds());
            assertEquals(1, flow.runtimeCognitionSurface().completionJudgment().mountedArchiveCount());
            assertEquals(Boolean.TRUE, flow.runtimeCognitionSurface().completionJudgment().mountedContextBudgetTruncated());
            assertEquals(Boolean.TRUE,
                flow.runtimeCognitionSurface().alignment().executionAndCompletionJudgmentPromptModeAligned());
            assertNotNull(flow.runtimeCognitionTimeline());
            assertEquals(4, flow.runtimeCognitionTimeline().size());
            var timelineByStage = flow.runtimeCognitionTimeline().stream()
                .collect(java.util.stream.Collectors.toMap(
                    entry -> entry.stage(),
                    entry -> entry
                ));
            assertEquals("codex", timelineByStage.get("route").workerId());
            assertEquals("mounted_context_primary", timelineByStage.get("execution").promptMode());
            assertEquals(3, timelineByStage.get("execution").mountedContextRenderedObjectCount());
            assertEquals(1, timelineByStage.get("execution").mountedContextHiddenObjectCount());
            assertEquals(1, timelineByStage.get("execution").mountedContextRenderedSelectionTraceCount());
            assertEquals(0, timelineByStage.get("execution").mountedContextHiddenSelectionTraceCount());
            assertEquals(Boolean.TRUE, timelineByStage.get("execution").mountedContextBudgetTruncated());
            assertEquals(List.of("exec_fact_1"), timelineByStage.get("execution").toolInvocationIds());
            assertEquals(Boolean.TRUE, timelineByStage.get("execution_judgment").alignedWithPreviousPromptMode());
            assertEquals(3, timelineByStage.get("execution_judgment").mountedContextRenderedObjectCount());
            assertEquals(Boolean.TRUE, timelineByStage.get("execution_judgment").mountedContextBudgetTruncated());
            assertEquals(List.of("exec_fact_1"), timelineByStage.get("execution_judgment").toolInvocationIds());
            assertEquals(1, timelineByStage.get("completion_judgment").mountedContextHiddenObjectCount());
            assertEquals(List.of("exec_fact_1"), timelineByStage.get("completion_judgment").toolInvocationIds());
            assertEquals(List.of("tool:read_file:input.txt"),
                timelineByStage.get("completion_judgment").evidenceRefs());
        }
    }

    @Test
    void getLiveFlowExtendsRuntimeCognitionTimelineWithContinuityBoundaries() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("live-flow-continuity-timeline.db"))) {
            TaskService service = service(db);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "continuity timeline task", "continuation", "user", "high",
                "把 pause resume handoff checkpoint 拉进同一条 runtime cognition timeline",
                "验证 continuity boundary 可见", null, null,
                Map.of("prompt_mode", "mounted_context_primary"), false
            ));

            Instant pausedAt = Instant.parse("2026-05-08T08:00:00Z");
            Instant checkpointAt = Instant.parse("2026-05-08T08:01:00Z");
            Instant resumedAt = Instant.parse("2026-05-08T08:02:00Z");
            Instant handoffAt = Instant.parse("2026-05-08T08:03:00Z");
            Instant packetAt = Instant.parse("2026-05-08T08:04:00Z");

            eventDao.insert(new Event(
                IdGenerator.newId("evt"),
                task.sessionId(),
                task.id(),
                pausedAt,
                "task_control_action",
                "task_service",
                null,
                "Task control action: pause",
                Map.of(
                    "action", "pause",
                    "action_category", "task_control",
                    "reason", "waiting for human review",
                    "assigned_worker", "codex",
                    "prompt_mode", "mounted_context_primary"
                )
            ));
            checkpointDao.insert(new com.agentcloud.model.Checkpoint(
                IdGenerator.newId("cp"),
                task.sessionId(),
                task.id(),
                checkpointAt,
                "pause_before",
                "pause checkpoint captured before waiting",
                Map.of(
                    "assigned_worker", "codex",
                    "prompt_mode", "mounted_context_primary",
                    "open_questions", List.of("confirm handoff target")
                ),
                Map.of(),
                Map.of("artifact_count", 0)
            ));
            eventDao.insert(new Event(
                IdGenerator.newId("evt"),
                task.sessionId(),
                task.id(),
                resumedAt,
                "task_control_action",
                "task_service",
                null,
                "Task control action: resume",
                Map.of(
                    "action", "resume",
                    "action_category", "task_control",
                    "assigned_worker", "codex",
                    "prompt_mode", "mounted_context_primary"
                )
            ));
            eventDao.insert(new Event(
                IdGenerator.newId("evt"),
                task.sessionId(),
                task.id(),
                handoffAt,
                "task_control_action",
                "task_service",
                null,
                "Task control action: handoff",
                Map.of(
                    "action", "handoff",
                    "action_category", "task_control",
                    "previous_worker", "codex",
                    "assigned_worker", "kimi",
                    "target_worker", "kimi",
                    "prompt_mode", "mounted_context_primary"
                )
            ));
            db.jdbi().onDemand(ResumePacketDao.class).insert(new ResumePacket(
                IdGenerator.newId("packet"),
                task.sessionId(),
                task.id(),
                packetAt,
                "1.1",
                "resume packet summary",
                "decision snapshot",
                "artifact snapshot",
                List.of("confirm resume sequencing"),
                "resume scheduler after handoff",
                Map.of(
                    "assigned_worker", "kimi",
                    "current_status", "waiting",
                    "current_node", "packet",
                    "prompt_mode", "mounted_context_primary",
                    "resume_hint", "continue from packet boundary",
                    "open_questions", List.of("confirm resume sequencing")
                )
            ));

            var flow = service.getLiveFlow(task.id(), 10);

            assertNotNull(flow.runtimeCognitionTimeline());
            assertTrue(flow.runtimeCognitionTimeline().size() >= 4);
            var continuityEntries = flow.runtimeCognitionTimeline().stream()
                .filter(entry -> "continuity_action".equals(entry.stage()))
                .toList();
            assertEquals(3, continuityEntries.size());
            assertEquals("pause", continuityEntries.get(0).continuityAction());
            assertEquals("waiting for human review", continuityEntries.get(0).reason());
            assertEquals("mounted_context_primary", continuityEntries.get(0).promptMode());
            assertEquals("resume", continuityEntries.get(1).continuityAction());
            assertEquals("handoff", continuityEntries.get(2).continuityAction());
            assertEquals("kimi", continuityEntries.get(2).workerId());
            assertEquals("kimi", continuityEntries.get(2).targetWorker());
            assertTrue(continuityEntries.get(2).summary().contains("handoff"));

            var checkpointEntries = flow.runtimeCognitionTimeline().stream()
                .filter(entry -> "checkpoint".equals(entry.stage()))
                .toList();
            assertEquals(1, checkpointEntries.size());
            assertEquals("pause_before", checkpointEntries.get(0).checkpointType());
            assertEquals("codex", checkpointEntries.get(0).workerId());
            assertEquals("mounted_context_primary", checkpointEntries.get(0).promptMode());
            assertEquals(List.of("confirm handoff target"), checkpointEntries.get(0).unfinishedItems());

            var packetEntries = flow.runtimeCognitionTimeline().stream()
                .filter(entry -> "resume_packet".equals(entry.stage()))
                .toList();
            assertEquals(1, packetEntries.size());
            assertEquals("resume_packet", packetEntries.get(0).continuityAction());
            assertEquals("kimi", packetEntries.get(0).workerId());
            assertEquals("mounted_context_primary", packetEntries.get(0).promptMode());
            assertEquals("waiting", packetEntries.get(0).executionStatus());
            assertEquals("continue from packet boundary", packetEntries.get(0).reason());
            assertEquals(List.of("confirm resume sequencing"), packetEntries.get(0).unfinishedItems());
        }
    }

    @Test
    void getLiveFlowIncludesAgentRunProjection() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("live-flow-agent-run.db"))) {
            TaskService service = service(db);
            AgentRunDao agentRunDao = db.jdbi().onDemand(AgentRunDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "agent live flow task", "coding", "user", "high",
                "实现 provider live flow 聚合", "查看 agent run 诊断", null, null,
                Map.of("model_mode", "strong_only"), false
            ));

            agentRunDao.insert(new AgentRunRecord(
                "arun_live",
                task.id(),
                task.sessionId(),
                "codex",
                "Codex",
                "executor",
                "codex",
                "strong",
                "completed",
                Instant.now().minusMillis(200),
                Instant.now(),
                200L,
                "Codex run completed",
                "worker_round",
                1,
                Map.of("selection_reason", "test route")
            ));
            eventDao.insert(new Event(
                IdGenerator.newId("evt"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_round",
                "control_node",
                null,
                "Worker round completed",
                Map.of("agent_run_id", "arun_live", "provider_id", "codex")
            ));
            artifactDao.insert(new Artifact(
                IdGenerator.newId("art"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_artifact",
                "Codex output",
                "memory://codex-output",
                null,
                "agent artifact",
                Map.of("agent_run_id", "arun_live", "provider_id", "codex")
            ));

            var flow = service.getLiveFlow(task.id(), 10);

            assertNotNull(flow.providerSelection());
            assertEquals("codex", flow.providerSelection().selectedProvider());
            assertEquals("arun_live", flow.agentRun().runId());
            assertEquals(1, flow.agentRunEvents().size());
            assertEquals(1, flow.agentArtifacts().size());
            assertEquals("arun_live", flow.agentRunEvents().get(0).runId());
            assertEquals("arun_live", flow.agentArtifacts().get(0).runId());
        }
    }

    @Test
    void getHarnessTraceCompressesAheReviewInputs() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("harness-trace-ahe.db"))) {
            TaskService service = service(db);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "harness trace task", "coding", "user", "high",
                "生成 AHE 复盘输入", "检查 blocked 状态和证据", null, null, Map.of(), false
            ));

            artifactDao.insert(new Artifact(
                IdGenerator.newId("art"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_artifact",
                "Blocked executor result",
                null,
                null,
                "工具链停在人工复核",
                Map.of("latest_worker_metadata", Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "tool_chain_step_count", 2,
                    "tool_chain_termination_reason", "planner_no_additional_tool",
                    "tool_chain_trace", List.of(
                        Map.of("tool_chain_step_index", 1, "tool_name", "read_file"),
                        Map.of("tool_chain_step_index", 2, "tool_name", "write_file")
                    ),
                    "execution_status", "blocked",
                    "evidence_refs", List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"),
                    "unfinished_items", List.of("manual_review")
                ))
            ));
            toolInvocationDao.insert(new ToolInvocationRecord(
                IdGenerator.newId("tool"),
                task.sessionId(),
                task.id(),
                "tool-worker",
                "exec_write_file",
                "write_file",
                Map.of("path", "draft.txt"),
                "draft updated",
                "succeeded",
                true,
                15,
                List.of("draft.txt"),
                Instant.now(),
                Map.of("tool_execution_mode", "multi_tool_round", "tool_chain_step_index", 2)
            ));

            var trace = service.getHarnessTrace(task.id(), 10);

            assertEquals(task.id(), trace.taskId());
            assertEquals("blocked", trace.executionStatus());
            assertEquals(List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"), trace.evidenceRefs());
            assertEquals(List.of("manual_review"), trace.unfinishedItems());
            assertEquals(1, trace.toolInvocations().size());
            assertNotNull(trace.experimentRun());
            assertEquals("multi_tool_round", trace.harnessMetadata().get("tool_execution_mode"));
            assertEquals(1, trace.harnessMetadata().get("tool_invocation_count"));
        }
    }

    @Test
    void getHarnessTraceFallsBackToToolInvocationMetadataWhenExperimentRunIsMissing() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("harness-trace-tool-metadata.db"))) {
            TaskService service = service(db);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "harness trace tool metadata", "coding", "user", "high",
                "聚合工具调用元数据", "无 artifact 时仍返回 schema 字段", null, null, Map.of(), false
            ));
            toolInvocationDao.insert(new ToolInvocationRecord(
                IdGenerator.newId("tool"),
                task.sessionId(),
                task.id(),
                "tool-worker",
                "exec_write_file",
                "write_file",
                Map.of("path", "draft.txt"),
                "draft updated",
                "succeeded",
                true,
                15,
                List.of("draft.txt"),
                Instant.now(),
                Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "execution_status", "blocked",
                    "evidence_refs", List.of("tool:write_file:draft.txt"),
                    "unfinished_items", List.of("manual_review")
                )
            ));

            var trace = service.getHarnessTrace(task.id(), 10);

            assertEquals("blocked", trace.executionStatus());
            assertEquals(List.of("tool:write_file:draft.txt"), trace.evidenceRefs());
            assertEquals(List.of("manual_review"), trace.unfinishedItems());
            assertEquals("multi_tool_round", trace.harnessMetadata().get("tool_execution_mode"));
            assertEquals(1, trace.harnessMetadata().get("tool_invocation_count"));
        }
    }

    TaskService service(DatabaseManager db) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
        SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
        ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
        DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
        ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
        CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);
        LearningMemoryDao learningMemoryDao = db.jdbi().onDemand(LearningMemoryDao.class);
        ExperimentRunDao experimentRunDao = db.jdbi().onDemand(ExperimentRunDao.class);
        AgentRunDao agentRunDao = db.jdbi().onDemand(AgentRunDao.class);
        AgentProviderRegistry providerRegistry = new AgentProviderRegistry()
            .register(new CodexProvider());

        TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
            null, null, null, null, null, null, null
        ) {
            @Override
            public TaskRuntimeContext build(Task task) {
                return new TaskRuntimeContext(
                    task,
                    null,
                    null,
                    List.of(),
                    decisionDao.listBySessionAndTask(task.sessionId(), task.id(), 20),
                    artifactDao.listBySessionAndTask(task.sessionId(), task.id(), 20),
                    List.of(),
                    new ActiveContext(
                        task.title(),
                        List.of("priority=high"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("继续扩写"),
                        List.of(),
                        List.of(),
                        List.of(),
                        "已汇总 live flow 所需的最小上下文",
                        "test runtime context",
                        12
                    ),
                    new MountedContextView(
                        null,
                        task.id(),
                        List.of(
                            new MountedContextPanel(
                                MountedContextPanelName.PINNED,
                                "Pinned",
                                List.of(new ContextObject(
                                    task.id() + ":constraint",
                                    "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/constraints",
                                    ContextObjectType.CONSTRAINT,
                                    "/sessions/" + task.sessionId() + "/tasks/" + task.id(),
                                    "Constraints",
                                    "保留 mounted context 里的关键约束",
                                    "保留 mounted context 里的关键约束",
                                    Instant.parse("2026-05-06T07:00:00Z"),
                                    ContextRetentionState.PINNED,
                                    List.of(),
                                    List.of(),
                                    Map.of("constraint_count", 1)
                                ))
                            )
                        ),
                        List.of("compat_mode=task_runtime_context_preserved")
                    )
                );
            }
        };

        return new TaskService(
            taskDao,
            sessionDao,
            eventDao,
            packetDao,
            new WorkerRouter(new WorkerRegistry()),
            null,
            null,
            null,
            runtimeContextBuilder,
            new ConsolidationService(decisionDao, artifactDao, eventDao, checkpointDao, taskDao),
            new LearningMemoryService(learningMemoryDao),
            toolInvocationDao,
            sessionMessageDao,
            new ExperimentRunService(experimentRunDao, decisionDao, artifactDao, eventDao, toolInvocationDao),
            new AgentRunService(agentRunDao, providerRegistry, eventDao, artifactDao)
        );
    }
}
