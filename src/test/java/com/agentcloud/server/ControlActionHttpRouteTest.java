package com.agentcloud.server;

import com.agentcloud.engine.ControlNodeGraph;
import com.agentcloud.engine.SessionService;
import com.agentcloud.engine.TaskService;
import com.agentcloud.model.Event;
import com.agentcloud.model.Session;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlActionHttpRouteTest {

    @TempDir
    Path tempDir;

    @Test
    void postPauseWritesHttpMetadataToMessageAndEvent() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("post-pause.db"))) {
            Task task = fixture.createManualTask("http pause");

            ApiCall response = fixture.postJson(
                "/api/v1/tasks/" + task.id() + "/pause",
                Map.of("reason", "waiting for review")
            );

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("paused", response.body().path("data").path("state").asText());
            assertEquals("packet", response.body().path("data").path("control_node").asText());

            SessionMessage actionMessage = fixture.findTaskActionMessage(task.sessionId(), task.id(), "pause");
            assertHttpMetadata(actionMessage.metadata(), "POST", "/api/v1/tasks/" + task.id() + "/pause", "waiting for review");
            assertEquals("已暂停", actionMessage.metadata().get("action_label"));
            assertTrue(actionMessage.content().contains("已暂停"));
            assertTrue(actionMessage.content().contains("当前：paused / packet"));

            SessionMessage stateMessage = fixture.findTaskStateMessage(task.sessionId(), task.id(), "paused");
            assertHttpMetadata(stateMessage.metadata(), "POST", "/api/v1/tasks/" + task.id() + "/pause", "waiting for review");
            assertEquals("active", stateMessage.metadata().get("previous_state"));
            assertEquals("paused", stateMessage.metadata().get("current_state"));
            assertTrue(stateMessage.content().contains("状态已更新"));
            assertTrue(stateMessage.content().contains("active -> paused"));

            Event controlEvent = fixture.findControlActionEvent(task.sessionId(), task.id(), "pause");
            assertHttpMetadata(controlEvent.payload(), "POST", "/api/v1/tasks/" + task.id() + "/pause", "waiting for review");

            Event stateEvent = fixture.findTaskStateEvent(task.sessionId(), task.id(), "paused");
            assertHttpMetadata(stateEvent.payload(), "POST", "/api/v1/tasks/" + task.id() + "/pause", "waiting for review");
            assertEquals("active", stateEvent.payload().get("previous_state"));
            assertEquals("paused", stateEvent.payload().get("current_state"));
        }
    }

    @Test
    void postResumeAcceptsEmptyBodyAndWritesHttpMetadata() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("post-resume.db"))) {
            Task task = fixture.createManualTask("http resume");
            fixture.postJson("/api/v1/tasks/" + task.id() + "/pause", Map.of("reason", "pause before resume"));

            ApiCall response = fixture.postEmpty("/api/v1/tasks/" + task.id() + "/resume");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("active", response.body().path("data").path("state").asText());
            assertEquals("scheduler", response.body().path("data").path("control_node").asText());

            SessionMessage actionMessage = fixture.findTaskActionMessage(task.sessionId(), task.id(), "resume");
            assertHttpMetadata(actionMessage.metadata(), "POST", "/api/v1/tasks/" + task.id() + "/resume", null);
            assertEquals("已恢复执行", actionMessage.metadata().get("action_label"));
            assertTrue(actionMessage.content().contains("已恢复执行"));

            SessionMessage stateMessage = fixture.findTaskStateMessage(task.sessionId(), task.id(), "active");
            assertHttpMetadata(stateMessage.metadata(), "POST", "/api/v1/tasks/" + task.id() + "/resume", null);
            assertEquals("paused", stateMessage.metadata().get("previous_state"));
            assertEquals("active", stateMessage.metadata().get("current_state"));

            SessionMessage progressMessage = fixture.findMessage(task.sessionId(), task.id(), "task_progress");
            assertTrue(progressMessage.content().contains("已恢复执行"));

            Event controlEvent = fixture.findControlActionEvent(task.sessionId(), task.id(), "resume");
            assertHttpMetadata(controlEvent.payload(), "POST", "/api/v1/tasks/" + task.id() + "/resume", null);

            Event stateEvent = fixture.findTaskStateEvent(task.sessionId(), task.id(), "active");
            assertHttpMetadata(stateEvent.payload(), "POST", "/api/v1/tasks/" + task.id() + "/resume", null);
            assertEquals("paused", stateEvent.payload().get("previous_state"));
            assertEquals("active", stateEvent.payload().get("current_state"));
        }
    }

    @Test
    void postContinueAcceptsEmptyBodyAndWritesHttpMetadata() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("post-continue.db"))) {
            Task task = fixture.createManualTask("http continue");

            ApiCall response = fixture.postEmpty("/api/v1/tasks/" + task.id() + "/continue");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("active", response.body().path("data").path("state").asText());
            assertEquals("scheduler", response.body().path("data").path("control_node").asText());

            SessionMessage actionMessage = fixture.findTaskActionMessage(task.sessionId(), task.id(), "continue");
            assertHttpMetadata(actionMessage.metadata(), "POST", "/api/v1/tasks/" + task.id() + "/continue", null);
            assertEquals("已继续推进", actionMessage.metadata().get("action_label"));
            assertTrue(actionMessage.content().contains("已继续推进"));

            assertFalse(fixture.hasTaskStateMessage(task.sessionId(), task.id()));

            SessionMessage progressMessage = fixture.findMessage(task.sessionId(), task.id(), "task_progress");
            assertTrue(progressMessage.content().contains("继续推进草稿"));

            Event controlEvent = fixture.findControlActionEvent(task.sessionId(), task.id(), "continue");
            assertHttpMetadata(controlEvent.payload(), "POST", "/api/v1/tasks/" + task.id() + "/continue", null);
            assertFalse(fixture.hasTaskStateEvent(task.sessionId(), task.id()));
        }
    }

    @Test
    void postEscalateWritesReasonAndHttpMetadata() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("post-escalate.db"))) {
            Task task = fixture.createManualTask("http escalate");

            ApiCall response = fixture.postJson(
                "/api/v1/tasks/" + task.id() + "/escalate",
                Map.of("reason", "need human approval")
            );

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("waiting_human", response.body().path("data").path("state").asText());
            assertEquals("human_gate", response.body().path("data").path("control_node").asText());

            SessionMessage actionMessage = fixture.findTaskActionMessage(task.sessionId(), task.id(), "escalate");
            assertHttpMetadata(actionMessage.metadata(), "POST", "/api/v1/tasks/" + task.id() + "/escalate", "need human approval");
            assertEquals("已升级到人工确认", actionMessage.metadata().get("action_label"));
            assertTrue(actionMessage.content().contains("已升级到人工确认"));

            SessionMessage stateMessage = fixture.findTaskStateMessage(task.sessionId(), task.id(), "waiting_human");
            assertHttpMetadata(stateMessage.metadata(), "POST", "/api/v1/tasks/" + task.id() + "/escalate", "need human approval");
            assertEquals("active", stateMessage.metadata().get("previous_state"));
            assertEquals("waiting_human", stateMessage.metadata().get("current_state"));

            Event controlEvent = fixture.findControlActionEvent(task.sessionId(), task.id(), "escalate");
            assertHttpMetadata(controlEvent.payload(), "POST", "/api/v1/tasks/" + task.id() + "/escalate", "need human approval");

            Event stateEvent = fixture.findTaskStateEvent(task.sessionId(), task.id(), "waiting_human");
            assertHttpMetadata(stateEvent.payload(), "POST", "/api/v1/tasks/" + task.id() + "/escalate", "need human approval");
            assertEquals("active", stateEvent.payload().get("previous_state"));
            assertEquals("waiting_human", stateEvent.payload().get("current_state"));
        }
    }

    @Test
    void postStateUpdateWritesHttpMetadataToStateProjection() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("post-state-update.db"))) {
            Task task = fixture.createManualTask("http state update");

            ApiCall response = fixture.postJson(
                "/api/v1/tasks/" + task.id() + "/state",
                Map.of("state", "done", "reason", "manual completion")
            );

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("done", response.body().path("data").path("status").asText());

            SessionMessage stateMessage = fixture.findTaskStateMessage(task.sessionId(), task.id(), "done");
            assertHttpMetadata(stateMessage.metadata(), "POST", "/api/v1/tasks/" + task.id() + "/state", "manual completion");
            assertEquals("active", stateMessage.metadata().get("previous_state"));
            assertEquals("done", stateMessage.metadata().get("current_state"));

            Event stateEvent = fixture.findTaskStateEvent(task.sessionId(), task.id(), "done");
            assertHttpMetadata(stateEvent.payload(), "POST", "/api/v1/tasks/" + task.id() + "/state", "manual completion");
            assertEquals("active", stateEvent.payload().get("previous_state"));
            assertEquals("done", stateEvent.payload().get("current_state"));
        }
    }

    @Test
    void postCloseSessionUsesFormalRoute() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("post-close-session.db"))) {
            Session session = fixture.createSession("http close");

            ApiCall response = fixture.postEmpty("/api/v1/sessions/" + session.id() + "/close");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("closed", response.body().path("data").path("status").asText());
        }
    }

    @Test
    void legacyGetPauseStillWorksAndSendsDeprecationHeaders() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("legacy-get-pause.db"))) {
            Task task = fixture.createManualTask("legacy get pause");

            ApiCall response = fixture.get("/api/v1/tasks/" + task.id() + "/pause");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("paused", response.body().path("data").path("state").asText());
            assertEquals("packet", response.body().path("data").path("control_node").asText());
            assertDeprecatedGetHeaders(response, "/api/v1/tasks/" + task.id() + "/pause");

            SessionMessage actionMessage = fixture.findTaskActionMessage(task.sessionId(), task.id(), "pause");
            assertLegacyHttpMetadata(actionMessage.metadata(), "GET", "/api/v1/tasks/" + task.id() + "/pause", null);

            SessionMessage stateMessage = fixture.findTaskStateMessage(task.sessionId(), task.id(), "paused");
            assertLegacyHttpMetadata(stateMessage.metadata(), "GET", "/api/v1/tasks/" + task.id() + "/pause", null);
            assertEquals("active", stateMessage.metadata().get("previous_state"));
            assertEquals("paused", stateMessage.metadata().get("current_state"));

            Event controlEvent = fixture.findControlActionEvent(task.sessionId(), task.id(), "pause");
            assertLegacyHttpMetadata(controlEvent.payload(), "GET", "/api/v1/tasks/" + task.id() + "/pause", null);

            Event stateEvent = fixture.findTaskStateEvent(task.sessionId(), task.id(), "paused");
            assertLegacyHttpMetadata(stateEvent.payload(), "GET", "/api/v1/tasks/" + task.id() + "/pause", null);
            assertEquals("active", stateEvent.payload().get("previous_state"));
            assertEquals("paused", stateEvent.payload().get("current_state"));
        }
    }

    @Test
    void legacyGetResumeStillWorksAndSendsDeprecationHeaders() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("legacy-get-resume.db"))) {
            Task task = fixture.createManualTask("legacy get resume");
            fixture.postJson("/api/v1/tasks/" + task.id() + "/pause", Map.of("reason", "pause before legacy resume"));

            ApiCall response = fixture.get("/api/v1/tasks/" + task.id() + "/resume");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("active", response.body().path("data").path("state").asText());
            assertEquals("scheduler", response.body().path("data").path("control_node").asText());
            assertDeprecatedGetHeaders(response, "/api/v1/tasks/" + task.id() + "/resume");

            SessionMessage actionMessage = fixture.findTaskActionMessage(task.sessionId(), task.id(), "resume");
            assertLegacyHttpMetadata(actionMessage.metadata(), "GET", "/api/v1/tasks/" + task.id() + "/resume", null);

            SessionMessage stateMessage = fixture.findTaskStateMessage(task.sessionId(), task.id(), "active");
            assertLegacyHttpMetadata(stateMessage.metadata(), "GET", "/api/v1/tasks/" + task.id() + "/resume", null);
            assertEquals("paused", stateMessage.metadata().get("previous_state"));
            assertEquals("active", stateMessage.metadata().get("current_state"));

            Event controlEvent = fixture.findControlActionEvent(task.sessionId(), task.id(), "resume");
            assertLegacyHttpMetadata(controlEvent.payload(), "GET", "/api/v1/tasks/" + task.id() + "/resume", null);

            Event stateEvent = fixture.findTaskStateEvent(task.sessionId(), task.id(), "active");
            assertLegacyHttpMetadata(stateEvent.payload(), "GET", "/api/v1/tasks/" + task.id() + "/resume", null);
            assertEquals("paused", stateEvent.payload().get("previous_state"));
            assertEquals("active", stateEvent.payload().get("current_state"));
        }
    }

    @Test
    void legacyGetContinueStillWorksAndSendsDeprecationHeaders() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("legacy-get-continue.db"))) {
            Task task = fixture.createManualTask("legacy get continue");

            ApiCall response = fixture.get("/api/v1/tasks/" + task.id() + "/continue");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("active", response.body().path("data").path("state").asText());
            assertEquals("scheduler", response.body().path("data").path("control_node").asText());
            assertDeprecatedGetHeaders(response, "/api/v1/tasks/" + task.id() + "/continue");

            SessionMessage actionMessage = fixture.findTaskActionMessage(task.sessionId(), task.id(), "continue");
            assertLegacyHttpMetadata(actionMessage.metadata(), "GET", "/api/v1/tasks/" + task.id() + "/continue", null);
            assertFalse(fixture.hasTaskStateMessage(task.sessionId(), task.id()));

            Event controlEvent = fixture.findControlActionEvent(task.sessionId(), task.id(), "continue");
            assertLegacyHttpMetadata(controlEvent.payload(), "GET", "/api/v1/tasks/" + task.id() + "/continue", null);
            assertFalse(fixture.hasTaskStateEvent(task.sessionId(), task.id()));
        }
    }

    @Test
    void legacyGetEscalateStillWorksAndSendsDeprecationHeaders() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("legacy-get-escalate.db"))) {
            Task task = fixture.createManualTask("legacy get escalate");

            ApiCall response = fixture.get("/api/v1/tasks/" + task.id() + "/escalate");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("waiting_human", response.body().path("data").path("state").asText());
            assertEquals("human_gate", response.body().path("data").path("control_node").asText());
            assertDeprecatedGetHeaders(response, "/api/v1/tasks/" + task.id() + "/escalate");

            SessionMessage actionMessage = fixture.findTaskActionMessage(task.sessionId(), task.id(), "escalate");
            assertLegacyHttpMetadata(actionMessage.metadata(), "GET", "/api/v1/tasks/" + task.id() + "/escalate", null);

            SessionMessage stateMessage = fixture.findTaskStateMessage(task.sessionId(), task.id(), "waiting_human");
            assertLegacyHttpMetadata(stateMessage.metadata(), "GET", "/api/v1/tasks/" + task.id() + "/escalate", null);
            assertEquals("active", stateMessage.metadata().get("previous_state"));
            assertEquals("waiting_human", stateMessage.metadata().get("current_state"));

            Event controlEvent = fixture.findControlActionEvent(task.sessionId(), task.id(), "escalate");
            assertLegacyHttpMetadata(controlEvent.payload(), "GET", "/api/v1/tasks/" + task.id() + "/escalate", null);

            Event stateEvent = fixture.findTaskStateEvent(task.sessionId(), task.id(), "waiting_human");
            assertLegacyHttpMetadata(stateEvent.payload(), "GET", "/api/v1/tasks/" + task.id() + "/escalate", null);
            assertEquals("active", stateEvent.payload().get("previous_state"));
            assertEquals("waiting_human", stateEvent.payload().get("current_state"));
        }
    }

    @Test
    void legacyGetCloseSessionStillWorksAndSendsDeprecationHeaders() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("legacy-get-close-session.db"))) {
            Session session = fixture.createSession("legacy close");

            ApiCall response = fixture.get("/api/v1/sessions/" + session.id() + "/close");

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("closed", response.body().path("data").path("status").asText());
            assertDeprecatedGetHeaders(response, "/api/v1/sessions/" + session.id() + "/close");
        }
    }

    @Test
    void postPauseHidesInternalFailureDetails() throws Exception {
        try (HttpFixture fixture = new HttpFixture(
            tempDir.resolve("post-pause-failure.db"),
            "sensitive pause failure detail"
        )) {
            Task task = fixture.createManualTask("failing pause");

            ApiCall response = fixture.postJson(
                "/api/v1/tasks/" + task.id() + "/pause",
                Map.of("reason", "trigger failure")
            );

            assertEquals(500, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("500", response.body().path("code").asText());
            assertEquals("internal error", response.body().path("message").asText());
            assertFalse(response.body().toString().contains("sensitive pause failure detail"));
        }
    }

    private void assertHttpMetadata(Map<String, Object> metadata, String method, String path, String reason) {
        assertNotNull(metadata);
        assertEquals("http_api", metadata.get("requested_via"));
        assertEquals(method, metadata.get("request_method"));
        assertEquals(path, metadata.get("request_path"));
        assertFalse(Boolean.TRUE.equals(metadata.get("legacy_control_route")));
        if (reason != null) {
            assertEquals(reason, metadata.get("reason"));
        }
    }

    private void assertLegacyHttpMetadata(Map<String, Object> metadata, String method, String path, String reason) {
        assertNotNull(metadata);
        assertEquals("http_api", metadata.get("requested_via"));
        assertEquals(method, metadata.get("request_method"));
        assertEquals(path, metadata.get("request_path"));
        assertEquals(Boolean.TRUE, metadata.get("legacy_control_route"));
        if (reason != null) {
            assertEquals(reason, metadata.get("reason"));
        }
    }

    private void assertDeprecatedGetHeaders(ApiCall response, String path) {
        assertEquals("true", response.header("Deprecation"));
        assertEquals(NioHttpServer.LEGACY_WRITE_ROUTE_SUNSET, response.header("Sunset"));
        assertEquals("POST", response.header("X-AgentCloud-Replacement-Method"));
        assertEquals("<" + path + ">; rel=\"alternate\"; title=\"Use POST\"", response.header("Link"));
        assertTrue(response.header("Warning").contains("Use POST " + path));
    }

    private static final class HttpFixture implements AutoCloseable {
        private final DatabaseManager db;
        private final TaskService taskService;
        private final SessionService sessionService;
        private final SessionMessageDao sessionMessageDao;
        private final EventDao eventDao;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final String baseUrl;

        private HttpFixture(Path dbPath) throws IOException {
            this(dbPath, null);
        }

        private HttpFixture(Path dbPath, String pauseFailureMessage) throws IOException {
            this.db = new DatabaseManager(dbPath);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            this.eventDao = db.jdbi().onDemand(EventDao.class);
            this.sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
            ControlNodeGraph graph = stubGraph(taskDao, pauseFailureMessage);

            this.taskService = new TaskService(
                taskDao, sessionDao, eventDao, null, null, null, graph,
                null, null, null, null, null, sessionMessageDao
            );
            this.sessionService = new SessionService(sessionDao, taskDao, sessionMessageDao, eventDao);
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            this.server.setExecutor(executor);
            this.server.createContext("/api/v1/tasks", new TaskHandler(taskService, NioHttpServer.SHARED_MAPPER));
            this.server.createContext("/api/v1/sessions", new SessionHandler(sessionService, NioHttpServer.SHARED_MAPPER));
            this.server.start();
            this.client = HttpClient.newHttpClient();
            this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private Task createManualTask(String title) {
            return taskService.createTask(new TaskCreateRequest(
                title,
                "continuation",
                "user",
                "high",
                "验证 HTTP control route",
                "保证 control action event/message 带上 request metadata",
                null,
                null,
                Map.of(),
                false
            ));
        }

        private Session createSession(String title) {
            return sessionService.createSession(title);
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

        private SessionMessage findTaskActionMessage(String sessionId, String taskId, String action) {
            return sessionMessages(sessionId, taskId).stream()
                .filter(message -> "task_action".equals(message.messageType()))
                .filter(message -> action.equals(stringValue(message.metadata(), "action")))
                .findFirst()
                .orElseThrow();
        }

        private SessionMessage findMessage(String sessionId, String taskId, String messageType) {
            return sessionMessages(sessionId, taskId).stream()
                .filter(message -> messageType.equals(message.messageType()))
                .findFirst()
                .orElseThrow();
        }

        private SessionMessage findTaskStateMessage(String sessionId, String taskId, String currentState) {
            return sessionMessages(sessionId, taskId).stream()
                .filter(message -> "task_state".equals(message.messageType()))
                .filter(message -> currentState.equals(stringValue(message.metadata(), "current_state")))
                .findFirst()
                .orElseThrow();
        }

        private boolean hasTaskStateMessage(String sessionId, String taskId) {
            return sessionMessages(sessionId, taskId).stream()
                .anyMatch(message -> "task_state".equals(message.messageType()));
        }

        private Event findControlActionEvent(String sessionId, String taskId, String action) {
            return eventDao.listBySessionAndTask(sessionId, taskId, 20).stream()
                .filter(event -> "task_control_action".equals(event.eventType()))
                .filter(event -> action.equals(stringValue(event.payload(), "action")))
                .findFirst()
                .orElseThrow();
        }

        private Event findTaskStateEvent(String sessionId, String taskId, String currentState) {
            return eventDao.listBySessionAndTask(sessionId, taskId, 20).stream()
                .filter(event -> "task_state_changed".equals(event.eventType()))
                .filter(event -> currentState.equals(stringValue(event.payload(), "current_state")))
                .findFirst()
                .orElseThrow();
        }

        private boolean hasTaskStateEvent(String sessionId, String taskId) {
            return eventDao.listBySessionAndTask(sessionId, taskId, 20).stream()
                .anyMatch(event -> "task_state_changed".equals(event.eventType()));
        }

        private List<SessionMessage> sessionMessages(String sessionId, String taskId) {
            return sessionMessageDao.listBySessionAndTask(sessionId, taskId, 20);
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

        private ControlNodeGraph stubGraph(TaskDao taskDao, String pauseFailureMessage) {
            return new ControlNodeGraph(
                taskDao, null, null, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task enter(Task task) {
                    Task updated = task.withStatus("active")
                        .withControlNode("scheduler")
                        .withSummary("继续推进草稿，已补出本轮主线。")
                        .withNextStep("继续扩写第二段。")
                        .withAssignedWorker("kimi-local-doc")
                        .withWaitingReason(null);
                    taskDao.updateState(updated);
                    return updated;
                }

                @Override
                public Task triggerPause(Task task, String reason) {
                    if (pauseFailureMessage != null) {
                        throw new IllegalStateException(pauseFailureMessage);
                    }
                    Task updated = task.withStatus("paused")
                        .withControlNode("packet")
                        .withWaitingReason(reason)
                        .withSummary("暂停等待 review。")
                        .withNextStep("review 完成后再 resume。");
                    taskDao.updateState(updated);
                    return updated;
                }

                @Override
                public Task triggerResume(Task task) {
                    Task updated = task.withStatus("active")
                        .withControlNode("scheduler")
                        .withWaitingReason(null)
                        .withSummary("已恢复执行，继续整理主线。")
                        .withNextStep("继续扩写第二段。")
                        .withAssignedWorker("kimi-local-doc");
                    taskDao.updateState(updated);
                    return updated;
                }

                @Override
                public Task triggerEscalate(Task task, String reason) {
                    Task updated = task.withStatus("waiting_human")
                        .withControlNode("human_gate")
                        .withWaitingReason(reason)
                        .withSummary("已进入人工确认。")
                        .withNextStep("等待人工审批。");
                    taskDao.updateState(updated);
                    return updated;
                }
            };
        }
    }

    private record ApiCall(int statusCode, HttpHeaders headers, JsonNode body) {
        private String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }
}
