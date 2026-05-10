package com.agentcloud.engine;

import com.agentcloud.model.Session;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.SessionMessageCreateRequest;
import com.agentcloud.model.Task;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.TaskDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionServiceMessageTest {

    @TempDir
    Path tempDir;

    @Test
    void addMessagePersistsAndListsBySession() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-message.db"))) {
            SessionService service = service(db);
            Session session = service.createSession("message session");

            SessionMessage created = service.addMessage(session.id(), new SessionMessageCreateRequest(
                "user",
                "note",
                "先整理选题，再决定是否发布任务。",
                null,
                Map.of("source_surface", "test")
            ));

            List<SessionMessage> messages = service.listMessages(session.id(), 20);
            SessionMessage note = messages.stream()
                .filter(message -> created.id().equals(message.id()))
                .findFirst()
                .orElseThrow();
            assertEquals("note", note.messageType());
            assertEquals("先整理选题，再决定是否发布任务。", note.content());
        }
    }

    @Test
    void addMessageRejectsTaskFromAnotherSession() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-message-mismatch.db"))) {
            SessionService service = service(db);
            Session sessionA = service.createSession("session a");
            Session sessionB = service.createSession("session b");

            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            taskDao.insert(Task.create("task_test", sessionA.id(), "root task", "active", "high"));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.addMessage(sessionB.id(), new SessionMessageCreateRequest(
                    "user",
                    "note",
                    "这条消息错误地引用了别的 session 任务。",
                    "task_test",
                    Map.of()
                ))
            );

            assertEquals("task must belong to the same session", error.getMessage());
        }
    }

    @Test
    void listMessagesFiltersByTask() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-message-filter.db"))) {
            SessionService service = service(db);
            Session session = service.createSession("message filter");

            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            taskDao.insert(Task.create("task_filter", session.id(), "task filter", "active", "high"));

            service.addMessage(session.id(), new SessionMessageCreateRequest(
                "user",
                "note",
                "这是一条普通备注。",
                null,
                Map.of("source_surface", "test")
            ));
            service.addMessage(session.id(), new SessionMessageCreateRequest(
                "user",
                "task_brief",
                "这是与任务关联的 brief。",
                "task_filter",
                Map.of("source_surface", "test")
            ));

            List<SessionMessage> messages = service.listMessages(session.id(), 20, "task_filter");
            assertEquals(1, messages.size());
            assertEquals("task_filter", messages.get(0).taskId());
            assertEquals("task_brief", messages.get(0).messageType());
        }
    }

    @Test
    void addMessageRejectsClosedSession() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-message-closed.db"))) {
            SessionService service = service(db);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);

            Session session = service.createSession("closed session");
            Instant closedAt = Instant.now();
            sessionDao.updateState(session.id(), "closed", closedAt, closedAt, null, "closed for follow-up");

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.addMessage(session.id(), new SessionMessageCreateRequest(
                    "user",
                    "note",
                    "这条消息不应写入 closed session。",
                    null,
                    Map.of()
                ))
            );

            assertEquals("session is closed", error.getMessage());
        }
    }

    @Test
    void listMessagesRejectsTaskFromAnotherSession() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-message-list-mismatch.db"))) {
            SessionService service = service(db);
            Session sessionA = service.createSession("session a");
            Session sessionB = service.createSession("session b");

            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            taskDao.insert(Task.create("task_list_test", sessionA.id(), "list task", "active", "high"));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.listMessages(sessionB.id(), 20, "task_list_test")
            );

            assertEquals("task must belong to the same session", error.getMessage());
        }
    }

    private SessionService service(DatabaseManager db) {
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        return new SessionService(sessionDao, taskDao, sessionMessageDao, eventDao);
    }
}
