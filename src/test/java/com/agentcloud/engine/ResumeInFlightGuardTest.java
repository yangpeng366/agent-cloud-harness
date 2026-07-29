package com.agentcloud.engine;

import com.agentcloud.model.Task;
import com.agentcloud.model.TaskControlResult;
import com.agentcloud.model.TaskCreateRequest;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.TaskDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * C-resume 防双轮：resumeTask 在 worker round 在跑（per-task enterLock 被另一线程持有）时 tryLock-abort，
 * 不并发起第二轮；recoverTask 的 resume 分支遇 busy 回退 async continue（不阻塞、不 stuck）。
 *
 * <p>与 instance-identity（方案 A，见 ControlNodeGraphStaleRoundGuardTest）互补：instance-identity 保证
 * 过期轮结果不落库（正确性），本类保证 resume 不与在跑轮并发起轮（效率/成本）。
 */
class ResumeInFlightGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void resumeAbortsWhenWorkerRoundInFlight() throws Exception {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("resume-inflight-abort.db"))) {
            TaskService service = service(db);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "demo resume busy", "continuation", "user", "high",
                "resume while round in flight", null, null, null, Map.of(), false
            ));
            Task paused = task.withStatus("paused").withControlNode("packet").withWaitingReason("manual pause");
            taskDao.updateState(paused);

            // 模拟在跑的 worker round：由另一条虚拟线程持有 per-task round lock（生产中 asyncEnterControlGraph 持锁）
            ReentrantLock lock = service.roundLock(task.id());
            CountDownLatch acquired = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = Thread.ofVirtual().name("resume-busy-holder").start(() -> {
                lock.lock();
                try {
                    acquired.countDown();
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            });
            acquired.await();
            try {
                TaskControlResult result = service.resumeTask(task.id());
                assertEquals("resume_busy", result.decision(),
                    "resume must abort (not start a second round) when a worker round is in flight on another thread");
                Task after = taskDao.findById(task.id()).orElseThrow();
                assertEquals("paused", after.status(),
                    "task must remain paused when resume is aborted; no state change, no round");
                assertEquals("packet", after.controlNode());
            } finally {
                release.countDown();
                holder.join();
            }
        }
    }

    @Test
    void resumeProceedsWhenNoWorkerRoundInFlight() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("resume-inflight-proceed.db"))) {
            TaskService service = service(db);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "demo resume proceed", "continuation", "user", "high",
                "resume with no round in flight", null, null, null, Map.of(), false
            ));
            Task paused = task.withStatus("paused").withControlNode("packet").withWaitingReason("manual pause");
            taskDao.updateState(paused);

            TaskControlResult result = service.resumeTask(task.id());
            assertEquals("resume", result.decision(),
                "resume must proceed normally when no worker round is in flight");
            Task after = taskDao.findById(task.id()).orElseThrow();
            assertEquals("active", after.status(), "task must be active after a normal resume");
            assertEquals("scheduler", after.controlNode());
        }
    }

    private TaskService service(DatabaseManager db) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);

        ControlNodeGraph graph = new ControlNodeGraph(
            taskDao, eventDao, sessionDao, null, null, null, null,
            null, null, null, null, null, null
        ) {
            @Override
            public Task triggerResume(Task task) {
                Task updated = task.withStatus("active")
                    .withControlNode("scheduler")
                    .withWaitingReason(null);
                taskDao.updateState(updated);
                return updated;
            }
        };
        return service(db, graph);
    }

    private TaskService service(DatabaseManager db, ControlNodeGraph graph) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
        TaskService service = new TaskService(
            taskDao, sessionDao, eventDao, null, null, null, graph,
            null, null, null, null, null, sessionMessageDao
        );
        assertNotNull(service);
        return service;
    }
}
