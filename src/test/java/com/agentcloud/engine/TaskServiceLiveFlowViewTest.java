package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.store.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceLiveFlowViewTest {

    @TempDir
    Path tempDir;

    @Test
    void getLiveFlowIncludesRelatedMessages() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("live-flow-related-messages.db"))) {
            TaskService service = service(db);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "live flow task", "continuation", "user", "high",
                "整理与 continuity 相关的输入", "形成一版结构化摘要", null, null, Map.of(), false
            ));

            messageDao.insert(new SessionMessage(
                IdGenerator.newId("msg"),
                task.sessionId(),
                task.id(),
                "user",
                "task_note",
                "这条消息应被 live_flow 聚合到 related_messages。",
                Instant.now(),
                Map.of("source_surface", "test")
            ));
            messageDao.insert(new SessionMessage(
                IdGenerator.newId("msg"),
                task.sessionId(),
                null,
                "user",
                "user_note",
                "这条 session 级消息不应出现在 related_messages 中。",
                Instant.now(),
                Map.of("source_surface", "test")
            ));

            var flow = service.getLiveFlow(task.id(), 10);

            assertEquals(task.id(), flow.task().id());
            assertEquals(2, flow.relatedMessages().size());
            assertEquals(task.id(), flow.experimentRun().taskId());
            assertTrue(flow.relatedMessages().stream().allMatch(message -> task.id().equals(message.taskId())));
            assertTrue(flow.relatedMessages().stream().anyMatch(message -> "task_receipt".equals(message.messageType())));
            assertTrue(flow.relatedMessages().stream().anyMatch(message -> "task_note".equals(message.messageType())));
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
                "完成两步 tool chain。",
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
                        )
                    )
                )
            ));
            toolInvocationDao.insert(new ToolInvocationRecord(
                IdGenerator.newId("tool"),
                task.sessionId(),
                task.id(),
                "kimi",
                "write_file",
                Map.of("path", "draft.txt"),
                "draft updated",
                true,
                21,
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
                "read_file",
                Map.of("path", "input.txt"),
                "input loaded",
                true,
                12,
                Instant.now(),
                Map.of(
                    "tool_execution_mode", "multi_tool_round",
                    "tool_chain_step_index", 1
                )
            ));

            var flow = service.getLiveFlow(task.id(), 10);

            assertEquals("learning_memory", flow.experimentRun().metadata().get("route_source"));
            assertEquals("kimi", flow.experimentRun().metadata().get("preferred_worker_hint"));
            assertEquals(Boolean.TRUE, flow.experimentRun().metadata().get("learning_hint_applied"));
            assertEquals("hint survived tier filter", flow.experimentRun().metadata().get("fallback_reason"));
            assertEquals("multi_tool_round", flow.experimentRun().metadata().get("tool_execution_mode"));
            assertEquals(2, ((Number) flow.experimentRun().metadata().get("tool_chain_step_count")).intValue());
            assertEquals("planner_no_additional_tool",
                flow.experimentRun().metadata().get("tool_chain_termination_reason"));
            assertEquals("2 steps · planner_no_additional_tool · read_file -> write_file",
                flow.experimentRun().metadata().get("tool_chain_trace_summary"));
            assertEquals(List.of("read_file", "write_file"), flow.experimentRun().metadata().get("tool_chain_tools"));
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
                    List.of(),
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
                        "已汇总 live flow 所需的最小上下文。",
                        "test runtime context",
                        12
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
            new ExperimentRunService(experimentRunDao, decisionDao, artifactDao, eventDao, toolInvocationDao)
        );
    }
}
