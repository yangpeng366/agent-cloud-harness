package com.agentcloud.server;

import com.agentcloud.engine.AgentRunService;
import com.agentcloud.engine.ConsolidationService;
import com.agentcloud.engine.ControlNodeGraph;
import com.agentcloud.engine.ExperimentRunService;
import com.agentcloud.engine.IdGenerator;
import com.agentcloud.engine.LearningMemoryService;
import com.agentcloud.engine.SessionService;
import com.agentcloud.engine.TaskService;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.providers.CodexProvider;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.AgentRunRecord;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.SessionMessage;
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
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.AgentRunDao;
import com.agentcloud.store.CheckpointDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ExperimentRunDao;
import com.agentcloud.store.LearningMemoryDao;
import com.agentcloud.store.ResumePacketDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.store.ToolInvocationDao;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskHandlerStabilitySmokeHttpTest {

    @TempDir
    Path tempDir;

    @Test
    void stabilitySmokeProvesCoreReadSurfacesRemainAvailableAfterManualContinue() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-stability-smoke.db"))) {
            Task task = harness.service.createTask(new TaskCreateRequest(
                "stability smoke task", "coding", "user", "high",
                "prove core read surfaces stay stable after manual continue", "smoke task brief", null, null, Map.of(), false
            ));
            harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/continue"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.discarding()
            );

            HttpResponse<String> taskResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id()))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> liveFlowResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/live_flow?limit=5"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> judgmentTraceResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/judgment_trace"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> experimentRunResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/experiment_run"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> toolTraceResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/tool_trace?limit=5"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> harnessTraceResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/harness_trace?limit=5"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> listResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks?status=" + task.status()))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, taskResponse.statusCode(), "GET /api/v1/tasks/{id} should remain stable");
            assertEquals(200, liveFlowResponse.statusCode(), "GET /api/v1/tasks/{id}/live_flow should remain stable");
            assertEquals(200, judgmentTraceResponse.statusCode(), "GET /api/v1/tasks/{id}/judgment_trace should remain stable");
            assertEquals(200, experimentRunResponse.statusCode(), "GET /api/v1/tasks/{id}/experiment_run should remain stable");
            assertEquals(200, toolTraceResponse.statusCode(), "GET /api/v1/tasks/{id}/tool_trace should remain stable");
            assertEquals(200, harnessTraceResponse.statusCode(), "GET /api/v1/tasks/{id}/harness_trace should remain stable");
            assertEquals(200, listResponse.statusCode(), "GET /api/v1/tasks list surface should remain stable");

            Map<String, Object> taskPayload = harness.readJson(taskResponse.body());
            Map<String, Object> liveFlowPayload = harness.readJson(liveFlowResponse.body());

            Map<String, Object> taskData = harness.map(taskPayload.get("data"));
            Map<String, Object> liveFlowData = harness.map(liveFlowPayload.get("data"));
            Map<String, Object> routePreview = harness.map(liveFlowData.get("route_preview"));
            Map<String, Object> judgmentTraceData = harness.map(harness.readJson(judgmentTraceResponse.body()).get("data"));

            assertEquals(task.id(), String.valueOf(taskData.get("id")));
            assertEquals("active", String.valueOf(taskData.get("status")));
            assertEquals("codex", String.valueOf(taskData.get("assigned_worker")));
            assertEquals(task.id(), String.valueOf(harness.map(liveFlowData.get("task")).get("id")));
            assertEquals("codex", String.valueOf(routePreview.get("selected_worker")));
            assertEquals("ready_fallback", String.valueOf(routePreview.get("route_source")));
            assertTrue(judgmentTraceData.containsKey("decision_rationale"));
            assertTrue(judgmentTraceData.containsKey("progress_detail"));
            assertTrue(judgmentTraceData.containsKey("progress_summary"));

            List<Map<String, Object>> listedTasks = harness.list(harness.readJson(listResponse.body()).get("data"));
            assertTrue(listedTasks.stream().anyMatch(item -> task.id().equals(String.valueOf(item.get("id")))));
        }
    }

    private static final class HttpHarness implements AutoCloseable {
        private final DatabaseManager db;
        private final TaskService service;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final int port;

        private HttpHarness(Path dbPath) throws IOException {
            this.db = new DatabaseManager(dbPath);
            this.service = service(db);
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = Executors.newCachedThreadPool();
            this.server.setExecutor(executor);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
            SessionService sessionService = new SessionService(sessionDao, taskDao, sessionMessageDao, eventDao);
            this.server.createContext("/api/v1/sessions", new SessionHandler(sessionService, NioHttpServer.SHARED_MAPPER));
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
            WorkerRouter workerRouter = new WorkerRouter(new WorkerRegistry());

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
                            List.of("继续推进"),
                            List.of(),
                            List.of(),
                            List.of(),
                            "已汇总 stability smoke 所需的最小上下文",
                            "stability smoke runtime context",
                            12
                        ),
                        new MountedContextView(
                            null,
                            task.id(),
                            List.of(new MountedContextPanel(
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
                            )),
                            List.of("compat_mode=task_runtime_context_preserved")
                        )
                    );
                }
            };
            ControlNodeGraph controlGraph = new ControlNodeGraph(
                taskDao, null, null, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task enter(Task task) {
                    Artifact latestArtifact = artifactDao.listBySessionAndTask(task.sessionId(), task.id(), 5).stream()
                        .findFirst()
                        .orElse(null);
                    if (latestArtifact == null) {
                        artifactDao.insert(new Artifact(
                            IdGenerator.newId("art"),
                            task.sessionId(),
                            task.id(),
                            Instant.now(),
                            "worker_artifact",
                            "Harness progress artifact",
                            null,
                            null,
                            "stability smoke 已产生首轮工件。",
                            Map.of(
                                "selected_worker", "codex",
                                "selected_worker_type", "codex",
                                "selected_model_tier", "strong",
                                "route_source", "ready_fallback",
                                "why_selected", "selected by harness stub after manual continue"
                            )
                        ));
                    }
                    Task updated = new Task(
                        task.id(),
                        task.sessionId(),
                        task.parentTaskId(),
                        task.title(),
                        "active",
                        task.priority(),
                        task.createdAt(),
                        Instant.now(),
                        task.startedAt(),
                        task.completedAt(),
                        task.ownerRole(),
                        "stability smoke 已完成一轮继续。",
                        task.goal(),
                        "继续扩写第二段。",
                        "codex",
                        "scheduler",
                        task.waitingReason(),
                        task.metadata()
                    );
                    taskDao.updateState(updated);
                    return updated;
                }
            };

            return new TaskService(
                taskDao,
                sessionDao,
                eventDao,
                packetDao,
                workerRouter,
                null,
                controlGraph,
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
