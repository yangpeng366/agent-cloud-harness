package com.agentcloud.server;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.providers.CodexProvider;
import com.agentcloud.engine.AgentRunService;
import com.agentcloud.engine.ConsolidationService;
import com.agentcloud.engine.ExperimentRunService;
import com.agentcloud.engine.IdGenerator;
import com.agentcloud.engine.LearningMemoryService;
import com.agentcloud.engine.TaskService;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.runtime.context.ContextObject;
import com.agentcloud.runtime.context.ContextObjectType;
import com.agentcloud.runtime.context.ContextRetentionState;
import com.agentcloud.runtime.context.MountedContextPanel;
import com.agentcloud.runtime.context.MountedContextPanelName;
import com.agentcloud.runtime.context.MountedContextView;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.*;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TaskHandlerLiveFlowHttpTest {

    @TempDir
    Path tempDir;

    @Test
    void liveFlowAndJudgmentTraceExposeRuntimeCognitionSurface() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-live-flow-cognition.db"))) {
            Task task = harness.service.createTask(new TaskCreateRequest(
                "http cognition task", "coding", "user", "high",
                "检查单任务 route/execution/judgment 对照面", "应经由 HTTP 返回 comparison view", null, null, Map.of(), false
            ));

            harness.toolInvocationDao.insert(new ToolInvocationRecord(
                "exec_http_cognition_1",
                task.sessionId(),
                task.id(),
                "codex",
                "exec_http_cognition_1",
                "read_file",
                Map.of("path", "input.txt"),
                "input loaded",
                "succeeded",
                true,
                24,
                List.of("input.txt"),
                Instant.now(),
                Map.ofEntries(
                    Map.entry("execution_status", "succeeded"),
                    Map.entry("tool_execution_mode", "single_tool_round"),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("mounted_context_panel_count", 3),
                    Map.entry("mounted_context_non_empty_panel_count", 2),
                    Map.entry("mounted_context_selection_trace_count", 1),
                    Map.entry("mounted_context_rendered_panel_count", 2),
                    Map.entry("mounted_context_hidden_panel_count", 0),
                    Map.entry("mounted_context_rendered_object_count", 3),
                    Map.entry("mounted_context_hidden_object_count", 1),
                    Map.entry("mounted_context_rendered_selection_trace_count", 1),
                    Map.entry("mounted_context_hidden_selection_trace_count", 0),
                    Map.entry("mounted_context_budget_truncated", true),
                    Map.entry("mounted_pinned_count", 1),
                    Map.entry("mounted_active_count", 2),
                    Map.entry("mounted_ancestor_count", 1),
                    Map.entry("mounted_sibling_count", 1),
                    Map.entry("mounted_evidence_count", 1),
                    Map.entry("mounted_index_count", 1),
                    Map.entry("mounted_archive_count", 1),
                    Map.entry("candidate_workers", List.of("codex", "kimi")),
                    Map.entry("evidence_refs", List.of("tool:read_file:input.txt")),
                    Map.entry("unfinished_items", List.of("manual_review"))
                )
            ));
            harness.decisionDao.insert(new Decision(
                IdGenerator.newId("dec"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "execution_judgment",
                "Execution judgment: continue",
                "http execution judgment should carry prompt mode alignment",
                "medium",
                null,
                Map.ofEntries(
                    Map.entry("action", "continue"),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("mounted_context_panel_count", 3),
                    Map.entry("mounted_context_non_empty_panel_count", 2),
                    Map.entry("mounted_context_selection_trace_count", 1),
                    Map.entry("mounted_context_rendered_panel_count", 2),
                    Map.entry("mounted_context_hidden_panel_count", 0),
                    Map.entry("mounted_context_rendered_object_count", 3),
                    Map.entry("mounted_context_hidden_object_count", 1),
                    Map.entry("mounted_context_rendered_selection_trace_count", 1),
                    Map.entry("mounted_context_hidden_selection_trace_count", 0),
                    Map.entry("mounted_context_budget_truncated", true),
                    Map.entry("mounted_pinned_count", 1),
                    Map.entry("mounted_active_count", 2),
                    Map.entry("mounted_ancestor_count", 1),
                    Map.entry("mounted_sibling_count", 1),
                    Map.entry("mounted_evidence_count", 1),
                    Map.entry("mounted_index_count", 1),
                    Map.entry("mounted_archive_count", 1),
                    Map.entry("candidate_workers", List.of("codex", "kimi")),
                    Map.entry("evidence_refs", List.of("tool:read_file:input.txt")),
                    Map.entry("unfinished_items", List.of("manual_review"))
                )
            ));
            harness.decisionDao.insert(new Decision(
                IdGenerator.newId("dec"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "completion_judgment",
                "Completion judgment: partial",
                "http completion judgment should carry prompt mode alignment",
                "medium",
                null,
                Map.ofEntries(
                    Map.entry("status", "partially_done"),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("mounted_context_panel_count", 3),
                    Map.entry("mounted_context_non_empty_panel_count", 2),
                    Map.entry("mounted_context_selection_trace_count", 1),
                    Map.entry("mounted_context_rendered_panel_count", 2),
                    Map.entry("mounted_context_hidden_panel_count", 0),
                    Map.entry("mounted_context_rendered_object_count", 3),
                    Map.entry("mounted_context_hidden_object_count", 1),
                    Map.entry("mounted_context_rendered_selection_trace_count", 1),
                    Map.entry("mounted_context_hidden_selection_trace_count", 0),
                    Map.entry("mounted_context_budget_truncated", true),
                    Map.entry("mounted_pinned_count", 1),
                    Map.entry("mounted_active_count", 2),
                    Map.entry("mounted_ancestor_count", 1),
                    Map.entry("mounted_sibling_count", 1),
                    Map.entry("mounted_evidence_count", 1),
                    Map.entry("mounted_index_count", 1),
                    Map.entry("mounted_archive_count", 1),
                    Map.entry("candidate_workers", List.of("codex", "kimi")),
                    Map.entry("evidence_refs", List.of("tool:read_file:input.txt")),
                    Map.entry("unfinished_items", List.of("manual_review"))
                )
            ));

            HttpResponse<String> flowResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/live_flow?limit=8"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> traceResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/judgment_trace"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> flowPayload = harness.readJson(flowResponse.body());
            Map<String, Object> flowData = harness.map(flowPayload.get("data"));
            Map<String, Object> flowSurface = harness.map(flowData.get("runtime_cognition_surface"));
            Map<String, Object> flowRoute = harness.map(flowSurface.get("route"));
            Map<String, Object> flowExecution = harness.map(flowSurface.get("execution"));
            Map<String, Object> flowAlignment = harness.map(flowSurface.get("alignment"));
            List<Map<String, Object>> flowTimeline = harness.list(flowData.get("runtime_cognition_timeline"));

            Map<String, Object> tracePayload = harness.readJson(traceResponse.body());
            Map<String, Object> traceData = harness.map(tracePayload.get("data"));
            Map<String, Object> traceSurface = harness.map(traceData.get("runtime_cognition_surface"));
            Map<String, Object> traceExecution = harness.map(traceSurface.get("execution"));

            assertEquals(200, flowResponse.statusCode());
            assertEquals(200, traceResponse.statusCode());
            assertEquals("codex", flowRoute.get("selected_worker"));
            assertEquals("codex", flowExecution.get("worker_id"));
            assertEquals("mounted_context_primary", flowExecution.get("prompt_mode"));
            assertEquals(Boolean.TRUE, flowExecution.get("mounted_render_used"));
            assertEquals(2, ((Number) flowExecution.get("mounted_context_non_empty_panel_count")).intValue());
            assertEquals(3, ((Number) flowExecution.get("mounted_context_rendered_object_count")).intValue());
            assertEquals(1, ((Number) flowExecution.get("mounted_context_hidden_object_count")).intValue());
            assertEquals(Boolean.TRUE, flowExecution.get("mounted_context_budget_truncated"));
            assertEquals(2, ((Number) flowExecution.get("mounted_active_count")).intValue());
            assertEquals(1, ((Number) flowExecution.get("mounted_archive_count")).intValue());
            assertEquals(Boolean.TRUE, flowAlignment.get("route_worker_matches_execution_worker"));
            assertEquals(Boolean.TRUE, flowAlignment.get("execution_and_execution_judgment_prompt_mode_aligned"));
            assertEquals(4, flowTimeline.size());
            Map<String, Map<String, Object>> timelineByStage = flowTimeline.stream()
                .collect(java.util.stream.Collectors.toMap(
                    item -> String.valueOf(item.get("stage")),
                    item -> item
                ));
            assertEquals("mounted_context_primary", timelineByStage.get("execution").get("prompt_mode"));
            assertEquals(3, ((Number) timelineByStage.get("execution").get("mounted_context_rendered_object_count")).intValue());
            assertEquals(1, ((Number) timelineByStage.get("execution").get("mounted_context_hidden_object_count")).intValue());
            assertEquals(1, ((Number) timelineByStage.get("execution").get("mounted_context_rendered_selection_trace_count")).intValue());
            assertEquals(0, ((Number) timelineByStage.get("execution").get("mounted_context_hidden_selection_trace_count")).intValue());
            assertEquals(Boolean.TRUE, timelineByStage.get("execution").get("mounted_context_budget_truncated"));
            assertEquals(Boolean.TRUE,
                timelineByStage.get("execution_judgment").get("aligned_with_previous_prompt_mode"));
            assertEquals(3, ((Number) timelineByStage.get("execution_judgment").get("mounted_context_rendered_object_count")).intValue());
            assertEquals(Boolean.TRUE,
                timelineByStage.get("execution_judgment").get("mounted_context_budget_truncated"));
            assertEquals(1, ((Number) timelineByStage.get("completion_judgment").get("mounted_context_hidden_object_count")).intValue());
            assertEquals("codex", timelineByStage.get("route").get("worker_id"));
            assertNotNull(traceSurface);
            assertEquals("exec_http_cognition_1", traceExecution.get("execution_id"));
            assertEquals(Boolean.TRUE, traceExecution.get("mounted_render_used"));
            assertEquals(1, ((Number) traceExecution.get("mounted_context_selection_trace_count")).intValue());
            assertEquals(3, ((Number) traceExecution.get("mounted_context_rendered_object_count")).intValue());
            assertEquals(Boolean.TRUE, traceExecution.get("mounted_context_budget_truncated"));
            assertEquals(List.of("tool:read_file:input.txt"), traceExecution.get("evidence_refs"));
        }
    }

    private static final class HttpHarness implements AutoCloseable {
        private final DatabaseManager db;
        private final TaskService service;
        private final ToolInvocationDao toolInvocationDao;
        private final DecisionDao decisionDao;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final int port;

        private HttpHarness(Path dbPath) throws IOException {
            this.db = new DatabaseManager(dbPath);
            this.toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            this.decisionDao = db.jdbi().onDemand(DecisionDao.class);
            this.service = service(db);
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = Executors.newCachedThreadPool();
            this.server.setExecutor(executor);
            this.server.createContext("/api/v1/tasks", new TaskHandler(service, NioHttpServer.SHARED_MAPPER));
            this.server.start();
            this.port = server.getAddress().getPort();
            this.client = HttpClient.newHttpClient();
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

        private TaskService service(DatabaseManager db) {
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);
            LearningMemoryDao learningMemoryDao = db.jdbi().onDemand(LearningMemoryDao.class);
            ExperimentRunDao experimentRunDao = db.jdbi().onDemand(ExperimentRunDao.class);
            AgentRunDao agentRunDao = db.jdbi().onDemand(AgentRunDao.class);
            AgentProviderRegistry providerRegistry = new AgentProviderRegistry()
                .register(new CodexProvider());

            TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
                null, null, null, null, null, null, null
            ) {
                @Override
                public TaskRuntimeContext build(Task task) {
                    return new TaskRuntimeContext(
                        task,
                        null,
                        null,
                        List.of(),
                        decisionDao.listBySessionAndTask(task.sessionId(), task.id(), 20),
                        artifactDao.listBySessionAndTask(task.sessionId(), task.id(), 20),
                        List.of(),
                        new ActiveContext(
                            task.title(),
                            List.of("priority=high"),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of("继续扩写"),
                            List.of(),
                            List.of(),
                            List.of(),
                            "已汇总 live flow 所需的最小上下文",
                            "test runtime context",
                            12
                        ),
                        new MountedContextView(
                            null,
                            task.id(),
                            List.of(
                                new MountedContextPanel(
                                    MountedContextPanelName.PINNED,
                                    "Pinned",
                                    List.of(new ContextObject(
                                        task.id() + ":constraint",
                                        "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/constraints",
                                        ContextObjectType.CONSTRAINT,
                                        "/sessions/" + task.sessionId() + "/tasks/" + task.id(),
                                        "Constraints",
                                        "保留 mounted context 里的关键约束",
                                        "保留 mounted context 里的关键约束",
                                        Instant.parse("2026-05-06T07:00:00Z"),
                                        ContextRetentionState.PINNED,
                                        List.of(),
                                        List.of(),
                                        Map.of("constraint_count", 1)
                                    ))
                                )
                            ),
                            List.of("compat_mode=task_runtime_context_preserved")
                        )
                    );
                }
            };

            return new TaskService(
                taskDao,
                sessionDao,
                eventDao,
                packetDao,
                new WorkerRouter(new WorkerRegistry()),
                null,
                null,
                null,
                runtimeContextBuilder,
                new ConsolidationService(decisionDao, artifactDao, eventDao, checkpointDao, taskDao),
                new LearningMemoryService(learningMemoryDao),
                toolInvocationDao,
                sessionMessageDao,
                new ExperimentRunService(experimentRunDao, decisionDao, artifactDao, eventDao, toolInvocationDao),
                new AgentRunService(agentRunDao, providerRegistry, eventDao, artifactDao)
            );
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            db.close();
        }
    }
}
