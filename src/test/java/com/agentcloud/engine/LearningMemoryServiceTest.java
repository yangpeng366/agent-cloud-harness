package com.agentcloud.engine;

import com.agentcloud.judgment.model.CompletionDecision;
import com.agentcloud.judgment.model.ExecutionDecision;
import com.agentcloud.model.LearningMemory;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.context.ContextObject;
import com.agentcloud.runtime.context.ContextObjectType;
import com.agentcloud.runtime.context.ContextRetentionState;
import com.agentcloud.runtime.context.MountedContextPanel;
import com.agentcloud.runtime.context.MountedContextPanelName;
import com.agentcloud.runtime.context.MountedContextView;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.LearningMemoryDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.worker.WorkerExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningMemoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void routingPreferenceTransitionsToStableHintAndBecomesPreferredWorker() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("learning-memory-routing.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            LearningMemoryDao learningMemoryDao = db.jdbi().onDemand(LearningMemoryDao.class);
            LearningMemoryService service = new LearningMemoryService(learningMemoryDao);

            Session session = Session.create("session_lm_1", "learning memory routing", "active");
            sessionDao.insert(session);
            Task task = task("task_lm_1", session.id(), "coding", "codex");
            taskDao.insert(task);

            TaskRuntimeContext runtimeContext = runtimeContext("Preserve repo layout");
            WorkerExecutionResult executionResult = executionResult("planner delegates to executor");
            ExecutionDecision executionDecision = new ExecutionDecision(
                "handoff",
                "executor is a better fit for the remaining implementation",
                null,
                true,
                false,
                "kimi"
            );
            CompletionDecision completionDecision = new CompletionDecision(
                "partially_done",
                "high",
                "planner phase only covered the delegation brief",
                "handoff to executor"
            );

            service.captureFromExecution(task, runtimeContext, executionResult, executionDecision, completionDecision);

            LearningMemory routingCandidate = learningMemoryDao
                .findLatestByTypeAndHintKey("routing_preference", "routing:coding:kimi")
                .orElseThrow();
            assertEquals("candidate", routingCandidate.state());
            assertEquals(1, routingCandidate.reinforcementCount());
            assertEquals("kimi", service.selectPreferredWorker("coding"));

            service.captureFromExecution(task, runtimeContext, executionResult, executionDecision, completionDecision);
            service.captureFromExecution(task, runtimeContext, executionResult, executionDecision, completionDecision);

            LearningMemory routingReinforced = learningMemoryDao
                .findLatestByTypeAndHintKey("routing_preference", "routing:coding:kimi")
                .orElseThrow();
            assertEquals("reinforced", routingReinforced.state());
            assertEquals(3, routingReinforced.reinforcementCount());

            service.captureFromExecution(task, runtimeContext, executionResult, executionDecision, completionDecision);
            service.captureFromExecution(task, runtimeContext, executionResult, executionDecision, completionDecision);

            LearningMemory routingStable = learningMemoryDao
                .findLatestByTypeAndHintKey("routing_preference", "routing:coding:kimi")
                .orElseThrow();
            assertEquals("stable_hint", routingStable.state());
            assertEquals(5, routingStable.reinforcementCount());
            assertEquals("kimi", service.selectPreferredWorker("coding"));
        }
    }

    @Test
    void contextRetentionHintRequiresReinforcementBeforeExposure() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("learning-memory-context.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            LearningMemoryDao learningMemoryDao = db.jdbi().onDemand(LearningMemoryDao.class);
            LearningMemoryService service = new LearningMemoryService(learningMemoryDao);

            Session session = Session.create("session_lm_2", "learning memory context", "active");
            sessionDao.insert(session);
            Task task = task("task_lm_2", session.id(), "research", "kimi");
            taskDao.insert(task);

            TaskRuntimeContext runtimeContext = runtimeContext("Keep comparison table visible");
            WorkerExecutionResult executionResult = executionResult("executor still needs more context");
            ExecutionDecision executionDecision = new ExecutionDecision(
                "continue",
                "checkpoint before the next round",
                null,
                true,
                false,
                null
            );
            CompletionDecision completionDecision = new CompletionDecision(
                "needs_clarification",
                "medium",
                "the retained comparison is still important",
                "resume with more context"
            );

            service.captureFromExecution(task, runtimeContext, executionResult, executionDecision, completionDecision);

            LearningMemory firstCandidate = learningMemoryDao
                .findLatestByTypeAndHintKey("context_retention_hint", "context:research:keep_comparison_table_visible")
                .orElseThrow();
            assertEquals("candidate", firstCandidate.state());
            assertEquals(1, firstCandidate.reinforcementCount());
            assertEquals(List.of(), service.contextRetentionHints("research"));

            service.captureFromExecution(task, runtimeContext, executionResult, executionDecision, completionDecision);

            LearningMemory secondCandidate = learningMemoryDao
                .findLatestByTypeAndHintKey("context_retention_hint", "context:research:keep_comparison_table_visible")
                .orElseThrow();
            assertEquals("candidate", secondCandidate.state());
            assertEquals(2, secondCandidate.reinforcementCount());
            assertEquals(1, service.contextRetentionHints("research").size());
            assertTrue(service.contextRetentionHints("research").get(0).contains("Keep comparison table visible"));
        }
    }

    @Test
    void contextRetentionHintPrefersMountedContextEvidenceWhenAvailable() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("learning-memory-mounted-context.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            LearningMemoryDao learningMemoryDao = db.jdbi().onDemand(LearningMemoryDao.class);
            LearningMemoryService service = new LearningMemoryService(learningMemoryDao);

            Session session = Session.create("session_lm_3", "learning memory mounted context", "active");
            sessionDao.insert(session);
            Task task = task("task_lm_3", session.id(), "research", "kimi");
            taskDao.insert(task);

            TaskRuntimeContext runtimeContext = runtimeContextWithMountedView(
                "Keep comparison table visible",
                "Keep comparison table visible for evaluator handoff."
            );
            WorkerExecutionResult executionResult = executionResult("mounted context retained the critical comparison table");
            ExecutionDecision executionDecision = new ExecutionDecision(
                "continue",
                "checkpoint before the next round",
                null,
                true,
                false,
                null
            );
            CompletionDecision completionDecision = new CompletionDecision(
                "partially_done",
                "medium",
                "the retained comparison is still important",
                "resume with more context"
            );

            service.captureFromExecution(task, runtimeContext, executionResult, executionDecision, completionDecision);

            LearningMemory candidate = learningMemoryDao
                .findLatestByTypeAndHintKey("context_retention_hint", "context:research:keep_comparison_table_visible_for_evaluator_handoff")
                .orElseThrow();
            assertEquals("candidate", candidate.state());
            assertEquals(1, candidate.reinforcementCount());
            assertTrue(candidate.summary().contains("Keep comparison table visible"));
            assertNotNull(candidate.evidence());
            assertEquals("mounted_context", candidate.evidence().get("retained_source"));
            assertEquals("pinned", candidate.evidence().get("mounted_context_panel"));
            assertEquals("pinned", candidate.evidence().get("mounted_context_retention_state"));
            assertEquals("constraint", candidate.evidence().get("mounted_context_object_type"));
            assertEquals("/sessions/session_lm_3/tasks/task_lm_3/constraints", candidate.evidence().get("mounted_context_object_path"));
            assertNotNull(candidate.metadata());
            assertEquals("mounted_context", candidate.metadata().get("source"));
            assertEquals("pinned", candidate.metadata().get("mounted_context_panel"));
            assertEquals("pinned", candidate.metadata().get("mounted_context_retention_state"));
            assertEquals(1, candidate.metadata().get("mounted_context_selection_trace_count"));
        }
    }

    @Test
    void completionPatternCapturesNonDoneCompletionStatuses() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("learning-memory-completion.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            LearningMemoryDao learningMemoryDao = db.jdbi().onDemand(LearningMemoryDao.class);
            LearningMemoryService service = new LearningMemoryService(learningMemoryDao);

            Session session = Session.create("session_lm_4", "learning memory completion", "active");
            sessionDao.insert(session);
            Task task = task("task_lm_4", session.id(), "evaluation", "kimi");
            taskDao.insert(task);

            TaskRuntimeContext runtimeContext = runtimeContext("Retain completion mismatch evidence");
            WorkerExecutionResult executionResult = executionResult("draft answer still misses acceptance criteria");
            ExecutionDecision executionDecision = new ExecutionDecision(
                "continue",
                "another round is required",
                "fill the missing acceptance criteria",
                false,
                false,
                null
            );
            CompletionDecision completionDecision = new CompletionDecision(
                "misaligned",
                "low",
                "the answer diverged from the requested format",
                "rewrite against the requested output contract"
            );

            service.captureFromExecution(task, runtimeContext, executionResult, executionDecision, completionDecision);

            LearningMemory candidate = learningMemoryDao
                .findLatestByTypeAndHintKey("completion_pattern", "completion:kimi:misaligned")
                .orElseThrow();
            assertEquals("candidate", candidate.state());
            assertEquals(1, candidate.reinforcementCount());
            assertTrue(candidate.summary().contains("misaligned"));
            assertNotNull(candidate.evidence());
            assertEquals("kimi", candidate.evidence().get("worker_id"));
            assertEquals("evaluation", candidate.evidence().get("task_type"));
            assertEquals("misaligned", candidate.evidence().get("completion_status"));
            assertEquals("low", candidate.evidence().get("alignment_level"));
            assertEquals("draft answer still misses acceptance criteria", candidate.evidence().get("output_summary"));
            assertNotNull(candidate.metadata());
            assertEquals("judgment", candidate.metadata().get("source"));
            assertEquals("completion_pattern", candidate.metadata().get("category"));
        }
    }

    @Test
    void workerHeuristicCapturesLowConfidenceWorkerOutputs() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("learning-memory-worker-heuristic.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            LearningMemoryDao learningMemoryDao = db.jdbi().onDemand(LearningMemoryDao.class);
            LearningMemoryService service = new LearningMemoryService(learningMemoryDao);

            Session session = Session.create("session_lm_5", "learning memory worker heuristic", "active");
            sessionDao.insert(session);
            Task task = task("task_lm_5", session.id(), "coding", "codex");
            taskDao.insert(task);

            TaskRuntimeContext runtimeContext = runtimeContext("Keep low-confidence failure mode visible");
            WorkerExecutionResult executionResult = new WorkerExecutionResult(
                "worker returned a tentative patch plan only",
                "",
                false,
                "",
                "",
                "inspect the concrete file path before editing",
                "low",
                64,
                120L,
                Map.of()
            );
            ExecutionDecision executionDecision = new ExecutionDecision(
                "checkpoint",
                "capture the failure mode before retry",
                "rerun with grounded file evidence",
                true,
                false,
                null
            );
            CompletionDecision completionDecision = new CompletionDecision(
                "partially_done",
                "medium",
                "the task still needs a grounded patch",
                "retry with direct file evidence"
            );

            service.captureFromExecution(task, runtimeContext, executionResult, executionDecision, completionDecision);

            LearningMemory candidate = learningMemoryDao
                .findLatestByTypeAndHintKey("worker_heuristic", "worker_low_confidence:codex:coding")
                .orElseThrow();
            assertEquals("candidate", candidate.state());
            assertEquals(1, candidate.reinforcementCount());
            assertTrue(candidate.summary().contains("low-confidence"));
            assertNotNull(candidate.evidence());
            assertEquals("codex", candidate.evidence().get("worker_id"));
            assertEquals("coding", candidate.evidence().get("task_type"));
            assertEquals("low", candidate.evidence().get("confidence"));
            assertEquals("inspect the concrete file path before editing", candidate.evidence().get("suggested_next_step"));
            assertEquals("partially_done", candidate.evidence().get("completion_status"));
            assertNotNull(candidate.metadata());
            assertEquals("worker_execution", candidate.metadata().get("source"));
            assertEquals("worker_heuristic", candidate.metadata().get("category"));
        }
    }

    private Task task(String taskId, String sessionId, String taskType, String assignedWorker) {
        return new Task(
            taskId,
            sessionId,
            null,
            "learning memory task",
            "active",
            "high",
            Instant.now(),
            Instant.now(),
            Instant.now(),
            null,
            null,
            null,
            "capture operational learning memory",
            null,
            assignedWorker,
            "continue",
            null,
            Map.of("task_type", taskType)
        );
    }

    private TaskRuntimeContext runtimeContext(String openQuestion) {
        return new TaskRuntimeContext(
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            new ActiveContext(
                "learning memory focus",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(openQuestion),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "runtime context summary",
                "runtime context payload",
                12
            )
        );
    }

    private TaskRuntimeContext runtimeContextWithMountedView(String openQuestion, String mountedSummary) {
        ActiveContext activeContext = new ActiveContext(
            "learning memory focus",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(openQuestion),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "runtime context summary",
            "runtime context payload",
            12
        );
        MountedContextView mountedContextView = new MountedContextView(
            null,
            "task_lm_3",
            List.of(
                new MountedContextPanel(
                    MountedContextPanelName.PINNED,
                    "Pinned",
                    List.of(new ContextObject(
                        "task_lm_3:constraints",
                        "/sessions/session_lm_3/tasks/task_lm_3/constraints",
                        ContextObjectType.CONSTRAINT,
                        "/sessions/session_lm_3/tasks/task_lm_3",
                        "Constraints",
                        mountedSummary,
                        mountedSummary,
                        Instant.parse("2026-05-06T06:20:00Z"),
                        ContextRetentionState.PINNED,
                        List.of(),
                        List.of(),
                        Map.of("constraint_count", 1)
                    ))
                )
            ),
            List.of("compat_mode=task_runtime_context_preserved")
        );
        return new TaskRuntimeContext(
            null,
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

    private WorkerExecutionResult executionResult(String summary) {
        return new WorkerExecutionResult(
            summary,
            "",
            false,
            "",
            "",
            "continue",
            "medium",
            64,
            120L,
            Map.of()
        );
    }
}
