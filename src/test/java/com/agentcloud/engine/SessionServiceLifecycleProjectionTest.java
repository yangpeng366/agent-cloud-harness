package com.agentcloud.engine;

import com.agentcloud.model.Event;
import com.agentcloud.model.Session;
import com.agentcloud.model.SessionMessage;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionServiceLifecycleProjectionTest {

    @TempDir
    Path tempDir;

    @Test
    void createSessionProjectsRequestMetadataToReceiptAndCreatedEvent() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-create-lifecycle.db"))) {
            SessionService service = service(db);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);

            Map<String, Object> requestMetadata = new LinkedHashMap<>();
            requestMetadata.put("requested_via", "http_api");
            requestMetadata.put("request_method", "POST");
            requestMetadata.put("request_path", "/api/v1/sessions");

            Session session = service.createSession("http session", requestMetadata);

            SessionMessage receiptMessage = messageDao.listBySession(session.id(), 20).stream()
                .filter(message -> "session_receipt".equals(message.messageType()))
                .findFirst()
                .orElseThrow();
            assertEquals("session_create", receiptMessage.metadata().get("action"));
            assertEquals("active", receiptMessage.metadata().get("session_status"));
            assertEquals("http_api", receiptMessage.metadata().get("requested_via"));
            assertEquals("POST", receiptMessage.metadata().get("request_method"));
            assertEquals("/api/v1/sessions", receiptMessage.metadata().get("request_path"));
            assertFalse(receiptMessage.metadata().containsKey("legacy_control_route"));

            Event createdEvent = eventDao.listBySession(session.id(), 20).stream()
                .filter(event -> "session_created".equals(event.eventType()))
                .findFirst()
                .orElseThrow();
            assertEquals("session_create", createdEvent.payload().get("action"));
            assertEquals("active", createdEvent.payload().get("session_status"));
            assertEquals("http_api", createdEvent.payload().get("requested_via"));
            assertEquals("POST", createdEvent.payload().get("request_method"));
            assertEquals("/api/v1/sessions", createdEvent.payload().get("request_path"));
        }
    }

    @Test
    void closeSessionProjectsRequestMetadataToStateMessageAndEvent() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-close-lifecycle.db"))) {
            SessionService service = service(db);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);

            Session session = service.createSession("close session");
            Map<String, Object> requestMetadata = new LinkedHashMap<>();
            requestMetadata.put("requested_via", "http_api");
            requestMetadata.put("request_method", "POST");
            requestMetadata.put("request_path", "/api/v1/sessions/" + session.id() + "/close");

            Session closed = service.closeSession(session.id(), requestMetadata);
            Session persisted = service.getSession(session.id());

            assertNotNull(closed.closedAt());
            assertNotNull(persisted.closedAt());
            assertEquals(closed.closedAt(), persisted.closedAt());

            SessionMessage stateMessage = messageDao.listBySession(session.id(), 20).stream()
                .filter(message -> "session_state".equals(message.messageType()))
                .filter(message -> "closed".equals(message.metadata().get("current_state")))
                .findFirst()
                .orElseThrow();
            assertEquals("session_close", stateMessage.metadata().get("action"));
            assertEquals("active", stateMessage.metadata().get("old_state"));
            assertEquals("closed", stateMessage.metadata().get("new_state"));
            assertEquals("active", stateMessage.metadata().get("previous_state"));
            assertEquals("closed", stateMessage.metadata().get("current_state"));
            assertEquals("closed", stateMessage.metadata().get("session_status"));
            assertEquals("http_api", stateMessage.metadata().get("requested_via"));
            assertEquals("POST", stateMessage.metadata().get("request_method"));
            assertEquals("/api/v1/sessions/" + session.id() + "/close", stateMessage.metadata().get("request_path"));
            assertNotNull(stateMessage.metadata().get("closed_at"));
            assertFalse(stateMessage.metadata().containsKey("legacy_control_route"));

            Event stateEvent = eventDao.listBySession(session.id(), 20).stream()
                .filter(event -> "session_state_changed".equals(event.eventType()))
                .filter(event -> "closed".equals(event.payload().get("current_state")))
                .findFirst()
                .orElseThrow();
            assertEquals("session_close", stateEvent.payload().get("action"));
            assertEquals("active", stateEvent.payload().get("old_state"));
            assertEquals("closed", stateEvent.payload().get("new_state"));
            assertEquals("active", stateEvent.payload().get("previous_state"));
            assertEquals("closed", stateEvent.payload().get("current_state"));
            assertEquals("closed", stateEvent.payload().get("session_status"));
            assertEquals("http_api", stateEvent.payload().get("requested_via"));
            assertEquals("POST", stateEvent.payload().get("request_method"));
            assertEquals("/api/v1/sessions/" + session.id() + "/close", stateEvent.payload().get("request_path"));
            assertNotNull(stateEvent.payload().get("closed_at"));
        }
    }

    @Test
    void pauseSessionProjectsRequestMetadataToStateMessageAndEvent() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-pause-lifecycle.db"))) {
            SessionService service = service(db);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);

            Session session = service.createSession("pause session");
            Map<String, Object> requestMetadata = new LinkedHashMap<>();
            requestMetadata.put("requested_via", "http_api");
            requestMetadata.put("request_method", "POST");
            requestMetadata.put("request_path", "/api/v1/sessions/" + session.id() + "/pause");

            Session paused = service.pauseSession(session.id(), requestMetadata);

            assertEquals("paused", paused.status());

            SessionMessage stateMessage = messageDao.listBySession(session.id(), 20).stream()
                .filter(message -> "session_state".equals(message.messageType()))
                .filter(message -> "paused".equals(message.metadata().get("current_state")))
                .findFirst()
                .orElseThrow();
            assertEquals("session_pause", stateMessage.metadata().get("action"));
            assertEquals("active", stateMessage.metadata().get("previous_state"));
            assertEquals("paused", stateMessage.metadata().get("current_state"));
            assertEquals("paused", stateMessage.metadata().get("session_status"));
            assertEquals("http_api", stateMessage.metadata().get("requested_via"));
            assertEquals("POST", stateMessage.metadata().get("request_method"));
            assertEquals("/api/v1/sessions/" + session.id() + "/pause", stateMessage.metadata().get("request_path"));

            Event stateEvent = eventDao.listBySession(session.id(), 20).stream()
                .filter(event -> "session_state_changed".equals(event.eventType()))
                .filter(event -> "paused".equals(event.payload().get("current_state")))
                .findFirst()
                .orElseThrow();
            assertEquals("session_pause", stateEvent.payload().get("action"));
            assertEquals("active", stateEvent.payload().get("previous_state"));
            assertEquals("paused", stateEvent.payload().get("current_state"));
            assertEquals("paused", stateEvent.payload().get("session_status"));
            assertEquals("http_api", stateEvent.payload().get("requested_via"));
            assertEquals("POST", stateEvent.payload().get("request_method"));
            assertEquals("/api/v1/sessions/" + session.id() + "/pause", stateEvent.payload().get("request_path"));
        }
    }

    @Test
    void resumeSessionProjectsRequestMetadataToStateMessageAndEvent() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-resume-lifecycle.db"))) {
            SessionService service = service(db);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);

            Session session = service.createSession("resume session");
            service.pauseSession(session.id());
            Map<String, Object> requestMetadata = new LinkedHashMap<>();
            requestMetadata.put("requested_via", "http_api");
            requestMetadata.put("request_method", "POST");
            requestMetadata.put("request_path", "/api/v1/sessions/" + session.id() + "/resume");

            Session resumed = service.resumeSession(session.id(), requestMetadata);

            assertEquals("active", resumed.status());

            SessionMessage stateMessage = messageDao.listBySession(session.id(), 20).stream()
                .filter(message -> "session_state".equals(message.messageType()))
                .filter(message -> "active".equals(message.metadata().get("current_state")))
                .findFirst()
                .orElseThrow();
            assertEquals("session_resume", stateMessage.metadata().get("action"));
            assertEquals("paused", stateMessage.metadata().get("previous_state"));
            assertEquals("active", stateMessage.metadata().get("current_state"));
            assertEquals("active", stateMessage.metadata().get("session_status"));
            assertEquals("http_api", stateMessage.metadata().get("requested_via"));
            assertEquals("POST", stateMessage.metadata().get("request_method"));
            assertEquals("/api/v1/sessions/" + session.id() + "/resume", stateMessage.metadata().get("request_path"));

            Event stateEvent = eventDao.listBySession(session.id(), 20).stream()
                .filter(event -> "session_state_changed".equals(event.eventType()))
                .filter(event -> "active".equals(event.payload().get("current_state")))
                .findFirst()
                .orElseThrow();
            assertEquals("session_resume", stateEvent.payload().get("action"));
            assertEquals("paused", stateEvent.payload().get("previous_state"));
            assertEquals("active", stateEvent.payload().get("current_state"));
            assertEquals("active", stateEvent.payload().get("session_status"));
            assertEquals("http_api", stateEvent.payload().get("requested_via"));
            assertEquals("POST", stateEvent.payload().get("request_method"));
            assertEquals("/api/v1/sessions/" + session.id() + "/resume", stateEvent.payload().get("request_path"));
        }
    }

    @Test
    void updateCurrentTaskRejectsClosedSession() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-update-current-task-closed.db"))) {
            SessionService service = service(db);
            Session session = service.createSession("closed session");
            service.closeSession(session.id());

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateCurrentTask(session.id(), "task_demo"));

            assertEquals("session is closed", error.getMessage());
        }
    }

    @Test
    void closeSessionRejectsUnfinishedTasks() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-close-open-task.db"))) {
            SessionService service = service(db);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);

            Session session = service.createSession("active task session");
            taskDao.insert(new Task(
                "task_open_1",
                session.id(),
                null,
                "unfinished task",
                "active",
                "high",
                null,
                null,
                null,
                null,
                null,
                null,
                "finish work before closing",
                null,
                null,
                "intake",
                null,
                Map.of("task_type", "continuation")
            ));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.closeSession(session.id()));

            assertEquals("session has unfinished tasks", error.getMessage());
        }
    }

    @Test
    void closeSessionIsIdempotentAndPreservesSessionPointers() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-close-idempotent.db"))) {
            SessionService service = service(db);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);

            Session session = service.createSession("close once");
            taskDao.insert(new Task(
                "task_done_1",
                session.id(),
                null,
                "finished task",
                "done",
                "high",
                null,
                null,
                null,
                Instant.now(),
                null,
                null,
                "all work is done",
                null,
                null,
                "end",
                null,
                Map.of("task_type", "continuation")
            ));
            sessionDao.updateState(session.id(), "active", Instant.now(), null, "task_done_1", "keep session summary");

            Session firstClose = service.closeSession(session.id());
            Session secondClose = service.closeSession(session.id());

            assertEquals("closed", firstClose.status());
            assertNotNull(firstClose.closedAt());
            assertEquals(firstClose.closedAt(), secondClose.closedAt());
            assertEquals("task_done_1", firstClose.currentTaskId());
            assertEquals("task_done_1", secondClose.currentTaskId());
            assertEquals("keep session summary", firstClose.summary());
            assertEquals("keep session summary", secondClose.summary());

            long stateMessageCount = messageDao.listBySession(session.id(), 20).stream()
                .filter(message -> "session_state".equals(message.messageType()))
                .count();
            long stateEventCount = eventDao.listBySession(session.id(), 20).stream()
                .filter(event -> "session_state_changed".equals(event.eventType()))
                .count();

            assertEquals(1L, stateMessageCount);
            assertEquals(1L, stateEventCount);
        }
    }

    @Test
    void updateCurrentTaskPreservesPausedSessionStatus() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-update-current-task-paused.db"))) {
            SessionService service = service(db);
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

            Session updated = service.updateCurrentTask(pausedSession.id(), "task_demo");

            assertEquals("paused", updated.status());
            assertEquals("task_demo", updated.currentTaskId());
            assertEquals("paused", service.getSession(pausedSession.id()).status());
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
