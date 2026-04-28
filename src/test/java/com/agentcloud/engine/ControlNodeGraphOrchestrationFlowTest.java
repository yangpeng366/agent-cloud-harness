package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.judgment.JudgmentContext;
import com.agentcloud.judgment.JudgmentService;
import com.agentcloud.judgment.model.CompletionDecision;
import com.agentcloud.judgment.model.ExecutionDecision;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContextBuilder;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.CheckpointDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ResumePacketDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.worker.WorkerExecutionResult;
import com.agentcloud.worker.WorkerExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlNodeGraphOrchestrationFlowTest {

    @TempDir
    Path tempDir;

    @Test
    void orchestratedTaskRunsPlannerThenExecutorInSingleEnter() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("orchestration-flow.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_1", "demo orchestration", "active"));

            WorkerRouter router = new WorkerRouter(new WorkerRegistry());
            ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
                new ActiveContextBuilder.DefaultActiveContextPolicy(),
                new ActiveContextBuilder.DefaultRetentionPolicy(),
                new ActiveContextBuilder.DefaultExclusionPolicy()
            );
            TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
                eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
            );

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, packetDao, router, null, null,
                new FakeWorkerExecutor(), runtimeContextBuilder, new FakeJudgmentService(),
                artifactDao, decisionDao, null
            );

            Task task = new Task(
                "task_1",
                "session_1",
                null,
                "run orchestrated loop",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "ship a validated result",
                null,
                null,
                "intake",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "prove planner to executor runtime path",
                    "model_mode", "orchestrated",
                    "orchestration_stage", "plan_pending"
                ))
            );
            taskDao.insert(task);

            Task finalTask = graph.enter(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();
            List<Artifact> artifacts = artifactDao.listBySessionAndTask(task.sessionId(), task.id(), 10);
            List<Decision> decisions = decisionDao.listBySessionAndTask(task.sessionId(), task.id(), 10);

            assertEquals("done", finalTask.status());
            assertEquals("done", persisted.status());
            assertEquals("end", persisted.controlNode());
            assertEquals("kimi", persisted.assignedWorker());
            assertEquals("completed", persisted.metadata().get("orchestration_stage"));
            assertEquals("codex", persisted.metadata().get("planner_worker"));
            assertEquals("kimi", persisted.metadata().get("executor_worker"));
            assertEquals("kimi", persisted.metadata().get("target_worker"));

            assertEquals(2, artifacts.size());
            assertTrue(artifacts.stream().anyMatch(a -> "strong".equals(metadataString(a.metadata(), "selected_model_tier"))));
            assertTrue(artifacts.stream().anyMatch(a -> "small".equals(metadataString(a.metadata(), "selected_model_tier"))));
            assertTrue(artifacts.stream().anyMatch(a -> "plan_pending".equals(metadataString(a.metadata(), "orchestration_stage"))));
            assertTrue(artifacts.stream().anyMatch(a -> "execution_pending".equals(metadataString(a.metadata(), "orchestration_stage"))));

            assertEquals(4, decisions.size());
            assertTrue(decisions.stream().anyMatch(d ->
                "execution_judgment".equals(d.decisionType())
                    && "handoff".equals(metadataString(d.metadata(), "action"))
                    && "kimi".equals(metadataString(d.metadata(), "target_worker"))
            ));
            assertTrue(decisions.stream().anyMatch(d ->
                "completion_judgment".equals(d.decisionType())
                    && "done:high".equals(metadataString(d.metadata(), "evaluation_result"))
            ));
        }
    }

    private static String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private static final class FakeWorkerExecutor implements WorkerExecutor {
        @Override
        public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
            if ("codex".equals(workerId)) {
                return new WorkerExecutionResult(
                    "Planner brief ready",
                    "Break the task into a compact execution brief for the delegated worker.",
                    true,
                    "Planner Brief",
                    "1. Update the runtime path. 2. Run the test entrypoint. 3. Report final state.",
                    "Implement the delegated runtime change and run verification.",
                    "high",
                    0,
                    12L,
                    Map.of("parser", "json")
                );
            }
            return new WorkerExecutionResult(
                "Executor completed the delegated step",
                "Implemented the delegated change and verified the expected runtime behavior.",
                true,
                "Execution Result",
                "Runtime path updated and verification completed successfully.",
                "Ready for acceptance.",
                "high",
                0,
                15L,
                Map.of("parser", "json")
            );
        }
    }

    private static final class FakeJudgmentService implements JudgmentService {
        @Override
        public ExecutionDecision judgeExecution(JudgmentContext context) {
            String stage = metadataString(context.task().metadata(), "orchestration_stage");
            if ("plan_pending".equals(stage)) {
                return new ExecutionDecision(
                    "continue",
                    "planner produced a delegation brief",
                    "Implement the delegated runtime change and run verification.",
                    false,
                    false,
                    null
                );
            }
            return new ExecutionDecision(
                "continue",
                "executor finished the delegated work",
                "Mark the task complete.",
                false,
                false,
                null
            );
        }

        @Override
        public CompletionDecision judgeCompletion(JudgmentContext context) {
            String stage = metadataString(context.task().metadata(), "orchestration_stage");
            if ("plan_pending".equals(stage)) {
                return new CompletionDecision(
                    "done",
                    "high",
                    "planner brief is ready",
                    "Delegate execution to a smaller worker."
                );
            }
            return new CompletionDecision(
                "done",
                "high",
                "executor output satisfies the task goal",
                "Mark the task complete."
            );
        }
    }
}
