package com.agentcloud.engine;

import com.agentcloud.judgment.model.CompletionDecision;
import com.agentcloud.judgment.model.ExecutionDecision;
import com.agentcloud.model.LearningMemory;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
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
