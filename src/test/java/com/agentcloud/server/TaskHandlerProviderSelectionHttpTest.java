package com.agentcloud.server;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.providers.CodexProvider;
import com.agentcloud.agent.providers.OpenClawProvider;
import com.agentcloud.engine.AgentRunService;
import com.agentcloud.engine.ConsolidationService;
import com.agentcloud.engine.ExperimentRunService;
import com.agentcloud.engine.LearningMemoryService;
import com.agentcloud.engine.TaskService;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.AgentRunRecord;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Event;
import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.store.AgentRunDao;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.CheckpointDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ExperimentRunDao;
import com.agentcloud.store.LearningMemoryDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.SessionMessageDao;
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

class TaskHandlerProviderSelectionHttpTest {

    @TempDir
    Path tempDir;

    @Test
    void providerSelectionProjectsWorkerRouteToProviderView() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("provider-selection.db"))) {
            Task task = fixture.service.createTask(new TaskCreateRequest(
                "provider selection",
                "coding",
                "user",
                "high",
                "verify provider selection",
                "project selected worker to provider view",
                null,
                null,
                Map.of("model_mode", "strong_only"),
                false
            ));

            HttpResponse<String> response = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri("/api/v1/tasks/" + task.id() + "/provider_selection"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JsonNode body = NioHttpServer.SHARED_MAPPER.readTree(response.body());
            JsonNode data = body.path("data");
            assertEquals(200, response.statusCode());
            assertEquals(task.id(), data.path("task_id").asText());
            assertEquals("codex", data.path("selected_provider").asText());
            assertEquals("Codex", data.path("provider_display_name").asText());
            assertFalse(data.path("provider_ready").asBoolean(true));
            assertEquals("unknown", data.path("provider_auth_status").asText());
            assertEquals("planner_executor", data.path("worker_role").asText());
            assertEquals("codex", data.path("selected_worker_id").asText());
            assertEquals("strong", data.path("selected_model_tier").asText());
            assertEquals("codex", data.path("candidate_providers").get(0).asText());
            assertEquals("capability_match", data.path("metadata").path("route_source").asText());
            assertEquals("coding", data.path("metadata").path("task_type").asText());
        }
    }

    @Test
    void agentRunReturnsLatestPersistedProviderRun() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("agent-run.db"))) {
            Task task = fixture.service.createTask(new TaskCreateRequest(
                "agent run",
                "coding",
                "user",
                "high",
                "verify agent run",
                "read latest run",
                null,
                null,
                Map.of("model_mode", "strong_only"),
                false
            ));
            Instant startedAt = Instant.parse("2026-04-29T10:01:00Z");
            fixture.agentRunDao.insert(new AgentRunRecord(
                "arun_latest",
                task.id(),
                task.sessionId(),
                "codex",
                "Codex",
                "planner_executor",
                "codex",
                "strong",
                "completed",
                startedAt,
                startedAt.plusMillis(42),
                42L,
                "Generated patch and tests",
                "run.completed",
                1,
                Map.of("selected_model_tier", "strong")
            ));

            HttpResponse<String> response = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri("/api/v1/tasks/" + task.id() + "/agent_run"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JsonNode body = NioHttpServer.SHARED_MAPPER.readTree(response.body());
            JsonNode data = body.path("data");
            assertEquals(200, response.statusCode());
            assertEquals("arun_latest", data.path("run_id").asText());
            assertEquals(task.id(), data.path("task_id").asText());
            assertEquals(task.sessionId(), data.path("session_id").asText());
            assertEquals("codex", data.path("provider_id").asText());
            assertEquals("completed", data.path("status").asText());
            assertEquals(42L, data.path("duration_ms").asLong());
            assertEquals("strong", data.path("metadata").path("selected_model_tier").asText());
        }
    }

    @Test
    void agentRunDetailAndProviderRunsUsePersistedRecords() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("agent-run-detail.db"))) {
            Task task = fixture.service.createTask(new TaskCreateRequest(
                "agent run detail",
                "coding",
                "user",
                "high",
                "verify agent run detail",
                "read run by id",
                null,
                null,
                Map.of("model_mode", "strong_only"),
                false
            ));
            Instant startedAt = Instant.parse("2026-04-29T11:01:00Z");
            fixture.agentRunDao.insert(new AgentRunRecord(
                "arun_detail",
                task.id(),
                task.sessionId(),
                "codex",
                "Codex",
                "planner_executor",
                "codex",
                "strong",
                "completed",
                startedAt,
                startedAt.plusMillis(7),
                7L,
                "Provider run detail",
                "run.completed",
                0,
                Map.of()
            ));

            HttpResponse<String> detailResponse = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri("/api/v1/agent_runs/arun_detail"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            JsonNode detailData = NioHttpServer.SHARED_MAPPER.readTree(detailResponse.body()).path("data");
            assertEquals(200, detailResponse.statusCode());
            assertEquals("arun_detail", detailData.path("run_id").asText());
            assertEquals("codex", detailData.path("provider_id").asText());

            HttpResponse<String> listResponse = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri("/api/v1/agents/codex/runs?status=completed"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            JsonNode listData = NioHttpServer.SHARED_MAPPER.readTree(listResponse.body()).path("data");
            assertEquals(200, listResponse.statusCode());
            assertEquals(1, listData.size());
            assertEquals("arun_detail", listData.get(0).path("run_id").asText());
        }
    }

    @Test
    void providerRunsStatusFilterIsAppliedBeforeLimit() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("agent-run-status-filter.db"))) {
            Task task = fixture.service.createTask(new TaskCreateRequest(
                "agent run status filter",
                "coding",
                "user",
                "high",
                "verify provider run status filter",
                "filter status before applying limit",
                null,
                null,
                Map.of("model_mode", "strong_only"),
                false
            ));
            Instant startedAt = Instant.parse("2026-04-29T11:10:00Z");
            fixture.agentRunDao.insert(new AgentRunRecord(
                "arun_new_completed",
                task.id(),
                task.sessionId(),
                "codex",
                "Codex",
                "planner_executor",
                "codex",
                "strong",
                "completed",
                startedAt.plusSeconds(2),
                startedAt.plusSeconds(2).plusMillis(8),
                8L,
                "Newer completed run",
                "run.completed",
                0,
                Map.of()
            ));
            fixture.agentRunDao.insert(new AgentRunRecord(
                "arun_old_failed",
                task.id(),
                task.sessionId(),
                "codex",
                "Codex",
                "planner_executor",
                "codex",
                "strong",
                "failed",
                startedAt,
                startedAt.plusMillis(5),
                5L,
                "Older failed run",
                "run.failed",
                0,
                Map.of()
            ));

            HttpResponse<String> response = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri("/api/v1/agents/codex/runs?status=failed&limit=1"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JsonNode data = NioHttpServer.SHARED_MAPPER.readTree(response.body()).path("data");
            assertEquals(200, response.statusCode());
            assertEquals(1, data.size());
            assertEquals("arun_old_failed", data.get(0).path("run_id").asText());
            assertEquals("failed", data.get(0).path("status").asText());
        }
    }

    @Test
    void agentRunsSearchSupportsProviderStatusRoleAndTaskFilters() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("agent-run-search.db"))) {
            Task targetTask = fixture.service.createTask(new TaskCreateRequest(
                "agent run search target",
                "coding",
                "user",
                "high",
                "verify agent run search",
                "filter runs by provider status role and task",
                null,
                null,
                Map.of("model_mode", "strong_only"),
                false
            ));
            Task otherTask = fixture.service.createTask(new TaskCreateRequest(
                "agent run search other",
                "research",
                "user",
                "medium",
                "verify agent run search noise",
                "noise task",
                null,
                null,
                Map.of(),
                false
            ));
            Instant startedAt = Instant.parse("2026-04-29T11:20:00Z");
            fixture.agentRunDao.insert(new AgentRunRecord(
                "arun_search_target",
                targetTask.id(),
                targetTask.sessionId(),
                "codex",
                "Codex",
                "executor",
                "codex",
                "strong",
                "failed",
                startedAt.plusSeconds(3),
                startedAt.plusSeconds(3).plusMillis(10),
                10L,
                "Target failed executor run",
                "run.failed",
                0,
                Map.of()
            ));
            fixture.agentRunDao.insert(new AgentRunRecord(
                "arun_search_wrong_status",
                targetTask.id(),
                targetTask.sessionId(),
                "codex",
                "Codex",
                "executor",
                "codex",
                "strong",
                "completed",
                startedAt.plusSeconds(2),
                startedAt.plusSeconds(2).plusMillis(10),
                10L,
                "Completed run",
                "run.completed",
                0,
                Map.of()
            ));
            fixture.agentRunDao.insert(new AgentRunRecord(
                "arun_search_wrong_role",
                targetTask.id(),
                targetTask.sessionId(),
                "codex",
                "Codex",
                "planner",
                "codex",
                "strong",
                "failed",
                startedAt.plusSeconds(1),
                startedAt.plusSeconds(1).plusMillis(10),
                10L,
                "Planner run",
                "run.failed",
                0,
                Map.of()
            ));
            fixture.agentRunDao.insert(new AgentRunRecord(
                "arun_search_wrong_task",
                otherTask.id(),
                otherTask.sessionId(),
                "codex",
                "Codex",
                "executor",
                "codex",
                "strong",
                "failed",
                startedAt,
                startedAt.plusMillis(10),
                10L,
                "Other task run",
                "run.failed",
                0,
                Map.of()
            ));

            HttpResponse<String> response = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri("/api/v1/agent_runs?provider_id=codex&status=failed&role=executor&task_id="
                    + targetTask.id() + "&limit=10"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JsonNode data = NioHttpServer.SHARED_MAPPER.readTree(response.body()).path("data");
            assertEquals(200, response.statusCode());
            assertEquals(1, data.size());
            assertEquals("arun_search_target", data.get(0).path("run_id").asText());
            assertEquals(targetTask.id(), data.get(0).path("task_id").asText());
            assertEquals("executor", data.get(0).path("worker_role").asText());
        }
    }

    @Test
    void agentRunEventsAndArtifactsUsePersistedTaskRecords() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("agent-run-trace.db"))) {
            Task task = fixture.service.createTask(new TaskCreateRequest(
                "agent run trace",
                "coding",
                "user",
                "high",
                "verify agent run trace",
                "read events and artifacts",
                null,
                null,
                Map.of("model_mode", "strong_only"),
                false
            ));
            Instant startedAt = Instant.parse("2026-04-29T12:01:00Z");
            fixture.agentRunDao.insert(new AgentRunRecord(
                "arun_trace",
                task.id(),
                task.sessionId(),
                "codex",
                "Codex",
                "planner_executor",
                "codex",
                "strong",
                "completed",
                startedAt,
                startedAt.plusMillis(9),
                9L,
                "Provider run trace",
                "run.completed",
                1,
                Map.of()
            ));
            fixture.eventDao.insert(new Event(
                "evt_trace",
                task.sessionId(),
                task.id(),
                startedAt.plusMillis(1),
                "run.completed",
                "control_node",
                "codex",
                "Run completed",
                Map.of("agent_run_id", "arun_trace", "provider_id", "codex")
            ));
            fixture.artifactDao.insert(new Artifact(
                "art_trace",
                task.sessionId(),
                task.id(),
                startedAt.plusMillis(2),
                "diff",
                "Patch diff",
                "file:///tmp/patch.diff",
                null,
                "Patch artifact",
                Map.of("agent_run_id", "arun_trace", "provider_id", "codex")
            ));

            HttpResponse<String> eventsResponse = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri("/api/v1/agent_runs/arun_trace/events"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            JsonNode events = NioHttpServer.SHARED_MAPPER.readTree(eventsResponse.body()).path("data");
            assertEquals(200, eventsResponse.statusCode());
            assertEquals(1, events.size());
            assertEquals("evt_trace", events.get(0).path("event_id").asText());
            assertEquals("arun_trace", events.get(0).path("run_id").asText());
            assertEquals("run.completed", events.get(0).path("event_type").asText());
            assertEquals("codex", events.get(0).path("payload").path("provider_id").asText());

            HttpResponse<String> artifactsResponse = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri("/api/v1/agent_runs/arun_trace/artifacts"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            JsonNode artifacts = NioHttpServer.SHARED_MAPPER.readTree(artifactsResponse.body()).path("data");
            assertEquals(200, artifactsResponse.statusCode());
            assertEquals(1, artifacts.size());
            assertEquals("art_trace", artifacts.get(0).path("artifact_id").asText());
            assertEquals("arun_trace", artifacts.get(0).path("run_id").asText());
            assertEquals("codex", artifacts.get(0).path("provider_id").asText());
            assertEquals("file:///tmp/patch.diff", artifacts.get(0).path("path").asText());
        }
    }

    @Test
    void harnessTraceEndpointReturnsAheReviewContract() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("harness-trace-http.db"))) {
            Task task = fixture.service.createTask(new TaskCreateRequest(
                "harness trace http",
                "coding",
                "user",
                "high",
                "verify harness trace endpoint",
                "return compressed AHE trace",
                null,
                null,
                Map.of("model_mode", "strong_only"),
                false
            ));
            Instant now = Instant.parse("2026-04-29T12:30:00Z");
            fixture.artifactDao.insert(new Artifact(
                "art_harness_trace",
                task.sessionId(),
                task.id(),
                now,
                "worker_artifact",
                "Harness trace worker result",
                null,
                null,
                "Worker stopped for review",
                Map.of("latest_worker_metadata", Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "tool_chain_step_count", 2,
                    "tool_chain_termination_reason", "planner_no_additional_tool",
                    "tool_chain_trace", java.util.List.of(
                        Map.of("tool_chain_step_index", 1, "tool_name", "read_file"),
                        Map.of("tool_chain_step_index", 2, "tool_name", "write_file")
                    ),
                    "execution_status", "blocked",
                    "evidence_refs", java.util.List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"),
                    "unfinished_items", java.util.List.of("manual_review")
                ))
            ));
            fixture.toolInvocationDao.insert(new ToolInvocationRecord(
                "tool_harness_trace",
                task.sessionId(),
                task.id(),
                "tool-worker",
                "exec_harness_trace",
                "write_file",
                Map.of("path", "draft.txt"),
                "draft updated",
                "succeeded",
                true,
                19,
                java.util.List.of("draft.txt"),
                now.plusMillis(1),
                Map.of("tool_execution_mode", "multi_tool_round", "tool_chain_step_index", 2)
            ));

            HttpResponse<String> response = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri("/api/v1/tasks/" + task.id() + "/harness_trace?limit=5"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JsonNode data = NioHttpServer.SHARED_MAPPER.readTree(response.body()).path("data");
            assertEquals(200, response.statusCode());
            assertEquals(task.id(), data.path("task_id").asText());
            assertEquals("blocked", data.path("execution_status").asText());
            assertEquals("tool:read_file:input.txt", data.path("evidence_refs").get(0).asText());
            assertEquals("manual_review", data.path("unfinished_items").get(0).asText());
            assertFalse(data.has("judgment_action"));
            assertFalse(data.has("suggested_next_action"));
            assertEquals(1, data.path("tool_invocations").size());
            assertEquals("write_file", data.path("tool_invocations").get(0).path("tool_name").asText());
            assertEquals("multi_tool_round", data.path("harness_metadata").path("tool_execution_mode").asText());
            assertEquals(1, data.path("harness_metadata").path("tool_invocation_count").asInt());
        }
    }

    @Test
    void runtimeHealthSummarizesProviderAndRecentRunStatus() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("runtime-health.db"))) {
            Task task = fixture.service.createTask(new TaskCreateRequest(
                "runtime health",
                "coding",
                "user",
                "high",
                "verify runtime health",
                "summarize provider and run status",
                null,
                null,
                Map.of("model_mode", "strong_only"),
                false
            ));
            Instant now = Instant.now();
            fixture.agentRunDao.insert(new AgentRunRecord(
                "arun_running",
                task.id(),
                task.sessionId(),
                "codex",
                "Codex",
                "planner_executor",
                "codex",
                "strong",
                "running",
                now.minusMillis(500),
                null,
                0L,
                "Provider run running",
                "run.running",
                0,
                Map.of()
            ));
            fixture.agentRunDao.insert(new AgentRunRecord(
                "arun_failed",
                task.id(),
                task.sessionId(),
                "codex",
                "Codex",
                "planner_executor",
                "codex",
                "strong",
                "failed",
                now.minusMillis(1000),
                now.minusMillis(800),
                200L,
                "Provider run failed",
                "run.failed",
                0,
                Map.of("error_type", "IllegalStateException")
            ));

            HttpResponse<String> response = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri("/api/v1/runtime_health"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JsonNode data = NioHttpServer.SHARED_MAPPER.readTree(response.body()).path("data");
            assertEquals(200, response.statusCode());
            assertEquals(1, data.path("active_run_count").asInt());
            assertEquals(1, data.path("failed_run_count_24h").asInt());
            assertEquals(1, data.path("crashed_run_count_24h").asInt());
            assertEquals(1, data.path("unavailable_provider_count").asInt());
            assertEquals("arun_running", data.path("active_runs").get(0).path("run_id").asText());
            assertEquals("arun_failed", data.path("recent_failures").get(0).path("run_id").asText());
            assertEquals(0.5d, data.path("provider_failure_rate").path("codex").asDouble());
            JsonNode providerStats = data.path("provider_stats");
            assertEquals(1, providerStats.size());
            JsonNode codexStats = providerStats.get(0);
            assertEquals("codex", codexStats.path("provider_id").asText());
            assertEquals(2, codexStats.path("total_runs").asInt());
            assertEquals(1, codexStats.path("active_runs").asInt());
            assertEquals(1, codexStats.path("failed_runs").asInt());
            assertEquals(1, codexStats.path("crashed_runs").asInt());
            assertEquals(200L, codexStats.path("average_duration_ms").asLong());
            assertEquals(0.5d, codexStats.path("failure_rate").asDouble());
            assertEquals("Provider run failed", codexStats.path("last_failure_summary").asText());
            assertEquals("24h", codexStats.path("metadata").path("stats_window").asText());
            assertFalse(codexStats.path("last_run_at").asText().isBlank());
        }
    }

    private static final class HttpFixture implements AutoCloseable {
        private final DatabaseManager db;
        private final TaskService service;
        private final AgentRunDao agentRunDao;
        private final EventDao eventDao;
        private final ArtifactDao artifactDao;
        private final ToolInvocationDao toolInvocationDao;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final int port;

        private HttpFixture(Path dbPath) throws IOException {
            this.db = new DatabaseManager(dbPath);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            this.eventDao = db.jdbi().onDemand(EventDao.class);
            this.artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            this.agentRunDao = db.jdbi().onDemand(AgentRunDao.class);
            this.toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);
            LearningMemoryDao learningMemoryDao = db.jdbi().onDemand(LearningMemoryDao.class);
            ExperimentRunDao experimentRunDao = db.jdbi().onDemand(ExperimentRunDao.class);
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);

            AgentProviderRegistry registry = new AgentProviderRegistry()
                .register(new OpenClawProvider())
                .register(new CodexProvider("definitely-missing-codex-binary-for-test"));
            AgentRunService agentRunService = new AgentRunService(agentRunDao, registry, eventDao, artifactDao);
            ConsolidationService consolidationService = new ConsolidationService(
                decisionDao, artifactDao, eventDao, checkpointDao, taskDao);
            LearningMemoryService learningMemoryService = new LearningMemoryService(learningMemoryDao);
            ExperimentRunService experimentRunService = new ExperimentRunService(
                experimentRunDao, decisionDao, artifactDao, eventDao, toolInvocationDao);

            this.service = new TaskService(
                taskDao,
                sessionDao,
                this.eventDao,
                null,
                new WorkerRouter(new WorkerRegistry()),
                null,
                null,
                null,
                null,
                consolidationService,
                learningMemoryService,
                toolInvocationDao,
                sessionMessageDao,
                experimentRunService,
                agentRunService
            );

            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            this.server.setExecutor(executor);
            this.server.createContext("/api/v1/tasks",
                new TaskHandler(service, null, registry, NioHttpServer.SHARED_MAPPER));
            this.server.createContext("/api/v1/agents",
                new AgentHandler(registry, agentRunService, NioHttpServer.SHARED_MAPPER));
            this.server.createContext("/api/v1/agent_runs",
                new AgentRunHandler(agentRunService));
            this.server.createContext("/api/v1/runtime_health",
                new RuntimeHealthHandler(agentRunService));
            this.server.start();
            this.client = HttpClient.newHttpClient();
            this.port = server.getAddress().getPort();
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
