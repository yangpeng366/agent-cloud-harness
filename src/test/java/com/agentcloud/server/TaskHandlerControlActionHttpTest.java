package com.agentcloud.server;

import com.agentcloud.engine.ControlNodeGraph;
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
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskHandlerControlActionHttpTest {

    @TempDir
    Path tempDir;

    @Test
    void postCreateTaskProjectsHttpMetadataToReceiptAndCreatedEvent() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-create.db"))) {
            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "title":"http create",
                          "task_type":"continuation",
                          "source":"user",
                          "priority":"high",
                          "intent":"创建一个带 HTTP 审计信息的任务",
                          "goal":"等待创建回执",
                          "auto_start":false
                        }
                        """))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));
            String taskId = data.get("id").toString();

            assertEquals(200, response.statusCode());
            assertEquals("active", data.get("status"));
            assertNotNull(taskId);

            Task persisted = harness.taskDao.findById(taskId).orElseThrow();
            List<SessionMessage> messages = harness.messageDao.listBySessionAndTask(persisted.sessionId(), persisted.id(), 20);
            SessionMessage receiptMessage = messages.getFirst();
            assertEquals("task_receipt", receiptMessage.messageType());
            assertEquals("http_api", receiptMessage.metadata().get("requested_via"));
            assertEquals("POST", receiptMessage.metadata().get("request_method"));
            assertEquals("/api/v1/tasks", receiptMessage.metadata().get("request_path"));
            assertFalse(receiptMessage.metadata().containsKey("legacy_control_route"));

            Event createdEvent = harness.eventDao.listBySessionAndTask(persisted.sessionId(), persisted.id(), 20).stream()
                .filter(event -> "task_created".equals(event.eventType()))
                .findFirst()
                .orElseThrow();
            assertEquals("http_api", createdEvent.payload().get("requested_via"));
            assertEquals("POST", createdEvent.payload().get("request_method"));
            assertEquals("/api/v1/tasks", createdEvent.payload().get("request_path"));
        }
    }

    @Test
    void postPauseUsesFormalWriteRouteAndReasonBody() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-post.db"))) {
            Task task = harness.createManualTask("post pause");

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/pause"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"needs review\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));
            assertEquals(200, response.statusCode());
            assertEquals("pause", data.get("decision"));
            assertEquals("needs review", data.get("reason"));

            Task persisted = harness.taskDao.findById(task.id()).orElseThrow();
            assertEquals("paused", persisted.status());
            assertEquals("packet", persisted.controlNode());
            assertEquals("needs review", persisted.waitingReason());

            List<SessionMessage> messages = harness.messageDao.listBySessionAndTask(task.sessionId(), task.id(), 20);
            SessionMessage actionMessage = messages.get(1);
            assertEquals("POST", actionMessage.metadata().get("request_method"));
            assertEquals("http_api", actionMessage.metadata().get("requested_via"));
            assertFalse(actionMessage.metadata().containsKey("legacy_control_route"));
        }
    }

    @Test
    void legacyGetPauseStillWorksAndIsMarkedForAudit() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-get.db"))) {
            Task task = harness.createManualTask("legacy pause");

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/pause"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));
            assertEquals(200, response.statusCode());
            assertEquals("pause", data.get("decision"));

            List<SessionMessage> messages = harness.messageDao.listBySessionAndTask(task.sessionId(), task.id(), 20);
            SessionMessage actionMessage = messages.get(1);
            assertEquals("GET", actionMessage.metadata().get("request_method"));
            assertEquals(Boolean.TRUE, actionMessage.metadata().get("legacy_control_route"));
            assertTrue(actionMessage.content().contains("pause"));
        }
    }

    @Test
    void listTasksAcceptsStatusAndLegacyStateQueryParams() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-list-status.db"))) {
            Task paused = harness.createManualTask("paused coding", "coding");
            Task active = harness.createManualTask("active coding", "coding");
            harness.saveTask(paused.withStatus("paused").withAssignedWorker("codex"));
            harness.saveTask(active.withAssignedWorker("codex"));

            HttpResponse<String> statusResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks?status=paused"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> stateResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks?state=paused"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> statusPayload = harness.readJson(statusResponse.body());
            Map<String, Object> statePayload = harness.readJson(stateResponse.body());
            List<Map<String, Object>> statusTasks = harness.list(statusPayload.get("data"));
            List<Map<String, Object>> stateTasks = harness.list(statePayload.get("data"));

            assertEquals(200, statusResponse.statusCode());
            assertEquals(200, stateResponse.statusCode());
            assertEquals(1, statusTasks.size());
            assertEquals(1, stateTasks.size());
            assertEquals(paused.id(), statusTasks.getFirst().get("id"));
            assertEquals(paused.id(), stateTasks.getFirst().get("id"));
        }
    }

    @Test
    void listTasksStillFiltersByTaskTypeAndAssignedWorker() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-list-filters.db"))) {
            Task codingCodex = harness.createManualTask("coding codex", "coding");
            Task codingKimi = harness.createManualTask("coding kimi", "coding");
            Task researchCodex = harness.createManualTask("research codex", "research");
            harness.saveTask(codingCodex.withStatus("paused").withAssignedWorker("codex"));
            harness.saveTask(codingKimi.withStatus("paused").withAssignedWorker("kimi"));
            harness.saveTask(researchCodex.withStatus("paused").withAssignedWorker("codex"));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks?status=paused&task_type=coding&assigned_worker=codex"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            List<Map<String, Object>> tasks = harness.list(payload.get("data"));

            assertEquals(200, response.statusCode());
            assertEquals(1, tasks.size());
            assertEquals(codingCodex.id(), tasks.getFirst().get("id"));
            assertEquals("codex", tasks.getFirst().get("assigned_worker"));
        }
    }

    @Test
    void postCreateTaskRejectsClosedSession() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-closed-session.db"))) {
            Session closedSession = harness.createClosedSession("closed session");

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "title":"closed session task",
                          "task_type":"continuation",
                          "source":"user",
                          "priority":"high",
                          "intent":"尝试向已关闭会话挂任务",
                          "goal":"应被拒绝",
                          "session_id":"%s",
                          "auto_start":false
                        }
                        """.formatted(closedSession.id())))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            assertEquals(400, response.statusCode());
            assertEquals(Boolean.FALSE, payload.get("success"));
            assertEquals("400", payload.get("code"));
            assertEquals("session is closed", payload.get("message"));
        }
    }

    private static final class TestHarness implements AutoCloseable {
        private final DatabaseManager db;
        private final TaskService service;
        private final TaskDao taskDao;
        private final SessionDao sessionDao;
        private final SessionMessageDao messageDao;
        private final EventDao eventDao;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final int port;

        private TestHarness(Path dbPath) throws IOException {
            this.db = new DatabaseManager(dbPath);
            this.taskDao = db.jdbi().onDemand(TaskDao.class);
            this.sessionDao = db.jdbi().onDemand(SessionDao.class);
            this.eventDao = db.jdbi().onDemand(EventDao.class);
            this.messageDao = db.jdbi().onDemand(SessionMessageDao.class);

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, this.eventDao, this.sessionDao, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task triggerPause(Task task, String reason) {
                    Task updated = task.withStatus("paused")
                        .withControlNode("packet")
                        .withAssignedWorker("codex")
                        .withWaitingReason(reason);
                    taskDao.updateState(updated);
                    return updated;
                }
            };

            this.service = new TaskService(
                taskDao, this.sessionDao, this.eventDao, null, null, null, graph,
                null, null, null, null, null, messageDao
            );
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = Executors.newCachedThreadPool();
            this.server.setExecutor(executor);
            this.server.createContext("/api/v1/tasks", new TaskHandler(service, NioHttpServer.SHARED_MAPPER));
            this.server.start();
            this.port = server.getAddress().getPort();
            this.client = HttpClient.newHttpClient();
        }

        private Task createManualTask(String title) {
            return createManualTask(title, "continuation");
        }

        private Task createManualTask(String title, String taskType) {
            return service.createTask(new TaskCreateRequest(
                title, taskType, "user", "high",
                "创建一个手动任务", "等待测试", null, null, Map.of(), false
            ));
        }

        private Session createClosedSession(String title) {
            Session session = Session.create("session_closed", title, "active");
            sessionDao.insert(session);
            Instant closedAt = Instant.now();
            sessionDao.updateState(session.id(), "closed", closedAt, closedAt, null, "Session closed");
            return sessionDao.findById(session.id()).orElseThrow();
        }

        private void saveTask(Task task) {
            taskDao.updateState(task);
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> readJson(String body) throws IOException {
            return NioHttpServer.SHARED_MAPPER.readValue(body, Map.class);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> map(Object value) {
            return (Map<String, Object>) value;
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> list(Object value) {
            return (List<Map<String, Object>>) value;
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            db.close();
        }
    }
}
