package com.agentcloud.engine;

import com.agentcloud.model.Session;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.SessionMessageCreateRequest;
import com.agentcloud.model.Task;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.TaskDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
            assertEquals(1, messages.size());
            assertEquals(created.id(), messages.get(0).id());
            assertEquals("note", messages.get(0).messageType());
            assertEquals("先整理选题，再决定是否发布任务。", messages.get(0).content());
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

    private SessionService service(DatabaseManager db) {
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
        return new SessionService(sessionDao, taskDao, sessionMessageDao);
    }
}
