package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.judgment.JudgmentContext;
import com.agentcloud.judgment.JudgmentService;
import com.agentcloud.judgment.model.CompletionDecision;
import com.agentcloud.judgment.model.ExecutionDecision;
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
import com.agentcloud.store.SessionMessageDao;
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

/**
 * 验证 instance-identity stale-round guard：worker round 执行期间发生并发控制动作
 * （bump exec_instance，等价于 triggerPause/Resume/Handoff/Escalate 或 recovery）后，
 * round 返回时 guard 检测到 exec_instance 变化，丢弃 round 结果，不 clobber 并发控制动作。
 * 对应 docs/CONTROL_GRAPH_ASYNC_INSTANCE_IDENTITY_PLAN.md 方案 A。
 */
class ControlNodeGraphStaleRoundGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void staleSuccessRoundDiscardedWhenConcurrentPauseBumpsExecInstance() {
        runStaleRoundGuardScenario(false);
    }

    @Test
    void staleFailureRoundDiscardedWhenConcurrentPauseBumpsExecInstance() {
        runStaleRoundGuardScenario(true);
    }

    private void runStaleRoundGuardScenario(boolean failRound) {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve(failRound ? "stale-fail.db" : "stale-success.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_1", "stale round guard", "active"));

            WorkerRouter router = new WorkerRouter(new WorkerRegistry());
            ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
                new ActiveContextBuilder.DefaultActiveContextPolicy(),
                new ActiveContextBuilder.DefaultRetentionPolicy(),
                new ActiveContextBuilder.DefaultExclusionPolicy()
            );
            TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
                eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
            );

            WorkerExecutor executor = new ConcurrentPauseBumpExecutor(taskDao, failRound);
            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, sessionMessageDao, packetDao, router, null, null,
                executor, runtimeContextBuilder, new NoopJudgmentService(), artifactDao, decisionDao, null
            );

            Task task = new Task(
                "task_1", "session_1", null, "stale round guard", "active", "high",
                Instant.now(), Instant.now(), Instant.now(), null, null, null,
                "ship a validated result", null, null, "intake", null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "model_mode", "orchestrated",
                    "orchestration_stage", "plan_pending",
                    "prompt_rendering_mode", "mounted_context_primary"
                ))
            );
            taskDao.insert(task);

            Task result = graph.enter(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();

            // 并发 pause 必须保留，不被 in-flight round 结果（成功或失败恢复）clobber
            assertEquals("paused", result.status(),
                "in-flight pause must not be clobbered by stale worker round");
            assertEquals("packet", result.controlNode());
            assertEquals("paused", persisted.status());
            assertEquals("packet", persisted.controlNode());
        }
    }

    /**
     * 模拟 worker round 执行期间发生并发控制动作：bump exec_instance + 置 paused/packet。
     * 若 failRound=true，bump 后抛异常模拟 round 超时/失败。
     */
    private static final class ConcurrentPauseBumpExecutor implements WorkerExecutor {
        private final TaskDao taskDao;
        private final boolean failRound;

        ConcurrentPauseBumpExecutor(TaskDao taskDao, boolean failRound) {
            this.taskDao = taskDao;
            this.failRound = failRound;
        }

        @Override
        public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
            Task current = taskDao.findById(context.task().id()).orElseThrow();
            Map<String, Object> md = current.metadata() != null
                ? new LinkedHashMap<>(current.metadata()) : new LinkedHashMap<>();
            long cur = md.get("exec_instance") instanceof Number n ? n.longValue() : 0L;
            md.put("exec_instance", cur + 1L);
            taskDao.updateState(current
                .withStatus("paused").withControlNode("packet")
                .withWaitingReason("operator pause").withMetadata(md));
            if (failRound) {
                throw new RuntimeException("worker execution timed out");
            }
            return new WorkerExecutionResult(
                "round output", "output text", false, "", "", "next", "high", "completed",
                List.of(), List.of(), 0, 10L, Map.of()
            );
        }
    }

    private static final class NoopJudgmentService implements JudgmentService {
        @Override
        public ExecutionDecision judgeExecution(JudgmentContext context) {
            return new ExecutionDecision("continue", "noop", "noop", false, false, false, null);
        }

        @Override
        public CompletionDecision judgeCompletion(JudgmentContext context) {
            return new CompletionDecision("done", "high", "noop", "noop");
        }
    }
}