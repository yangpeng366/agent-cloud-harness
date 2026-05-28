package com.agentcloud.server;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.engine.AgentRunService;
import com.agentcloud.engine.ControlNodeGraph;
import com.agentcloud.engine.TaskService;
import com.agentcloud.engine.memory.PacketBuilder;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.AgentRunRecord;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.Session;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.context.ContextObject;
import com.agentcloud.runtime.context.ContextReference;
import com.agentcloud.runtime.context.ContextObjectType;
import com.agentcloud.runtime.context.ContextRetentionState;
import com.agentcloud.runtime.context.MountedContextPanel;
import com.agentcloud.runtime.context.MountedContextPanelName;
import com.agentcloud.runtime.context.MountedContextView;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.AgentRunDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ResumePacketDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.store.TaskRecoveryJobDao;
import com.agentcloud.store.ToolInvocationDao;
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
    void postCreateTaskReturnsNormalizedProviderExecutionContract() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-create-contract.db"))) {
            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "title":"http provider contract",
                          "task_type":"coding",
                          "source":"user",
                          "priority":"high",
                          "intent":"修改 D:\\\\gitAll\\\\agent-cloud-harness\\\\src\\\\main\\\\java\\\\com\\\\agentcloud\\\\engine\\\\TaskService.java 并补测试。",
                          "goal":"验证 HTTP 直建任务也能给 worker 明确本地执行合同。",
                          "metadata":{
                            "validation_commands":["mvn -Dtest=TaskServiceAutoStartTest test"],
                            "write_scope":["src/main/java/com/agentcloud/engine","docs"],
                            "acceptance_criteria":["HTTP 响应和 DB 都能看到 provider 执行合同"]
                          },
                          "auto_start":false
                        }
                        """))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));
            Map<String, Object> responseMetadata = harness.map(data.get("metadata"));
            String taskId = data.get("id").toString();

            assertEquals(200, response.statusCode());
            assertEquals("D:\\gitAll\\agent-cloud-harness", responseMetadata.get("workspace_root"));
            assertEquals("D:\\gitAll\\agent-cloud-harness", responseMetadata.get("cwd"));
            assertEquals("D:\\gitAll\\agent-cloud-harness", responseMetadata.get("repo_path"));
            assertTrue(((List<?>) responseMetadata.get("reference_paths"))
                .contains("D:\\gitAll\\agent-cloud-harness\\src\\main\\java\\com\\agentcloud\\engine\\TaskService.java"));
            assertEquals(List.of("mvn -Dtest=TaskServiceAutoStartTest test"), responseMetadata.get("validation_commands"));
            assertEquals(List.of("src/main/java/com/agentcloud/engine", "docs"), responseMetadata.get("write_scope"));
            assertEquals(List.of("HTTP 响应和 DB 都能看到 provider 执行合同"), responseMetadata.get("acceptance_criteria"));

            Task persisted = harness.taskDao.findById(taskId).orElseThrow();
            assertEquals(responseMetadata.get("repo_path"), persisted.metadata().get("repo_path"));
            assertEquals(responseMetadata.get("reference_paths"), persisted.metadata().get("reference_paths"));
            assertEquals(responseMetadata.get("target_paths"), persisted.metadata().get("target_paths"));
            assertEquals(responseMetadata.get("validation_commands"), persisted.metadata().get("validation_commands"));

            Event createdEvent = harness.eventDao.listBySessionAndTask(persisted.sessionId(), persisted.id(), 20).stream()
                .filter(event -> "task_created".equals(event.eventType()))
                .findFirst()
                .orElseThrow();
            assertEquals("coding", createdEvent.payload().get("task_type"));
            assertEquals(Boolean.FALSE, createdEvent.payload().get("auto_start"));
            assertEquals("http_api", createdEvent.payload().get("requested_via"));
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
    void getRecoverableTasksListsRecentInterruptedTasksBeforeGenericTaskRoute() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-recoverable-list.db"))) {
            Task interrupted = harness.createManualTask("recoverable coding", "coding");
            harness.saveTask(interrupted
                .withStatus("waiting_human")
                .withControlNode("human_gate")
                .withAssignedWorker("codex")
                .withMetadata(new java.util.LinkedHashMap<>(Map.of(
                    "failure_class", "worker_runtime_transient",
                    "provider_failure_class", "provider_runtime_transient"
                ))));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/recoverable?limit=5"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            List<Map<String, Object>> plans = harness.list(payload.get("data"));

            assertEquals(200, response.statusCode());
            assertEquals(1, plans.size());
            assertEquals(interrupted.id(), plans.getFirst().get("task_id"));
            assertEquals(Boolean.TRUE, plans.getFirst().get("recoverable"));
            assertEquals("fresh_session", plans.getFirst().get("recovery_execution_mode"));
        }
    }

    @Test
    void getRecoverableTasksClassifiesProviderFailureFromWaitingReasonEvidence() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-recoverable-waiting-reason.db"))) {
            Task interrupted = harness.createManualTask("recoverable waiting reason", "coding");
            harness.saveTask(interrupted
                .withStatus("waiting_human")
                .withControlNode("human_gate")
                .withAssignedWorker("codex")
                .withWaitingReason("thread not found: 29180"));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/recoverable?limit=5"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            List<Map<String, Object>> plans = harness.list(payload.get("data"));

            assertEquals(200, response.statusCode());
            assertEquals(1, plans.size());
            assertEquals(interrupted.id(), plans.getFirst().get("task_id"));
            assertEquals("provider_runtime_transient", plans.getFirst().get("provider_failure_class"));
            assertEquals("task.waiting_reason", plans.getFirst().get("failure_evidence_source"));
            assertEquals("thread not found: 29180", plans.getFirst().get("failure_evidence"));
            assertEquals("fresh_session", plans.getFirst().get("recovery_execution_mode"));
        }
    }

    @Test
    void getRecoverableTasksUsesAgentRunProviderErrorAsFailureEvidence() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-recoverable-agent-run-provider-error.db"))) {
            Task interrupted = harness.createManualTask("recoverable provider error", "coding");
            harness.saveTask(interrupted
                .withStatus("waiting_human")
                .withControlNode("human_gate")
                .withAssignedWorker("codex"));
            Instant startedAt = Instant.parse("2026-05-18T10:00:00Z");
            harness.agentRunDao.insert(new AgentRunRecord(
                "arun_provider_error",
                interrupted.id(),
                interrupted.sessionId(),
                "codex",
                "Codex",
                "planner_executor",
                "codex",
                "strong",
                "timeout",
                startedAt,
                startedAt.plusMillis(150_000),
                150_000L,
                "thread not found: 27316",
                "run.failed",
                0,
                Map.of(
                    "worker_execution_status", "timeout",
                    "provider_error", "codex turn completion timed out",
                    "provider_turn_status", "timeout",
                    "provider_failure_class", "provider_runtime_transient",
                    "provider_failure_reason", "codex turn completion timed out"
                )
            ));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/recoverable?limit=5"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> body = NioHttpServer.SHARED_MAPPER.readValue(response.body(), Map.class);
            List<Map<String, Object>> plans = (List<Map<String, Object>>) body.get("data");
            assertEquals(200, response.statusCode());
            assertEquals(1, plans.size());
            assertEquals(interrupted.id(), plans.getFirst().get("task_id"));
            assertEquals("provider_runtime_transient", plans.getFirst().get("provider_failure_class"));
            assertEquals("agent_run.metadata.provider_error", plans.getFirst().get("failure_evidence_source"));
            assertEquals("codex turn completion timed out", plans.getFirst().get("failure_evidence"));
            assertEquals("fresh_session", plans.getFirst().get("recovery_execution_mode"));
        }
    }

    @Test
    void getRecoverableTasksClassifiesOversizedOutputFailureAsRuntimeTransient() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-recoverable-large-output.db"))) {
            Task interrupted = harness.createManualTask("recoverable large output", "coding");
            harness.saveTask(interrupted
                .withStatus("failed")
                .withControlNode("human_gate")
                .withAssignedWorker("codex")
                .withSummary("provider failed after codex produced output too large for the response channel"));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/recoverable?limit=5"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            List<Map<String, Object>> plans = harness.list(payload.get("data"));

            assertEquals(200, response.statusCode());
            assertEquals(1, plans.size());
            assertEquals(interrupted.id(), plans.getFirst().get("task_id"));
            assertEquals(Boolean.TRUE, plans.getFirst().get("recoverable"));
            assertEquals("provider_runtime_transient", plans.getFirst().get("provider_failure_class"));
            assertEquals("task.summary", plans.getFirst().get("failure_evidence_source"));
            assertEquals("fresh_session", plans.getFirst().get("recovery_execution_mode"));
        }
    }

    @Test
    void postRecoverAsyncAcceptsOversizedOutputFailureAsFreshSessionRecovery() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-recover-async-large-output.db"))) {
            Task interrupted = harness.createManualTask("recover async large output", "coding");
            harness.saveTask(interrupted
                .withStatus("failed")
                .withControlNode("human_gate")
                .withAssignedWorker("codex")
                .withSummary("provider failed: output too large, maximum output exceeded")
                .withMetadata(new java.util.LinkedHashMap<>(Map.of(
                    "provider_thread_id", "thread_with_large_output",
                    "codex_thread_id", "codex_large_output"
                ))));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + interrupted.id() + "/recover?async=true"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"auto\",\"reason\":\"recover large output\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));
            Map<String, Object> plan = harness.map(data.get("plan"));

            assertEquals(202, response.statusCode());
            assertEquals(Boolean.TRUE, data.get("accepted"));
            assertEquals(Boolean.TRUE, data.get("async"));
            assertEquals(Boolean.TRUE, plan.get("recoverable"));
            assertEquals("provider_runtime_transient", plan.get("provider_failure_class"));
            assertEquals("task.summary", plan.get("failure_evidence_source"));
            assertEquals("fresh_session", plan.get("recovery_execution_mode"));

            List<Map<String, Object>> jobs = waitForRecoveryJobStatus(
                harness,
                interrupted.id(),
                data.get("request_id").toString(),
                "succeeded"
            );
            assertEquals("resume", jobs.getFirst().get("recommended_action"));
            assertEquals("fresh_session", jobs.getFirst().get("recovery_execution_mode"));
            Task persisted = harness.taskDao.findById(interrupted.id()).orElseThrow();
            assertFalse(persisted.metadata().containsKey("provider_thread_id"));
            assertFalse(persisted.metadata().containsKey("codex_thread_id"));
        }
    }

    @Test
    void postRecoverResumesWithFreshSessionMetadataAndClearsProviderContinuation() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-recover-post.db"))) {
            Task interrupted = harness.createManualTask("recover post", "coding");
            harness.saveTask(interrupted
                .withStatus("waiting_human")
                .withControlNode("human_gate")
                .withAssignedWorker("codex")
                .withWaitingReason("thread not found")
                .withMetadata(new java.util.LinkedHashMap<>(Map.of(
                    "failure_class", "worker_runtime_transient",
                    "provider_failure_class", "provider_runtime_transient",
                    "provider_session_id", "sess_old",
                    "provider_thread_id", "thread_old",
                    "codex_thread_id", "codex_old"
                ))));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + interrupted.id() + "/recover"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"auto\",\"reason\":\"retry from test\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));
            Map<String, Object> plan = harness.map(data.get("plan"));
            Map<String, Object> control = harness.map(data.get("control_result"));
            Task persisted = harness.taskDao.findById(interrupted.id()).orElseThrow();

            assertEquals(200, response.statusCode());
            assertEquals(Boolean.TRUE, plan.get("recoverable"));
            assertEquals("resume", plan.get("recommended_action"));
            assertEquals("resume", control.get("decision"));
            assertEquals("active", persisted.status());
            assertEquals("scheduler", persisted.controlNode());
            assertEquals("fresh_session", persisted.metadata().get("recovery_execution_mode"));
            assertEquals(Boolean.TRUE, persisted.metadata().get("manual_recovery_requested"));
            assertFalse(persisted.metadata().containsKey("provider_session_id"));
            assertFalse(persisted.metadata().containsKey("provider_thread_id"));
            assertFalse(persisted.metadata().containsKey("codex_thread_id"));
        }
    }

    @Test
    void postRecoverAsyncReturnsAcceptedWithoutWaitingForControlResult() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-recover-async.db"))) {
            Task interrupted = harness.createManualTask("recover async", "coding");
            harness.saveTask(interrupted
                .withStatus("waiting_human")
                .withControlNode("human_gate")
                .withAssignedWorker("codex")
                .withWaitingReason("thread not found")
                .withMetadata(new java.util.LinkedHashMap<>(Map.of(
                    "failure_class", "worker_runtime_transient",
                    "provider_failure_class", "provider_runtime_transient",
                    "provider_thread_id", "thread_old"
                ))));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + interrupted.id() + "/recover?async=true"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"auto\",\"reason\":\"async retry\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));
            Map<String, Object> plan = harness.map(data.get("plan"));

            assertEquals(202, response.statusCode());
            assertEquals(Boolean.TRUE, data.get("accepted"));
            assertEquals(Boolean.TRUE, data.get("async"));
            assertTrue(data.get("request_id").toString().startsWith("recovery_"));
            assertEquals("/api/v1/tasks/" + interrupted.id() + "/live_flow", data.get("status_url"));
            assertFalse(data.containsKey("control_result"));
            assertFalse(data.containsKey("handoff_result"));
            assertEquals(Boolean.TRUE, plan.get("recoverable"));
            assertEquals("fresh_session", plan.get("recovery_execution_mode"));

            HttpResponse<String> jobsResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + interrupted.id() + "/recovery_jobs?limit=5"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            Map<String, Object> jobsPayload = harness.readJson(jobsResponse.body());
            List<Map<String, Object>> jobs = harness.list(jobsPayload.get("data"));

            assertEquals(200, jobsResponse.statusCode());
            assertEquals(1, jobs.size());
            assertEquals(data.get("request_id"), jobs.getFirst().get("id"));
            assertEquals(interrupted.id(), jobs.getFirst().get("task_id"));
            assertTrue(List.of("accepted", "running", "succeeded").contains(jobs.getFirst().get("status")));
            assertEquals("resume", jobs.getFirst().get("recommended_action"));
            assertEquals("fresh_session", jobs.getFirst().get("recovery_execution_mode"));

            waitForRecoveryJobStatus(
                harness,
                interrupted.id(),
                data.get("request_id").toString(),
                "succeeded"
            );
        }
    }

    @Test
    void postRecoverAsyncAutoHandoffUsesTargetWorkerAndRecordsJob() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-recover-async-handoff.db"))) {
            Task interrupted = harness.createManualTask("recover async handoff", "coding");
            harness.saveTask(interrupted
                .withStatus("waiting_human")
                .withControlNode("human_gate")
                .withAssignedWorker("codex")
                .withWaitingReason("thread not found")
                .withMetadata(new java.util.LinkedHashMap<>(Map.of(
                    "failure_class", "worker_runtime_transient",
                    "provider_failure_class", "provider_runtime_transient"
                ))));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + interrupted.id() + "/recover?async=true"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"auto\",\"target_worker\":\"claude\",\"reason\":\"async switch\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));
            Map<String, Object> plan = harness.map(data.get("plan"));
            String requestId = data.get("request_id").toString();

            assertEquals(202, response.statusCode());
            assertEquals(Boolean.TRUE, data.get("accepted"));
            assertEquals("handoff", plan.get("recommended_action"));
            assertEquals("claude", plan.get("target_worker"));
            assertFalse(data.containsKey("control_result"));
            assertFalse(data.containsKey("handoff_result"));

            List<Map<String, Object>> jobs = waitForRecoveryJobStatus(
                harness, interrupted.id(), requestId, "succeeded");
            Map<String, Object> job = jobs.stream()
                .filter(item -> requestId.equals(String.valueOf(item.get("id"))))
                .findFirst()
                .orElseThrow();
            Task persisted = harness.taskDao.findById(interrupted.id()).orElseThrow();

            assertEquals("handoff", job.get("recommended_action"));
            assertEquals("claude", job.get("target_worker"));
            assertEquals("fresh_session", job.get("recovery_execution_mode"));
            assertEquals("claude", persisted.assignedWorker());
            assertEquals("scheduler", persisted.controlNode());
        }
    }

    @Test
    void postRecoverAsyncStillRejectsProviderEnvironmentBlockedFailures() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-recover-async-blocked.db"))) {
            Task interrupted = harness.createManualTask("recover async blocked", "coding");
            harness.saveTask(interrupted
                .withStatus("waiting_human")
                .withControlNode("human_gate")
                .withAssignedWorker("codex")
                .withMetadata(new java.util.LinkedHashMap<>(Map.of(
                    "provider_failure_class", "provider_auth_failed"
                ))));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + interrupted.id() + "/recover?async=true"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"auto\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());

            assertEquals(400, response.statusCode());
            assertEquals(Boolean.FALSE, payload.get("success"));
            assertTrue(payload.get("message").toString().contains("provider_auth_failed"));
        }
    }

    @Test
    void postRecoverAsyncRecordsFailedJobWithSanitizedErrorSummary() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-recover-async-failed.db"))) {
            harness.failNextResume("provider unavailable token=secret-token\n" + "x".repeat(600));
            Task interrupted = harness.createManualTask("recover async failed", "coding");
            harness.saveTask(interrupted
                .withStatus("waiting_human")
                .withControlNode("human_gate")
                .withAssignedWorker("codex")
                .withWaitingReason("thread not found")
                .withMetadata(new java.util.LinkedHashMap<>(Map.of(
                    "failure_class", "worker_runtime_transient",
                    "provider_failure_class", "provider_runtime_transient",
                    "provider_thread_id", "thread_old"
                ))));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + interrupted.id() + "/recover?async=true"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"auto\",\"reason\":\"async failed retry\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));

            List<Map<String, Object>> jobs = waitForRecoveryJobStatus(
                harness, interrupted.id(), data.get("request_id").toString(), "failed");
            Map<String, Object> failedJob = jobs.getFirst();

            assertEquals(202, response.statusCode());
            assertEquals("failed", failedJob.get("status"));
            assertTrue(failedJob.get("error_message").toString().contains("provider unavailable"));
            assertFalse(failedJob.get("error_message").toString().contains("secret-token"));
            assertTrue(failedJob.get("error_message").toString().length() <= 223);
        }
    }

    @Test
    void recoveryJobDaoMarksActiveJobsInterruptedOnStartupReconcile() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-recovery-job-reconcile.db"))) {
            Task task = harness.createManualTask("recover interrupted", "coding");
            Instant acceptedAt = Instant.parse("2026-05-17T10:00:00Z");
            harness.recoveryJobDao.insert(new com.agentcloud.model.TaskRecoveryJob(
                "recovery_accepted",
                task.id(),
                task.sessionId(),
                "accepted",
                "auto",
                "resume",
                null,
                "fresh_session",
                "worker_runtime_transient",
                "provider_runtime_transient",
                "/api/v1/tasks/" + task.id() + "/live_flow",
                acceptedAt,
                null,
                null,
                null,
                Map.of()
            ));
            harness.recoveryJobDao.insert(new com.agentcloud.model.TaskRecoveryJob(
                "recovery_running",
                task.id(),
                task.sessionId(),
                "running",
                "auto",
                "resume",
                null,
                "fresh_session",
                "worker_runtime_transient",
                "provider_runtime_transient",
                "/api/v1/tasks/" + task.id() + "/live_flow",
                acceptedAt,
                acceptedAt.plusSeconds(1),
                null,
                null,
                Map.of()
            ));
            harness.recoveryJobDao.insert(new com.agentcloud.model.TaskRecoveryJob(
                "recovery_succeeded",
                task.id(),
                task.sessionId(),
                "succeeded",
                "auto",
                "resume",
                null,
                "fresh_session",
                "worker_runtime_transient",
                "provider_runtime_transient",
                "/api/v1/tasks/" + task.id() + "/live_flow",
                acceptedAt,
                acceptedAt.plusSeconds(1),
                acceptedAt.plusSeconds(2),
                null,
                Map.of()
            ));

            Instant reconciledAt = Instant.parse("2026-05-17T10:05:00Z");
            int updated = harness.recoveryJobDao.markActiveJobsInterrupted(
                reconciledAt,
                "harness restarted before async recovery completed"
            );
            List<com.agentcloud.model.TaskRecoveryJob> jobs = harness.recoveryJobDao.listByTask(task.id(), 10);

            assertEquals(2, updated);
            Map<String, com.agentcloud.model.TaskRecoveryJob> byId = jobs.stream()
                .collect(java.util.stream.Collectors.toMap(com.agentcloud.model.TaskRecoveryJob::id, job -> job));
            assertEquals("interrupted", byId.get("recovery_accepted").status());
            assertEquals(reconciledAt, byId.get("recovery_accepted").completedAt());
            assertEquals("harness restarted before async recovery completed", byId.get("recovery_accepted").errorMessage());
            assertEquals("interrupted", byId.get("recovery_running").status());
            assertEquals("succeeded", byId.get("recovery_succeeded").status());
            assertEquals(acceptedAt.plusSeconds(2), byId.get("recovery_succeeded").completedAt());

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/recovery_jobs?limit=10"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            Map<String, Object> payload = harness.readJson(response.body());
            List<Map<String, Object>> apiJobs = harness.list(payload.get("data"));
            Map<String, Map<String, Object>> apiJobsById = apiJobs.stream()
                .collect(java.util.stream.Collectors.toMap(job -> job.get("id").toString(), job -> job));
            assertEquals("interrupted", apiJobsById.get("recovery_accepted").get("status"));
            assertEquals("harness restarted before async recovery completed",
                apiJobsById.get("recovery_accepted").get("error_message"));
            assertEquals("interrupted", apiJobsById.get("recovery_running").get("status"));
            assertEquals("succeeded", apiJobsById.get("recovery_succeeded").get("status"));
        }
    }

    @Test
    void toolTraceExposesExecutionStatusAndTouchedPaths() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-tool-trace-contract.db"))) {
            Task task = harness.createManualTask("tool trace contract", "coding");
            Instant now = Instant.parse("2026-05-17T06:30:00Z");
            harness.toolInvocationDao.insert(new ToolInvocationRecord(
                "tool_trace_contract_1",
                task.sessionId(),
                task.id(),
                "codex",
                "exec_tool_trace_contract",
                "write_file",
                Map.of("path", "docs/output.md"),
                "updated output doc",
                "succeeded",
                true,
                42,
                List.of("docs/output.md"),
                now,
                Map.of("tool_execution_mode", "multi_tool_round")
            ));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/tool_trace?limit=5"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            List<Map<String, Object>> traces = harness.list(payload.get("data"));
            Map<String, Object> trace = traces.getFirst();

            assertEquals(200, response.statusCode());
            assertEquals("tool_trace_contract_1", trace.get("id"));
            assertEquals("exec_tool_trace_contract", trace.get("execution_id"));
            assertEquals("succeeded", trace.get("status"));
            assertEquals(Boolean.TRUE, trace.get("success"));
            assertEquals(List.of("docs/output.md"), trace.get("touched_paths"));
            assertEquals("write_file", trace.get("tool_name"));
        }
    }

    @Test
    void postRecoverAutoHandoffUsesTargetWorkerAndReturnsHandoffResult() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-recover-auto-handoff.db"))) {
            Task interrupted = harness.createManualTask("recover handoff", "coding");
            harness.saveTask(interrupted
                .withStatus("waiting_human")
                .withControlNode("human_gate")
                .withAssignedWorker("codex")
                .withMetadata(new java.util.LinkedHashMap<>(Map.of(
                    "failure_class", "worker_runtime_transient",
                    "provider_failure_class", "provider_runtime_transient",
                    "auto_handoff_target", "claude"
                ))));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + interrupted.id() + "/recover"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"auto\",\"reason\":\"switch worker\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));
            Map<String, Object> plan = harness.map(data.get("plan"));
            Map<String, Object> handoff = harness.map(data.get("handoff_result"));
            Task persisted = harness.taskDao.findById(interrupted.id()).orElseThrow();

            assertEquals(200, response.statusCode());
            assertEquals(Boolean.TRUE, plan.get("recoverable"));
            assertEquals("handoff", plan.get("recommended_action"));
            assertEquals("claude", plan.get("target_worker"));
            assertEquals("codex", handoff.get("previous_worker"));
            assertEquals("claude", handoff.get("assigned_worker"));
            assertEquals("claude", persisted.assignedWorker());
            assertEquals("scheduler", persisted.controlNode());
        }
    }

    @Test
    void postRecoverRejectsProviderEnvironmentBlockedFailures() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-recover-blocked.db"))) {
            Task interrupted = harness.createManualTask("recover blocked", "coding");
            harness.saveTask(interrupted
                .withStatus("waiting_human")
                .withControlNode("human_gate")
                .withAssignedWorker("codex")
                .withMetadata(new java.util.LinkedHashMap<>(Map.of(
                    "provider_failure_class", "provider_auth_failed"
                ))));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + interrupted.id() + "/recover"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"auto\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());

            assertEquals(400, response.statusCode());
            assertEquals(Boolean.FALSE, payload.get("success"));
            assertTrue(payload.get("message").toString().contains("provider_auth_failed"));
        }
    }

    @Test
    void selectWorkerIncludesPinnedAndUnpinnedRecoveryDiagnostics() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-select-worker-diagnostics.db"))) {
            Task task = harness.createManualTask("pinned coding task", "coding");
            Task pinned = task.withAssignedWorker("claude").withMetadata(new java.util.LinkedHashMap<>(Map.of(
                "task_type", "coding",
                "assigned_worker", "claude"
            )));
            harness.saveTask(pinned);

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + pinned.id() + "/select_worker"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));
            Map<String, Object> currentPinned = harness.map(data.get("current_pinned_route"));
            Map<String, Object> recoveryUnpinned = harness.map(data.get("recovery_unpinned_recommendation"));

            assertEquals(200, response.statusCode());
            assertEquals("claude", data.get("selected_worker"));
            assertEquals("task_pinned", data.get("route_source"));
            assertEquals("claude", currentPinned.get("selected_worker"));
            assertEquals("task_pinned", currentPinned.get("route_source"));
            assertEquals("codex", recoveryUnpinned.get("selected_worker"));
            assertEquals("capability_match", recoveryUnpinned.get("route_source"));
            assertEquals("coding", recoveryUnpinned.get("task_type"));
        }
    }

    @Test
    void selectWorkerProjectsTopLevelRecoveryProviderDeprioritizationHints() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-select-worker-provider-deprioritized.db"))) {
            Task task = harness.createManualTask("pinned coding task", "coding");
            Task pinned = task.withAssignedWorker("claude").withMetadata(new java.util.LinkedHashMap<>(Map.of(
                "task_type", "coding",
                "assigned_worker", "claude"
            )));
            harness.saveTask(pinned);

            Instant base = Instant.parse("2026-04-29T14:20:00Z");
            harness.agentRunDao.insert(new AgentRunRecord(
                "arun_claude_select_hot_1",
                task.id(),
                task.sessionId(),
                "claude",
                "Claude",
                "planner_executor",
                "claude",
                "strong",
                "failed",
                base.minusSeconds(20),
                base.minusSeconds(18),
                200L,
                "worker claude failed: thread not found (15252)",
                "run.failed",
                0,
                Map.of("worker_execution_status", "failed")
            ));
            harness.agentRunDao.insert(new AgentRunRecord(
                "arun_claude_select_hot_2",
                task.id(),
                task.sessionId(),
                "claude",
                "Claude",
                "planner_executor",
                "claude",
                "strong",
                "timeout",
                base.minusSeconds(10),
                base.minusSeconds(8),
                150L,
                "worker claude failed: provider unavailable",
                "run.failed",
                0,
                Map.of("worker_execution_status", "timeout")
            ));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + pinned.id() + "/select_worker"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));

            assertEquals(200, response.statusCode());
            assertEquals("claude", data.get("selected_worker"));
            assertEquals(Boolean.TRUE, data.get("recovery_provider_deprioritized"));
            assertEquals("claude", data.get("recovery_deprioritized_provider"));
            assertEquals("recent transient provider failures", data.get("recovery_deprioritization_reason"));
        }
    }

    @Test
    void selectWorkerPromotesContinuationRepoModificationTaskToEffectiveCodingType() throws Exception {
        try (TestHarness harness = new TestHarness(tempDir.resolve("task-handler-select-worker-effective-task-type.db"))) {
            Task task = harness.createManualTask("repo modification task", "continuation");
            Task updated = task.withMetadata(new java.util.LinkedHashMap<>(Map.of(
                "task_type", "continuation",
                "goal", "根据文档修改 D:\\gitAll\\articleeditor\\src\\main\\java\\ArticleThirdService.java，并补测试。"
            )));
            harness.saveTask(updated);

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/select_worker"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));

            assertEquals(200, response.statusCode());
            assertEquals("coding", data.get("task_type"));
            assertEquals("codex", data.get("selected_worker"));
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

    @Test
    void getRuntimeContextReturnsMountedContextViewSurface() throws Exception {
        try (RuntimeContextHarness harness = new RuntimeContextHarness(tempDir.resolve("task-handler-runtime-context.db"))) {
            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/task_runtime_http/runtime_context"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));
            Map<String, Object> mountedContextView = harness.map(data.get("mounted_context_view"));
            List<Map<String, Object>> panels = harness.list(mountedContextView.get("panels"));
            List<?> selectionTrace = (List<?>) mountedContextView.get("selection_trace");
            Map<String, Object> pinnedPanel = panels.stream()
                .filter(panel -> "pinned".equals(panel.get("name")))
                .findFirst()
                .orElseThrow();
            Map<String, Object> archivePanel = panels.stream()
                .filter(panel -> "archive_handles".equals(panel.get("name")))
                .findFirst()
                .orElseThrow();
            List<Map<String, Object>> pinnedObjects = harness.list(pinnedPanel.get("objects"));
            List<Map<String, Object>> archiveObjects = harness.list(archivePanel.get("objects"));
            Map<String, Object> retrievalCapsule = archiveObjects.stream()
                .filter(object -> "Retrieval Policy Capsule".equals(object.get("title")))
                .findFirst()
                .orElseThrow();
            Map<String, Object> retrievalMetadata = harness.map(retrievalCapsule.get("metadata"));

            assertEquals(200, response.statusCode());
            assertEquals("task_runtime_http", mountedContextView.get("task_id"));
            assertTrue(selectionTrace.contains("compat_mode=task_runtime_context_preserved"));
            assertEquals(1, pinnedObjects.size());
            assertEquals("constraint", pinnedObjects.getFirst().get("type"));
            assertEquals("pinned", pinnedObjects.getFirst().get("retention_state"));
            assertEquals("/sessions/session_runtime_http/tasks/task_runtime_http/constraints",
                pinnedObjects.getFirst().get("path"));
            assertEquals("/sessions/session_runtime_http/tasks/task_runtime_http",
                pinnedObjects.getFirst().get("parent_path"));
            assertEquals("cold_capsule", retrievalCapsule.get("retention_state"));
            assertEquals(Boolean.TRUE, retrievalMetadata.get("needs_archive_retrieval"));
            assertEquals(Boolean.TRUE, retrievalMetadata.get("needs_external_fact_refresh"));
            assertEquals(List.of(
                    "/sessions/session_runtime_http/tasks/task_runtime_http/tool_invocations",
                    "/sessions/session_runtime_http/tasks/task_runtime_http/packets/packet_runtime_http"
                ),
                retrievalMetadata.get("retrieval_candidate_paths"));
        }
    }

    @Test
    void getHandoffPacketReturnsSharedRuntimeFactSurface() throws Exception {
        try (HandoffPacketHarness harness = new HandoffPacketHarness(tempDir.resolve("task-handler-handoff-packet.db"))) {
            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/task_packet_http/handoff_packet?target_worker=kimi"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));
            Map<String, Object> handoffPacket = harness.map(data.get("handoff_packet"));
            Map<String, Object> metadata = harness.map(handoffPacket.get("metadata"));
            Map<String, Object> runtimeFacts = harness.map(metadata.get("runtime_facts"));
            Map<String, Object> runtimeSurface = harness.map(metadata.get("runtime_cognition_surface"));
            Map<String, Object> routeSurface = harness.map(runtimeSurface.get("route"));
            Map<String, Object> executionSurface = harness.map(runtimeSurface.get("execution"));

            assertEquals(200, response.statusCode());
            assertEquals("task_packet_http", data.get("task_id"));
            assertEquals("codex", data.get("from_worker"));
            assertEquals("kimi", data.get("to_worker"));
            assertEquals("1.0", handoffPacket.get("packet_version"));
            assertEquals(Boolean.TRUE, handoffPacket.get("machine_readable_first"));
            assertEquals("orchestrated", metadata.get("model_mode"));
            assertEquals("execution_pending", metadata.get("orchestration_stage"));
            assertEquals("mounted_context_shadow", metadata.get("prompt_mode"));
            assertEquals("task_packet_http", runtimeFacts.get("task_id"));
            assertEquals("Apply the final executor patch.", runtimeFacts.get("recommended_next_step"));
            assertEquals("codex", routeSurface.get("selected_worker"));
            assertEquals("preassigned", routeSurface.get("route_source"));
            assertEquals("mounted_context_shadow", executionSurface.get("prompt_mode"));
        }
    }

    private static List<Map<String, Object>> waitForRecoveryJobStatus(TestHarness harness,
                                                                       String taskId,
                                                                       String requestId,
                                                                       String expectedStatus) throws Exception {
        AssertionError lastFailure = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            HttpResponse<String> jobsResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + taskId + "/recovery_jobs?limit=5"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            Map<String, Object> jobsPayload = harness.readJson(jobsResponse.body());
            List<Map<String, Object>> jobs = harness.list(jobsPayload.get("data"));
            if (!jobs.isEmpty() && requestId.equals(jobs.getFirst().get("id"))) {
                if (expectedStatus.equals(jobs.getFirst().get("status"))) {
                    return jobs;
                }
                lastFailure = new AssertionError("expected recovery job status "
                    + expectedStatus + " but was " + jobs.getFirst().get("status"));
            }
            Thread.sleep(50);
        }
        throw lastFailure != null ? lastFailure : new AssertionError("recovery job not found: " + requestId);
    }

    private static final class FailingResumeControlNodeGraph extends ControlNodeGraph {
        private final TaskDao taskDao;
        private String resumeFailureMessage;

        private FailingResumeControlNodeGraph(TaskDao taskDao, EventDao eventDao, SessionDao sessionDao) {
            super(taskDao, eventDao, sessionDao, null, null, null, null,
                null, null, null, null, null, null);
            this.taskDao = taskDao;
        }

        private void failNextResume(String message) {
            this.resumeFailureMessage = message;
        }

        @Override
        public Task triggerPause(Task task, String reason) {
            Task updated = task.withStatus("paused")
                .withControlNode("packet")
                .withAssignedWorker("codex")
                .withWaitingReason(reason);
            taskDao.updateState(updated);
            return updated;
        }

        @Override
        public Task triggerResume(Task task) {
            if (resumeFailureMessage != null) {
                String message = resumeFailureMessage;
                resumeFailureMessage = null;
                throw new IllegalStateException(message);
            }
            Task updated = task.withStatus("active")
                .withControlNode("scheduler")
                .withWaitingReason(null);
            taskDao.updateState(updated);
            return updated;
        }

        @Override
        public Task triggerHandoff(Task task, String targetWorker) {
            Task updated = task.withStatus("active")
                .withControlNode("scheduler")
                .withAssignedWorker(targetWorker)
                .withWaitingReason(null);
            taskDao.updateState(updated);
            return updated;
        }
    }

    private static final class TestHarness implements AutoCloseable {
        private final DatabaseManager db;
        private final TaskService service;
        private final TaskDao taskDao;
        private final SessionDao sessionDao;
        private final SessionMessageDao messageDao;
        private final EventDao eventDao;
        private final AgentRunDao agentRunDao;
        private final ToolInvocationDao toolInvocationDao;
        private final TaskRecoveryJobDao recoveryJobDao;
        private final FailingResumeControlNodeGraph graph;
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
            this.agentRunDao = db.jdbi().onDemand(AgentRunDao.class);
            this.toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            this.recoveryJobDao = db.jdbi().onDemand(TaskRecoveryJobDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            WorkerRouter workerRouter = new WorkerRouter(new WorkerRegistry());
            PacketBuilder packetBuilder = new PacketBuilder(decisionDao, artifactDao, taskDao);

            this.graph = new FailingResumeControlNodeGraph(taskDao, this.eventDao, this.sessionDao);

            this.service = new TaskService(
                taskDao, this.sessionDao, this.eventDao, null, workerRouter, packetBuilder, graph,
                null, null, null, null, this.toolInvocationDao, messageDao, null,
                new AgentRunService(agentRunDao, new AgentProviderRegistry()), this.recoveryJobDao
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

        private void failNextResume(String message) {
            graph.failNextResume(message);
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

    private static final class RuntimeContextHarness implements AutoCloseable {
        private final DatabaseManager db;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final int port;

        private RuntimeContextHarness(Path dbPath) throws IOException {
            this.db = new DatabaseManager(dbPath);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);

            Session session = Session.create("session_runtime_http", "runtime http", "active");
            sessionDao.insert(session);
            Task task = new Task(
                "task_runtime_http",
                session.id(),
                null,
                "runtime context endpoint",
                "active",
                "high",
                Instant.parse("2026-05-06T07:10:00Z"),
                Instant.parse("2026-05-06T07:10:00Z"),
                null,
                null,
                null,
                "已有 runtime summary",
                "验证 runtime_context HTTP 输出",
                null,
                "codex",
                "continue",
                null,
                Map.of("task_type", "coding")
            );
            taskDao.insert(task);
            eventDao.insert(new Event(
                "evt_runtime_http",
                session.id(),
                task.id(),
                Instant.parse("2026-05-06T07:10:01Z"),
                "task_progressed",
                "system",
                null,
                "mounted context runtime prepared",
                Map.of()
            ));

            TaskRuntimeContext runtimeContext = new TaskRuntimeContext(
                task,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new ActiveContext(
                    "runtime http",
                    List.of("priority=high"),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of("保留关键约束"),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of("budget=12"),
                    "runtime summary",
                    "runtime synthesized context",
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
                                "constraint_runtime_http",
                                "/sessions/session_runtime_http/tasks/task_runtime_http/constraints",
                                ContextObjectType.CONSTRAINT,
                                "/sessions/session_runtime_http/tasks/task_runtime_http",
                                "Constraints",
                                "runtime_context 接口需要暴露 mounted context",
                                "runtime_context 接口需要暴露 mounted context",
                                Instant.parse("2026-05-06T07:10:02Z"),
                                ContextRetentionState.PINNED,
                                List.of(),
                                List.of(),
                                Map.of("constraint_count", 1)
                            ))
                        ),
                        new MountedContextPanel(
                            MountedContextPanelName.ARCHIVE_HANDLES,
                            "Archive Handles",
                            List.of(new ContextObject(
                                "retrieval_capsule_runtime_http",
                                "/sessions/session_runtime_http/tasks/task_runtime_http/archive/retrieval_policy_capsule",
                                ContextObjectType.CAPSULE,
                                "/sessions/session_runtime_http/tasks/task_runtime_http",
                                "Retrieval Policy Capsule",
                                "Archive retrieval is recommended before the next round.",
                                "needs_archive_retrieval: true\nneeds_external_fact_refresh: true",
                                Instant.parse("2026-05-06T07:10:03Z"),
                                ContextRetentionState.COLD_CAPSULE,
                                List.of(
                                    new ContextReference(
                                        "reopen_candidate",
                                        "/sessions/session_runtime_http/tasks/task_runtime_http/tool_invocations",
                                        "tool_invocations"
                                    ),
                                    new ContextReference(
                                        "reopen_candidate",
                                        "/sessions/session_runtime_http/tasks/task_runtime_http/packets/packet_runtime_http",
                                        "packets:packet_runtime_http"
                                    )
                                ),
                                List.of(),
                                Map.of(
                                    "needs_archive_retrieval", true,
                                    "needs_external_fact_refresh", true,
                                    "retrieval_candidate_paths", List.of(
                                        "/sessions/session_runtime_http/tasks/task_runtime_http/tool_invocations",
                                        "/sessions/session_runtime_http/tasks/task_runtime_http/packets/packet_runtime_http"
                                    ),
                                    "reopen_candidate_paths", List.of(
                                        "/sessions/session_runtime_http/tasks/task_runtime_http/tool_invocations",
                                        "/sessions/session_runtime_http/tasks/task_runtime_http/packets/packet_runtime_http"
                                    ),
                                    "target_path", "/sessions/session_runtime_http/tasks/task_runtime_http/tool_invocations"
                                )
                            ))
                        )
                    ),
                    List.of("compat_mode=task_runtime_context_preserved")
                )
            );

            TaskService service = new TaskService(
                taskDao, sessionDao, eventDao, null, new WorkerRouter(new WorkerRegistry()), null, null,
                null, null, null, null, null
            ) {
                @Override
                public TaskRuntimeContext getRuntimeContext(String taskId) {
                    if (!"task_runtime_http".equals(taskId)) {
                        throw new IllegalArgumentException("task not found");
                    }
                    return runtimeContext;
                }
            };

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

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            db.close();
        }
    }

    private static final class HandoffPacketHarness implements AutoCloseable {
        private final DatabaseManager db;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final int port;

        private HandoffPacketHarness(Path dbPath) throws IOException {
            this.db = new DatabaseManager(dbPath);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);

            Session session = Session.create("session_packet_http", "handoff packet http", "active");
            sessionDao.insert(session);

            Task task = new Task(
                "task_packet_http",
                session.id(),
                null,
                "complete orchestrated execution",
                "active",
                "high",
                Instant.parse("2026-05-07T09:00:00Z"),
                Instant.parse("2026-05-07T09:00:00Z"),
                Instant.parse("2026-05-07T09:00:00Z"),
                null,
                null,
                "Planner phase is done and executor should continue.",
                "Ship the task through executor continuation.",
                "Apply the final executor patch.",
                "codex",
                "handoff",
                "Need executor continuation.",
                Map.of(
                    "task_type", "coding",
                    "model_mode", "orchestrated",
                    "orchestration_stage", "execution_pending",
                    "prompt_mode", "mounted_context_shadow",
                    "planner_worker", "codex",
                    "executor_worker", "kimi",
                    "route_source", "preassigned",
                    "candidate_workers", List.of("codex", "kimi"),
                    "open_questions", List.of("Should executor keep the current file layout?")
                )
            );
            taskDao.insert(task);
            taskDao.insert(new Task(
                "task_packet_http_done",
                session.id(),
                task.id(),
                "prepare executor brief",
                "done",
                "high",
                Instant.parse("2026-05-07T09:01:00Z"),
                Instant.parse("2026-05-07T09:01:00Z"),
                Instant.parse("2026-05-07T09:01:00Z"),
                Instant.parse("2026-05-07T09:03:00Z"),
                null,
                "Executor brief prepared.",
                null,
                null,
                "codex",
                "end",
                null,
                Map.of()
            ));
            taskDao.insert(new Task(
                "task_packet_http_pending",
                session.id(),
                task.id(),
                "apply executor patch",
                "active",
                "high",
                Instant.parse("2026-05-07T09:02:00Z"),
                Instant.parse("2026-05-07T09:02:00Z"),
                Instant.parse("2026-05-07T09:02:00Z"),
                null,
                null,
                null,
                null,
                "Apply the final executor patch.",
                "kimi",
                "scheduler",
                null,
                Map.of()
            ));
            decisionDao.insert(new Decision(
                "dec_packet_http",
                session.id(),
                task.id(),
                Instant.parse("2026-05-07T09:04:00Z"),
                "completion_judgment",
                "Planner output is ready for executor handoff.",
                "The remaining work is execution-heavy.",
                "medium",
                null,
                Map.of()
            ));
            artifactDao.insert(new Artifact(
                "art_packet_http",
                session.id(),
                task.id(),
                Instant.parse("2026-05-07T09:05:00Z"),
                "worker_output",
                "Planner delegation brief",
                null,
                null,
                "Executor can continue from this brief.",
                Map.of("selected_model_tier", "strong")
            ));

            TaskService service = new TaskService(
                taskDao,
                sessionDao,
                eventDao,
                packetDao,
                new WorkerRouter(new WorkerRegistry()),
                new PacketBuilder(decisionDao, artifactDao, taskDao),
                null,
                null,
                null,
                null,
                null,
                null,
                null
            );

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

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            db.close();
        }
    }
}
