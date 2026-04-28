package com.agentcloud.server;

import com.agentcloud.engine.SessionService;
import com.agentcloud.model.Event;
import com.agentcloud.model.Session;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.TaskDao;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionHandlerLifecycleHttpTest {

    @TempDir
    Path tempDir;

    @Test
    void postCreateSessionProjectsHttpMetadataToReceiptAndCreatedEvent() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("session-handler-create.db"))) {
            ApiCall response = fixture.postJson("/api/v1/sessions", Map.of("title", "http session"));

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            String sessionId = response.body().path("data").path("id").asText();
            assertEquals("active", response.body().path("data").path("status").asText());

            SessionMessage receiptMessage = fixture.findSessionMessage(sessionId, "session_receipt");
            assertEquals("session_create", receiptMessage.metadata().get("action"));
            assertEquals("http_api", receiptMessage.metadata().get("requested_via"));
            assertEquals("POST", receiptMessage.metadata().get("request_method"));
            assertEquals("/api/v1/sessions", receiptMessage.metadata().get("request_path"));
            assertFalse(receiptMessage.metadata().containsKey("legacy_control_route"));

            Event createdEvent = fixture.findSessionEvent(sessionId, "session_created");
            assertEquals("session_create", createdEvent.payload().get("action"));
            assertEquals("http_api", createdEvent.payload().get("requested_via"));
            assertEquals("POST", createdEvent.payload().get("request_method"));
            assertEquals("/api/v1/sessions", createdEvent.payload().get("request_path"));
        }
    }

    @Test
    void postCloseSessionProjectsHttpMetadataToStateMessageAndEvent() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("session-handler-close.db"))) {
            Session session = fixture.createSession("http close");

            ApiCall response = fixture.postEmpty("/api/v1/sessions/" + session.id() + "/close");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("closed", response.body().path("data").path("status").asText());
            assertFalse(response.body().path("data").path("closed_at").isMissingNode());
            assertFalse(response.body().path("data").path("closed_at").isNull());

            SessionMessage stateMessage = fixture.findSessionStateMessage(session.id(), "closed");
            assertEquals("session_close", stateMessage.metadata().get("action"));
            assertEquals("active", stateMessage.metadata().get("previous_state"));
            assertEquals("closed", stateMessage.metadata().get("current_state"));
            assertEquals("http_api", stateMessage.metadata().get("requested_via"));
            assertEquals("POST", stateMessage.metadata().get("request_method"));
            assertEquals("/api/v1/sessions/" + session.id() + "/close", stateMessage.metadata().get("request_path"));
            assertTrue(stateMessage.metadata().containsKey("closed_at"));
            assertFalse(stateMessage.metadata().containsKey("legacy_control_route"));

            Event stateEvent = fixture.findSessionStateEvent(session.id(), "closed");
            assertEquals("session_close", stateEvent.payload().get("action"));
            assertEquals("active", stateEvent.payload().get("previous_state"));
            assertEquals("closed", stateEvent.payload().get("current_state"));
            assertEquals("http_api", stateEvent.payload().get("requested_via"));
            assertEquals("POST", stateEvent.payload().get("request_method"));
            assertEquals("/api/v1/sessions/" + session.id() + "/close", stateEvent.payload().get("request_path"));
            assertTrue(stateEvent.payload().containsKey("closed_at"));
        }
    }

    @Test
    void postPauseSessionProjectsHttpMetadataToStateMessageAndEvent() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("session-handler-pause.db"))) {
            Session session = fixture.createSession("http pause");

            ApiCall response = fixture.postEmpty("/api/v1/sessions/" + session.id() + "/pause");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("paused", response.body().path("data").path("status").asText());

            SessionMessage stateMessage = fixture.findSessionStateMessage(session.id(), "paused");
            assertEquals("session_pause", stateMessage.metadata().get("action"));
            assertEquals("active", stateMessage.metadata().get("previous_state"));
            assertEquals("paused", stateMessage.metadata().get("current_state"));
            assertEquals("http_api", stateMessage.metadata().get("requested_via"));
            assertEquals("POST", stateMessage.metadata().get("request_method"));
            assertEquals("/api/v1/sessions/" + session.id() + "/pause", stateMessage.metadata().get("request_path"));
            assertFalse(stateMessage.metadata().containsKey("legacy_control_route"));

            Event stateEvent = fixture.findSessionStateEvent(session.id(), "paused");
            assertEquals("session_pause", stateEvent.payload().get("action"));
            assertEquals("active", stateEvent.payload().get("previous_state"));
            assertEquals("paused", stateEvent.payload().get("current_state"));
            assertEquals("http_api", stateEvent.payload().get("requested_via"));
            assertEquals("POST", stateEvent.payload().get("request_method"));
            assertEquals("/api/v1/sessions/" + session.id() + "/pause", stateEvent.payload().get("request_path"));
        }
    }

    @Test
    void postResumeSessionProjectsHttpMetadataToStateMessageAndEvent() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("session-handler-resume.db"))) {
            Session session = fixture.createSession("http resume");
            fixture.postEmpty("/api/v1/sessions/" + session.id() + "/pause");

            ApiCall response = fixture.postEmpty("/api/v1/sessions/" + session.id() + "/resume");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("active", response.body().path("data").path("status").asText());

            SessionMessage stateMessage = fixture.findSessionStateMessage(session.id(), "active");
            assertEquals("session_resume", stateMessage.metadata().get("action"));
            assertEquals("paused", stateMessage.metadata().get("previous_state"));
            assertEquals("active", stateMessage.metadata().get("current_state"));
            assertEquals("http_api", stateMessage.metadata().get("requested_via"));
            assertEquals("POST", stateMessage.metadata().get("request_method"));
            assertEquals("/api/v1/sessions/" + session.id() + "/resume", stateMessage.metadata().get("request_path"));
            assertFalse(stateMessage.metadata().containsKey("legacy_control_route"));

            Event stateEvent = fixture.findSessionStateEvent(session.id(), "active");
            assertEquals("session_resume", stateEvent.payload().get("action"));
            assertEquals("paused", stateEvent.payload().get("previous_state"));
            assertEquals("active", stateEvent.payload().get("current_state"));
            assertEquals("http_api", stateEvent.payload().get("requested_via"));
            assertEquals("POST", stateEvent.payload().get("request_method"));
            assertEquals("/api/v1/sessions/" + session.id() + "/resume", stateEvent.payload().get("request_path"));
        }
    }

    @Test
    void legacyGetCloseSessionStillMarksLifecycleProjectionForAudit() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("session-handler-legacy-close.db"))) {
            Session session = fixture.createSession("legacy close");

            ApiCall response = fixture.get("/api/v1/sessions/" + session.id() + "/close");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("closed", response.body().path("data").path("status").asText());
            assertFalse(response.body().path("data").path("closed_at").isMissingNode());
            assertFalse(response.body().path("data").path("closed_at").isNull());
            assertEquals("true", response.header("Deprecation"));
            assertEquals(NioHttpServer.LEGACY_WRITE_ROUTE_SUNSET, response.header("Sunset"));
            assertEquals("POST", response.header("X-AgentCloud-Replacement-Method"));

            SessionMessage stateMessage = fixture.findSessionStateMessage(session.id(), "closed");
            assertEquals("GET", stateMessage.metadata().get("request_method"));
            assertTrue(stateMessage.metadata().containsKey("closed_at"));
            assertEquals(Boolean.TRUE, stateMessage.metadata().get("legacy_control_route"));

            Event stateEvent = fixture.findSessionStateEvent(session.id(), "closed");
            assertEquals("GET", stateEvent.payload().get("request_method"));
            assertTrue(stateEvent.payload().containsKey("closed_at"));
            assertEquals(Boolean.TRUE, stateEvent.payload().get("legacy_control_route"));
        }
    }

    @Test
    void postCloseSessionRejectsUnfinishedTasks() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("session-handler-close-open-task.db"))) {
            Session session = fixture.createSession("session with active task");
            fixture.createTask(session, "active");

            ApiCall response = fixture.postEmpty("/api/v1/sessions/" + session.id() + "/close");

            assertEquals(400, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("400", response.body().path("code").asText());
            assertEquals("session has unfinished tasks", response.body().path("message").asText());
        }
    }

    @Test
    void postCloseSessionIsIdempotentAndKeepsOriginalClosedAt() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("session-handler-close-idempotent.db"))) {
            Session session = fixture.createSession("close twice");
            Task task = fixture.createTask(session, "done");
            fixture.updateSessionCurrentTaskAndSummary(session.id(), task.id(), "keep session summary");

            ApiCall first = fixture.postEmpty("/api/v1/sessions/" + session.id() + "/close");
            ApiCall second = fixture.postEmpty("/api/v1/sessions/" + session.id() + "/close");

            assertEquals(200, first.statusCode());
            assertEquals(200, second.statusCode());
            assertEquals("closed", first.body().path("data").path("status").asText());
            assertEquals("closed", second.body().path("data").path("status").asText());
            assertEquals(first.body().path("data").path("closed_at").asText(), second.body().path("data").path("closed_at").asText());
            assertEquals(task.id(), second.body().path("data").path("current_task_id").asText());
            assertEquals("keep session summary", second.body().path("data").path("summary").asText());

            long stateMessageCount = fixture.sessionMessages(session.id()).stream()
                .filter(message -> "session_state".equals(message.messageType()))
                .count();
            long stateEventCount = fixture.sessionEvents(session.id()).stream()
                .filter(event -> "session_state_changed".equals(event.eventType()))
                .count();

            assertEquals(1L, stateMessageCount);
            assertEquals(1L, stateEventCount);
        }
    }

    private static final class HttpFixture implements AutoCloseable {
        private final DatabaseManager db;
        private final SessionService sessionService;
        private final SessionDao sessionDao;
        private final SessionMessageDao sessionMessageDao;
        private final EventDao eventDao;
        private final TaskDao taskDao;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final String baseUrl;

        private HttpFixture(Path dbPath) throws IOException {
            this.db = new DatabaseManager(dbPath);
            this.taskDao = db.jdbi().onDemand(TaskDao.class);
            this.sessionDao = db.jdbi().onDemand(SessionDao.class);
            this.sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
            this.eventDao = db.jdbi().onDemand(EventDao.class);
            this.sessionService = new SessionService(sessionDao, taskDao, sessionMessageDao, eventDao);
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            this.server.setExecutor(executor);
            this.server.createContext("/api/v1/sessions", new SessionHandler(sessionService, NioHttpServer.SHARED_MAPPER));
            this.server.start();
            this.client = HttpClient.newHttpClient();
            this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private Session createSession(String title) {
            return sessionService.createSession(title);
        }

        private Task createTask(Session session, String status) {
            Task task = new Task(
                "task_" + session.id(),
                session.id(),
                null,
                "session task",
                status,
                "high",
                null,
                null,
                null,
                null,
                null,
                null,
                "keep session open",
                null,
                null,
                "intake",
                null,
                Map.of("task_type", "continuation")
            );
            taskDao.insert(task);
            return task;
        }

        private void updateSessionCurrentTaskAndSummary(String sessionId, String taskId, String summary) {
            sessionDao.updateState(sessionId, "active", java.time.Instant.now(), null, taskId, summary);
        }

        private ApiCall postJson(String path, Map<String, Object> body) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(NioHttpServer.SHARED_MAPPER.writeValueAsString(body)))
                .build();
            return execute(request);
        }

        private ApiCall postEmpty(String path) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
            return execute(request);
        }

        private ApiCall get(String path) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .GET()
                .build();
            return execute(request);
        }

        private ApiCall execute(HttpRequest request) throws Exception {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiCall(
                response.statusCode(),
                response.headers(),
                NioHttpServer.SHARED_MAPPER.readTree(response.body())
            );
        }

        private SessionMessage findSessionMessage(String sessionId, String messageType) {
            return sessionMessages(sessionId).stream()
                .filter(message -> messageType.equals(message.messageType()))
                .findFirst()
                .orElseThrow();
        }

        private SessionMessage findSessionStateMessage(String sessionId, String currentState) {
            return sessionMessages(sessionId).stream()
                .filter(message -> "session_state".equals(message.messageType()))
                .filter(message -> currentState.equals(stringValue(message.metadata(), "current_state")))
                .findFirst()
                .orElseThrow();
        }

        private Event findSessionEvent(String sessionId, String eventType) {
            return sessionEvents(sessionId).stream()
                .filter(event -> eventType.equals(event.eventType()))
                .findFirst()
                .orElseThrow();
        }

        private Event findSessionStateEvent(String sessionId, String currentState) {
            return sessionEvents(sessionId).stream()
                .filter(event -> "session_state_changed".equals(event.eventType()))
                .filter(event -> currentState.equals(stringValue(event.payload(), "current_state")))
                .findFirst()
                .orElseThrow();
        }

        private List<SessionMessage> sessionMessages(String sessionId) {
            return sessionMessageDao.listBySession(sessionId, 20);
        }

        private List<Event> sessionEvents(String sessionId) {
            return eventDao.listBySession(sessionId, 20);
        }

        private String stringValue(Map<String, Object> metadata, String key) {
            if (metadata == null || key == null) {
                return null;
            }
            Object value = metadata.get(key);
            return value == null ? null : value.toString();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.close();
            db.close();
        }
    }

    private record ApiCall(int statusCode, HttpHeaders headers, JsonNode body) {
        private String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }
}
