package com.agentcloud.server;

import com.agentcloud.engine.ControlNodeGraph;
import com.agentcloud.engine.SessionService;
import com.agentcloud.engine.SkillRegistry;
import com.agentcloud.engine.TaskService;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.SkillDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.tool.HostToolAvailability;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiErrorContractHttpTest {

    @TempDir
    Path tempDir;

    @Test
    void missingTaskControlActionReturnsStable404() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("missing-task.db"))) {
            ApiCall response = fixture.postJson("/api/v1/tasks/task-missing/pause", Map.of("reason", "missing"));

            assertEquals(404, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("404", response.body().path("code").asText());
            assertEquals("not found", response.body().path("message").asText());
        }
    }

    @Test
    void missingSessionMessagesReturnsStable404() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("missing-session.db"))) {
            ApiCall response = fixture.get("/api/v1/sessions/session-missing/messages");

            assertEquals(404, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("404", response.body().path("code").asText());
            assertEquals("not found", response.body().path("message").asText());
        }
    }

    @Test
    void missingSessionMessagePostReturnsStable404() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("missing-session-post.db"))) {
            ApiCall response = fixture.postJson(
                "/api/v1/sessions/session-missing/messages",
                Map.of("role", "user", "content", "missing session")
            );

            assertEquals(404, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("404", response.body().path("code").asText());
            assertEquals("not found", response.body().path("message").asText());
        }
    }

    @Test
    void missingSessionCloseReturnsStable404() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("missing-session-close.db"))) {
            ApiCall response = fixture.send("POST", "/api/v1/sessions/session-missing/close", "", "application/json");

            assertEquals(404, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("404", response.body().path("code").asText());
            assertEquals("not found", response.body().path("message").asText());
        }
    }

    @Test
    void missingSessionPauseReturnsStable404() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("missing-session-pause.db"))) {
            ApiCall response = fixture.send("POST", "/api/v1/sessions/session-missing/pause", "", "application/json");

            assertEquals(404, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("404", response.body().path("code").asText());
            assertEquals("not found", response.body().path("message").asText());
        }
    }

    @Test
    void getSessionPauseReturnsStable405() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("session-get-pause-method.db"))) {
            ApiCall response = fixture.send("GET", "/api/v1/sessions/session-any/pause", "", "application/json");

            assertEquals(405, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("405", response.body().path("code").asText());
            assertEquals("method not allowed", response.body().path("message").asText());
        }
    }

    @Test
    void invalidJsonReturnsStable400() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("invalid-json.db"))) {
            ApiCall response = fixture.postRaw(
                "/api/v1/workers",
                "{\"worker_id\":\"broken\",",
                "application/json"
            );

            assertEquals(400, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("400", response.body().path("code").asText());
            assertEquals("invalid json body", response.body().path("message").asText());
        }
    }

    @Test
    void workerRegistrationAcceptsSupportedCommandToolCapabilitiesForCurrentHost() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("worker-tools.db"))) {
            List<String> toolCapabilities = supportedCommandToolCapabilities();
            ApiCall response = fixture.postJson("/api/v1/workers", Map.of(
                "worker_id", "command-worker",
                "worker_type", "codex",
                "capabilities", java.util.List.of("ops"),
                "tool_capabilities", toolCapabilities,
                "tool_scope", java.util.List.of(tempDir.toString())
            ));

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertTrue(response.body().path("data").path("tool_capabilities").isArray());
            assertEquals(toolCapabilities.size(), response.body().path("data").path("tool_capabilities").size());
        }
    }

    @Test
    void workerReadinessReportsToolChecksForDeclaredCommandCapabilities() throws Exception {
        List<String> toolCapabilities = supportedCommandToolCapabilities();
        Assumptions.assumeFalse(toolCapabilities.isEmpty(), "no supported command tool available on this host");
        String toolCapability = toolCapabilities.get(0);

        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("worker-readiness-tools.db"))) {
            ApiCall registration = fixture.postJson("/api/v1/workers", Map.of(
                "worker_id", "ready-worker",
                "worker_type", "codex",
                "capabilities", java.util.List.of("ops"),
                "tool_capabilities", java.util.List.of(toolCapability),
                "tool_scope", java.util.List.of(tempDir.toString())
            ));

            assertEquals(200, registration.statusCode());
            assertTrue(registration.body().path("data").path("metadata").path("host_tool_availability")
                .path(toolCapability).asBoolean());

            ApiCall readiness = fixture.get("/api/v1/workers/ready-worker/readiness");

            assertEquals(200, readiness.statusCode());
            assertTrue(readiness.body().path("success").asBoolean());
            assertTrue(readiness.body().path("data").path("ready").asBoolean());
            assertTrue(readiness.body().path("data").path("checks").path("tool:" + toolCapability).asBoolean());
            assertEquals("ready", readiness.body().path("data").path("reason").asText());
        }
    }

    @Test
    void workerRegistrationRejectsWindowsOnlyToolCapabilityOnNonWindowsHost() throws Exception {
        if (HostToolAvailability.isWindowsHost()) {
            return;
        }
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("worker-tools-nonwindows.db"))) {
            ApiCall response = fixture.postJson("/api/v1/workers", Map.of(
                "worker_id", "command-worker",
                "worker_type", "codex",
                "capabilities", java.util.List.of("ops"),
                "tool_capabilities", java.util.List.of("powershell"),
                "tool_scope", java.util.List.of(tempDir.toString())
            ));

            assertEquals(400, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("powershell is only available on Windows hosts", response.body().path("message").asText());
        }
    }

    @Test
    void workerRegistrationRejectsGitToolCapabilityWhenGitUnavailable() throws Exception {
        if (HostToolAvailability.isToolCapabilityAvailable("git")) {
            return;
        }
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("worker-tools-nogit.db"))) {
            ApiCall response = fixture.postJson("/api/v1/workers", Map.of(
                "worker_id", "command-worker",
                "worker_type", "codex",
                "capabilities", java.util.List.of("ops"),
                "tool_capabilities", java.util.List.of("git"),
                "tool_scope", java.util.List.of(tempDir.toString())
            ));

            assertEquals(400, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("git is not available on this host", response.body().path("message").asText());
        }
    }

    @Test
    void workerRegistrationAcceptsPatchFileToolCapability() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("worker-patch-tool.db"))) {
            ApiCall response = fixture.postJson("/api/v1/workers", Map.of(
                "worker_id", "patch-worker",
                "worker_type", "codex",
                "capabilities", java.util.List.of("coding"),
                "tool_capabilities", java.util.List.of("patch_file"),
                "tool_scope", java.util.List.of(tempDir.toString())
            ));

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("patch_file", response.body().path("data").path("tool_capabilities").get(0).asText());
        }
    }

    @Test
    void workerRegistrationRequiresToolScopeWhenToolCapabilitiesAreDeclared() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("worker-scope-required.db"))) {
            ApiCall response = fixture.postJson("/api/v1/workers", Map.of(
                "worker_id", "scope-missing-worker",
                "worker_type", "codex",
                "capabilities", java.util.List.of("coding"),
                "tool_capabilities", java.util.List.of("patch_file")
            ));

            assertEquals(400, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("tool_scope is required when tool_capabilities are declared",
                response.body().path("message").asText());
        }
    }

    @Test
    void skillRegistrationRequiresName() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("skill-name.db"))) {
            ApiCall response = fixture.postJson("/api/v1/skills", Map.of("description", "missing name"));

            assertEquals(400, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("400", response.body().path("code").asText());
            assertEquals("name is required", response.body().path("message").asText());
        }
    }

    @Test
    void unsupportedMethodReturnsStable405() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("method-not-allowed.db"))) {
            ApiCall response = fixture.send("DELETE", "/api/v1/workers", "", "application/json");

            assertEquals(405, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("405", response.body().path("code").asText());
            assertEquals("method not allowed", response.body().path("message").asText());
        }
    }

    @Test
    void missingExperimentRunTaskReturnsStable404() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("missing-experiment-run.db"))) {
            ApiCall response = fixture.get("/api/v1/experiment_runs/task-missing");

            assertEquals(404, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("404", response.body().path("code").asText());
            assertEquals("not found", response.body().path("message").asText());
        }
    }

    private static final class HttpFixture implements AutoCloseable {
        private final DatabaseManager db;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final String baseUrl;

        private HttpFixture(Path dbPath) throws IOException {
            this.db = new DatabaseManager(dbPath);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
            SkillDao skillDao = db.jdbi().onDemand(SkillDao.class);

            TaskService taskService = new TaskService(
                taskDao,
                sessionDao,
                eventDao,
                null,
                null,
                null,
                new ControlNodeGraph(taskDao, eventDao, sessionDao, null, null, null, null,
                    null, null, null, null, null, null),
                null,
                null,
                null,
                null,
                null
            );
            SessionService sessionService = new SessionService(sessionDao, taskDao, sessionMessageDao, eventDao);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            SkillRegistry skillRegistry = new SkillRegistry(skillDao);

            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            this.server.setExecutor(executor);
            this.server.createContext("/api/v1/tasks", new TaskHandler(taskService, NioHttpServer.SHARED_MAPPER));
            this.server.createContext("/api/v1/sessions", new SessionHandler(sessionService, NioHttpServer.SHARED_MAPPER));
            this.server.createContext("/api/v1/workers", new WorkerHandler(workerRegistry, NioHttpServer.SHARED_MAPPER));
            this.server.createContext("/api/v1/skills", new SkillHandler(skillRegistry, NioHttpServer.SHARED_MAPPER));
            this.server.createContext("/api/v1/experiment_runs", new ExperimentRunHandler(taskService, null));
            this.server.start();
            this.client = HttpClient.newHttpClient();
            this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private ApiCall get(String path) throws IOException, InterruptedException {
            return send("GET", path, "", "application/json");
        }

        private ApiCall postJson(String path, Map<String, Object> body) throws IOException, InterruptedException {
            return send(
                "POST",
                path,
                NioHttpServer.SHARED_MAPPER.writeValueAsString(body),
                "application/json"
            );
        }

        private ApiCall postRaw(String path, String body, String contentType) throws IOException, InterruptedException {
            return send("POST", path, body, contentType);
        }

        private ApiCall send(String method, String path, String body, String contentType) throws IOException, InterruptedException {
            HttpRequest.BodyPublisher publisher = body == null || body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
            if (contentType != null && !contentType.isBlank()) {
                builder.header("Content-Type", contentType);
            }
            HttpRequest request = builder.method(method, publisher).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiCall(response.statusCode(), NioHttpServer.SHARED_MAPPER.readTree(response.body()));
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            db.close();
        }
    }

    private record ApiCall(int statusCode, JsonNode body) {
    }

    private List<String> supportedCommandToolCapabilities() {
        return HostToolAvailability.supportedCommandToolCapabilities();
    }
}
