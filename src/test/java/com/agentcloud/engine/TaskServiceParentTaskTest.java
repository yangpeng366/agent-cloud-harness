package com.agentcloud.engine;

import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskServiceParentTaskTest {

    @TempDir
    Path tempDir;

    @Test
    void childTaskInheritsParentSessionWhenSessionIdMissing() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("parent-inherit.db"))) {
            TaskService service = service(db);

            Task parent = service.createTask(new TaskCreateRequest(
                "root task", "continuation", "user", "high",
                "root intent", "root goal", null, null, Map.of(), false
            ));

            Task child = service.createTask(new TaskCreateRequest(
                "follow-up task", "continuation", "user", "high",
                "follow-up intent", "follow-up goal", parent.id(), null, Map.of(), false
            ));

            assertEquals(parent.sessionId(), child.sessionId());
            assertEquals(parent.id(), child.parentTaskId());
            assertEquals(parent.id(), child.metadata().get("parent_task_id"));
        }
    }

    @Test
    void childTaskRejectsMismatchedSession() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("parent-mismatch.db"))) {
            TaskService service = service(db);

            Task parent = service.createTask(new TaskCreateRequest(
                "root task", "continuation", "user", "high",
                "root intent", "root goal", null, null, Map.of(), false
            ));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.createTask(new TaskCreateRequest(
                    "bad follow-up", "continuation", "user", "high",
                    "follow-up intent", "follow-up goal", parent.id(), "session_other", Map.of(), false
                ))
            );

            assertEquals("parent task must belong to the same session", error.getMessage());
        }
    }

    @Test
    void taskRejectsClosedSession() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("closed-session-reject.db"))) {
            TaskService service = service(db);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);

            Session session = Session.create("session_closed", "closed session", "active");
            sessionDao.insert(session);
            Instant closedAt = Instant.now();
            sessionDao.updateState(session.id(), "closed", closedAt, closedAt, null, "Session closed");

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.createTask(new TaskCreateRequest(
                    "should fail", "continuation", "user", "high",
                    "cannot attach to closed session", "expect validation", null, session.id(), Map.of(), false
                ))
            );

            assertEquals("session is closed", error.getMessage());
        }
    }

    @Test
    void taskCreationPreservesPausedSessionStatus() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("paused-session-preserve.db"))) {
            TaskService service = service(db);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);

            Session pausedSession = new Session(
                "session_paused",
                "paused session",
                "paused",
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                null,
                null
            );
            sessionDao.insert(pausedSession);

            Task task = service.createTask(new TaskCreateRequest(
                "should stay paused", "continuation", "user", "high",
                "bind task to paused session", "preserve session status", null, pausedSession.id(), Map.of(), false
            ));
            Session persisted = sessionDao.findById(pausedSession.id()).orElseThrow();

            assertEquals("paused", persisted.status());
            assertEquals(task.id(), persisted.currentTaskId());
        }
    }

    private TaskService service(DatabaseManager db) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        return new TaskService(
            taskDao, sessionDao, eventDao, null, null, null, null,
            null, null, null, null, null
        );
    }
}
