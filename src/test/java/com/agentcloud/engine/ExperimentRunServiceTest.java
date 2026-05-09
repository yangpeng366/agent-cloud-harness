package com.agentcloud.engine;

import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ExperimentRunRecord;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ExperimentRunDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.store.ToolInvocationDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentRunServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void refreshAggregatesComparableBaselineMetrics() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("experiment-run-service.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            ExperimentRunDao experimentRunDao = db.jdbi().onDemand(ExperimentRunDao.class);

            ExperimentRunService service = new ExperimentRunService(
                experimentRunDao,
                decisionDao,
                artifactDao,
                eventDao,
                toolInvocationDao
            );

            String sessionId = IdGenerator.newId("session");
            String taskId = IdGenerator.newId("task");
            sessionDao.insert(Session.create(sessionId, "baseline session", "active"));

            Task task = new Task(
                taskId,
                sessionId,
                null,
                "run orchestrated baseline",
                "done",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                "Executor delivered the target draft.",
                "Produce a verified draft.",
                null,
                "kimi",
                "end",
                null,
                Map.of(
                    "task_type", "coding",
                    "model_mode", "orchestrated",
                    "experiment_name", "baseline-min",
                    "task_case_key", "case-001",
                    "task_length_bucket", "long",
                    "planner_worker", "codex",
                    "planner_model_tier", "strong",
                    "executor_worker", "kimi",
                    "executor_model_tier", "small"
                )
            );
            taskDao.insert(task);

            artifactDao.insert(new Artifact(
                IdGenerator.newId("art"),
                sessionId,
                taskId,
                Instant.now(),
                "worker_output",
                "Planner brief",
                null,
                null,
                "Planner delegated the concrete execution step.",
                Map.of("selected_model_tier", "strong", "selected_worker", "codex")
            ));
            artifactDao.insert(new Artifact(
                IdGenerator.newId("art"),
                sessionId,
                taskId,
                Instant.now(),
                "worker_artifact",
                "Executor draft",
                null,
                null,
                "Execution worker produced the requested draft.",
                Map.ofEntries(
                    Map.entry("selected_model_tier", "small"),
                    Map.entry("selected_worker", "kimi"),
                    Map.entry("route_source", "learning_memory"),
                    Map.entry("preferred_worker_hint", "kimi"),
                    Map.entry("learning_hint_applied", true),
                    Map.entry("fallback_reason", "hint matched candidate set"),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_panel_count", 7),
                    Map.entry("mounted_context_rendered_panel_count", 6),
                    Map.entry("mounted_context_hidden_panel_count", 0),
                    Map.entry("mounted_context_rendered_object_count", 18),
                    Map.entry("mounted_context_hidden_object_count", 4),
                    Map.entry("mounted_context_rendered_selection_trace_count", 4),
                    Map.entry("mounted_context_hidden_selection_trace_count", 1),
                    Map.entry("mounted_context_budget_truncated", true),
                    Map.entry("image_input_count", 2),
                    Map.entry("image_input_used", true),
                    Map.entry("latest_worker_metadata", Map.of(
                        "execution_status", "blocked",
                        "evidence_refs", List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"),
                        "unfinished_items", List.of("manual_review"),
                        "tool_execution_mode", "multi_tool_round",
                        "tool_chain_step_count", 2,
                        "tool_chain_termination_reason", "planner_no_additional_tool",
                        "tool_chain_trace", List.of(
                            Map.of("tool_chain_step_index", 1, "tool_name", "read_file"),
                            Map.of("tool_chain_step_index", 2, "tool_name", "write_file")
                        )
                    ))
                )
            ));
            toolInvocationDao.insert(new ToolInvocationRecord(
                IdGenerator.newId("tool"),
                sessionId,
                taskId,
                "kimi",
                "exec_write_file",
                "write_file",
                Map.of("path", "draft.txt"),
                "Draft written.",
                "succeeded",
                true,
                42,
                List.of("draft.txt"),
                Instant.now(),
                Map.of("tool_execution_mode", "single_tool_round")
            ));
            eventDao.insert(new Event(
                IdGenerator.newId("evt"),
                sessionId,
                taskId,
                Instant.now(),
                "node_handoff",
                "control_node",
                null,
                "Planner handed execution to small worker.",
                Map.of("target_worker", "kimi")
            ));
            eventDao.insert(new Event(
                IdGenerator.newId("evt"),
                sessionId,
                taskId,
                Instant.now(),
                "node_human_gate",
                "control_node",
                null,
                "Asked for final human review once.",
                Map.of("gate", "final_review")
            ));
            eventDao.insert(new Event(
                IdGenerator.newId("evt"),
                sessionId,
                taskId,
                Instant.now(),
                "task_control_action",
                "task_service",
                null,
                "Task control action: resume",
                Map.of("action", "resume")
            ));
            decisionDao.insert(new Decision(
                IdGenerator.newId("dec"),
                sessionId,
                taskId,
                Instant.now(),
                "execution_judgment",
                "Execution judgment: done",
                "Executor output is ready for final review.",
                "medium",
                null,
                Map.ofEntries(
                    Map.entry("action", "done"),
                    Map.entry("next_step", "handoff to strong evaluator"),
                    Map.entry("needs_checkpoint", false),
                    Map.entry("needs_human", false),
                    Map.entry("selected_worker", "kimi"),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("prompt_rendering_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("mounted_context_panel_count", 7),
                    Map.entry("mounted_context_selection_trace_count", 1),
                    Map.entry("mounted_context_rendered_object_count", 18),
                    Map.entry("mounted_context_hidden_object_count", 4),
                    Map.entry("mounted_context_budget_truncated", true)
                )
            ));
            decisionDao.insert(new Decision(
                IdGenerator.newId("dec"),
                sessionId,
                taskId,
                Instant.now(),
                "completion_judgment",
                "Completion judgment: done",
                "Acceptance criteria satisfied.",
                "medium",
                null,
                Map.ofEntries(
                    Map.entry("status", "done"),
                    Map.entry("alignment_level", "high"),
                    Map.entry("evaluation_result", "done:high"),
                    Map.entry("evaluation_reason", "strong evaluator accepted the delegated output"),
                    Map.entry("evaluator_role", "strong_evaluator"),
                    Map.entry("evaluator_model_tier", "strong"),
                    Map.entry("evaluator_reason", "orchestrated mode uses strong-tier judgment to review delegated execution output"),
                    Map.entry("orchestration_closed_loop_observed", true),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("prompt_rendering_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("mounted_context_panel_count", 7),
                    Map.entry("mounted_context_selection_trace_count", 1),
                    Map.entry("mounted_context_rendered_object_count", 18),
                    Map.entry("mounted_context_hidden_object_count", 4),
                    Map.entry("mounted_context_budget_truncated", true)
                )
            ));

            ExperimentRunRecord run = service.refresh(task);

            assertEquals(taskId, run.taskId());
            assertEquals("baseline-min", run.experimentName());
            assertEquals("case-001", run.taskCaseKey());
            assertEquals("orchestrated", run.modelMode());
            assertEquals("long", run.taskLengthBucket());
            assertEquals("done", run.completionStatus());
            assertEquals("accepted", run.acceptanceResult());
            assertEquals(3, run.totalSteps());
            assertEquals(1, run.handoffCount());
            assertEquals(1, run.resumeCount());
            assertEquals(1, run.humanGateCount());
            assertEquals(1.45, run.totalCost(), 0.001);
            assertEquals(0.69, run.strongModelCostRatio(), 0.01);
            assertEquals(Boolean.TRUE, run.recoverySuccess());
            assertEquals("done:high", run.finalArtifactQualityNote());
            assertNull(run.failureReason());
            assertEquals(1, service.listRuns("baseline-min", null, null, null, 10).size());
            assertEquals(0, service.listRuns("baseline-min", null, null, "small_only", 10).size());
            assertTrue(run.metadata().containsKey("cost_basis"));
            assertEquals("kimi", run.metadata().get("selected_worker"));
            assertEquals("learning_memory", run.metadata().get("route_source"));
            assertEquals("kimi", run.metadata().get("preferred_worker_hint"));
            assertEquals(Boolean.TRUE, run.metadata().get("learning_hint_applied"));
            assertEquals("hint matched candidate set", run.metadata().get("fallback_reason"));
            assertEquals("mounted_context_primary", run.metadata().get("prompt_mode"));
            assertEquals(Boolean.TRUE, run.metadata().get("mounted_render_used"));
            assertEquals(7, ((Number) run.metadata().get("mounted_context_panel_count")).intValue());
            assertEquals(18, ((Number) run.metadata().get("mounted_context_rendered_object_count")).intValue());
            assertEquals(4, ((Number) run.metadata().get("mounted_context_hidden_object_count")).intValue());
            assertEquals(Boolean.TRUE, run.metadata().get("mounted_context_budget_truncated"));
            assertEquals(2, ((Number) run.metadata().get("image_input_count")).intValue());
            assertEquals(Boolean.TRUE, run.metadata().get("image_input_used"));
            assertEquals("multi_tool_round", run.metadata().get("tool_execution_mode"));
            assertEquals(2, ((Number) run.metadata().get("tool_chain_step_count")).intValue());
            assertEquals("planner_no_additional_tool", run.metadata().get("tool_chain_termination_reason"));
            assertEquals("2 steps · planner_no_additional_tool · read_file -> write_file",
                run.metadata().get("tool_chain_trace_summary"));
            assertEquals(List.of("read_file", "write_file"), run.metadata().get("tool_chain_tools"));
            assertEquals("strong_evaluator", run.metadata().get("evaluator_role"));
            assertEquals("strong", run.metadata().get("evaluator_model_tier"));
            assertEquals("strong evaluator accepted the delegated output", run.metadata().get("evaluation_reason"));
            assertEquals("done", run.metadata().get("execution_judgment_action"));
            assertEquals("handoff to strong evaluator", run.metadata().get("execution_judgment_next_step"));
            assertEquals(Boolean.FALSE, run.metadata().get("execution_judgment_needs_checkpoint"));
            assertEquals(Boolean.FALSE, run.metadata().get("execution_judgment_needs_human"));
            assertEquals("mounted_context_primary", run.metadata().get("execution_judgment_prompt_mode"));
            assertEquals(Boolean.TRUE, run.metadata().get("execution_judgment_mounted_context_injected"));
            assertEquals(7, ((Number) run.metadata().get("execution_judgment_mounted_context_panel_count")).intValue());
            assertEquals(18, ((Number) run.metadata().get("execution_judgment_mounted_context_rendered_object_count")).intValue());
            assertEquals(Boolean.TRUE, run.metadata().get("execution_judgment_mounted_context_budget_truncated"));
            assertEquals("done", run.metadata().get("completion_judgment_status"));
            assertEquals("high", run.metadata().get("completion_alignment_level"));
            assertEquals("mounted_context_primary", run.metadata().get("completion_judgment_prompt_mode"));
            assertEquals(Boolean.TRUE, run.metadata().get("completion_judgment_mounted_context_injected"));
            assertEquals(7, ((Number) run.metadata().get("completion_judgment_mounted_context_panel_count")).intValue());
            assertEquals(4, ((Number) run.metadata().get("completion_judgment_mounted_context_hidden_object_count")).intValue());
            assertEquals(Boolean.TRUE, run.metadata().get("completion_judgment_mounted_context_budget_truncated"));
            assertEquals(Boolean.TRUE, run.metadata().get("has_route_evidence"));
            assertEquals(Boolean.TRUE, run.metadata().get("has_execution_judgment"));
            assertEquals(Boolean.TRUE, run.metadata().get("has_completion_judgment"));
            assertEquals(Boolean.TRUE, run.metadata().get("has_closed_loop_evidence_chain"));
            assertEquals("route=learning_memory:kimi -> exec=done -> completion=done:high",
                run.metadata().get("judgment_evidence_chain"));
            assertEquals(
                "route=present | execution_judgment=present | completion_judgment=present"
                    + " | closed_loop_evidence_chain=complete | route_signal=learning_memory:kimi"
                    + " | exec_action=done | completion=done:high"
                    + " | tool_chain=2 steps · planner_no_additional_tool · read_file -> write_file"
                    + " | orchestration=codex -> kimi -> strong_evaluator(strong) [closed_loop]",
                run.metadata().get("closed_loop_proof_summary")
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> closedLoopEvidence = (Map<String, Object>) run.metadata().get("closed_loop_evidence");
            assertEquals("complete", closedLoopEvidence.get("chain_status"));
            assertEquals(Boolean.TRUE, closedLoopEvidence.get("has_route_evidence"));
            assertEquals(Boolean.TRUE, closedLoopEvidence.get("has_execution_judgment"));
            assertEquals(Boolean.TRUE, closedLoopEvidence.get("has_completion_judgment"));
            @SuppressWarnings("unchecked")
            Map<String, Object> routeEvidence = (Map<String, Object>) closedLoopEvidence.get("route");
            assertEquals("learning_memory", routeEvidence.get("route_source"));
            assertEquals("kimi", routeEvidence.get("selected_worker"));
            assertEquals("small", routeEvidence.get("selected_model_tier"));
            assertEquals(Boolean.TRUE, routeEvidence.get("learning_hint_applied"));
            @SuppressWarnings("unchecked")
            Map<String, Object> workerExecutionEvidence =
                (Map<String, Object>) closedLoopEvidence.get("worker_execution");
            assertEquals("blocked", workerExecutionEvidence.get("execution_status"));
            assertEquals(List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"),
                workerExecutionEvidence.get("evidence_refs"));
            assertEquals(List.of("manual_review"), workerExecutionEvidence.get("unfinished_items"));
            @SuppressWarnings("unchecked")
            Map<String, Object> executionJudgmentEvidence =
                (Map<String, Object>) closedLoopEvidence.get("execution_judgment");
            assertEquals("done", executionJudgmentEvidence.get("action"));
            assertEquals("handoff to strong evaluator", executionJudgmentEvidence.get("next_step"));
            @SuppressWarnings("unchecked")
            Map<String, Object> completionJudgmentEvidence =
                (Map<String, Object>) closedLoopEvidence.get("completion_judgment");
            assertEquals("done", completionJudgmentEvidence.get("status"));
            assertEquals("high", completionJudgmentEvidence.get("alignment_level"));
            assertEquals("strong_evaluator", completionJudgmentEvidence.get("evaluator_role"));
            @SuppressWarnings("unchecked")
            Map<String, Object> toolChainEvidence = (Map<String, Object>) closedLoopEvidence.get("tool_chain");
            assertEquals("multi_tool_round", toolChainEvidence.get("execution_mode"));
            assertEquals(2, ((Number) toolChainEvidence.get("step_count")).intValue());
            assertEquals(List.of("read_file", "write_file"), toolChainEvidence.get("tool_names"));
            @SuppressWarnings("unchecked")
            Map<String, Object> orchestrationEvidence = (Map<String, Object>) closedLoopEvidence.get("orchestration");
            assertEquals("codex", orchestrationEvidence.get("planner_worker"));
            assertEquals("kimi", orchestrationEvidence.get("executor_worker"));
            assertEquals(Boolean.TRUE, orchestrationEvidence.get("closed_loop_observed"));
            @SuppressWarnings("unchecked")
            Map<String, Object> tracePointers = (Map<String, Object>) closedLoopEvidence.get("trace_pointers");
            assertEquals(taskId, tracePointers.get("task_id"));
            assertEquals(sessionId, tracePointers.get("session_id"));
            assertTrue(((String) tracePointers.get("worker_artifact_id")).startsWith("art_"));
            assertTrue(((String) tracePointers.get("execution_judgment_id")).startsWith("dec_"));
            assertTrue(((String) tracePointers.get("completion_judgment_id")).startsWith("dec_"));
            @SuppressWarnings("unchecked")
            Map<String, Object> taskSurfaceRefs = (Map<String, Object>) closedLoopEvidence.get("task_surface_refs");
            assertEquals(taskId, taskSurfaceRefs.get("task_id"));
            assertEquals("/api/v1/tasks/" + taskId + "/live_flow", taskSurfaceRefs.get("live_flow_path"));
            assertEquals("/api/v1/tasks/" + taskId + "/runtime_context", taskSurfaceRefs.get("runtime_context_path"));
            assertEquals("/api/v1/tasks/" + taskId + "/harness_trace", taskSurfaceRefs.get("harness_trace_path"));
            assertEquals("/api/v1/tasks/" + taskId + "/judgment_trace", taskSurfaceRefs.get("judgment_trace_path"));
            assertEquals("/api/v1/tasks/" + taskId + "/tool_trace", taskSurfaceRefs.get("tool_trace_path"));
            assertEquals(List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"), run.metadata().get("evidence_refs"));
            assertEquals(List.of("manual_review"), run.metadata().get("unfinished_items"));
            assertEquals(1, ((List<?>) tracePointers.get("tool_invocation_ids")).size());
            assertEquals(1, ((List<?>) tracePointers.get("tool_execution_ids")).size());
            assertEquals(Boolean.TRUE, run.metadata().get("orchestration_closed_loop_observed"));
            assertEquals("codex -> kimi -> strong_evaluator(strong) [closed_loop]",
                run.metadata().get("orchestration_proof_summary"));
        }
    }

    @Test
    void listRunsCanFilterByToolChainMetadata() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("experiment-run-filters.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            ExperimentRunDao experimentRunDao = db.jdbi().onDemand(ExperimentRunDao.class);

            ExperimentRunService service = new ExperimentRunService(
                experimentRunDao,
                decisionDao,
                artifactDao,
                eventDao,
                toolInvocationDao
            );

            Instant now = Instant.now();
            sessionDao.insert(Session.create("session_1", "session one", "active"));
            sessionDao.insert(Session.create("session_2", "session two", "active"));
            sessionDao.insert(Session.create("session_3", "session three", "active"));
            taskDao.insert(new Task(
                "task_1",
                "session_1",
                null,
                "two-step multi tool",
                "done",
                "high",
                now,
                now,
                now,
                now,
                null,
                "ok",
                "compare tool chains",
                null,
                "kimi",
                "end",
                null,
                Map.of("task_type", "coding")
            ));
            taskDao.insert(new Task(
                "task_2",
                "session_2",
                null,
                "guarded multi tool",
                "failed",
                "high",
                now,
                now,
                now,
                now,
                null,
                "retry needed",
                "compare tool chains",
                null,
                "kimi",
                "end",
                "guard tripped",
                Map.of("task_type", "coding")
            ));
            taskDao.insert(new Task(
                "task_3",
                "session_3",
                null,
                "single tool",
                "done",
                "high",
                now,
                now,
                now,
                now,
                null,
                "ok",
                "compare tool chains",
                null,
                "codex",
                "end",
                null,
                Map.of("task_type", "coding")
            ));
            experimentRunDao.upsert(new ExperimentRunRecord(
                "xrun_1",
                "session_1",
                "task_1",
                "baseline-filters",
                "case-001",
                "two-step multi tool",
                "coding",
                "medium",
                "orchestrated",
                3,
                "done",
                "accepted",
                1.0,
                0.5,
                0,
                0,
                0,
                null,
                null,
                "ok",
                now,
                now,
                Map.ofEntries(
                    Map.entry("route_source", "learning_memory"),
                    Map.entry("orchestration_closed_loop_observed", true),
                    Map.entry("execution_judgment_action", "done"),
                    Map.entry("completion_judgment_status", "done"),
                    Map.entry("completion_alignment_level", "high"),
                    Map.entry("has_route_evidence", true),
                    Map.entry("has_execution_judgment", true),
                    Map.entry("has_completion_judgment", true),
                    Map.entry("has_closed_loop_evidence_chain", true),
                    Map.entry("tool_execution_mode", "multi_tool_round"),
                    Map.entry("tool_chain_step_count", 2),
                    Map.entry("tool_chain_termination_reason", "planner_no_additional_tool")
                )
            ));
            experimentRunDao.upsert(new ExperimentRunRecord(
                "xrun_2",
                "session_2",
                "task_2",
                "baseline-filters",
                "case-002",
                "guarded multi tool",
                "coding",
                "long",
                "small_only",
                5,
                "failed",
                "rejected",
                0.7,
                0.0,
                0,
                0,
                0,
                "guard tripped",
                null,
                "retry needed",
                now,
                now.plusSeconds(1),
                Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "has_route_evidence", false,
                    "has_execution_judgment", false,
                    "has_completion_judgment", false,
                    "has_closed_loop_evidence_chain", false,
                    "tool_chain_step_count", 4,
                    "tool_chain_termination_reason", "repeated_tool_guard"
                )
            ));
            experimentRunDao.upsert(new ExperimentRunRecord(
                "xrun_3",
                "session_3",
                "task_3",
                "baseline-filters",
                "case-003",
                "single tool",
                "coding",
                "short",
                "strong_only",
                1,
                "done",
                "accepted",
                1.1,
                0.9,
                0,
                0,
                0,
                null,
                null,
                "ok",
                now,
                now.plusSeconds(2),
                Map.of(
                    "tool_execution_mode", "single_tool_round",
                    "has_route_evidence", true,
                    "has_execution_judgment", false,
                    "has_completion_judgment", false,
                    "has_closed_loop_evidence_chain", false,
                    "tool_chain_step_count", 1
                )
            ));

            assertEquals(3, service.listRuns("baseline-filters", null, null, null, 10).size());
            assertEquals(List.of("task_2"), service.listRuns(
                "baseline-filters",
                null,
                null,
                null,
                "multi_tool_round",
                "repeated_tool_guard",
                3,
                5,
                10
            ).stream().map(ExperimentRunRecord::taskId).toList());
            assertEquals(List.of("task_1"), service.listRuns(
                "baseline-filters",
                null,
                null,
                null,
                null,
                null,
                2,
                2,
                10
            ).stream().map(ExperimentRunRecord::taskId).toList());
            assertEquals(List.of("task_3"), service.listRuns(
                "baseline-filters",
                null,
                null,
                null,
                "single_tool_round",
                null,
                null,
                1,
                10
            ).stream().map(ExperimentRunRecord::taskId).toList());
            assertEquals(List.of("task_2"), service.listRuns(
                "baseline-filters",
                null,
                null,
                null,
                "failed",
                "rejected",
                true,
                null,
                10
            ).stream().map(ExperimentRunRecord::taskId).toList());
            assertEquals(List.of("task_3", "task_1"), service.listRuns(
                "baseline-filters",
                null,
                null,
                null,
                "done",
                "accepted",
                false,
                null,
                10
            ).stream().map(ExperimentRunRecord::taskId).toList());
            assertEquals(List.of("task_1"), service.listRuns(
                "baseline-filters",
                null,
                null,
                "orchestrated",
                null,
                null,
                null,
                null,
                "learning_memory",
                true,
                10
            ).stream().map(ExperimentRunRecord::taskId).toList());
            assertEquals(List.of("task_1"), service.listRuns(
                "baseline-filters",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                true,
                true,
                true,
                10
            ).stream().map(ExperimentRunRecord::taskId).toList());
        }
    }

    @Test
    void summarizeRunsAggregatesGoalOutcomeMetrics() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("experiment-run-summary.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            ExperimentRunDao experimentRunDao = db.jdbi().onDemand(ExperimentRunDao.class);

            ExperimentRunService service = new ExperimentRunService(
                experimentRunDao,
                decisionDao,
                artifactDao,
                eventDao,
                toolInvocationDao
            );

            Instant now = Instant.now();
            sessionDao.insert(Session.create("session_1", "summary one", "active"));
            sessionDao.insert(Session.create("session_2", "summary two", "active"));
            taskDao.insert(new Task(
                "task_1",
                "session_1",
                null,
                "accepted run",
                "done",
                "high",
                now,
                now,
                now,
                now,
                null,
                "ok",
                "summarize eval",
                null,
                "kimi",
                "end",
                null,
                Map.of("task_type", "coding")
            ));
            taskDao.insert(new Task(
                "task_2",
                "session_2",
                null,
                "rejected run",
                "failed",
                "high",
                now,
                now,
                now,
                now,
                null,
                "retry",
                "summarize eval",
                null,
                "codex",
                "end",
                "guard tripped",
                Map.of("task_type", "coding")
            ));
            experimentRunDao.upsert(new ExperimentRunRecord(
                "xrun_summary_1",
                "session_1",
                "task_1",
                "baseline-summary",
                "case-001",
                "accepted run",
                "coding",
                "medium",
                "orchestrated",
                3,
                "done",
                "accepted",
                1.2,
                0.5,
                1,
                1,
                0,
                null,
                Boolean.TRUE,
                "ok",
                now,
                now,
                Map.ofEntries(
                    Map.entry("tool_execution_mode", "multi_tool_round"),
                    Map.entry("route_source", "learning_memory"),
                    Map.entry("orchestration_closed_loop_observed", true),
                    Map.entry("execution_judgment_action", "done"),
                    Map.entry("completion_judgment_status", "done"),
                    Map.entry("completion_alignment_level", "high"),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("mounted_context_panel_count", 7),
                    Map.entry("mounted_context_rendered_object_count", 18),
                    Map.entry("mounted_context_budget_truncated", true),
                    Map.entry("execution_judgment_prompt_mode", "mounted_context_primary"),
                    Map.entry("execution_judgment_mounted_context_rendered", true),
                    Map.entry("execution_judgment_mounted_render_used", true),
                    Map.entry("execution_judgment_mounted_context_injected", true),
                    Map.entry("execution_judgment_mounted_context_panel_count", 7),
                    Map.entry("execution_judgment_mounted_context_rendered_object_count", 18),
                    Map.entry("completion_judgment_prompt_mode", "mounted_context_primary"),
                    Map.entry("completion_judgment_mounted_context_rendered", true),
                    Map.entry("completion_judgment_mounted_render_used", true),
                    Map.entry("completion_judgment_mounted_context_injected", true),
                    Map.entry("completion_judgment_mounted_context_panel_count", 7),
                    Map.entry("completion_judgment_mounted_context_rendered_object_count", 18),
                    Map.entry("has_route_evidence", true),
                    Map.entry("has_execution_judgment", true),
                    Map.entry("has_completion_judgment", true),
                    Map.entry("has_closed_loop_evidence_chain", true)
                )
            ));
            experimentRunDao.upsert(new ExperimentRunRecord(
                "xrun_summary_2",
                "session_2",
                "task_2",
                "baseline-summary",
                "case-002",
                "rejected run",
                "coding",
                "long",
                "small_only",
                5,
                "failed",
                "rejected",
                0.8,
                0.0,
                2,
                0,
                1,
                "guard tripped",
                Boolean.FALSE,
                "retry",
                now,
                now.plusSeconds(1),
                Map.ofEntries(
                    Map.entry("tool_execution_mode", "multi_tool_round"),
                    Map.entry("route_source", "capability_match"),
                    Map.entry("execution_judgment_action", "escalate"),
                    Map.entry("completion_judgment_status", "misaligned"),
                    Map.entry("completion_alignment_level", "low"),
                    Map.entry("prompt_mode", "mounted_context_shadow"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", false),
                    Map.entry("mounted_context_panel_count", 5),
                    Map.entry("mounted_context_rendered_object_count", 12),
                    Map.entry("execution_judgment_prompt_mode", "mounted_context_shadow"),
                    Map.entry("execution_judgment_mounted_context_rendered", true),
                    Map.entry("execution_judgment_mounted_render_used", true),
                    Map.entry("execution_judgment_mounted_context_injected", false),
                    Map.entry("execution_judgment_mounted_context_panel_count", 5),
                    Map.entry("execution_judgment_mounted_context_rendered_object_count", 12),
                    Map.entry("completion_judgment_prompt_mode", "mounted_context_shadow"),
                    Map.entry("completion_judgment_mounted_context_rendered", true),
                    Map.entry("completion_judgment_mounted_render_used", true),
                    Map.entry("completion_judgment_mounted_context_injected", false),
                    Map.entry("completion_judgment_mounted_context_panel_count", 5),
                    Map.entry("completion_judgment_mounted_context_rendered_object_count", 12),
                    Map.entry("has_route_evidence", true),
                    Map.entry("has_execution_judgment", true),
                    Map.entry("has_completion_judgment", true),
                    Map.entry("has_closed_loop_evidence_chain", true)
                )
            ));

            var summary = service.summarizeRuns(
                "baseline-summary",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            );

            assertEquals(2, summary.runCount());
            assertEquals(1, summary.completionStatusCounts().get("done"));
            assertEquals(1, summary.completionStatusCounts().get("failed"));
            assertEquals(1, summary.acceptanceResultCounts().get("accepted"));
            assertEquals(1, summary.acceptanceResultCounts().get("rejected"));
            assertEquals(1, summary.modelModeCounts().get("orchestrated"));
            assertEquals(1, summary.modelModeCounts().get("small_only"));
            assertEquals(1, summary.routeSourceCounts().get("learning_memory"));
            assertEquals(1, summary.routeSourceCounts().get("capability_match"));
            assertEquals(1, summary.failureReasonCount());
            assertEquals(1, summary.recoverySuccessCount());
            assertEquals(1, summary.orchestrationClosedLoopObservedCount());
            assertEquals(1, summary.orchestratedRunCount());
            assertEquals(0, summary.runsWithTracePointersCount());
            assertEquals(0, summary.runsWithJudgmentTracePointersCount());
            assertEquals(0, summary.runsWithTaskSurfaceRefsCount());
            assertEquals(0, summary.runsWithJudgmentSurfaceRefsCount());
            assertEquals(0, summary.runsWithToolTraceSurfaceRefsCount());
            assertEquals(3, summary.handoffCount());
            assertEquals(1, summary.resumeCount());
            assertEquals(1, summary.humanGateCount());
            assertEquals(2.0, summary.totalCost(), 0.001);
            assertEquals(1.0, summary.averageCost(), 0.001);
            assertEquals(0.25, summary.averageStrongModelCostRatio(), 0.001);
            assertEquals(2, summary.promptModeSummaries().size());
            assertEquals(1, summary.promptModeSummaries().get("mounted_context_primary").runCount());
            assertEquals(1, summary.promptModeSummaries().get("mounted_context_primary").mountedContextInjectedCount());
            assertEquals(1.0, summary.promptModeSummaries().get("mounted_context_primary").mountedContextInjectedRate(), 0.001);
            assertEquals(7.0, summary.promptModeSummaries().get("mounted_context_primary").averageMountedContextPanelCount(), 0.001);
            assertEquals(1, summary.promptModeSummaries().get("mounted_context_shadow").runCount());
            assertEquals(0, summary.promptModeSummaries().get("mounted_context_shadow").mountedContextInjectedCount());
            assertEquals(0.0, summary.promptModeSummaries().get("mounted_context_shadow").mountedContextInjectedRate(), 0.001);
            assertEquals(1, summary.promptModeSummaries().get("mounted_context_shadow").mountedRenderUsedCount());
            assertEquals(5.0, summary.promptModeSummaries().get("mounted_context_shadow").averageMountedContextPanelCount(), 0.001);
            assertEquals(2, summary.executionJudgmentPromptModeSummaries().size());
            assertEquals(1, summary.executionJudgmentPromptModeSummaries()
                .get("mounted_context_primary").mountedContextInjectedCount());
            assertEquals(0, summary.executionJudgmentPromptModeSummaries()
                .get("mounted_context_shadow").mountedContextInjectedCount());
            assertEquals(2, summary.completionJudgmentPromptModeSummaries().size());
            assertEquals(1, summary.completionJudgmentPromptModeSummaries()
                .get("mounted_context_primary").mountedContextInjectedCount());
            assertEquals(0, summary.completionJudgmentPromptModeSummaries()
                .get("mounted_context_shadow").mountedContextInjectedCount());

            var closedLoopOnly = service.summarizeRuns(
                "baseline-summary",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "learning_memory",
                null,
                true,
                true,
                true,
                true,
                null,
                null,
                null,
                null
            );
            assertEquals(1, closedLoopOnly.runCount());
            assertEquals(1, closedLoopOnly.runsWithRouteEvidenceCount());
            assertEquals(1, closedLoopOnly.runsWithExecutionJudgmentCount());
            assertEquals(1, closedLoopOnly.runsWithCompletionJudgmentCount());
            assertEquals(1, closedLoopOnly.runsWithClosedLoopEvidenceChainCount());
            assertEquals(0, closedLoopOnly.runsWithTracePointersCount());
            assertEquals(0, closedLoopOnly.runsWithJudgmentTracePointersCount());
            assertEquals(0, closedLoopOnly.runsWithTaskSurfaceRefsCount());
            assertEquals(0, closedLoopOnly.runsWithJudgmentSurfaceRefsCount());
            assertEquals(0, closedLoopOnly.runsWithToolTraceSurfaceRefsCount());
            assertEquals(1, closedLoopOnly.routeSourceCounts().get("learning_memory"));
            assertEquals(1, closedLoopOnly.promptModeSummaries().size());
            assertEquals(1, closedLoopOnly.promptModeSummaries().get("mounted_context_primary").runCount());
        }
    }
}
