package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.RuntimeFactSetAssembler;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.runtime.model.RuntimeFactSet;
import com.agentcloud.store.ToolInvocationDao;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeFactSetAssemblerTest {

    @Test
    void assembleBuildsFactSetFromRuntimeContextAndToolTrace() {
        Instant now = Instant.parse("2026-05-05T06:00:00Z");
        Task task = new Task(
            "task_1",
            "session_1",
            null,
            "runtime facts task",
            "active",
            "high",
            now,
            now,
            now,
            null,
            null,
            null,
            "unify facts",
            "review packet",
            "codex",
            "continue",
            null,
            Map.of("task_type", "coding", "model_mode", "strong_only")
        );

        Decision executionDecision = new Decision(
            "dec_exec",
            task.sessionId(),
            task.id(),
            now.plusSeconds(1),
            "execution_judgment",
            "Need review",
            "Execution suggests pausing for human review",
            "medium",
            null,
            Map.of(
                "action", "pause",
                "next_step", "review generated draft",
                "needs_context_reopen", true,
                "evidence_gap_detected", true,
                "needs_archive_retrieval", true,
                "needs_external_fact_refresh", true,
                "reopen_candidate_paths", List.of(
                    "/sessions/session_1/tasks/task_1/tool_invocations",
                    "/sessions/session_1/tasks/task_1/packets/packet_fact_1"
                )
            )
        );
        Decision completionDecision = new Decision(
            "dec_done",
            task.sessionId(),
            task.id(),
            now.plusSeconds(2),
            "completion_judgment",
            "Not done yet",
            "More work remains",
            "low",
            null,
            Map.of("status", "incomplete", "suggested_next_action", "finish edits")
        );
        Artifact latestArtifact = new Artifact(
            "art_1",
            task.sessionId(),
            task.id(),
            now.plusSeconds(3),
            "worker_artifact",
            "Draft summary",
            null,
            null,
            "Draft body",
            Map.of("suggested_next_step", "apply polish")
        );
        TaskRuntimeContext runtimeContext = new TaskRuntimeContext(
            task,
            null,
            null,
            List.of(),
            List.of(executionDecision, completionDecision),
            List.of(latestArtifact),
            List.of(),
            new ActiveContext(
                "finish draft",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("ship patch"),
                List.of(),
                List.of(),
                List.of(),
                "continuity says review draft",
                "ctx",
                12
            )
        );

        TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(null, null, null, null, null, null, null) {
            @Override
            public TaskRuntimeContext build(Task ignored) {
                return runtimeContext;
            }
        };

        ToolInvocationRecord record = new ToolInvocationRecord(
            "tool_1",
            task.sessionId(),
            task.id(),
            "codex",
            "exec_1",
            "write_file",
            Map.of("path", "draft.txt"),
            "draft updated",
            "succeeded",
            true,
            21,
            List.of("draft.txt"),
            now.plusSeconds(4),
            Map.of("tool_execution_mode", "multi_tool_round")
        );

        ToolInvocationDao toolInvocationDao = new InMemoryToolInvocationDao(record);
        WorkerRouter router = routerWithCodex();
        RuntimeFactSetAssembler assembler = new RuntimeFactSetAssembler(runtimeContextBuilder, toolInvocationDao, router);

        RuntimeFactSet facts = assembler.assemble(task, 10);

        assertEquals(task.id(), facts.taskId());
        assertEquals("Draft body", facts.latestOutput());
        assertEquals("pause", facts.recommendedAction());
        assertEquals("review generated draft", facts.recommendedNextStep());
        assertEquals(1, facts.toolInvocations().size());
        assertEquals("write_file", facts.toolInvocations().get(0).toolName());
        assertNotNull(facts.executionBoundary());
        assertEquals("exec_1", facts.executionBoundary().executionId());
        assertEquals("codex", facts.executionBoundary().workerId());
        assertEquals("succeeded", facts.executionBoundary().executionStatus());
        assertEquals(1, facts.executionBoundary().toolInvocationCount());
        assertEquals(List.of("tool_1"), facts.executionBoundary().toolInvocationIds());
        assertEquals("1 tool call · succeeded · write_file", facts.executionBoundary().traceSummary());
        assertNotNull(facts.routePreview());
        assertEquals("codex", facts.routePreview().selectedWorker());
        assertTrue((Boolean) facts.metadata().get("has_runtime_context"));
        assertTrue((Boolean) facts.metadata().get("has_execution_judgment"));
        assertTrue((Boolean) facts.metadata().get("has_completion_judgment"));
        assertTrue((Boolean) facts.metadata().get("has_execution_boundary"));
        assertEquals("exec_1", facts.metadata().get("execution_id"));
        assertEquals("succeeded", facts.metadata().get("execution_status"));
        assertEquals(21L, facts.metadata().get("execution_duration_ms"));
        assertEquals(1, facts.metadata().get("execution_tool_invocation_count"));
        assertEquals("1 tool call · succeeded · write_file", facts.metadata().get("execution_trace_summary"));
        assertEquals(Boolean.TRUE, facts.metadata().get("needs_context_reopen"));
        assertEquals(Boolean.TRUE, facts.metadata().get("evidence_gap_detected"));
        assertEquals(Boolean.TRUE, facts.metadata().get("needs_archive_retrieval"));
        assertEquals(Boolean.TRUE, facts.metadata().get("needs_external_fact_refresh"));
        assertEquals(List.of(
                "/sessions/session_1/tasks/task_1/tool_invocations",
                "/sessions/session_1/tasks/task_1/packets/packet_fact_1"
            ),
            facts.metadata().get("reopen_candidate_paths"));
        assertFalse((Boolean) facts.metadata().get("has_latest_packet"));
    }

    @Test
    void assembleReturnsEmptyFactSetWhenTaskIsNull() {
        RuntimeFactSetAssembler assembler = new RuntimeFactSetAssembler(null, null, null);

        RuntimeFactSet facts = assembler.assemble(null, 10);

        assertNotNull(facts);
        assertEquals("", facts.taskId());
        assertEquals("", facts.latestOutput());
        assertEquals(List.of(), facts.toolInvocations());
        assertEquals(Map.of(), facts.metadata());
        assertEquals(null, facts.executionBoundary());
    }

    @Test
    void assemblePrefersLatestWorkerMetadataForRoundLevelExecutionBoundary() {
        Instant now = Instant.parse("2026-05-05T06:00:00Z");
        Task task = new Task(
            "task_2",
            "session_2",
            null,
            "runtime facts worker round",
            "active",
            "high",
            now,
            now,
            now,
            null,
            null,
            null,
            "unify worker round facts",
            "review tool chain",
            "kimi",
            "continue",
            null,
            Map.of("task_type", "coding", "model_mode", "orchestrated", "orchestration_stage", "execution_active")
        );

        Artifact latestArtifact = new Artifact(
            "art_round",
            task.sessionId(),
            task.id(),
            now.plusSeconds(2),
            "worker_artifact",
            "Executor result",
            null,
            null,
            "round summary",
            Map.ofEntries(
                Map.entry("selected_worker", "kimi"),
                Map.entry("route_source", "learning_memory"),
                Map.entry("preferred_worker_hint", "kimi"),
                Map.entry("learning_hint_applied", true),
                Map.entry("fallback_reason", "hint survived tier filter"),
                Map.entry("latest_worker_metadata", Map.ofEntries(
                    Map.entry("selected_worker", "kimi"),
                    Map.entry("selected_worker_type", "kimi"),
                    Map.entry("selected_model_tier", "small"),
                    Map.entry("execution_role", "executor"),
                    Map.entry("selection_scope", "executor"),
                    Map.entry("route_source", "learning_memory"),
                    Map.entry("preferred_worker_hint", "kimi"),
                    Map.entry("learning_hint_applied", true),
                    Map.entry("fallback_reason", "hint survived tier filter"),
                    Map.entry("tool_execution_mode", "multi_tool_round"),
                    Map.entry("tool_chain_step_count", 2),
                    Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
                    Map.entry("tool_chain_trace", List.of(
                        Map.of("tool_chain_step_index", 1, "tool_name", "read_file"),
                        Map.of("tool_chain_step_index", 2, "tool_name", "write_file")
                    )),
                    Map.entry("execution_status", "blocked"),
                    Map.entry("provider_turn_status", "cancelled"),
                    Map.entry("provider_abort_reason", "user_interrupted"),
                    Map.entry("provider_timeout_kind", "max_duration"),
                    Map.entry("provider_activity_timeout_ms", 180_000L),
                    Map.entry("provider_turn_activity_timeout_ms", 180_000L),
                    Map.entry("provider_turn_max_duration_ms", 900_000L),
                    Map.entry("partial_output_chars", 640),
                    Map.entry("partial_timeout_min_output_chars", 200),
                    Map.entry("evidence_refs", List.of("tool:read_file:input.txt", "tool:write_file:draft.txt")),
                    Map.entry("unfinished_items", List.of("manual_review"))
                ))
            )
        );
        TaskRuntimeContext runtimeContext = new TaskRuntimeContext(
            task,
            null,
            null,
            List.of(),
            List.of(),
            List.of(latestArtifact),
            List.of(),
            new ActiveContext(
                "review tool chain",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("manual_review"),
                List.of(),
                List.of(),
                List.of(),
                "continuity says inspect latest worker metadata",
                "ctx",
                8
            )
        );

        TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(null, null, null, null, null, null, null) {
            @Override
            public TaskRuntimeContext build(Task ignored) {
                return runtimeContext;
            }
        };

        ToolInvocationRecord writeRecord = new ToolInvocationRecord(
            "tool_write",
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
            now.plusSeconds(4),
            Map.of("tool_execution_mode", "multi_tool_round", "tool_chain_step_index", 2)
        );
        ToolInvocationRecord readRecord = new ToolInvocationRecord(
            "tool_read",
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
            now.plusSeconds(3),
            Map.of("tool_execution_mode", "multi_tool_round", "tool_chain_step_index", 1)
        );

        RuntimeFactSetAssembler assembler = new RuntimeFactSetAssembler(
            runtimeContextBuilder,
            new InMemoryToolInvocationDao(writeRecord, readRecord),
            routerWithCodexAndKimi()
        );

        RuntimeFactSet facts = assembler.assemble(task, 10);

        assertNotNull(facts.executionBoundary());
        assertEquals("blocked", facts.executionBoundary().executionStatus());
        assertEquals(2, facts.executionBoundary().toolInvocationCount());
        assertEquals(List.of("tool_write"), facts.executionBoundary().toolInvocationIds());
        assertEquals("2 steps · planner_no_additional_tool · read_file -> write_file", facts.executionBoundary().traceSummary());
        assertEquals("write_file", facts.executionBoundary().metadata().get("latest_tool_name"));
        assertEquals("learning_memory", facts.routePreview().routeSource());
        assertEquals("kimi", facts.routePreview().preferredWorkerHint());
        assertTrue(facts.routePreview().learningHintApplied());
        assertEquals("blocked", facts.metadata().get("execution_status"));
        assertEquals("cancelled", facts.executionBoundary().metadata().get("provider_turn_status"));
        assertEquals("user_interrupted", facts.executionBoundary().metadata().get("provider_abort_reason"));
        assertEquals("max_duration", facts.executionBoundary().metadata().get("provider_timeout_kind"));
        assertEquals(180_000L, facts.executionBoundary().metadata().get("provider_activity_timeout_ms"));
        assertEquals(180_000L, facts.executionBoundary().metadata().get("provider_turn_activity_timeout_ms"));
        assertEquals(900_000L, facts.executionBoundary().metadata().get("provider_turn_max_duration_ms"));
        assertEquals(640, ((Number) facts.executionBoundary().metadata().get("partial_output_chars")).intValue());
        assertEquals(200, ((Number) facts.executionBoundary().metadata().get("partial_timeout_min_output_chars")).intValue());
        assertEquals(List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"), facts.metadata().get("evidence_refs"));
        assertEquals(List.of("manual_review"), facts.metadata().get("unfinished_items"));
    }

    @Test
    void assembleRoutePreviewPrefersCurrentPinnedWorkerWhenHistoricalWorkerMetadataDrifts() {
        Instant now = Instant.parse("2026-05-05T06:00:00Z");
        Task task = new Task(
            "task_3",
            "session_3",
            null,
            "runtime facts pinned route",
            "active",
            "high",
            now,
            now,
            now,
            null,
            null,
            null,
            "keep current worker projection aligned",
            "continue on kimi",
            "kimi",
            "scheduler",
            null,
            Map.of(
                "task_type", "continuation",
                "model_mode", "orchestrated",
                "orchestration_stage", "execution_active",
                "target_worker", "kimi"
            )
        );

        Artifact latestArtifact = new Artifact(
            "art_drift",
            task.sessionId(),
            task.id(),
            now.plusSeconds(2),
            "worker_artifact",
            "historical planner artifact",
            null,
            null,
            "planner artifact should not override current route preview",
            Map.of(
                "latest_worker_metadata", Map.of(
                    "selected_worker", "codex",
                    "selected_worker_type", "codex",
                    "selected_model_tier", "strong",
                    "execution_role", "planner_executor",
                    "selection_scope", "planner",
                    "route_source", "ready_fallback",
                    "why_selected", "selected by model tier preference (strong) on ready-worker fallback: taskType=continuation, worker=codex",
                    "candidate_workers", List.of("codex", "claude")
                )
            )
        );
        TaskRuntimeContext runtimeContext = new TaskRuntimeContext(
            task,
            null,
            null,
            List.of(),
            List.of(),
            List.of(latestArtifact),
            List.of(),
            new ActiveContext(
                "continue on kimi",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "continuity says stay with current executor",
                "ctx",
                8
            )
        );

        TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(null, null, null, null, null, null, null) {
            @Override
            public TaskRuntimeContext build(Task ignored) {
                return runtimeContext;
            }
        };

        RuntimeFactSetAssembler assembler = new RuntimeFactSetAssembler(
            runtimeContextBuilder,
            new InMemoryToolInvocationDao(),
            routerWithCodexAndKimi()
        );

        RuntimeFactSet facts = assembler.assemble(task, 10);

        assertNotNull(facts.routePreview());
        assertEquals("kimi", facts.routePreview().selectedWorker());
        assertEquals("task_pinned", facts.routePreview().routeSource());
        assertEquals("small", facts.routePreview().selectedModelTier());
        assertEquals("executor", facts.routePreview().selectionScope());
        assertTrue(facts.routePreview().whySelected().contains("task-pinned worker"));
    }

    private WorkerRouter routerWithCodex() {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(new Worker(
            "codex",
            "codex",
            List.of("coding", "continuation"),
            List.of("read_file", "write_file"),
            List.of("workspace"),
            Map.of(),
            Map.of("model_tier", "strong", "primary_role", "executor"),
            false,
            true
        ));
        return new WorkerRouter(registry);
    }

    private WorkerRouter routerWithCodexAndKimi() {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(new Worker(
            "codex",
            "codex",
            List.of("coding", "continuation"),
            List.of("read_file", "write_file"),
            List.of("workspace"),
            Map.of(),
            Map.of("model_tier", "strong", "primary_role", "executor"),
            false,
            true
        ));
        registry.register(new Worker(
            "kimi",
            "kimi",
            List.of("coding", "continuation"),
            List.of("read_file", "write_file"),
            List.of("workspace"),
            Map.of(),
            Map.of("model_tier", "small", "primary_role", "executor"),
            false,
            true
        ));
        return new WorkerRouter(registry);
    }

    private static final class InMemoryToolInvocationDao implements ToolInvocationDao {
        private final List<ToolInvocationRecord> records;

        private InMemoryToolInvocationDao(ToolInvocationRecord... records) {
            this.records = List.of(records);
        }

        @Override
        public void insertRaw(String id, String sessionId, String taskId, String workerId, String executionId,
                              String toolName, String arguments, String resultSummary, String status,
                              boolean success, Integer elapsedMs, String touchedPaths, Instant createdAt,
                              String metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ToolInvocationRecord> listByTask(String taskId, int limit) {
            return records.stream().filter(record -> taskId.equals(record.taskId())).limit(limit).toList();
        }

        @Override
        public List<ToolInvocationRecord> listBySessionAndTask(String sessionId, String taskId, int limit) {
            return records.stream()
                .filter(record -> sessionId.equals(record.sessionId()) && taskId.equals(record.taskId()))
                .limit(limit)
                .toList();
        }

        @Override
        public org.jdbi.v3.core.Handle getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, X extends Exception> R withHandle(org.jdbi.v3.core.HandleCallback<R, X> callback) throws X {
            throw new UnsupportedOperationException();
        }
    }
}
