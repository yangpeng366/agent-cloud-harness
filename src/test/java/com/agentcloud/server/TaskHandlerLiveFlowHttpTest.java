package com.agentcloud.server;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.providers.CodexProvider;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                    Map.entry("provider_timeout_kind", "max_duration"),
                    Map.entry("provider_abort_reason", "user_interrupted"),
                    Map.entry("partial_output_chars", 640),
                    Map.entry("partial_timeout_min_output_chars", 200),
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
                    Map.entry("needs_context_reopen", true),
                    Map.entry("evidence_gap_detected", true),
                    Map.entry("needs_archive_retrieval", true),
                    Map.entry("needs_external_fact_refresh", true),
                    Map.entry("reopen_candidate_paths", List.of(
                        "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/tool_invocations",
                        "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/packets/packet_http_1"
                    )),
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
                    Map.entry("needs_context_reopen", true),
                    Map.entry("evidence_gap_detected", true),
                    Map.entry("needs_archive_retrieval", true),
                    Map.entry("needs_external_fact_refresh", true),
                    Map.entry("reopen_candidate_paths", List.of(
                        "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/tool_invocations",
                        "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/packets/packet_http_1"
                    )),
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
            Map<String, Object> flowExecutionJudgment = harness.map(flowSurface.get("execution_judgment"));
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
            assertEquals("max_duration", flowExecution.get("provider_timeout_kind"));
            assertEquals("user_interrupted", flowExecution.get("provider_abort_reason"));
            assertEquals(640, ((Number) flowExecution.get("partial_output_chars")).intValue());
            assertEquals(200, ((Number) flowExecution.get("partial_timeout_min_output_chars")).intValue());
            assertEquals("mounted_context_primary", flowExecution.get("prompt_mode"));
            assertEquals(Boolean.TRUE, flowExecution.get("mounted_render_used"));
            assertEquals(2, ((Number) flowExecution.get("mounted_context_non_empty_panel_count")).intValue());
            assertEquals(3, ((Number) flowExecution.get("mounted_context_rendered_object_count")).intValue());
            assertEquals(1, ((Number) flowExecution.get("mounted_context_hidden_object_count")).intValue());
            assertEquals(Boolean.TRUE, flowExecution.get("mounted_context_budget_truncated"));
            assertEquals(2, ((Number) flowExecution.get("mounted_active_count")).intValue());
            assertEquals(1, ((Number) flowExecution.get("mounted_archive_count")).intValue());
            assertEquals(Boolean.TRUE, flowExecutionJudgment.get("needs_context_reopen"));
            assertEquals(Boolean.TRUE, flowExecutionJudgment.get("evidence_gap_detected"));
            assertEquals(Boolean.TRUE, flowExecutionJudgment.get("needs_archive_retrieval"));
            assertEquals(Boolean.TRUE, flowExecutionJudgment.get("needs_external_fact_refresh"));
            assertEquals(List.of(
                    "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/tool_invocations",
                    "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/packets/packet_http_1"
                ),
                flowExecutionJudgment.get("reopen_candidate_paths"));
            assertEquals("reopen=reopen:tool_invocations, reopen:packets:packet_http_1",
                flowExecutionJudgment.get("reopen_summary"));
            assertEquals(Boolean.TRUE, flowAlignment.get("route_worker_matches_execution_worker"));
            assertEquals(Boolean.TRUE, flowAlignment.get("execution_and_execution_judgment_prompt_mode_aligned"));
            assertEquals(4, flowTimeline.size());
            Map<String, Map<String, Object>> timelineByStage = flowTimeline.stream()
                .collect(java.util.stream.Collectors.toMap(
                    item -> String.valueOf(item.get("stage")),
                    item -> item
            ));
            assertEquals("mounted_context_primary", timelineByStage.get("execution").get("prompt_mode"));
            assertEquals(Boolean.TRUE, timelineByStage.get("execution").get("mounted_render_used"));
            assertEquals(3, ((Number) timelineByStage.get("execution").get("mounted_context_rendered_object_count")).intValue());
            assertEquals(1, ((Number) timelineByStage.get("execution").get("mounted_context_hidden_object_count")).intValue());
            assertEquals(1, ((Number) timelineByStage.get("execution").get("mounted_context_rendered_selection_trace_count")).intValue());
            assertEquals(0, ((Number) timelineByStage.get("execution").get("mounted_context_hidden_selection_trace_count")).intValue());
            assertEquals(Boolean.TRUE, timelineByStage.get("execution").get("mounted_context_budget_truncated"));
            assertEquals("proof=tool:exec_http_cognition_1, evidence:tool:read_file:input.txt",
                timelineByStage.get("execution").get("proof_summary"));
            assertTrue(String.valueOf(timelineByStage.get("execution").get("summary")).contains("proof=tool:exec_http_cognition_1"));
            assertEquals(Boolean.TRUE,
                timelineByStage.get("execution_judgment").get("aligned_with_previous_prompt_mode"));
            assertEquals(Boolean.TRUE, timelineByStage.get("execution_judgment").get("mounted_render_used"));
            assertEquals(3, ((Number) timelineByStage.get("execution_judgment").get("mounted_context_rendered_object_count")).intValue());
            assertEquals(Boolean.TRUE,
                timelineByStage.get("execution_judgment").get("mounted_context_budget_truncated"));
            assertEquals(Boolean.TRUE, timelineByStage.get("execution_judgment").get("needs_context_reopen"));
            assertEquals(Boolean.TRUE, timelineByStage.get("execution_judgment").get("evidence_gap_detected"));
            assertEquals(Boolean.TRUE, timelineByStage.get("execution_judgment").get("needs_archive_retrieval"));
            assertEquals(Boolean.TRUE, timelineByStage.get("execution_judgment").get("needs_external_fact_refresh"));
            assertEquals(List.of(
                    "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/tool_invocations",
                    "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/packets/packet_http_1"
                ),
                timelineByStage.get("execution_judgment").get("reopen_candidate_paths"));
            assertEquals("reopen=reopen:tool_invocations, reopen:packets:packet_http_1",
                timelineByStage.get("execution_judgment").get("reopen_summary"));
            assertEquals("proof=evidence:tool:read_file:input.txt",
                timelineByStage.get("execution_judgment").get("proof_summary"));
            assertTrue(String.valueOf(timelineByStage.get("execution_judgment").get("summary"))
                .contains("reopen=reopen:tool_invocations"));
            assertEquals(Boolean.TRUE, timelineByStage.get("completion_judgment").get("mounted_render_used"));
            assertEquals(1, ((Number) timelineByStage.get("completion_judgment").get("mounted_context_hidden_object_count")).intValue());
            assertEquals("proof=evidence:tool:read_file:input.txt",
                timelineByStage.get("completion_judgment").get("proof_summary"));
            assertEquals("codex", timelineByStage.get("route").get("worker_id"));
            assertNotNull(traceSurface);
            assertEquals("exec_http_cognition_1", traceExecution.get("execution_id"));
            assertEquals(Boolean.TRUE, traceExecution.get("mounted_render_used"));
            assertEquals(1, ((Number) traceExecution.get("mounted_context_selection_trace_count")).intValue());
            assertEquals(3, ((Number) traceExecution.get("mounted_context_rendered_object_count")).intValue());
            assertEquals(Boolean.TRUE, traceExecution.get("mounted_context_budget_truncated"));
            assertEquals("proof=tool:exec_http_cognition_1, evidence:tool:read_file:input.txt",
                traceExecution.get("proof_summary"));
            assertEquals(List.of("tool:read_file:input.txt"), traceExecution.get("evidence_refs"));
        }
    }

    @Test
    void liveFlowHttpExposesProviderExecutionDiagnostics() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-live-flow-provider-diagnostics.db"))) {
            Task task = harness.service.createTask(new TaskCreateRequest(
                "http provider diagnostics task", "coding", "user", "high",
                "检查 live_flow HTTP 是否透出 provider 诊断字段", "HTTP JSON should expose provider execution diagnostics",
                null, null, Map.of(), false
            ));

            harness.db.jdbi().onDemand(ArtifactDao.class).insert(new Artifact(
                IdGenerator.newId("art"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_round",
                "Provider worker round",
                "",
                "",
                "worker codex failed: thread not found (27984)",
                Map.of("latest_worker_metadata", Map.ofEntries(
                    Map.entry("selected_worker", "codex"),
                    Map.entry("execution_status", "timeout"),
                    Map.entry("execution_backend", "provider_app_server"),
                    Map.entry("provider_id", "codex"),
                    Map.entry("provider_session_id", "019e4401-f18c-7fa2-b63d-8544108edcf5"),
                    Map.entry("provider_thread_id", "019e4401-f18c-7fa2-b63d-8544108edcf5"),
                    Map.entry("resume_provider_session_id", "019e4401-f18c-7fa2-b63d-8544108edcf5"),
                    Map.entry("provider_error", "codex turn completion timed out"),
                    Map.entry("provider_turn_status", "timeout"),
                    Map.entry("provider_failure_class", "provider_runtime_transient"),
                    Map.entry("provider_failure_reason", "turn timed out"),
                    Map.entry("provider_retryable", true),
                    Map.entry("provider_protocol_trace", List.of("thread/started", "turn/started")),
                    Map.entry("provider_run_dir", "D:\\tmp\\provider-runs\\codex\\task-http"),
                    Map.entry("provider_prompt_path", "D:\\tmp\\provider-runs\\codex\\task-http\\prompt.txt"),
                    Map.entry("provider_event_log_path", "D:\\tmp\\provider-runs\\codex\\task-http\\events.jsonl"),
                    Map.entry("provider_last_message_path", "D:\\tmp\\provider-runs\\codex\\task-http\\last_message.md"),
                    Map.entry("provider_run_metadata_path", "D:\\tmp\\provider-runs\\codex\\task-http\\metadata.json")
                ))
            ));

            HttpResponse<String> flowResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/live_flow?limit=8"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> flowPayload = harness.readJson(flowResponse.body());
            Map<String, Object> flowData = harness.map(flowPayload.get("data"));
            Map<String, Object> flowSurface = harness.map(flowData.get("runtime_cognition_surface"));
            Map<String, Object> flowExecution = harness.map(flowSurface.get("execution"));

            assertEquals(200, flowResponse.statusCode());
            assertEquals("provider_app_server", flowExecution.get("execution_backend"));
            assertEquals("codex", flowExecution.get("provider_id"));
            assertEquals("019e4401-f18c-7fa2-b63d-8544108edcf5", flowExecution.get("provider_session_id"));
            assertEquals("019e4401-f18c-7fa2-b63d-8544108edcf5", flowExecution.get("provider_thread_id"));
            assertEquals("019e4401-f18c-7fa2-b63d-8544108edcf5", flowExecution.get("resume_provider_session_id"));
            assertEquals("codex turn completion timed out", flowExecution.get("provider_error"));
            assertEquals("timeout", flowExecution.get("provider_turn_status"));
            assertEquals("provider_runtime_transient", flowExecution.get("provider_failure_class"));
            assertEquals("turn timed out", flowExecution.get("provider_failure_reason"));
            assertEquals(Boolean.TRUE, flowExecution.get("provider_retryable"));
            assertEquals(List.of("thread/started", "turn/started"), flowExecution.get("provider_protocol_trace"));
            assertEquals("D:\\tmp\\provider-runs\\codex\\task-http", flowExecution.get("provider_run_dir"));
            assertEquals("D:\\tmp\\provider-runs\\codex\\task-http\\prompt.txt", flowExecution.get("provider_prompt_path"));
            assertEquals("D:\\tmp\\provider-runs\\codex\\task-http\\events.jsonl", flowExecution.get("provider_event_log_path"));
            assertEquals("D:\\tmp\\provider-runs\\codex\\task-http\\last_message.md", flowExecution.get("provider_last_message_path"));
            assertEquals("D:\\tmp\\provider-runs\\codex\\task-http\\metadata.json", flowExecution.get("provider_run_metadata_path"));
        }
    }

    @Test
    void providerRunFileHttpReadsBoundedLastMessageFromLatestExecutionMetadata() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-provider-run-file.db"))) {
            Task task = harness.service.createTask(new TaskCreateRequest(
                "provider run file", "coding", "user", "high",
                "读取 provider run 文件", "HTTP should read provider run file content",
                null, null, Map.of(), false
            ));
            Path runDir = tempDir.resolve("provider-run").toAbsolutePath().normalize();
            Files.createDirectories(runDir);
            Path promptPath = runDir.resolve("prompt.txt");
            Path lastMessagePath = runDir.resolve("last_message.md");
            Path metadataPath = runDir.resolve("metadata.json");
            Files.writeString(promptPath, "prompt", StandardCharsets.UTF_8);
            Files.writeString(lastMessagePath, "codex final answer", StandardCharsets.UTF_8);
            Files.writeString(metadataPath, "{\"status\":\"done\"}", StandardCharsets.UTF_8);

            harness.db.jdbi().onDemand(ArtifactDao.class).insert(new Artifact(
                IdGenerator.newId("art"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_round",
                "Provider worker round",
                "",
                "",
                "codex final answer",
                Map.of("latest_worker_metadata", Map.of(
                    "selected_worker", "codex",
                    "execution_status", "succeeded",
                    "provider_run_dir", runDir.toString(),
                    "provider_prompt_path", promptPath.toString(),
                    "provider_last_message_path", lastMessagePath.toString(),
                    "provider_run_metadata_path", metadataPath.toString()
                ))
            ));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/provider_run_file?kind=last_message"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> payload = harness.readJson(response.body());
            Map<String, Object> data = harness.map(payload.get("data"));
            assertEquals(200, response.statusCode());
            assertEquals("last_message", data.get("kind"));
            assertEquals(lastMessagePath.toString(), data.get("path"));
            assertEquals("codex final answer", data.get("content"));
            assertEquals(Boolean.FALSE, data.get("truncated"));
        }
    }

    @Test
    void providerRunFileHttpRejectsPathOutsideProviderRunDir() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-provider-run-file-outside.db"))) {
            Task task = harness.service.createTask(new TaskCreateRequest(
                "provider run file outside", "coding", "user", "high",
                "拒绝 provider run 外部文件", "HTTP should reject provider run file outside run dir",
                null, null, Map.of(), false
            ));
            Path runDir = tempDir.resolve("provider-run-safe").toAbsolutePath().normalize();
            Path outside = tempDir.resolve("outside-last-message.md").toAbsolutePath().normalize();
            Files.createDirectories(runDir);
            Files.writeString(outside, "secret", StandardCharsets.UTF_8);

            harness.db.jdbi().onDemand(ArtifactDao.class).insert(new Artifact(
                IdGenerator.newId("art"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_round",
                "Provider worker round",
                "",
                "",
                "worker output",
                Map.of("latest_worker_metadata", Map.of(
                    "selected_worker", "codex",
                    "execution_status", "failed",
                    "provider_run_dir", runDir.toString(),
                    "provider_last_message_path", outside.toString()
                ))
            ));

            HttpResponse<String> response = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/provider_run_file?kind=last_message"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(400, response.statusCode());
        }
    }

    @Test
    void liveFlowHttpExposesContinuityTimelineEntries() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-live-flow-continuity.db"))) {
            Task task = harness.service.createTask(new TaskCreateRequest(
                "http continuity task", "continuation", "user", "high",
                "检查 live_flow 是否透出 continuity timeline", "HTTP contract should expose continuity entries",
                null, null, Map.of("prompt_mode", "mounted_context_primary"), false
            ));

            harness.db.jdbi().onDemand(EventDao.class).insert(new Event(
                IdGenerator.newId("evt"),
                task.sessionId(),
                task.id(),
                Instant.parse("2026-05-08T09:00:00Z"),
                "task_control_action",
                "task_service",
                null,
                "Task control action: pause",
                Map.of(
                    "action", "pause",
                    "action_category", "task_control",
                    "reason", "need human confirmation",
                    "assigned_worker", "codex",
                    "prompt_mode", "mounted_context_primary"
                )
            ));
            harness.db.jdbi().onDemand(CheckpointDao.class).insert(new com.agentcloud.model.Checkpoint(
                IdGenerator.newId("cp"),
                task.sessionId(),
                task.id(),
                Instant.parse("2026-05-08T09:01:00Z"),
                "handoff_before",
                "handoff checkpoint captured",
                Map.of(
                    "assigned_worker", "codex",
                    "runtime_cognition_surface", Map.of(
                        "route", Map.of(
                            "selected_worker", "codex",
                            "route_source", "http_checkpoint_surface",
                            "candidate_workers", List.of("codex", "kimi")
                        ),
                        "execution", Map.of(
                            "worker_id", "codex",
                            "prompt_mode", "mounted_context_primary",
                            "tool_invocation_ids", List.of("tool-http-alpha"),
                            "tool_invocation_count", 1,
                            "evidence_refs", List.of("/tasks/http/checkpoints/cp-1"),
                            "unfinished_items", List.of("confirm executor after handoff"),
                            "proof_summary", "tool=tool-http-alpha | evidence=/tasks/http/checkpoints/cp-1"
                        ),
                        "execution_judgment", Map.of(
                            "needs_context_reopen", true,
                            "evidence_gap_detected", true,
                            "needs_archive_retrieval", true,
                            "needs_external_fact_refresh", true,
                            "reopen_candidate_paths", List.of(
                                "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/tool_invocations",
                                "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/packets/packet-http-checkpoint-1"
                            )
                        )
                    )
                ),
                Map.of(),
                Map.of("artifact_count", 0)
            ));
            harness.db.jdbi().onDemand(EventDao.class).insert(new Event(
                IdGenerator.newId("evt"),
                task.sessionId(),
                task.id(),
                Instant.parse("2026-05-08T09:02:00Z"),
                "task_control_action",
                "task_service",
                null,
                "Task control action: handoff",
                Map.of(
                    "action", "handoff",
                    "action_category", "task_control",
                    "previous_worker", "codex",
                    "assigned_worker", "kimi",
                    "target_worker", "kimi",
                    "prompt_mode", "mounted_context_primary",
                    "runtime_cognition_surface", Map.of(
                        "route", Map.of(
                            "selected_worker", "kimi",
                            "route_source", "http_handoff_surface",
                            "candidate_workers", List.of("kimi", "codex")
                        ),
                        "execution", Map.of(
                            "worker_id", "kimi",
                            "prompt_mode", "mounted_context_primary",
                            "execution_status", "waiting",
                            "tool_invocation_ids", List.of("tool-http-handoff"),
                            "tool_invocation_count", 1,
                            "evidence_refs", List.of("/tasks/http/handoffs/handoff-1"),
                            "unfinished_items", List.of("executor should continue"),
                            "proof_summary", "tool=tool-http-handoff | evidence=/tasks/http/handoffs/handoff-1"
                        ),
                        "execution_judgment", Map.of(
                            "needs_context_reopen", true,
                            "evidence_gap_detected", true,
                            "needs_archive_retrieval", true,
                            "needs_external_fact_refresh", true,
                            "reopen_candidate_paths", List.of(
                                "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/checkpoints",
                                "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/packets/packet-http-handoff-1"
                            )
                        )
                    )
                )
            ));
            harness.db.jdbi().onDemand(ResumePacketDao.class).insert(new ResumePacket(
                IdGenerator.newId("packet"),
                task.sessionId(),
                task.id(),
                Instant.parse("2026-05-08T09:03:00Z"),
                "1.1",
                "http resume packet summary",
                "http decision snapshot",
                "http artifact snapshot",
                List.of("confirm packet replay"),
                "resume scheduler after handoff",
                Map.of(
                    "assigned_worker", "kimi",
                    "current_status", "waiting",
                    "current_node", "packet",
                    "resume_hint", "continue from saved packet",
                    "runtime_cognition_surface", Map.of(
                        "route", Map.of(
                            "selected_worker", "kimi",
                            "route_source", "http_resume_surface",
                            "candidate_workers", List.of("kimi", "codex")
                        ),
                        "execution", Map.of(
                            "worker_id", "kimi",
                            "prompt_mode", "mounted_context_primary",
                            "execution_status", "waiting",
                            "tool_invocation_ids", List.of("tool-http-beta"),
                            "tool_invocation_count", 1,
                            "evidence_refs", List.of("/tasks/http/packets/packet-1"),
                            "unfinished_items", List.of("confirm packet replay"),
                            "proof_summary", "tool=tool-http-beta | evidence=/tasks/http/packets/packet-1"
                        ),
                        "execution_judgment", Map.of(
                            "needs_context_reopen", true,
                            "evidence_gap_detected", true,
                            "needs_archive_retrieval", true,
                            "needs_external_fact_refresh", true,
                            "reopen_candidate_paths", List.of(
                                "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/tool_invocations",
                                "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/packets/packet-1"
                            )
                        )
                    )
                )
            ));

            HttpResponse<String> flowResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/live_flow?limit=10"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> flowPayload = harness.readJson(flowResponse.body());
            Map<String, Object> flowData = harness.map(flowPayload.get("data"));
            List<Map<String, Object>> timeline = harness.list(flowData.get("runtime_cognition_timeline"));
            Map<String, Object> pauseEntry = timeline.stream()
                .filter(item -> "continuity_action".equals(String.valueOf(item.get("stage"))))
                .filter(item -> "pause".equals(String.valueOf(item.get("continuity_action"))))
                .findFirst()
                .orElseThrow();
            Map<String, Object> handoffEntry = timeline.stream()
                .filter(item -> "continuity_action".equals(String.valueOf(item.get("stage"))))
                .filter(item -> "handoff".equals(String.valueOf(item.get("continuity_action"))))
                .findFirst()
                .orElseThrow();
            Map<String, Object> checkpointEntry = timeline.stream()
                .filter(item -> "checkpoint".equals(String.valueOf(item.get("stage"))))
                .findFirst()
                .orElseThrow();
            Map<String, Object> packetEntry = timeline.stream()
                .filter(item -> "resume_packet".equals(String.valueOf(item.get("stage"))))
                .findFirst()
                .orElseThrow();

            assertEquals(200, flowResponse.statusCode());
            assertEquals("need human confirmation", pauseEntry.get("reason"));
            assertEquals("mounted_context_primary", pauseEntry.get("prompt_mode"));
            assertEquals("kimi", handoffEntry.get("worker_id"));
            assertEquals("kimi", handoffEntry.get("target_worker"));
            assertEquals("http_handoff_surface", handoffEntry.get("route_source"));
            assertEquals("waiting", handoffEntry.get("execution_status"));
            assertEquals(Boolean.TRUE, handoffEntry.get("needs_context_reopen"));
            assertEquals(Boolean.TRUE, handoffEntry.get("evidence_gap_detected"));
            assertEquals(Boolean.TRUE, handoffEntry.get("needs_archive_retrieval"));
            assertEquals(Boolean.TRUE, handoffEntry.get("needs_external_fact_refresh"));
            assertEquals(List.of(
                    "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/checkpoints",
                    "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/packets/packet-http-handoff-1"
                ),
                handoffEntry.get("reopen_candidate_paths"));
            assertEquals("reopen=reopen:checkpoints, reopen:packets:packet-http-handoff-1",
                handoffEntry.get("reopen_summary"));
            assertEquals(List.of("tool-http-handoff"), handoffEntry.get("tool_invocation_ids"));
            assertEquals(List.of("/tasks/http/handoffs/handoff-1"), handoffEntry.get("evidence_refs"));
            assertEquals(List.of("executor should continue"), handoffEntry.get("unfinished_items"));
            assertEquals("handoff_before", checkpointEntry.get("checkpoint_type"));
            assertEquals("mounted_context_primary", checkpointEntry.get("prompt_mode"));
            assertEquals("http_checkpoint_surface", checkpointEntry.get("route_source"));
            assertEquals(Boolean.TRUE, checkpointEntry.get("needs_context_reopen"));
            assertEquals(Boolean.TRUE, checkpointEntry.get("evidence_gap_detected"));
            assertEquals(Boolean.TRUE, checkpointEntry.get("needs_archive_retrieval"));
            assertEquals(Boolean.TRUE, checkpointEntry.get("needs_external_fact_refresh"));
            assertEquals(List.of(
                    "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/tool_invocations",
                    "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/packets/packet-http-checkpoint-1"
                ),
                checkpointEntry.get("reopen_candidate_paths"));
            assertEquals("reopen=reopen:tool_invocations, reopen:packets:packet-http-checkpoint-1",
                checkpointEntry.get("reopen_summary"));
            assertEquals(List.of("tool-http-alpha"), checkpointEntry.get("tool_invocation_ids"));
            assertEquals(List.of("/tasks/http/checkpoints/cp-1"), checkpointEntry.get("evidence_refs"));
            assertEquals(List.of("confirm executor after handoff"), checkpointEntry.get("unfinished_items"));
            assertEquals("resume_packet", packetEntry.get("continuity_action"));
            assertEquals("mounted_context_primary", packetEntry.get("prompt_mode"));
            assertEquals("http_resume_surface", packetEntry.get("route_source"));
            assertEquals("waiting", packetEntry.get("execution_status"));
            assertEquals("continue from saved packet", packetEntry.get("reason"));
            assertEquals(Boolean.TRUE, packetEntry.get("needs_context_reopen"));
            assertEquals(Boolean.TRUE, packetEntry.get("evidence_gap_detected"));
            assertEquals(Boolean.TRUE, packetEntry.get("needs_archive_retrieval"));
            assertEquals(Boolean.TRUE, packetEntry.get("needs_external_fact_refresh"));
            assertEquals(List.of(
                    "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/tool_invocations",
                    "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/packets/packet-1"
                ),
                packetEntry.get("reopen_candidate_paths"));
            assertEquals("reopen=reopen:tool_invocations, reopen:packets:packet-1",
                packetEntry.get("reopen_summary"));
            assertEquals(List.of("tool-http-beta"), packetEntry.get("tool_invocation_ids"));
            assertEquals(List.of("/tasks/http/packets/packet-1"), packetEntry.get("evidence_refs"));
            assertEquals(List.of("confirm packet replay"), packetEntry.get("unfinished_items"));
        }
    }

    @Test
    void liveFlowHttpLabelsExternalFactRefreshCheckpoint() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-live-flow-external-fact-refresh.db"))) {
            Task task = harness.service.createTask(new TaskCreateRequest(
                "http external fact refresh task", "research", "user", "high",
                "把 external fact refresh checkpoint 暴露到 HTTP live flow",
                "验证 label 和 signal 经由 HTTP 可见", null, null,
                Map.of("prompt_mode", "mounted_context_primary"), false
            ));

            harness.db.jdbi().onDemand(CheckpointDao.class).insert(new com.agentcloud.model.Checkpoint(
                IdGenerator.newId("cp"),
                task.sessionId(),
                task.id(),
                Instant.parse("2026-05-09T11:01:00Z"),
                "external_fact_refresh_before",
                "external fact refresh checkpoint captured",
                Map.of(
                    "assigned_worker", "codex",
                    "runtime_cognition_surface", Map.of(
                        "route", Map.of(
                            "selected_worker", "codex",
                            "route_source", "http_external_fact_refresh_surface",
                            "candidate_workers", List.of("codex", "kimi")
                        ),
                        "execution", Map.of(
                            "worker_id", "codex",
                            "prompt_mode", "mounted_context_primary",
                            "execution_status", "waiting",
                            "tool_invocation_ids", List.of("tool-http-refresh"),
                            "tool_invocation_count", 1,
                            "evidence_refs", List.of("/tasks/http/external-refresh/cache-1"),
                            "unfinished_items", List.of("refresh external facts"),
                            "proof_summary", "tool=tool-http-refresh | evidence=/tasks/http/external-refresh/cache-1"
                        ),
                        "execution_judgment", Map.of(
                            "evidence_gap_detected", true,
                            "needs_external_fact_refresh", true,
                            "reopen_candidate_paths", List.of(
                                "/sessions/" + task.sessionId() + "/tasks/" + task.id() + "/packets/packet-http-refresh-1"
                            )
                        )
                    )
                ),
                Map.of(),
                Map.of("artifact_count", 0)
            ));

            HttpResponse<String> flowResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/live_flow?limit=10"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> flowPayload = harness.readJson(flowResponse.body());
            Map<String, Object> flowData = harness.map(flowPayload.get("data"));
            List<Map<String, Object>> timeline = harness.list(flowData.get("runtime_cognition_timeline"));
            Map<String, Object> checkpointEntry = timeline.stream()
                .filter(item -> "checkpoint".equals(String.valueOf(item.get("stage"))))
                .findFirst()
                .orElseThrow();

            assertEquals(200, flowResponse.statusCode());
            assertEquals("external_fact_refresh_before", checkpointEntry.get("checkpoint_type"));
            assertEquals("External Fact Refresh Checkpoint", checkpointEntry.get("label"));
            assertEquals("http_external_fact_refresh_surface", checkpointEntry.get("route_source"));
            assertEquals(Boolean.TRUE, checkpointEntry.get("needs_external_fact_refresh"));
            assertTrue(String.valueOf(checkpointEntry.get("summary")).contains("needs_external_fact_refresh=true"));
        }
    }

    @Test
    void liveFlowRoutePreviewPrefersCurrentPinnedWorkerOverHistoricalPlannerMetadata() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-live-flow-route-drift.db"))) {
            Task task = harness.service.createTask(new TaskCreateRequest(
                "http pinned route task", "continuation", "user", "high",
                "handoff 后 live flow route preview 不应继续显示旧 planner worker",
                "当前 pinned worker 应覆盖历史 planner route metadata",
                null,
                null,
                Map.of(
                    "model_mode", "orchestrated",
                    "orchestration_stage", "execution_active",
                    "target_worker", "kimi"
                ),
                false
            ));
            Task pinnedTask = task.withAssignedWorker("kimi");
            harness.db.jdbi().onDemand(TaskDao.class).updateState(pinnedTask);
            harness.db.jdbi().onDemand(ArtifactDao.class).insert(new com.agentcloud.model.Artifact(
                IdGenerator.newId("art"),
                task.sessionId(),
                task.id(),
                Instant.parse("2026-05-08T09:01:00Z"),
                "worker_artifact",
                "historical planner artifact",
                null,
                null,
                "old planner route should stay historical",
                Map.of(
                    "latest_worker_metadata", Map.of(
                        "selected_worker", "codex",
                        "selected_worker_type", "codex",
                        "selected_model_tier", "strong",
                        "execution_role", "planner_executor",
                        "selection_scope", "planner",
                        "route_source", "ready_fallback",
                        "why_selected", "selected by model tier preference (strong) on ready-worker fallback: taskType=continuation, worker=codex",
                        "candidate_workers", List.of("codex", "claude")
                    )
                )
            ));

            HttpResponse<String> flowResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/live_flow?limit=6"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> flowPayload = harness.readJson(flowResponse.body());
            Map<String, Object> flowData = harness.map(flowPayload.get("data"));
            Map<String, Object> routePreview = harness.map(flowData.get("route_preview"));

            assertEquals(200, flowResponse.statusCode());
            assertEquals("kimi", routePreview.get("selected_worker"));
            assertEquals("task_pinned", routePreview.get("route_source"));
            assertEquals("small", routePreview.get("selected_model_tier"));
            assertEquals("executor", routePreview.get("selection_scope"));
            assertTrue(String.valueOf(routePreview.get("why_selected")).contains("task-pinned worker"));
        }
    }

    @Test
    void liveFlowRoutePreviewExposesDispatchSkippedWorkers() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-live-flow-dispatch-skipped.db"))) {
            Task task = harness.service.createTask(new TaskCreateRequest(
                "dispatch skipped route task", "coding", "user", "high",
                "确认 live_flow 透出 dispatch skipped worker 结构化诊断",
                "route trace should expose provider failure metadata",
                null, null, Map.of(), false
            ));
            harness.db.jdbi().onDemand(TaskDao.class).updateState(task.withAssignedWorker("kimi"));
            harness.db.jdbi().onDemand(ArtifactDao.class).insert(new Artifact(
                "art_dispatch_skipped",
                task.sessionId(),
                task.id(),
                Instant.parse("2026-05-22T08:00:00Z"),
                "worker_artifact",
                "dispatch skipped worker artifact",
                null,
                null,
                "worker route diagnostics",
                Map.of(
                    "latest_worker_metadata", Map.ofEntries(
                        Map.entry("selected_worker", "kimi"),
                        Map.entry("selected_worker_type", "kimi"),
                        Map.entry("selected_model_tier", "small"),
                        Map.entry("execution_role", "executor"),
                        Map.entry("selection_scope", "executor"),
                        Map.entry("route_source", "capability_match"),
                        Map.entry("why_selected", "selected by capability match: taskType=coding, worker=kimi"),
                        Map.entry("candidate_workers", List.of("codex", "kimi")),
                        Map.entry("fallback_reason", "dispatch readiness skipped worker(s): codex skipped: thread not found during dispatch preflight"),
                        Map.entry("dispatch_skipped_workers", List.of(Map.of(
                            "worker_id", "codex",
                            "reason", "thread not found during dispatch preflight",
                            "provider_failure_class", "provider_runtime_transient",
                            "provider_failure_reason", "thread not found during dispatch preflight",
                            "provider_retryable", true
                        ))),
                        Map.entry("execution_status", "succeeded")
                    )
                )
            ));

            HttpResponse<String> flowResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/live_flow?limit=6"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> flowPayload = harness.readJson(flowResponse.body());
            Map<String, Object> flowData = harness.map(flowPayload.get("data"));
            Map<String, Object> routePreview = harness.map(flowData.get("route_preview"));
            Map<String, Object> runtimeFacts = harness.map(flowData.get("runtime_facts"));
            Map<String, Object> runtimeMetadata = harness.map(runtimeFacts.get("metadata"));
            Map<String, Object> surface = harness.map(flowData.get("runtime_cognition_surface"));
            Map<String, Object> routeSurface = harness.map(surface.get("route"));
            List<Map<String, Object>> routeSkipped = harness.list(routePreview.get("dispatch_skipped_workers"));
            List<Map<String, Object>> metadataSkipped = harness.list(runtimeMetadata.get("dispatch_skipped_workers"));
            List<Map<String, Object>> surfaceSkipped = harness.list(routeSurface.get("dispatch_skipped_workers"));

            assertEquals(200, flowResponse.statusCode());
            assertEquals("kimi", routePreview.get("selected_worker"));
            assertEquals("codex", routeSkipped.getFirst().get("worker_id"));
            assertEquals("provider_runtime_transient", routeSkipped.getFirst().get("provider_failure_class"));
            assertEquals(Boolean.TRUE, routeSkipped.getFirst().get("provider_retryable"));
            assertEquals("codex", metadataSkipped.getFirst().get("worker_id"));
            assertEquals("provider_runtime_transient", surfaceSkipped.getFirst().get("provider_failure_class"));
        }
    }

    @Test
    void liveFlowHttpIncludesSessionContinuityMessagesInRelatedMessages() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-live-flow-related-messages.db"))) {
            Task task = harness.service.createTask(new TaskCreateRequest(
                "http related message task", "continuation", "user", "high",
                "确认 live_flow related_messages 会带 session continuity message",
                "HTTP contract 里应出现 continuity_scope=session",
                null, null, Map.of(), false
            ));
            SessionMessageDao messageDao = harness.db.jdbi().onDemand(SessionMessageDao.class);
            messageDao.insert(new SessionMessage(
                IdGenerator.newId("msg"),
                task.sessionId(),
                task.id(),
                "user",
                "task_note",
                "这条 task note 应继续出现在 related_messages 中",
                Instant.now(),
                Map.of("source_surface", "http_test")
            ));
            messageDao.insert(new SessionMessage(
                IdGenerator.newId("msg"),
                task.sessionId(),
                null,
                "user",
                "user_note",
                "这条 session continuity message 也应进入 live_flow related_messages",
                Instant.now(),
                Map.of("source_surface", "http_test")
            ));

            HttpResponse<String> flowResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/live_flow?limit=8"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> flowPayload = harness.readJson(flowResponse.body());
            Map<String, Object> flowData = harness.map(flowPayload.get("data"));
            List<Map<String, Object>> relatedMessages = harness.list(flowData.get("related_messages"));

            assertEquals(200, flowResponse.statusCode());
            assertTrue(relatedMessages.stream().anyMatch(item ->
                "task_note".equals(String.valueOf(item.get("message_type")))
                    && task.id().equals(String.valueOf(item.get("task_id")))));
            assertTrue(relatedMessages.stream().anyMatch(item -> {
                Map<String, Object> metadata = harness.map(item.get("metadata"));
                Object taskId = item.get("task_id");
                return (taskId == null || String.valueOf(taskId).isBlank())
                    && "user_note".equals(String.valueOf(item.get("message_type")))
                    && "session".equals(String.valueOf(metadata.get("continuity_scope")));
            }));
        }
    }

    @Test
    void liveFlowRoutePreviewIncludesPinnedAndRecoveryRouteDiagnostics() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-live-flow-route-diagnostics.db"))) {
            Task task = harness.service.createTask(new TaskCreateRequest(
                "route diagnostics task", "coding", "user", "high",
                "确认 live_flow route_preview 会带 pinned 与 unpinned 诊断",
                "route diagnostics should be visible", null, null, Map.of(), false
            ));
            Task pinnedTask = task.withAssignedWorker("claude").withMetadata(new java.util.LinkedHashMap<>(Map.of(
                "task_type", "coding",
                "assigned_worker", "claude"
            )));
            harness.db.jdbi().onDemand(TaskDao.class).updateState(pinnedTask);

            HttpResponse<String> flowResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/live_flow?limit=6"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> flowPayload = harness.readJson(flowResponse.body());
            Map<String, Object> flowData = harness.map(flowPayload.get("data"));
            Map<String, Object> routePreview = harness.map(flowData.get("route_preview"));
            Map<String, Object> currentPinned = harness.map(routePreview.get("current_pinned_route"));
            Map<String, Object> recoveryUnpinned = harness.map(routePreview.get("recovery_unpinned_recommendation"));

            assertEquals(200, flowResponse.statusCode());
            assertEquals("claude", routePreview.get("selected_worker"));
            assertEquals("task_pinned", routePreview.get("route_source"));
            assertEquals("claude", currentPinned.get("selected_worker"));
            assertEquals("task_pinned", currentPinned.get("route_source"));
            assertEquals("codex", recoveryUnpinned.get("selected_worker"));
            assertEquals("capability_match", recoveryUnpinned.get("route_source"));
        }
    }

    @Test
    void liveFlowRoutePreviewExplainsProviderDeprioritizationForRecoveryRecommendation() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-live-flow-provider-deprioritized.db"))) {
            Task task = harness.service.createTask(new TaskCreateRequest(
                "provider deprioritization route preview", "coding", "user", "high",
                "确认 live_flow 会解释 provider 热失败后的恢复建议",
                "route diagnostics should show provider deprioritization", null, null, Map.of(), false
            ));
            Task pinnedTask = task.withAssignedWorker("claude").withMetadata(new java.util.LinkedHashMap<>(Map.of(
                "task_type", "coding",
                "assigned_worker", "claude"
            )));
            harness.db.jdbi().onDemand(TaskDao.class).updateState(pinnedTask);

            Instant base = Instant.parse("2026-04-29T14:00:00Z");
            harness.db.jdbi().onDemand(AgentRunDao.class).insert(new AgentRunRecord(
                "arun_claude_fail_1",
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
            harness.db.jdbi().onDemand(AgentRunDao.class).insert(new AgentRunRecord(
                "arun_claude_fail_2",
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

            HttpResponse<String> flowResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/live_flow?limit=6"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> flowPayload = harness.readJson(flowResponse.body());
            Map<String, Object> flowData = harness.map(flowPayload.get("data"));
            Map<String, Object> routePreview = harness.map(flowData.get("route_preview"));
            Map<String, Object> recoveryUnpinned = harness.map(routePreview.get("recovery_unpinned_recommendation"));

            assertEquals(200, flowResponse.statusCode());
            assertEquals("codex", recoveryUnpinned.get("selected_worker"));
            assertEquals(Boolean.TRUE, recoveryUnpinned.get("provider_deprioritized"));
            assertEquals("claude", recoveryUnpinned.get("deprioritized_provider"));
            assertEquals("recent transient provider failures", recoveryUnpinned.get("deprioritization_reason"));
        }
    }

    @Test
    void liveFlowRoutePreviewPromotesContinuationRepoModificationTaskToEffectiveCodingType() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-live-flow-effective-task-type.db"))) {
            Task task = harness.service.createTask(new TaskCreateRequest(
                "route preview effective type", "continuation", "user", "high",
                "根据文档修改 D:\\gitAll\\articleeditor\\src\\main\\java\\ArticleThirdService.java，并补测试。",
                "确认 live_flow route_preview 会使用有效 coding 语义", null, null, Map.of(), false
            ));

            HttpResponse<String> flowResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + task.id() + "/live_flow?limit=6"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> flowPayload = harness.readJson(flowResponse.body());
            Map<String, Object> flowData = harness.map(flowPayload.get("data"));
            Map<String, Object> routePreview = harness.map(flowData.get("route_preview"));

            assertEquals(200, flowResponse.statusCode());
            assertEquals("coding", routePreview.get("task_type"));
            assertEquals("codex", routePreview.get("selected_worker"));
        }
    }

    @Test
    void dialogueSmokeFlowPersistsNoteTaskBriefAndLiveFlowRelatedMessages() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-dialogue-smoke.db"))) {
            HttpResponse<String> sessionResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/sessions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "title":"dialogue smoke session"
                        }
                        """))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            Map<String, Object> sessionPayload = harness.readJson(sessionResponse.body());
            Map<String, Object> sessionData = harness.map(sessionPayload.get("data"));
            String sessionId = String.valueOf(sessionData.get("id"));

            HttpResponse<String> noteResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/sessions/" + sessionId + "/messages"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "role":"user",
                          "message_type":"user_note",
                          "content":"先整理结构，再发布一个 manual-start 任务。",
                          "metadata":{
                            "source_surface":"web_dialogue",
                            "created_via":"dialogue_workspace"
                          }
                        }
                        """))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            HttpResponse<String> taskResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "title":"dialogue smoke task",
                          "task_type":"continuation",
                          "source":"user",
                          "priority":"high",
                          "intent":"先整理结构，再发布一个 manual-start 任务。",
                          "goal":"等待 follow-up",
                          "session_id":"%s",
                          "auto_start":false,
                          "metadata":{
                            "source_surface":"web_dialogue",
                            "created_via":"dialogue_workspace"
                          }
                        }
                        """.formatted(sessionId)))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            Map<String, Object> taskPayload = harness.readJson(taskResponse.body());
            Map<String, Object> taskData = harness.map(taskPayload.get("data"));
            String taskId = String.valueOf(taskData.get("id"));

            HttpResponse<String> briefResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/sessions/" + sessionId + "/messages"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "role":"user",
                          "message_type":"task_brief",
                          "content":"先整理结构，再发布一个 manual-start 任务。",
                          "task_id":"%s",
                          "metadata":{
                            "source_surface":"web_dialogue",
                            "created_via":"dialogue_workspace",
                            "mirrored_from":"task_form"
                          }
                        }
                        """.formatted(taskId)))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            HttpResponse<String> messagesResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/sessions/" + sessionId + "/messages?limit=10"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> sessionTasksResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/sessions/" + sessionId + "/tasks"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> liveFlowResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + taskId + "/live_flow?limit=10"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> messagesPayload = harness.readJson(messagesResponse.body());
            List<Map<String, Object>> messages = harness.list(messagesPayload.get("data"));
            Map<String, Object> messagesByTypeNote = messages.stream()
                .filter(item -> "user_note".equals(String.valueOf(item.get("message_type"))))
                .findFirst()
                .orElseThrow();
            Map<String, Object> messagesByTypeBrief = messages.stream()
                .filter(item -> "task_brief".equals(String.valueOf(item.get("message_type"))))
                .findFirst()
                .orElseThrow();

            Map<String, Object> sessionTasksPayload = harness.readJson(sessionTasksResponse.body());
            List<Map<String, Object>> sessionTasks = harness.list(sessionTasksPayload.get("data"));

            Map<String, Object> liveFlowPayload = harness.readJson(liveFlowResponse.body());
            Map<String, Object> liveFlowData = harness.map(liveFlowPayload.get("data"));
            List<Map<String, Object>> relatedMessages = harness.list(liveFlowData.get("related_messages"));

            assertEquals(200, sessionResponse.statusCode());
            assertEquals(200, noteResponse.statusCode());
            assertEquals(200, taskResponse.statusCode());
            assertEquals(200, briefResponse.statusCode());
            assertEquals(200, messagesResponse.statusCode());
            assertEquals(200, sessionTasksResponse.statusCode());
            assertEquals(200, liveFlowResponse.statusCode());
            assertEquals("先整理结构，再发布一个 manual-start 任务。", messagesByTypeNote.get("content"));
            assertEquals(taskId, String.valueOf(messagesByTypeBrief.get("task_id")));
            assertTrue(sessionTasks.stream().anyMatch(item -> taskId.equals(String.valueOf(item.get("id")))));
            assertTrue(relatedMessages.stream().anyMatch(item -> {
                Map<String, Object> metadata = harness.map(item.get("metadata"));
                return "task_brief".equals(String.valueOf(item.get("message_type")))
                    && taskId.equals(String.valueOf(item.get("task_id")))
                    && "task".equals(String.valueOf(metadata.get("continuity_scope")));
            }));
            assertTrue(relatedMessages.stream().anyMatch(item -> {
                Map<String, Object> metadata = harness.map(item.get("metadata"));
                Object relatedTaskId = item.get("task_id");
                return (relatedTaskId == null || String.valueOf(relatedTaskId).isBlank())
                    && "user_note".equals(String.valueOf(item.get("message_type")))
                    && "session".equals(String.valueOf(metadata.get("continuity_scope")));
            }));
        }
    }

    @Test
    void activeSessionStillAcceptsMessagesAndNewTasksAfterPreviousTaskDone() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-dialogue-followup-after-done.db"))) {
            HttpResponse<String> sessionResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/sessions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "title":"dialogue followup session"
                        }
                        """))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            String sessionId = String.valueOf(harness.map(harness.readJson(sessionResponse.body()).get("data")).get("id"));

            HttpResponse<String> firstTaskResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "title":"first task done",
                          "task_type":"continuation",
                          "source":"user",
                          "priority":"high",
                          "intent":"先完成第一轮任务。",
                          "goal":"验证任务完成后 session 仍可继续。",
                          "session_id":"%s",
                          "auto_start":false
                        }
                        """.formatted(sessionId)))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            String firstTaskId = String.valueOf(harness.map(harness.readJson(firstTaskResponse.body()).get("data")).get("id"));

            HttpResponse<String> doneResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + firstTaskId + "/state"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "state":"done",
                          "reason":"first round completed"
                        }
                        """))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            HttpResponse<String> noteResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/sessions/" + sessionId + "/messages"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "role":"user",
                          "message_type":"user_note",
                          "content":"第一轮完成后，继续补一个 follow-up 说明。",
                          "metadata":{
                            "source_surface":"web_dialogue",
                            "created_via":"dialogue_workspace"
                          }
                        }
                        """))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            HttpResponse<String> nextTaskResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "title":"second task after done",
                          "task_type":"continuation",
                          "source":"user",
                          "priority":"high",
                          "intent":"基于第一轮结果继续推进第二轮。",
                          "goal":"验证同 session 可继续发新任务。",
                          "session_id":"%s",
                          "parent_task_id":"%s",
                          "auto_start":false
                        }
                        """.formatted(sessionId, firstTaskId)))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            HttpResponse<String> messagesResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/sessions/" + sessionId + "/messages?limit=12"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            HttpResponse<String> sessionTasksResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/sessions/" + sessionId + "/tasks"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> nextTaskPayload = harness.readJson(nextTaskResponse.body());
            String nextTaskId = String.valueOf(harness.map(nextTaskPayload.get("data")).get("id"));
            List<Map<String, Object>> messages = harness.list(harness.readJson(messagesResponse.body()).get("data"));
            List<Map<String, Object>> sessionTasks = harness.list(harness.readJson(sessionTasksResponse.body()).get("data"));

            assertEquals(200, sessionResponse.statusCode());
            assertEquals(200, firstTaskResponse.statusCode());
            assertEquals(200, doneResponse.statusCode());
            assertEquals(200, noteResponse.statusCode());
            assertEquals(200, nextTaskResponse.statusCode());
            assertEquals(200, messagesResponse.statusCode());
            assertEquals(200, sessionTasksResponse.statusCode());
            assertTrue(messages.stream().anyMatch(item ->
                "user_note".equals(String.valueOf(item.get("message_type")))
                    && "第一轮完成后，继续补一个 follow-up 说明。".equals(String.valueOf(item.get("content")))));
            assertTrue(sessionTasks.stream().anyMatch(item ->
                firstTaskId.equals(String.valueOf(item.get("id")))
                    && "done".equals(String.valueOf(item.get("status")))));
            assertTrue(sessionTasks.stream().anyMatch(item ->
                nextTaskId.equals(String.valueOf(item.get("id")))
                    && firstTaskId.equals(String.valueOf(item.get("parent_task_id")))));
        }
    }

    @Test
    void sessionMessagesExposeStructuredAssistantProgressMetadata() throws Exception {
        try (HttpHarness harness = new HttpHarness(tempDir.resolve("task-handler-dialogue-progress-metadata.db"))) {
            HttpResponse<String> taskResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {
                          "title":"dialogue progress metadata",
                          "task_type":"continuation",
                          "source":"user",
                          "priority":"high",
                          "intent":"先生成一轮结构化 progress message。",
                          "goal":"验证 session messages 里的 assistant metadata 合同。",
                          "auto_start":false
                        }
                        """))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, Object> taskPayload = harness.readJson(taskResponse.body());
            Map<String, Object> taskData = harness.map(taskPayload.get("data"));
            String sessionId = String.valueOf(taskData.get("session_id"));
            String taskId = String.valueOf(taskData.get("id"));

            HttpResponse<String> continueResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/tasks/" + taskId + "/continue"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            HttpResponse<String> messagesResponse = harness.client.send(
                HttpRequest.newBuilder(harness.uri("/api/v1/sessions/" + sessionId + "/messages?task_id=" + taskId + "&limit=10"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            List<Map<String, Object>> messages = harness.list(harness.readJson(messagesResponse.body()).get("data"));
            Map<String, Object> progressMessage = messages.stream()
                .filter(item -> "task_progress".equals(String.valueOf(item.get("message_type"))))
                .findFirst()
                .orElseThrow();
            Map<String, Object> metadata = harness.map(progressMessage.get("metadata"));

            assertEquals(200, taskResponse.statusCode());
            assertEquals(200, continueResponse.statusCode());
            assertEquals(200, messagesResponse.statusCode());
            assertEquals("continue", String.valueOf(metadata.get("trigger")));
            assertEquals("continuation", String.valueOf(metadata.get("task_type")));
            assertEquals("orchestrated", String.valueOf(metadata.get("model_mode")));
            assertEquals("codex", String.valueOf(metadata.get("assigned_worker")));
            assertEquals("ready_fallback", String.valueOf(metadata.get("route_source")));
            assertEquals("继续扩写第二段。", String.valueOf(metadata.get("next_step")));
            assertTrue(String.valueOf(metadata.get("summary_preview")).contains("继续推进草稿"));
            assertTrue(String.valueOf(progressMessage.get("content")).contains("已完成一轮推进"));
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
                            "已继续推进草稿，并补出下一轮扩写线索。",
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
                        "已继续推进草稿，形成可扩写的首段结构。",
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
