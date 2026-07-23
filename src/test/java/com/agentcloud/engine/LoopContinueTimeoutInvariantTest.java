package com.agentcloud.engine;

import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loop 验收标准 #3: HTTP /continue 超时（或 controlGraph.enter 抛异常）不把 active 任务判成 failed。
 *
 * <p>当 controlGraph.enter() 抛出 RuntimeException 时，continueTask 不应改变 task 级状态，
 * task 应保持 active 而不是被标记为 failed。
 */
class LoopContinueTimeoutInvariantTest {

    @TempDir
    Path tempDir;

    @Test
    void continueTaskExceptionDoesNotMarkTaskAsFailed() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("loop-continue-invariant.db"))) {
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);

            // ControlNodeGraph that throws on enter() to simulate HTTP timeout / worker runtime failure
            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task enter(Task task) {
                    throw new RuntimeException("simulated timeout during continue");
                }
            };

            TaskService service = new TaskService(
                taskDao, sessionDao, eventDao, null, null, null, graph,
                null, null, null, null, null, sessionMessageDao
            );

            Task task = service.createTask(new TaskCreateRequest(
                "demo continue invariant", "continuation", "user", "high",
                "active task should survive continue timeout", null, null, null, Map.of(), false
            ));

            // Task should be active before continue
            assertEquals("active", task.status());

            // continueTask should propagate the exception (HTTP layer will catch it and return 500)
            assertThrows(RuntimeException.class, () -> service.continueTask(task.id()));

            // Task should still be active, NOT failed
            Task afterException = taskDao.findById(task.id()).orElseThrow();
            assertEquals("active", afterException.status(),
                "task must remain active when continue throws, not failed");
        }
    }

    @Test
    void continueTaskPreservesTaskStatusWhenSchedulerNodeThrows() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("loop-scheduler-invariant.db"))) {
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task enter(Task task) {
                    // Simulate what happens when scheduler -> execute -> timeout
                    // The task should stay at whatever state it was before the failure
                    throw new RuntimeException("worker execution timeout");
                }
            };

            TaskService service = new TaskService(
                taskDao, sessionDao, eventDao, null, null, null, graph,
                null, null, null, null, null, sessionMessageDao
            );

            Task task = service.createTask(new TaskCreateRequest(
                "demo scheduler invariant", "continuation", "user", "high",
                "active task should survive scheduler timeout", null, null, null, Map.of(), false
            ));

            String originalStatus = task.status();
            String originalNode = task.controlNode();

            assertThrows(RuntimeException.class, () -> service.continueTask(task.id()));

            Task afterException = taskDao.findById(task.id()).orElseThrow();
            assertEquals(originalStatus, afterException.status(),
                "task status must not change when continue throws");
            assertEquals(originalNode, afterException.controlNode(),
                "task control node must not change when continue throws");
        }
    }

    @Test
    void continueTaskDoesNotWriteFailedEventOnException() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("loop-event-invariant.db"))) {
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task enter(Task task) {
                    throw new RuntimeException("simulated timeout");
                }
            };

            TaskService service = new TaskService(
                taskDao, sessionDao, eventDao, null, null, null, graph,
                null, null, null, null, null, sessionMessageDao
            );

            Task task = service.createTask(new TaskCreateRequest(
                "demo event invariant", "continuation", "user", "high",
                "no failed event on continue exception", null, null, null, Map.of(), false
            ));

            assertThrows(RuntimeException.class, () -> service.continueTask(task.id()));

            // Verify no "task_failed" event was written
            boolean hasFailedEvent = eventDao.listBySessionAndTask(task.sessionId(), task.id(), 50).stream()
                .anyMatch(event -> "task_failed".equals(event.eventType())
                    || "failed".equals(event.eventType()));
            assertTrue(!hasFailedEvent,
                "no task_failed event should be written when continue throws an exception");
        }
    }
}