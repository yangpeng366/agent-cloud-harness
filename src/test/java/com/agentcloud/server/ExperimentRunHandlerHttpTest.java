package com.agentcloud.server;

import com.agentcloud.engine.ExperimentRunService;
import com.agentcloud.model.ExperimentRunRecord;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ExperimentRunDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.store.ToolInvocationDao;
import com.fasterxml.jackson.databind.JsonNode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentRunHandlerHttpTest {

    @TempDir
    Path tempDir;

    @Test
    void listEndpointSupportsToolChainFilters() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("experiment-run-http.db"))) {
            Instant now = Instant.now();
            fixture.seedRun(
                "session_1",
                "task_1",
                "two-step multi tool",
                "orchestrated",
                "done",
                now,
                Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "tool_chain_step_count", 2,
                    "tool_chain_termination_reason", "planner_no_additional_tool"
                )
            );
            fixture.seedRun(
                "session_2",
                "task_2",
                "guarded multi tool",
                "small_only",
                "failed",
                now.plusSeconds(1),
                Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "tool_chain_step_count", 4,
                    "tool_chain_termination_reason", "repeated_tool_guard"
                )
            );
            fixture.seedRun(
                "session_3",
                "task_3",
                "single tool",
                "strong_only",
                "done",
                now.plusSeconds(2),
                Map.of(
                    "tool_execution_mode", "single_tool_round",
                    "tool_chain_step_count", 1
                )
            );

            HttpResponse<String> response = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri(
                    "/api/v1/experiment_runs?experiment_name=baseline-http"
                        + "&tool_execution_mode=multi_tool_round"
                        + "&tool_chain_termination_reason=repeated_tool_guard"
                        + "&min_tool_chain_steps=3"
                        + "&max_tool_chain_steps=5"
                ))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JsonNode body = NioHttpServer.SHARED_MAPPER.readTree(response.body());
            assertEquals(200, response.statusCode());
            assertTrue(body.path("success").asBoolean());
            assertEquals(1, body.path("data").size());
            assertEquals("task_2", body.path("data").get(0).path("task_id").asText());
            assertEquals("repeated_tool_guard",
                body.path("data").get(0).path("metadata").path("tool_chain_termination_reason").asText());
        }
    }

    private static final class HttpFixture implements AutoCloseable {
        private final DatabaseManager db;
        private final SessionDao sessionDao;
        private final TaskDao taskDao;
        private final ExperimentRunDao experimentRunDao;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final int port;

        private HttpFixture(Path dbPath) throws IOException {
            this.db = new DatabaseManager(dbPath);
            this.sessionDao = db.jdbi().onDemand(SessionDao.class);
            this.taskDao = db.jdbi().onDemand(TaskDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            this.experimentRunDao = db.jdbi().onDemand(ExperimentRunDao.class);

            ExperimentRunService experimentRunService = new ExperimentRunService(
                experimentRunDao,
                decisionDao,
                artifactDao,
                eventDao,
                toolInvocationDao
            );

            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            this.server.setExecutor(executor);
            this.server.createContext("/api/v1/experiment_runs", new ExperimentRunHandler(null, experimentRunService));
            this.server.start();
            this.client = HttpClient.newHttpClient();
            this.port = server.getAddress().getPort();
        }

        private void seedRun(String sessionId,
                             String taskId,
                             String title,
                             String modelMode,
                             String status,
                             Instant updatedAt,
                             Map<String, Object> metadata) {
            Instant createdAt = updatedAt.minusSeconds(10);
            sessionDao.insert(Session.create(sessionId, title + " session", "active"));
            taskDao.insert(new Task(
                taskId,
                sessionId,
                null,
                title,
                status,
                "high",
                createdAt,
                createdAt,
                createdAt,
                updatedAt,
                null,
                title,
                "http filter regression",
                null,
                "kimi",
                "end",
                "failed".equalsIgnoreCase(status) ? "guard tripped" : null,
                Map.of("task_type", "coding")
            ));
            experimentRunDao.upsert(new ExperimentRunRecord(
                "xrun_" + taskId,
                sessionId,
                taskId,
                "baseline-http",
                "case-" + taskId,
                title,
                "coding",
                "medium",
                modelMode,
                3,
                status,
                "failed".equalsIgnoreCase(status) ? "rejected" : "accepted",
                1.0,
                "strong_only".equals(modelMode) ? 1.0 : 0.0,
                0,
                0,
                0,
                "failed".equalsIgnoreCase(status) ? "guard tripped" : null,
                null,
                title,
                createdAt,
                updatedAt,
                metadata
            ));
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.close();
            db.close();
        }
    }
}
