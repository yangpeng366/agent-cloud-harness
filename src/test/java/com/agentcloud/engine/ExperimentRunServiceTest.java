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
                    "task_length_bucket", "long"
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
                Map.of(
                    "selected_model_tier", "small",
                    "selected_worker", "kimi",
                    "route_source", "learning_memory",
                    "preferred_worker_hint", "kimi",
                    "learning_hint_applied", true,
                    "fallback_reason", "hint matched candidate set",
                    "latest_worker_metadata", Map.of(
                        "tool_execution_mode", "multi_tool_round",
                        "tool_chain_step_count", 2,
                        "tool_chain_termination_reason", "planner_no_additional_tool",
                        "tool_chain_trace", List.of(
                            Map.of("tool_chain_step_index", 1, "tool_name", "read_file"),
                            Map.of("tool_chain_step_index", 2, "tool_name", "write_file")
                        )
                    )
                )
            ));
            toolInvocationDao.insert(new ToolInvocationRecord(
                IdGenerator.newId("tool"),
                sessionId,
                taskId,
                "kimi",
                "write_file",
                Map.of("path", "draft.txt"),
                "Draft written.",
                true,
                42,
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
                "completion_judgment",
                "Completion judgment: done",
                "Acceptance criteria satisfied.",
                "medium",
                null,
                Map.of(
                    "status", "done",
                    "alignment_level", "high",
                    "evaluation_result", "done:high"
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
            assertEquals("multi_tool_round", run.metadata().get("tool_execution_mode"));
            assertEquals(2, ((Number) run.metadata().get("tool_chain_step_count")).intValue());
            assertEquals("planner_no_additional_tool", run.metadata().get("tool_chain_termination_reason"));
            assertEquals("2 steps · planner_no_additional_tool · read_file -> write_file",
                run.metadata().get("tool_chain_trace_summary"));
            assertEquals(List.of("read_file", "write_file"), run.metadata().get("tool_chain_tools"));
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
                Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "tool_chain_step_count", 2,
                    "tool_chain_termination_reason", "planner_no_additional_tool"
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
        }
    }
}
