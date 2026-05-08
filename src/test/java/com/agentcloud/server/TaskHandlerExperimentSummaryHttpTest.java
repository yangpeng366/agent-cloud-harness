package com.agentcloud.server;

import com.agentcloud.engine.ExperimentMatrixService;
import com.agentcloud.engine.ExperimentRunService;
import com.agentcloud.engine.TaskService;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ExperimentRunDao;
import com.agentcloud.store.ResumePacketDao;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskHandlerExperimentSummaryHttpTest {

    @TempDir
    Path tempDir;

    @Test
    void taskExperimentSummaryEndpointReturnsModeComparisonForSelectedTask() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("task-experiment-summary.db"))) {
            Task strongTask = fixture.createExperimentTask("strong_only");
            Task smallTask = fixture.createExperimentTask("small_only");
            Task orchestratedTask = fixture.createExperimentTask("orchestrated");

            fixture.insertRouteArtifact(strongTask, Map.of(
                "route_source", "capability_match",
                "selected_model_tier", "strong",
                "prompt_mode", "active_context_only",
                "mounted_context_rendered", false,
                "mounted_context_injected", false
            ));
            fixture.insertRouteArtifact(smallTask, Map.of(
                "route_source", "capability_match",
                "preferred_worker_hint", "codex",
                "learning_hint_applied", false,
                "fallback_reason", "hint filtered by model tier",
                "selected_model_tier", "small",
                "prompt_mode", "mounted_context_shadow",
                "mounted_context_rendered", true,
                "mounted_context_injected", false,
                "mounted_context_panel_count", 5
            ));
            fixture.insertRouteArtifact(orchestratedTask, Map.of(
                "route_source", "learning_memory",
                "preferred_worker_hint", "kimi",
                "learning_hint_applied", true,
                "fallback_reason", "hint survived tier filter",
                "selected_model_tier", "small",
                "prompt_mode", "mounted_context_primary",
                "mounted_context_rendered", true,
                "mounted_context_injected", true,
                "mounted_context_panel_count", 7
            ));

            fixture.service.updateTaskState(strongTask.id(), "done", "baseline strong completed");
            fixture.service.updateTaskState(smallTask.id(), "failed", "baseline small stalled");
            fixture.service.updateTaskState(orchestratedTask.id(), "waiting_human", "baseline orchestration needs review");

            HttpResponse<String> response = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri("/api/v1/tasks/" + orchestratedTask.id() + "/experiment_summary"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JsonNode body = NioHttpServer.SHARED_MAPPER.readTree(response.body());
            assertEquals(200, response.statusCode());
            assertTrue(body.path("success").asBoolean());
            assertEquals("baseline-console", body.path("data").path("experiment_name").asText());
            assertEquals(3, body.path("data").path("mode_summaries").size());
            JsonNode orchestratedSummary = body.path("data").path("mode_summaries").get(2);
            assertEquals("orchestrated", orchestratedSummary.path("model_mode").asText());
            assertEquals(1, orchestratedSummary.path("learning_hint_applied_count").asInt());
            assertEquals(1.0, orchestratedSummary.path("learning_hint_applied_rate").asDouble());
            assertEquals(1, orchestratedSummary.path("runs_with_task_surface_refs").asInt());
            assertEquals(0, orchestratedSummary.path("runs_with_judgment_surface_refs").asInt());
            assertEquals(0, orchestratedSummary.path("runs_with_tool_trace_surface_refs").asInt());
            assertEquals(1, orchestratedSummary.path("route_source_counts").path("learning_memory").asInt());
            assertEquals(1, orchestratedSummary.path("runs_with_prompt_mode_data").asInt());
            assertEquals(1, orchestratedSummary.path("prompt_mode_counts").path("mounted_context_primary").asInt());
            assertEquals(1, orchestratedSummary.path("runs_with_mounted_context_rendered").asInt());
            assertEquals(1, orchestratedSummary.path("runs_with_mounted_context_injected").asInt());
            assertEquals(1.0, orchestratedSummary.path("mounted_context_rendered_rate").asDouble());
            assertEquals(1.0, orchestratedSummary.path("mounted_context_injected_rate").asDouble());
            assertEquals(7.0, orchestratedSummary.path("average_mounted_context_panel_count").asDouble());
            assertEquals("needs_followup",
                body.path("data").path("case_comparisons").get(0).path("runs_by_mode").path("orchestrated").path("acceptance_result").asText());
        }
    }

    @Test
    void taskExperimentSummaryEndpointReturnsNotFoundForAdHocTask() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("task-experiment-summary-missing.db"))) {
            Task task = fixture.service.createTask(new TaskCreateRequest(
                "ad hoc task", "coding", "user", "high",
                "no experiment metadata", "verify 404", null, null, Map.of(), false
            ));

            HttpResponse<String> response = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri("/api/v1/tasks/" + task.id() + "/experiment_summary"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JsonNode body = NioHttpServer.SHARED_MAPPER.readTree(response.body());
            assertEquals(404, response.statusCode());
            assertFalse(body.path("success").asBoolean(true));
            assertEquals("not found", body.path("message").asText());
        }
    }

    private static final class HttpFixture implements AutoCloseable {
        private final DatabaseManager db;
        private final TaskService service;
        private final ArtifactDao artifactDao;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final int port;

        private HttpFixture(Path dbPath) throws IOException {
            this.db = new DatabaseManager(dbPath);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            this.artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            ExperimentRunDao experimentRunDao = db.jdbi().onDemand(ExperimentRunDao.class);

            ExperimentRunService experimentRunService = new ExperimentRunService(
                experimentRunDao,
                decisionDao,
                artifactDao,
                eventDao,
                toolInvocationDao
            );
            ExperimentMatrixService experimentMatrixService = new ExperimentMatrixService(null, experimentRunService);

            this.service = new TaskService(
                taskDao,
                sessionDao,
                eventDao,
                packetDao,
                new WorkerRouter(new WorkerRegistry()),
                null,
                null,
                null,
                new TaskRuntimeContextBuilder(null, null, null, null, null, null, null),
                null,
                null,
                toolInvocationDao,
                null,
                experimentRunService
            );

            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            this.server.setExecutor(executor);
            this.server.createContext("/api/v1/tasks",
                new TaskHandler(service, experimentMatrixService, NioHttpServer.SHARED_MAPPER));
            this.server.start();
            this.client = HttpClient.newHttpClient();
            this.port = server.getAddress().getPort();
        }

        private Task createExperimentTask(String modelMode) {
            return service.createTask(new TaskCreateRequest(
                "matrix " + modelMode,
                "coding",
                "user",
                "high",
                "experiment route visibility " + modelMode,
                "collect route metrics",
                null,
                null,
                Map.of(
                    "experiment_name", "baseline-console",
                    "task_case_key", "case-001",
                    "task_length_bucket", "short",
                    "model_mode", modelMode,
                    "task_type", "coding"
                ),
                false
            ));
        }

        private void insertRouteArtifact(Task task, Map<String, Object> metadata) {
            artifactDao.insert(new Artifact(
                com.agentcloud.engine.IdGenerator.newId("art"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_artifact",
                "route artifact for " + task.id(),
                null,
                null,
                "seed route metadata for experiment summary",
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
