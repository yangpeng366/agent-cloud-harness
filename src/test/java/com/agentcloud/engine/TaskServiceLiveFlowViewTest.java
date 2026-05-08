package com.agentcloud.engine;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.providers.CodexProvider;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.AgentRunRecord;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Event;
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
import com.agentcloud.store.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceLiveFlowViewTest {

    @TempDir
    Path tempDir;

    @Test
    void getJudgmentTraceWithoutToolEvidenceLeavesExecutionBoundaryNull() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("judgment-trace-no-execution-boundary.db"))) {
            TaskService service = service(db);

            Task task = service.createTask(new TaskCreateRequest(
                "judgment trace empty task", "continuation", "user", "high",
                "没有 tool evidence 时不应伪造 execution boundary", "保持 judgment trace 干净", null, null, Map.of(), false
            ));

            var trace = service.getJudgmentTrace(task.id());

            assertNull(trace.executionBoundary());
            assertNotNull(trace.runtimeFacts());
            assertEquals(task.id(), trace.runtimeFacts().taskId());
            assertEquals(0, ((Number) trace.runtimeFacts().metadata().get("tool_invocation_count")).intValue());
        }
    }

    @Test
    void getLiveFlowIncludesRelatedMessages() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("live-flow-related-messages.db"))) {
            TaskService service = service(db);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "live flow task", "continuation", "user", "high",
                "整理 continuity 相关的输入", "形成一版结构化摘要", null, null, Map.of(), false
            ));

            messageDao.insert(new SessionMessage(
                IdGenerator.newId("msg"),
                task.sessionId(),
                task.id(),
                "user",
                "task_note",
                "这条消息应被 live_flow 聚合进 related_messages",
                Instant.now(),
                Map.of("source_surface", "test")
            ));
            messageDao.insert(new SessionMessage(
                IdGenerator.newId("msg"),
                task.sessionId(),
                null,
                "user",
                "user_note",
                "这条 session 级消息不应出现在 related_messages 中",
                Instant.now(),
                Map.of("source_surface", "test")
            ));

            var flow = service.getLiveFlow(task.id(), 10);

            assertEquals(task.id(), flow.task().id());
            assertNotNull(flow.runtimeFacts());
            assertEquals(task.id(), flow.runtimeFacts().taskId());
            assertEquals(2, flow.relatedMessages().size());
            assertEquals(task.id(), flow.experimentRun().taskId());
            assertTrue(flow.relatedMessages().stream().allMatch(message -> task.id().equals(message.taskId())));
            assertTrue(flow.relatedMessages().stream().anyMatch(message -> "task_receipt".equals(message.messageType())));
            assertTrue(flow.relatedMessages().stream().anyMatch(message -> "task_note".equals(message.messageType())));
        }
    }

    @Test
    void getLiveFlowCarriesMountedContextRuntimeSurface() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("live-flow-mounted-context.db"))) {
            TaskService service = service(db);

            Task task = service.createTask(new TaskCreateRequest(
                "mounted live flow task", "continuation", "user", "high",
                "聚合 mounted context", "确认 live flow 暴露 mounted context", null, null, Map.of(), false
            ));

            var flow = service.getLiveFlow(task.id(), 10);

            assertNotNull(flow.runtimeContext());
            assertNotNull(flow.runtimeContext().mountedContextView());
            assertEquals(task.id(), flow.runtimeContext().mountedContextView().taskId());
            assertTrue(flow.runtimeContext().mountedContextView().selectionTrace()
                .contains("compat_mode=task_runtime_context_preserved"));
            assertTrue(flow.runtimeContext().mountedContextView().objects(MountedContextPanelName.PINNED).stream()
                .anyMatch(object -> object.type() == ContextObjectType.CONSTRAINT
                    && object.retentionState() == ContextRetentionState.PINNED
                    && object.summary().contains("保留 mounted context 里的关键约束")));
        }
    }

    @Test
    void getLiveFlowCarriesExperimentRunToolChainSummary() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("live-flow-tool-chain.db"))) {
            TaskService service = service(db);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "tool chain live flow task", "coding", "user", "high",
                "多步 tool chain 汇总", "给 live flow 增加 tool chain 摘要", null, null, Map.of(), false
            ));

            artifactDao.insert(new Artifact(
                IdGenerator.newId("art"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_artifact",
                "Executor result",
                null,
                null,
                "完成两步 tool chain",
                Map.of(
                    "selected_model_tier", "small",
                    "route_source", "learning_memory",
                    "preferred_worker_hint", "kimi",
                    "learning_hint_applied", true,
                    "fallback_reason", "hint survived tier filter",
                    "latest_worker_metadata", Map.of(
                        "tool_execution_mode", "multi_tool_round",
                        "tool_chain_step_count", 2,
                        "tool_chain_termination_reason", "planner_no_additional_tool",
                        "tool_chain_trace", List.of(
                            Map.of("tool_chain_step_index", 1, "tool_name", "read_file"),
                            Map.of("tool_chain_step_index", 2, "tool_name", "write_file")
                        ),
                        "execution_status", "blocked",
                        "evidence_refs", List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"),
                        "unfinished_items", List.of("manual_review")
                    )
                )
            ));
            toolInvocationDao.insert(new ToolInvocationRecord(
                IdGenerator.newId("tool"),
                task.sessionId(),
                task.id(),
                "kimi",
                "exec_write_file",
                "write_file",
                Map.of("path", "draft.txt"),
                "draft updated",
                "succeeded",
                true,
                21,
                List.of("draft.txt"),
                Instant.now(),
                Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "tool_chain_step_index", 2
                )
            ));
            toolInvocationDao.insert(new ToolInvocationRecord(
                IdGenerator.newId("tool"),
                task.sessionId(),
                task.id(),
                "kimi",
                "exec_read_file",
                "read_file",
                Map.of("path", "input.txt"),
                "input loaded",
                "succeeded",
                true,
                12,
                List.of("input.txt"),
                Instant.now(),
                Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "tool_chain_step_index", 1
                )
            ));

            var flow = service.getLiveFlow(task.id(), 10);

            assertEquals("learning_memory", flow.experimentRun().metadata().get("route_source"));
            assertNotNull(flow.runtimeFacts());
            assertEquals(flow.routePreview().selectedWorker(), flow.runtimeFacts().routePreview().selectedWorker());
            assertEquals(2, flow.runtimeFacts().toolInvocations().size());
            assertEquals(Set.of("read_file", "write_file"),
                flow.runtimeFacts().toolInvocations().stream().map(ToolInvocationRecord::toolName).collect(java.util.stream.Collectors.toSet()));
            assertEquals("blocked", flow.runtimeFacts().metadata().get("execution_status"));
            assertEquals("kimi", flow.experimentRun().metadata().get("preferred_worker_hint"));
            assertEquals(Boolean.TRUE, flow.experimentRun().metadata().get("learning_hint_applied"));
            assertEquals("hint survived tier filter", flow.experimentRun().metadata().get("fallback_reason"));
            assertNotNull(flow.executionBoundary());
            assertEquals("exec_read_file", flow.executionBoundary().executionId());
            assertEquals("kimi", flow.executionBoundary().workerId());
            assertEquals("blocked", flow.executionBoundary().executionStatus());
            assertEquals(2, flow.executionBoundary().toolInvocationCount());
            assertEquals("write_file", flow.executionBoundary().metadata().get("latest_tool_name"));
            assertEquals("2 steps · planner_no_additional_tool · read_file -> write_file", flow.executionBoundary().traceSummary());
            assertEquals("multi_tool_round", flow.experimentRun().metadata().get("tool_execution_mode"));
            assertEquals(2, ((Number) flow.experimentRun().metadata().get("tool_chain_step_count")).intValue());
            assertEquals("planner_no_additional_tool",
                flow.experimentRun().metadata().get("tool_chain_termination_reason"));
            assertEquals("2 steps · planner_no_additional_tool · read_file -> write_file",
                flow.experimentRun().metadata().get("tool_chain_trace_summary"));
            assertEquals(List.of("read_file", "write_file"), flow.experimentRun().metadata().get("tool_chain_tools"));
            assertEquals("blocked", flow.experimentRun().metadata().get("execution_status"));
            assertEquals(List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"),
                flow.experimentRun().metadata().get("evidence_refs"));
            assertEquals(List.of("manual_review"), flow.experimentRun().metadata().get("unfinished_items"));
        }
    }

    @Test
    void getJudgmentTraceCarriesExecutionBoundary() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("judgment-trace-execution-boundary.db"))) {
            TaskService service = service(db);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "judgment trace task", "coding", "user", "high",
                "把 execution boundary 带进 judgment trace", "方便 operator 对照判断与执行事实", null, null, Map.of(), false
            ));

            toolInvocationDao.insert(new ToolInvocationRecord(
                "exec_boundary_1",
                task.sessionId(),
                task.id(),
                "codex",
                "exec_boundary_1",
                "read_file",
                Map.of("path", "input.txt"),
                "input loaded",
                "succeeded",
                true,
                18,
                List.of("input.txt"),
                Instant.now(),
                Map.of(
                    "execution_status", "succeeded",
                    "execution_duration_ms", 18,
                    "tool_execution_mode", "single_tool_round"
                )
            ));

            var trace = service.getJudgmentTrace(task.id());

            assertNotNull(trace.executionBoundary());
            assertNotNull(trace.runtimeFacts());
            assertEquals("codex", trace.runtimeFacts().routePreview().selectedWorker());
            assertEquals(1, trace.runtimeFacts().toolInvocations().size());
            assertEquals("exec_boundary_1", trace.executionBoundary().executionId());
            assertEquals("codex", trace.executionBoundary().workerId());
            assertEquals("succeeded", trace.executionBoundary().executionStatus());
            assertEquals(1, trace.executionBoundary().toolInvocationCount());
            assertEquals("read_file", trace.executionBoundary().metadata().get("latest_tool_name"));
        }
    }

    @Test
    void getLiveFlowIncludesAgentRunProjection() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("live-flow-agent-run.db"))) {
            TaskService service = service(db);
            AgentRunDao agentRunDao = db.jdbi().onDemand(AgentRunDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "agent live flow task", "coding", "user", "high",
                "实现 provider live flow 聚合", "查看 agent run 诊断", null, null,
                Map.of("model_mode", "strong_only"), false
            ));

            agentRunDao.insert(new AgentRunRecord(
                "arun_live",
                task.id(),
                task.sessionId(),
                "codex",
                "Codex",
                "executor",
                "codex",
                "strong",
                "completed",
                Instant.now().minusMillis(200),
                Instant.now(),
                200L,
                "Codex run completed",
                "worker_round",
                1,
                Map.of("selection_reason", "test route")
            ));
            eventDao.insert(new Event(
                IdGenerator.newId("evt"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_round",
                "control_node",
                null,
                "Worker round completed",
                Map.of("agent_run_id", "arun_live", "provider_id", "codex")
            ));
            artifactDao.insert(new Artifact(
                IdGenerator.newId("art"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_artifact",
                "Codex output",
                "memory://codex-output",
                null,
                "agent artifact",
                Map.of("agent_run_id", "arun_live", "provider_id", "codex")
            ));

            var flow = service.getLiveFlow(task.id(), 10);

            assertNotNull(flow.providerSelection());
            assertEquals("codex", flow.providerSelection().selectedProvider());
            assertEquals("arun_live", flow.agentRun().runId());
            assertEquals(1, flow.agentRunEvents().size());
            assertEquals(1, flow.agentArtifacts().size());
            assertEquals("arun_live", flow.agentRunEvents().get(0).runId());
            assertEquals("arun_live", flow.agentArtifacts().get(0).runId());
        }
    }

    @Test
    void getHarnessTraceCompressesAheReviewInputs() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("harness-trace-ahe.db"))) {
            TaskService service = service(db);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "harness trace task", "coding", "user", "high",
                "生成 AHE 复盘输入", "检查 blocked 状态和证据", null, null, Map.of(), false
            ));

            artifactDao.insert(new Artifact(
                IdGenerator.newId("art"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_artifact",
                "Blocked executor result",
                null,
                null,
                "工具链停在人工复核",
                Map.of("latest_worker_metadata", Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "tool_chain_step_count", 2,
                    "tool_chain_termination_reason", "planner_no_additional_tool",
                    "tool_chain_trace", List.of(
                        Map.of("tool_chain_step_index", 1, "tool_name", "read_file"),
                        Map.of("tool_chain_step_index", 2, "tool_name", "write_file")
                    ),
                    "execution_status", "blocked",
                    "evidence_refs", List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"),
                    "unfinished_items", List.of("manual_review")
                ))
            ));
            toolInvocationDao.insert(new ToolInvocationRecord(
                IdGenerator.newId("tool"),
                task.sessionId(),
                task.id(),
                "tool-worker",
                "exec_write_file",
                "write_file",
                Map.of("path", "draft.txt"),
                "draft updated",
                "succeeded",
                true,
                15,
                List.of("draft.txt"),
                Instant.now(),
                Map.of("tool_execution_mode", "multi_tool_round", "tool_chain_step_index", 2)
            ));

            var trace = service.getHarnessTrace(task.id(), 10);

            assertEquals(task.id(), trace.taskId());
            assertEquals("blocked", trace.executionStatus());
            assertEquals(List.of("tool:read_file:input.txt", "tool:write_file:draft.txt"), trace.evidenceRefs());
            assertEquals(List.of("manual_review"), trace.unfinishedItems());
            assertEquals(1, trace.toolInvocations().size());
            assertNotNull(trace.experimentRun());
            assertEquals("multi_tool_round", trace.harnessMetadata().get("tool_execution_mode"));
            assertEquals(1, trace.harnessMetadata().get("tool_invocation_count"));
        }
    }

    @Test
    void getHarnessTraceFallsBackToToolInvocationMetadataWhenExperimentRunIsMissing() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("harness-trace-tool-metadata.db"))) {
            TaskService service = service(db);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "harness trace tool metadata", "coding", "user", "high",
                "聚合工具调用元数据", "无 artifact 时仍返回 schema 字段", null, null, Map.of(), false
            ));
            toolInvocationDao.insert(new ToolInvocationRecord(
                IdGenerator.newId("tool"),
                task.sessionId(),
                task.id(),
                "tool-worker",
                "exec_write_file",
                "write_file",
                Map.of("path", "draft.txt"),
                "draft updated",
                "succeeded",
                true,
                15,
                List.of("draft.txt"),
                Instant.now(),
                Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "execution_status", "blocked",
                    "evidence_refs", List.of("tool:write_file:draft.txt"),
                    "unfinished_items", List.of("manual_review")
                )
            ));

            var trace = service.getHarnessTrace(task.id(), 10);

            assertEquals("blocked", trace.executionStatus());
            assertEquals(List.of("tool:write_file:draft.txt"), trace.evidenceRefs());
            assertEquals(List.of("manual_review"), trace.unfinishedItems());
            assertEquals("multi_tool_round", trace.harnessMetadata().get("tool_execution_mode"));
            assertEquals(1, trace.harnessMetadata().get("tool_invocation_count"));
        }
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
}
