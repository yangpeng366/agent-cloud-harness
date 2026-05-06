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

    @Test
    void listEndpointSupportsGoalOutcomeFilters() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("experiment-run-http-outcomes.db"))) {
            Instant now = Instant.now();
            fixture.seedRun(
                "session_1",
                "task_1",
                "accepted baseline",
                "orchestrated",
                "done",
                now,
                Map.of("tool_execution_mode", "single_tool_round")
            );
            fixture.seedRun(
                "session_2",
                "task_2",
                "rejected baseline",
                "small_only",
                "failed",
                now.plusSeconds(1),
                Map.of("tool_execution_mode", "multi_tool_round")
            );

            HttpResponse<String> response = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri(
                    "/api/v1/experiment_runs?experiment_name=baseline-http"
                        + "&completion_status=failed"
                        + "&acceptance_result=rejected"
                        + "&failure_reason_present=true"
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
            assertEquals("rejected", body.path("data").get(0).path("acceptance_result").asText());
            assertEquals("guard tripped", body.path("data").get(0).path("failure_reason").asText());
        }
    }

    @Test
    void summaryEndpointAggregatesGoalOutcomeMetrics() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("experiment-run-http-summary.db"))) {
            Instant now = Instant.now();
            fixture.seedRun(
                "session_1",
                "task_1",
                "accepted baseline",
                "orchestrated",
                "done",
                now,
                Map.of(
                    "tool_execution_mode", "single_tool_round",
                    "route_source", "learning_memory",
                    "orchestration_closed_loop_observed", true,
                    "execution_judgment_action", "done",
                    "completion_judgment_status", "done",
                    "completion_alignment_level", "high",
                    "has_route_evidence", true,
                    "has_execution_judgment", true,
                    "has_completion_judgment", true,
                    "has_closed_loop_evidence_chain", true
                )
            );
            fixture.seedRun(
                "session_2",
                "task_2",
                "rejected baseline",
                "small_only",
                "failed",
                now.plusSeconds(1),
                Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "route_source", "capability_match",
                    "execution_judgment_action", "escalate",
                    "completion_judgment_status", "misaligned",
                    "completion_alignment_level", "low",
                    "has_route_evidence", true,
                    "has_execution_judgment", true,
                    "has_completion_judgment", true,
                    "has_closed_loop_evidence_chain", true
                )
            );

            HttpResponse<String> response = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri(
                    "/api/v1/experiment_runs/summary?experiment_name=baseline-http"
                        + "&route_source=learning_memory"
                        + "&orchestration_closed_loop_observed=true"
                ))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JsonNode body = NioHttpServer.SHARED_MAPPER.readTree(response.body());
            JsonNode data = body.path("data");
            assertEquals(200, response.statusCode());
            assertTrue(body.path("success").asBoolean());
            assertEquals(1, data.path("run_count").asInt());
            assertEquals(1, data.path("completion_status_counts").path("done").asInt());
            assertEquals(1, data.path("acceptance_result_counts").path("accepted").asInt());
            assertEquals(1, data.path("model_mode_counts").path("orchestrated").asInt());
            assertEquals(1, data.path("route_source_counts").path("learning_memory").asInt());
            assertEquals(1, data.path("execution_action_counts").path("done").asInt());
            assertEquals(1, data.path("completion_judgment_status_counts").path("done").asInt());
            assertEquals(1, data.path("completion_alignment_level_counts").path("high").asInt());
            assertEquals(1, data.path("orchestration_closed_loop_observed_count").asInt());
            assertEquals(1, data.path("orchestrated_run_count").asInt());
            assertEquals(1, data.path("runs_with_route_evidence_count").asInt());
            assertEquals(1, data.path("runs_with_execution_judgment_count").asInt());
            assertEquals(1, data.path("runs_with_completion_judgment_count").asInt());
            assertEquals(1, data.path("runs_with_closed_loop_evidence_chain_count").asInt());
            assertEquals(0, data.path("runs_with_trace_pointers_count").asInt());
            assertEquals(0, data.path("runs_with_judgment_trace_pointers_count").asInt());
            assertEquals(0, data.path("runs_with_task_surface_refs_count").asInt());
            assertEquals(0, data.path("runs_with_judgment_surface_refs_count").asInt());
            assertEquals(0, data.path("runs_with_tool_trace_surface_refs_count").asInt());
            assertEquals(0, data.path("failure_reason_count").asInt());
            assertEquals(0, data.path("recovery_success_count").asInt());
            assertEquals(0, data.path("handoff_count").asInt());
            assertEquals(0, data.path("resume_count").asInt());
            assertEquals(0, data.path("human_gate_count").asInt());
            assertEquals(1.0, data.path("total_cost").asDouble(), 0.001);
            assertEquals(1.0, data.path("average_cost").asDouble(), 0.001);
            assertEquals(0.0, data.path("average_strong_model_cost_ratio").asDouble(), 0.001);
        }
    }

    @Test
    void listAndSummaryEndpointsSupportClosedLoopEvidenceFilters() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("experiment-run-http-evidence.db"))) {
            Instant now = Instant.now();
            fixture.seedRun(
                "session_1",
                "task_1",
                "closed loop run",
                "orchestrated",
                "done",
                now,
                Map.of(
                    "route_source", "learning_memory",
                    "execution_judgment_action", "done",
                    "completion_judgment_status", "done",
                    "completion_alignment_level", "high",
                    "has_route_evidence", true,
                    "has_execution_judgment", true,
                    "has_completion_judgment", true,
                    "has_closed_loop_evidence_chain", true,
                    "closed_loop_proof_summary", "route=present | execution_judgment=present"
                )
            );
            fixture.seedRun(
                "session_2",
                "task_2",
                "partial loop run",
                "small_only",
                "failed",
                now.plusSeconds(1),
                Map.of(
                    "route_source", "capability_match",
                    "has_route_evidence", true,
                    "has_execution_judgment", false,
                    "has_completion_judgment", false,
                    "has_closed_loop_evidence_chain", false
                )
            );

            HttpResponse<String> listResponse = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri(
                    "/api/v1/experiment_runs?experiment_name=baseline-http"
                        + "&has_route_evidence=true"
                        + "&has_execution_judgment=true"
                        + "&has_completion_judgment=true"
                        + "&has_closed_loop_evidence_chain=true"
                ))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JsonNode listBody = NioHttpServer.SHARED_MAPPER.readTree(listResponse.body());
            assertEquals(200, listResponse.statusCode());
            assertTrue(listBody.path("success").asBoolean());
            assertEquals(1, listBody.path("data").size());
            assertEquals("task_1", listBody.path("data").get(0).path("task_id").asText());
            assertEquals("route=present | execution_judgment=present",
                listBody.path("data").get(0).path("metadata").path("closed_loop_proof_summary").asText());

            HttpResponse<String> summaryResponse = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri(
                    "/api/v1/experiment_runs/summary?experiment_name=baseline-http"
                        + "&has_route_evidence=true"
                        + "&has_execution_judgment=true"
                        + "&has_completion_judgment=true"
                        + "&has_closed_loop_evidence_chain=true"
                ))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JsonNode summaryBody = NioHttpServer.SHARED_MAPPER.readTree(summaryResponse.body());
            JsonNode data = summaryBody.path("data");
            assertEquals(200, summaryResponse.statusCode());
            assertTrue(summaryBody.path("success").asBoolean());
            assertEquals(1, data.path("run_count").asInt());
            assertEquals(1, data.path("runs_with_route_evidence_count").asInt());
            assertEquals(1, data.path("runs_with_execution_judgment_count").asInt());
            assertEquals(1, data.path("runs_with_completion_judgment_count").asInt());
            assertEquals(1, data.path("runs_with_closed_loop_evidence_chain_count").asInt());
            assertEquals(1, data.path("route_source_counts").path("learning_memory").asInt());
        }
    }

    @Test
    void taskEndpointExposesStructuredClosedLoopEvidenceChain() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("experiment-run-http-task-view.db"))) {
            Instant now = Instant.now();
            fixture.seedRun(
                "session_1",
                "task_1",
                "closed loop task view",
                "orchestrated",
                "done",
                now,
                Map.ofEntries(
                    Map.entry("route_source", "learning_memory"),
                    Map.entry("selected_worker", "kimi"),
                    Map.entry("selected_model_tier", "small"),
                    Map.entry("execution_status", "blocked"),
                    Map.entry("evidence_refs", java.util.List.of("tool:read_file:input.txt", "tool:write_file:draft.txt")),
                    Map.entry("unfinished_items", java.util.List.of("manual_review")),
                    Map.entry("execution_judgment_action", "done"),
                    Map.entry("execution_judgment_next_step", "handoff to strong evaluator"),
                    Map.entry("completion_judgment_status", "done"),
                    Map.entry("completion_alignment_level", "high"),
                    Map.entry("evaluator_role", "strong_evaluator"),
                    Map.entry("evaluator_model_tier", "strong"),
                    Map.entry("tool_execution_mode", "multi_tool_round"),
                    Map.entry("tool_chain_step_count", 2),
                    Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
                    Map.entry("tool_chain_tools", java.util.List.of("read_file", "write_file")),
                    Map.entry("has_route_evidence", true),
                    Map.entry("has_execution_judgment", true),
                    Map.entry("has_completion_judgment", true),
                    Map.entry("has_closed_loop_evidence_chain", true),
                    Map.entry("judgment_evidence_chain", "route=learning_memory:kimi -> exec=done -> completion=done:high"),
                    Map.entry(
                        "closed_loop_proof_summary",
                        "route=present | execution_judgment=present | completion_judgment=present | closed_loop_evidence_chain=complete"
                    ),
                    Map.entry("orchestration_closed_loop_observed", true),
                    Map.entry("orchestration_proof_summary", "codex -> kimi -> strong_evaluator(strong) [closed_loop]"),
                    Map.entry("closed_loop_evidence", Map.ofEntries(
                        Map.entry("chain_status", "complete"),
                        Map.entry("has_route_evidence", true),
                        Map.entry("has_execution_judgment", true),
                        Map.entry("has_completion_judgment", true),
                        Map.entry("route", Map.of(
                            "present", true,
                            "route_source", "learning_memory",
                            "selected_worker", "kimi",
                            "selected_model_tier", "small"
                        )),
                        Map.entry("worker_execution", Map.of(
                            "execution_status", "blocked",
                            "evidence_refs", java.util.List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"),
                            "unfinished_items", java.util.List.of("manual_review")
                        )),
                        Map.entry("execution_judgment", Map.of(
                            "action", "done",
                            "next_step", "handoff to strong evaluator"
                        )),
                        Map.entry("completion_judgment", Map.of(
                            "status", "done",
                            "alignment_level", "high",
                            "evaluator_role", "strong_evaluator",
                            "evaluator_model_tier", "strong"
                        )),
                        Map.entry("tool_chain", Map.of(
                            "execution_mode", "multi_tool_round",
                            "step_count", 2,
                            "termination_reason", "planner_no_additional_tool",
                            "tool_names", java.util.List.of("read_file", "write_file")
                        )),
                        Map.entry("orchestration", Map.of(
                            "planner_worker", "codex",
                            "executor_worker", "kimi",
                            "evaluator_role", "strong_evaluator",
                            "evaluator_model_tier", "strong",
                            "closed_loop_observed", true
                        )),
                        Map.entry("trace_pointers", Map.of(
                            "task_id", "task_1",
                            "session_id", "session_1",
                            "worker_artifact_id", "art_worker_1",
                            "execution_judgment_id", "dec_exec_1",
                            "completion_judgment_id", "dec_completion_1",
                            "tool_invocation_ids", java.util.List.of("tool_1", "tool_2"),
                            "tool_execution_ids", java.util.List.of("exec_1")
                        )),
                        Map.entry("task_surface_refs", Map.of(
                            "task_id", "task_1",
                            "live_flow_path", "/api/v1/tasks/task_1/live_flow",
                            "runtime_context_path", "/api/v1/tasks/task_1/runtime_context",
                            "harness_trace_path", "/api/v1/tasks/task_1/harness_trace",
                            "judgment_trace_path", "/api/v1/tasks/task_1/judgment_trace",
                            "tool_trace_path", "/api/v1/tasks/task_1/tool_trace"
                        ))
                    ))
                )
            );

            HttpResponse<String> response = fixture.client.send(
                HttpRequest.newBuilder(fixture.uri("/api/v1/experiment_runs/task_1"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            JsonNode body = NioHttpServer.SHARED_MAPPER.readTree(response.body());
            JsonNode evidence = body.path("data").path("metadata").path("closed_loop_evidence");
            assertEquals(200, response.statusCode());
            assertTrue(body.path("success").asBoolean());
            assertEquals("complete", evidence.path("chain_status").asText());
            assertTrue(evidence.path("has_route_evidence").asBoolean());
            assertEquals("learning_memory", evidence.path("route").path("route_source").asText());
            assertEquals("kimi", evidence.path("route").path("selected_worker").asText());
            assertEquals("blocked", evidence.path("worker_execution").path("execution_status").asText());
            assertEquals("tool:read_file:input.txt",
                evidence.path("worker_execution").path("evidence_refs").get(0).asText());
            assertEquals("manual_review",
                evidence.path("worker_execution").path("unfinished_items").get(0).asText());
            assertEquals("done", evidence.path("execution_judgment").path("action").asText());
            assertEquals("done", evidence.path("completion_judgment").path("status").asText());
            assertEquals("multi_tool_round", evidence.path("tool_chain").path("execution_mode").asText());
            assertEquals(2, evidence.path("tool_chain").path("step_count").asInt());
            assertTrue(evidence.path("orchestration").path("closed_loop_observed").asBoolean());
            assertEquals("task_1", evidence.path("trace_pointers").path("task_id").asText());
            assertEquals("session_1", evidence.path("trace_pointers").path("session_id").asText());
            assertEquals("art_worker_1", evidence.path("trace_pointers").path("worker_artifact_id").asText());
            assertEquals("dec_exec_1", evidence.path("trace_pointers").path("execution_judgment_id").asText());
            assertEquals("dec_completion_1",
                evidence.path("trace_pointers").path("completion_judgment_id").asText());
            assertEquals("tool_1",
                evidence.path("trace_pointers").path("tool_invocation_ids").get(0).asText());
            assertEquals("exec_1",
                evidence.path("trace_pointers").path("tool_execution_ids").get(0).asText());
            assertEquals("/api/v1/tasks/task_1/live_flow",
                evidence.path("task_surface_refs").path("live_flow_path").asText());
            assertEquals("/api/v1/tasks/task_1/harness_trace",
                evidence.path("task_surface_refs").path("harness_trace_path").asText());
            assertEquals("/api/v1/tasks/task_1/judgment_trace",
                evidence.path("task_surface_refs").path("judgment_trace_path").asText());
            assertEquals("/api/v1/tasks/task_1/tool_trace",
                evidence.path("task_surface_refs").path("tool_trace_path").asText());
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
