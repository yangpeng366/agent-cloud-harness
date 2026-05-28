package com.agentcloud.server;

import com.agentcloud.model.AgentAction;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.store.AgentActionDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentActionHandlerHttpTest {
    private static final ObjectMapper MAPPER = NioHttpServer.SHARED_MAPPER;

    @TempDir
    Path tempDir;

    @Test
    void listAndFetchAgentActions() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("agent-actions-http.db"))) {
            AgentAction action = action("act_http_1", "accepted", "WRITE_ARTIFACT");
            fixture.agentActionDao.insert(action);

            ApiCall list = fixture.get("/api/v1/agent_actions?task_id=task_http_1");
            assertEquals(200, list.statusCode());
            assertEquals("act_http_1", list.body().path("data").get(0).path("id").asText());
            assertEquals("write report", list.body().path("data").get(0).path("summary").asText());

            ApiCall byId = fixture.get("/api/v1/agent_actions/act_http_1");
            assertEquals(200, byId.statusCode());
            assertEquals("WRITE_ARTIFACT", byId.body().path("data").path("action_type").asText());
        }
    }

    @Test
    void listRequiresFilter() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("agent-actions-filter.db"))) {
            ApiCall response = fixture.get("/api/v1/agent_actions");

            assertEquals(400, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("task_id, session_id, action_type, or status is required", response.body().path("message").asText());
        }
    }

    private AgentAction action(String id, String status, String type) {
        return new AgentAction(
            id,
            "session_http_1",
            "task_http_1",
            "exec_http_1",
            type,
            status,
            "write report",
            Map.of("title", "Report", "content", "done"),
            "low",
            false,
            "runtime_policy",
            "",
            Instant.parse("2026-05-27T00:00:00Z"),
            Instant.parse("2026-05-27T00:00:00Z"),
            Map.of("source", "test")
        );
    }

    private static final class HttpFixture implements AutoCloseable {
        private final DatabaseManager db;
        private final AgentActionDao agentActionDao;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client = HttpClient.newHttpClient();
        private final String baseUrl;

        private HttpFixture(Path dbPath) throws IOException {
            this.db = new DatabaseManager(dbPath);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            sessionDao.insert(new Session(
                "session_http_1",
                "agent action session",
                "open",
                Instant.parse("2026-05-27T00:00:00Z"),
                Instant.parse("2026-05-27T00:00:00Z"),
                null,
                null,
                null,
                null,
                Map.of()
            ));
            taskDao.insert(new Task(
                "task_http_1",
                "session_http_1",
                null,
                "agent action task",
                "active",
                "high",
                Instant.parse("2026-05-27T00:00:00Z"),
                Instant.parse("2026-05-27T00:00:00Z"),
                null,
                null,
                null,
                "summary",
                "goal",
                "next",
                "codex",
                "continue",
                null,
                Map.of("task_type", "coding")
            ));
            this.agentActionDao = db.jdbi().onDemand(AgentActionDao.class);
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = Executors.newSingleThreadExecutor();
            server.setExecutor(executor);
            server.createContext("/api/v1/agent_actions", new AgentActionHandler(agentActionDao));
            server.start();
            this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private ApiCall get(String path) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiCall(response.statusCode(), MAPPER.readTree(response.body()));
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            db.close();
        }
    }

    private record ApiCall(int statusCode, JsonNode body) {}
}
