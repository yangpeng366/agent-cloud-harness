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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发安全测试：验证 per-taskId ReentrantLock 保证同一 task 的控制节点操作串行化，
 * 消除并发竞态（重复 judgment / decision 插入冲突 / 状态覆盖）。
 */
class ControlNodeGraphConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentEnterSameTaskSerializesWithoutCorruption() throws Exception {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("concurrency.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_conc", "concurrency test", "active"));

            WorkerRouter router = new WorkerRouter(new WorkerRegistry());
            ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
                new ActiveContextBuilder.DefaultActiveContextPolicy(),
                new ActiveContextBuilder.DefaultRetentionPolicy(),
                new ActiveContextBuilder.DefaultExclusionPolicy()
            );
            TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
                eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
            );

            CountingJudgmentService judgment = new CountingJudgmentService();
            CountingWorkerExecutor worker = new CountingWorkerExecutor();

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, sessionMessageDao, packetDao, router, null, null,
                worker, runtimeContextBuilder, judgment,
                artifactDao, decisionDao, null
            );

            String taskId = "task_conc_" + UUID.randomUUID();
            Task task = newTask(taskId, "session_conc");
            taskDao.insert(task);

            int threadCount = 6;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        graph.enter(task);
                    } catch (Throwable t) {
                        firstError.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "all threads should complete within timeout");
            pool.shutdownNow();

            Throwable err = firstError.get();
            if (err != null) {
                throw new AssertionError("concurrent enter failed: " + err.getMessage(), err);
            }

            // 所有线程串行执行完毕后，task 最终状态为 done
            Task persisted = taskDao.findById(taskId).orElseThrow();
            assertEquals("done", persisted.status(), "task should reach done after serialized execution");

            // 判断与 worker 执行次数应等于 threadCount × 单次流程用量（每线程完整跑一遍，串行无重复插入冲突）
            int expectedJudgmentCalls = threadCount * 2;
            assertTrue(judgment.executionCalls.get() <= expectedJudgmentCalls,
                "judgeExecution calls=" + judgment.executionCalls.get() + " should not exceed " + expectedJudgmentCalls);
            assertTrue(judgment.completionCalls.get() <= expectedJudgmentCalls,
                "judgeCompletion calls=" + judgment.completionCalls.get() + " should not exceed " + expectedJudgmentCalls);
        }
    }

    @Test
    void enterAndTriggerResumeConcurrentlyDoNotCorruptState() throws Exception {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("concurrency-trigger.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_trig", "trigger concurrency test", "active"));

            WorkerRouter router = new WorkerRouter(new WorkerRegistry());
            ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
                new ActiveContextBuilder.DefaultActiveContextPolicy(),
                new ActiveContextBuilder.DefaultRetentionPolicy(),
                new ActiveContextBuilder.DefaultExclusionPolicy()
            );
            TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
                eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
            );

            CountingJudgmentService judgment = new CountingJudgmentService();
            CountingWorkerExecutor worker = new CountingWorkerExecutor();

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, sessionMessageDao, packetDao, router, null, null,
                worker, runtimeContextBuilder, judgment,
                artifactDao, decisionDao, null
            );

            String taskId = "task_trig_" + UUID.randomUUID();
            Task task = newTask(taskId, "session_trig");
            taskDao.insert(task);

            int threadCount = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            // 一半线程 enter，一半线程 triggerResume
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        if (idx % 2 == 0) {
                            graph.enter(task);
                        } else {
                            graph.triggerResume(task);
                        }
                    } catch (Throwable t) {
                        firstError.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "all threads should complete");
            pool.shutdownNow();

            Throwable err = firstError.get();
            if (err != null) {
                throw new AssertionError("concurrent enter/triggerResume failed: " + err.getMessage(), err);
            }
        }
    }

    private Task newTask(String taskId, String sessionId) {
        return new Task(
            taskId, sessionId, null, "concurrent test task", "active", "high",
            Instant.now(), Instant.now(), Instant.now(), null, null, null,
            "verify concurrency safety", null, null, "intake", null,
            new LinkedHashMap<>(Map.of(
                "task_type", "coding",
                "intent", "test concurrency",
                "model_mode", "orchestrated",
                "orchestration_stage", "plan_pending",
                "prompt_rendering_mode", "mounted_context_primary"
            ))
        );
    }

    private static final class CountingWorkerExecutor implements WorkerExecutor {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
            calls.incrementAndGet();
            if ("codex".equals(workerId)) {
                return new WorkerExecutionResult(
                    "Planner brief ready",
                    "Break the task into a compact execution brief for the delegated worker.",
                    true, "Planner Brief",
                    "1. Update runtime. 2. Run tests. 3. Report state.",
                    "Implement the delegated change.",
                    "high", 0, 12L,
                    Map.ofEntries(
                        Map.entry("parser", "json"),
                        Map.entry("selected_worker", workerId),
                        Map.entry("selected_model_tier", "strong"),
                        Map.entry("execution_role", "planner"),
                        Map.entry("execution_status", "completed"),
                        Map.entry("tool_chain_step_count", 1),
                        Map.entry("tool_chain_termination_reason", "planner_brief_ready"),
                        Map.entry("tool_invocation_ids", List.of("plan_1")),
                        Map.entry("evidence_refs", List.of("tool:read_file:brief.md"))
                    )
                );
            }
            return new WorkerExecutionResult(
                "Executor completed", "Implemented and verified.", true, "Execution Result",
                "Runtime path updated.", "Ready for acceptance.", "high", 0, 15L,
                Map.ofEntries(
                    Map.entry("parser", "json"),
                    Map.entry("selected_worker", workerId),
                    Map.entry("selected_model_tier", "small"),
                    Map.entry("execution_role", "executor"),
                    Map.entry("execution_status", "completed"),
                    Map.entry("tool_chain_step_count", 1),
                    Map.entry("tool_chain_termination_reason", "executor_step_done"),
                    Map.entry("tool_invocation_ids", List.of("exec_1")),
                    Map.entry("evidence_refs", List.of("tool:patch_file:runtime.java"))
                )
            );
        }
    }

    private static final class CountingJudgmentService implements JudgmentService {
        final AtomicInteger executionCalls = new AtomicInteger();
        final AtomicInteger completionCalls = new AtomicInteger();

        @Override
        public ExecutionDecision judgeExecution(JudgmentContext context) {
            executionCalls.incrementAndGet();
            String stage = metadataString(context.task().metadata(), "orchestration_stage");
            if ("plan_pending".equals(stage)) {
                return new ExecutionDecision(
                    "continue", "planner produced a brief",
                    "Implement the delegated change.", false, true, false, null
                );
            }
            return new ExecutionDecision(
                "continue", "executor finished", "Mark complete.", false, false, null
            );
        }

        @Override
        public CompletionDecision judgeCompletion(JudgmentContext context) {
            completionCalls.incrementAndGet();
            String stage = metadataString(context.task().metadata(), "orchestration_stage");
            if ("plan_pending".equals(stage)) {
                return new CompletionDecision("done", "high", "planner brief ready", "Delegate to executor.");
            }
            return new CompletionDecision("done", "high", "executor output satisfies goal", "Mark complete.");
        }

        private static String metadataString(Map<String, Object> metadata, String key) {
            if (metadata == null || key == null) return null;
            Object v = metadata.get(key);
            return v == null ? null : v.toString();
        }
    }
}
