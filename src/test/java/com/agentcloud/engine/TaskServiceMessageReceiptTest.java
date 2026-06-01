package com.agentcloud.engine;

import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ExperimentRunDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.ToolInvocationDao;
import com.agentcloud.store.TaskDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceMessageReceiptTest {

    @TempDir
    Path tempDir;

    @Test
    void createTaskWritesAssistantReceiptMessage() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-receipt.db"))) {
            TaskService service = service(db, null);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "demo article", "continuation", "user", "high",
                "写一篇 continuity 相关文章", "完成公众号终稿", null, null, Map.of(), false
            ));

            List<SessionMessage> messages = messageDao.listBySession(task.sessionId(), 20);
            assertEquals(1, messages.size());
            assertEquals("assistant", messages.get(0).role());
            assertEquals("task_receipt", messages.get(0).messageType());
            assertEquals(task.id(), messages.get(0).taskId());
            assertTrue(messages.get(0).content().contains("manual-start"));
        }
    }

    @Test
    void updateTaskStateWritesSystemStateMessage() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-state-message.db"))) {
            TaskService service = service(db, null);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "demo state", "continuation", "user", "high",
                "先起一个手动任务", "等待人工确认", null, null, Map.of(), false
            ));

            service.updateTaskState(task.id(), "paused", "waiting for review");

            List<SessionMessage> messages = messageDao.listBySession(task.sessionId(), 20);
            assertEquals(2, messages.size());
            SessionMessage stateMessage = messages.get(1);
            assertEquals("system", stateMessage.role());
            assertEquals("task_state", stateMessage.messageType());
            assertEquals(task.id(), stateMessage.taskId());
            assertTrue(stateMessage.content().contains("paused"));
            assertTrue(stateMessage.content().contains("waiting for review"));
        }
    }

    @Test
    void autoStartWritesAssistantProgressMessage() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-progress-message.db"))) {
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, null, null, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task enter(Task task) {
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
                        "已生成文章提纲与核心论点。",
                        task.goal(),
                        "继续扩写首稿。",
                        "kimi-local-doc",
                        "scheduler",
                        task.waitingReason(),
                        task.metadata()
                    );
                    taskDao.updateState(updated);
                    return updated;
                }
            };

            TaskService service = service(db, graph);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "demo article", "continuation", "user", "high",
                "写一篇 continuity 文章", "完成终稿", null, null, Map.of(), true
            ));

            List<SessionMessage> messages = messageDao.listBySession(task.sessionId(), 20);
            assertEquals(2, messages.size());
            SessionMessage progress = messages.get(1);
            assertEquals("assistant", progress.role());
            assertEquals("task_progress", progress.messageType());
            assertEquals(task.id(), progress.taskId());
            assertEquals("auto_start", progress.metadata().get("trigger"));
            assertEquals("continuation", progress.metadata().get("task_type"));
            assertEquals("orchestrated", progress.metadata().get("model_mode"));
            assertEquals("kimi-local-doc", progress.metadata().get("assigned_worker"));
            assertTrue(progress.content().contains("已生成文章提纲与核心论点"));
            assertTrue(progress.content().contains("继续扩写首稿"));
            assertTrue(progress.content().contains("已完成一轮推进"));
        }
    }

    @Test
    void continueWritesAssistantResultMessageForTerminalTask() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-result-message.db"))) {
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, null, null, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task enter(Task task) {
                    Task updated = new Task(
                        task.id(),
                        task.sessionId(),
                        task.parentTaskId(),
                        task.title(),
                        "done",
                        task.priority(),
                        task.createdAt(),
                        Instant.now(),
                        task.startedAt(),
                        Instant.now(),
                        task.ownerRole(),
                        "终稿已完成，包含标题、导语与正文。",
                        task.goal(),
                        null,
                        "kimi-local-doc",
                        "end",
                        task.waitingReason(),
                        task.metadata()
                    );
                    taskDao.updateState(updated);
                    return updated;
                }
            };

            TaskService service = service(db, graph);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "demo finish", "continuation", "user", "high",
                "先创建一个 manual-start 任务", "完成终稿", null, null, Map.of(), false
            ));

            service.continueTask(task.id());

            List<SessionMessage> messages = messageDao.listBySession(task.sessionId(), 20);
            assertEquals(4, messages.size());
            SessionMessage stateMessage = messages.get(2);
            assertEquals("system", stateMessage.role());
            assertEquals("task_state", stateMessage.messageType());
            assertEquals("active", stateMessage.metadata().get("previous_state"));
            assertEquals("done", stateMessage.metadata().get("current_state"));

            SessionMessage resultMessage = messages.get(3);
            assertEquals("assistant", resultMessage.role());
            assertEquals("task_result", resultMessage.messageType());
            assertEquals(task.id(), resultMessage.taskId());
            assertEquals("continue", resultMessage.metadata().get("trigger"));
            assertEquals("continuation", resultMessage.metadata().get("task_type"));
            assertEquals("orchestrated", resultMessage.metadata().get("model_mode"));
            assertEquals("kimi-local-doc", resultMessage.metadata().get("assigned_worker"));
            assertTrue(resultMessage.content().contains("终稿已完成"));
            assertTrue(resultMessage.content().contains("done / end"));
            assertTrue(resultMessage.content().contains("已形成当前结果"));
        }
    }

    @Test
    void continueWritesAssistantProgressMessageWithExperimentRouteMetadata() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-progress-experiment-message.db"))) {
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, null, null, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task enter(Task task) {
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
                        "完成两步 tool chain，并生成一版可继续扩写的草稿。",
                        task.goal(),
                        "继续扩写终稿并补充结尾。",
                        "kimi",
                        "scheduler",
                        task.waitingReason(),
                        task.metadata()
                    );
                    taskDao.updateState(updated);
                    return updated;
                }
            };

            TaskService service = service(db, graph, true);
            Task task = service.createTask(new TaskCreateRequest(
                "experiment progress", "coding", "user", "high",
                "先创建一个带实验元数据的任务", "补充 tool-aware 路由观测", null, null,
                Map.of(
                    "experiment_name", "baseline-batch",
                    "task_case_key", "coding-medium-1",
                    "task_length_bucket", "medium",
                    "model_mode", "orchestrated"
                ),
                false
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
                    "selected_worker", "kimi",
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
                "exec_read_file",
                "read_file",
                Map.of("path", "input.txt"),
                "input loaded",
                "succeeded",
                true,
                14,
                List.of("input.txt"),
                Instant.now(),
                Map.of("tool_execution_mode", "multi_tool_round", "tool_chain_step_index", 1)
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
                24,
                List.of("draft.txt"),
                Instant.now(),
                Map.of("tool_execution_mode", "multi_tool_round", "tool_chain_step_index", 2)
            ));

            service.continueTask(task.id());

            List<SessionMessage> messages = messageDao.listBySession(task.sessionId(), 20);
            assertEquals(3, messages.size());
            SessionMessage progress = messages.get(2);
            assertEquals("assistant", progress.role());
            assertEquals("task_progress", progress.messageType());
            assertEquals("continue", progress.metadata().get("trigger"));
            assertEquals("baseline-batch", progress.metadata().get("experiment_name"));
            assertEquals("coding-medium-1", progress.metadata().get("task_case_key"));
            assertEquals("medium", progress.metadata().get("task_length_bucket"));
            assertEquals("orchestrated", progress.metadata().get("model_mode"));
            assertEquals("small", progress.metadata().get("selected_model_tier"));
            assertEquals("learning_memory", progress.metadata().get("route_source"));
            assertEquals("kimi", progress.metadata().get("preferred_worker_hint"));
            assertEquals(Boolean.TRUE, progress.metadata().get("learning_hint_applied"));
            assertEquals("hint survived tier filter", progress.metadata().get("fallback_reason"));
            assertEquals("multi_tool_round", progress.metadata().get("tool_execution_mode"));
            assertEquals(2, ((Number) progress.metadata().get("tool_chain_step_count")).intValue());
            assertEquals("planner_no_additional_tool", progress.metadata().get("tool_chain_termination_reason"));
            assertEquals("2 steps · planner_no_additional_tool · read_file -> write_file",
                progress.metadata().get("tool_chain_trace_summary"));
            assertEquals(List.of("read_file", "write_file"), progress.metadata().get("tool_chain_tools"));
            assertTrue(progress.content().contains("完成两步 tool chain"));
            assertTrue(progress.content().contains("继续扩写终稿"));
        }
    }

    @Test
    void continueWritesAssistantResultMessageWithAcceptanceMetadata() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-result-experiment-message.db"))) {
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, null, null, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task enter(Task task) {
                    Task updated = new Task(
                        task.id(),
                        task.sessionId(),
                        task.parentTaskId(),
                        task.title(),
                        "done",
                        task.priority(),
                        task.createdAt(),
                        Instant.now(),
                        task.startedAt(),
                        Instant.now(),
                        task.ownerRole(),
                        "终稿已完成，并通过当前验收标准。",
                        task.goal(),
                        null,
                        "codex",
                        "end",
                        task.waitingReason(),
                        task.metadata()
                    );
                    taskDao.updateState(updated);
                    return updated;
                }
            };

            TaskService service = service(db, graph, true);
            Task task = service.createTask(new TaskCreateRequest(
                "experiment result", "continuation", "user", "high",
                "先创建一个 terminal task", "形成终稿", null, null,
                Map.of(
                    "experiment_name", "baseline-batch",
                    "task_case_key", "continuation-long-1",
                    "task_length_bucket", "long",
                    "model_mode", "strong_only"
                ),
                false
            ));

            decisionDao.insert(new Decision(
                IdGenerator.newId("dec"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "completion_judgment",
                "done",
                "终稿满足当前验收标准。",
                "high",
                null,
                Map.of(
                    "status", "done",
                    "alignment_level", "high",
                    "evaluation_result", "meets_acceptance_bar"
                )
            ));

            service.continueTask(task.id());

            List<SessionMessage> messages = messageDao.listBySession(task.sessionId(), 20);
            assertEquals(4, messages.size());
            SessionMessage result = messages.get(3);
            assertEquals("assistant", result.role());
            assertEquals("task_result", result.messageType());
            assertEquals("continue", result.metadata().get("trigger"));
            assertEquals("strong_only", result.metadata().get("model_mode"));
            assertEquals("done", result.metadata().get("completion_status"));
            assertEquals("accepted", result.metadata().get("acceptance_result"));
            assertEquals("meets_acceptance_bar", result.metadata().get("evaluation_result"));
            assertEquals(0.0, ((Number) result.metadata().get("total_cost")).doubleValue());
            assertFalse(result.metadata().containsKey("failure_reason"));
            assertTrue(result.content().contains("终稿已完成"));
            assertTrue(result.content().contains("done / end"));
        }
    }

    @Test
    void continueWritesAssistantProgressMessageWithRecoveryMetadata() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-progress-recovery-message.db"))) {
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, null, null, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task enter(Task task) {
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
                        "worker 失联后已安排自动切换。",
                        task.goal(),
                        "等待新 worker 继续推进。",
                        "kimi",
                        "scheduler",
                        task.waitingReason(),
                        new java.util.LinkedHashMap<>(Map.of(
                            "model_mode", "orchestrated",
                            "failure_class", "worker_runtime_transient",
                            "failure_summary_readable", "worker codex failed: thread not found",
                            "recovery_policy", "same_worker_retry_then_auto_handoff",
                            "recovery_stage", "auto_handoff_scheduled",
                            "recovery_execution_mode", "fresh_session",
                            "auto_same_worker_retry_count", 1,
                            "auto_handoff_count", 1,
                            "auto_handoff_target", "kimi",
                            "previous_worker", "codex"
                        ))
                    );
                    taskDao.updateState(updated);
                    return updated;
                }
            };

            TaskService service = service(db, graph, true);
            Task task = service.createTask(new TaskCreateRequest(
                "recovery progress", "coding", "user", "high",
                "先创建一个需要恢复的任务", "完成恢复链可见性", null, null,
                Map.of("model_mode", "orchestrated"),
                false
            ));

            artifactDao.insert(new Artifact(
                IdGenerator.newId("art"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_output",
                "Failed worker round",
                null,
                null,
                "worker codex failed: thread not found",
                Map.ofEntries(
                    Map.entry("selected_worker", "kimi"),
                    Map.entry("selected_model_tier", "small"),
                    Map.entry("route_source", "capability_match"),
                    Map.entry("output_text", "worker codex failed: thread not found"),
                    Map.entry("execution_status", "failed"),
                    Map.entry("failure_class", "worker_runtime_transient"),
                    Map.entry("failure_summary_readable", "worker codex failed: thread not found"),
                    Map.entry("recovery_policy", "same_worker_retry_then_auto_handoff"),
                    Map.entry("recovery_stage", "auto_handoff_scheduled"),
                    Map.entry("recovery_execution_mode", "fresh_session"),
                    Map.entry("auto_same_worker_retry_count", 1),
                    Map.entry("auto_handoff_count", 1),
                    Map.entry("auto_handoff_target", "kimi"),
                    Map.entry("previous_worker", "codex")
                )
            ));

            service.continueTask(task.id());

            List<SessionMessage> messages = messageDao.listBySession(task.sessionId(), 20);
            SessionMessage progress = messages.get(messages.size() - 1);
            assertEquals("assistant", progress.role());
            assertEquals("task_progress", progress.messageType());
            assertEquals("worker_runtime_transient", progress.metadata().get("failure_class"));
            assertEquals("auto_handoff_scheduled", progress.metadata().get("recovery_stage"));
            assertEquals("same_worker_retry_then_auto_handoff", progress.metadata().get("recovery_policy"));
            assertEquals("fresh_session", progress.metadata().get("recovery_execution_mode"));
            assertEquals(1, ((Number) progress.metadata().get("auto_same_worker_retry_count")).intValue());
            assertEquals(1, ((Number) progress.metadata().get("auto_handoff_count")).intValue());
            assertEquals("kimi", progress.metadata().get("auto_handoff_target"));
            assertEquals("codex", progress.metadata().get("previous_worker"));
            String fullContent = String.valueOf(progress.metadata().get("full_content"));
            assertTrue(fullContent.contains("失败摘要"));
            assertTrue(fullContent.contains("恢复模式"));
            assertTrue(fullContent.contains("新会话"));
            assertTrue(fullContent.contains("worker codex failed: thread not found"));
            assertFalse(fullContent.contains("Failure Summary"));
            assertFalse(fullContent.contains("Recovery Mode"));
        }
    }

    @Test
    void continueWritesAssistantProgressMessageWithProviderDiagnostics() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-progress-provider-diagnostics-message.db"))) {
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, null, null, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task enter(Task task) {
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
                        "Codex 超时后已进入恢复流程。",
                        task.goal(),
                        "等待 fresh session 继续推进。",
                        "kimi",
                        "scheduler",
                        task.waitingReason(),
                        new java.util.LinkedHashMap<>(Map.ofEntries(
                            Map.entry("model_mode", "orchestrated"),
                            Map.entry("failure_class", "worker_runtime_transient"),
                            Map.entry("failure_summary_readable", "worker codex failed: thread not found (27984)"),
                            Map.entry("provider_error", "codex turn completion timed out"),
                            Map.entry("provider_turn_status", "timeout"),
                            Map.entry("provider_failure_class", "provider_runtime_transient"),
                            Map.entry("provider_failure_reason", "turn timed out"),
                            Map.entry("provider_retryable", true),
                            Map.entry("provider_thread_id", "019e4401-f18c-7fa2-b63d-8544108edcf5"),
                            Map.entry("provider_protocol_trace", List.of("thread/started", "turn/started")),
                            Map.entry("recovery_stage", "auto_handoff_scheduled"),
                            Map.entry("recovery_execution_mode", "fresh_session")
                        ))
                    );
                    taskDao.updateState(updated);
                    return updated;
                }
            };

            TaskService service = service(db, graph, true);
            Task task = service.createTask(new TaskCreateRequest(
                "provider diagnostics progress", "coding", "user", "high",
                "先创建一个 provider 超时恢复任务", "消息 metadata 必须带 provider diagnostics", null, null,
                Map.of("model_mode", "orchestrated"),
                false
            ));

            artifactDao.insert(new Artifact(
                IdGenerator.newId("art"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_output",
                "Provider timeout worker round",
                null,
                null,
                "worker codex failed: thread not found (27984)",
                Map.ofEntries(
                    Map.entry("selected_worker", "kimi"),
                    Map.entry("selected_model_tier", "small"),
                    Map.entry("route_source", "capability_match"),
                    Map.entry("execution_status", "timeout"),
                    Map.entry("failure_class", "worker_runtime_transient"),
                    Map.entry("failure_summary_readable", "worker codex failed: thread not found (27984)"),
                    Map.entry("output_text", "worker codex failed: thread not found (27984)"),
                    Map.entry("provider_error", "codex turn completion timed out"),
                    Map.entry("provider_turn_status", "timeout"),
                    Map.entry("provider_failure_class", "provider_runtime_transient"),
                    Map.entry("provider_failure_reason", "turn timed out"),
                    Map.entry("provider_retryable", true),
                    Map.entry("provider_thread_id", "019e4401-f18c-7fa2-b63d-8544108edcf5"),
                    Map.entry("provider_protocol_trace", List.of("thread/started", "turn/started")),
                    Map.entry("recovery_stage", "auto_handoff_scheduled"),
                    Map.entry("recovery_execution_mode", "fresh_session")
                )
            ));

            service.continueTask(task.id());

            List<SessionMessage> messages = messageDao.listBySession(task.sessionId(), 20);
            SessionMessage progress = messages.get(messages.size() - 1);
            assertEquals("assistant", progress.role());
            assertEquals("task_progress", progress.messageType());
            assertEquals("codex turn completion timed out", progress.metadata().get("provider_error"));
            assertEquals("timeout", progress.metadata().get("provider_turn_status"));
            assertEquals("provider_runtime_transient", progress.metadata().get("provider_failure_class"));
            assertEquals("turn timed out", progress.metadata().get("provider_failure_reason"));
            assertEquals(Boolean.TRUE, progress.metadata().get("provider_retryable"));
            assertEquals("019e4401-f18c-7fa2-b63d-8544108edcf5", progress.metadata().get("provider_thread_id"));
            assertFalse(progress.metadata().containsKey("provider_protocol_trace"));
            assertEquals(2, progress.metadata().get("provider_protocol_trace_count"));
            assertEquals(List.of("thread/started", "turn/started"), progress.metadata().get("provider_protocol_trace_preview"));
            assertEquals("codex turn completion timed out", progress.metadata().get("summary_preview"));
            assertTrue(progress.content().contains("codex turn completion timed out"));
            String fullContent = String.valueOf(progress.metadata().get("full_content"));
            assertTrue(fullContent.contains("失败摘要\ncodex turn completion timed out"));
            assertFalse(fullContent.contains("失败摘要\nworker codex failed: thread not found (27984)"));
            assertFalse(fullContent.contains("Failure Summary"));
        }
    }

    @Test
    void continueSuppressesUnreadableWorkerOutputFromExpandedFailureContent() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-progress-recovery-sanitized-message.db"))) {
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, null, null, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task enter(Task task) {
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
                        "worker 失联后已安排自动切换。",
                        task.goal(),
                        "等待新 worker 继续推进。",
                        "kimi",
                        "scheduler",
                        task.waitingReason(),
                        new java.util.LinkedHashMap<>(Map.of(
                            "model_mode", "orchestrated",
                            "failure_class", "worker_runtime_transient",
                            "failure_summary_readable", "worker codex failed: thread not found (19120)",
                            "recovery_policy", "same_worker_retry_then_auto_handoff",
                            "recovery_stage", "auto_handoff_scheduled",
                            "auto_same_worker_retry_count", 1,
                            "auto_handoff_count", 1,
                            "auto_handoff_target", "kimi",
                            "previous_worker", "codex"
                        ))
                    );
                    taskDao.updateState(updated);
                    return updated;
                }
            };

            TaskService service = service(db, graph, true);
            Task task = service.createTask(new TaskCreateRequest(
                "recovery progress noisy output", "coding", "user", "high",
                "先创建一个带旧噪声结果的恢复任务", "展开态也应该只保留短失败摘要", null, null,
                Map.of("model_mode", "orchestrated"),
                false
            ));

            String noisy = "����: û���ҵ����� \"19120\"��\n"
                + "我会先把和“下一步规划”最相关的文档与路线图过一遍。\n"
                + ".github\n"
                + "docs\\ARCHITECTURE.md\n"
                + "---";
            artifactDao.insert(new Artifact(
                IdGenerator.newId("art"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "worker_output",
                "Failed worker round",
                null,
                null,
                "worker codex failed: thread not found (19120)",
                Map.ofEntries(
                    Map.entry("selected_worker", "kimi"),
                    Map.entry("selected_model_tier", "small"),
                    Map.entry("route_source", "capability_match"),
                    Map.entry("output_text", noisy),
                    Map.entry("artifact_content", noisy),
                    Map.entry("execution_status", "failed"),
                    Map.entry("failure_class", "worker_runtime_transient"),
                    Map.entry("failure_summary_readable", "worker codex failed: thread not found (19120)"),
                    Map.entry("recovery_policy", "same_worker_retry_then_auto_handoff"),
                    Map.entry("recovery_stage", "auto_handoff_scheduled"),
                    Map.entry("auto_same_worker_retry_count", 1),
                    Map.entry("auto_handoff_count", 1),
                    Map.entry("auto_handoff_target", "kimi"),
                    Map.entry("previous_worker", "codex")
                )
            ));

            service.continueTask(task.id());

            List<SessionMessage> messages = messageDao.listBySession(task.sessionId(), 20);
            SessionMessage progress = messages.get(messages.size() - 1);
            String fullContent = String.valueOf(progress.metadata().get("full_content"));
            assertTrue(fullContent.contains("失败摘要"));
            assertTrue(fullContent.contains("worker codex failed: thread not found (19120)"));
            assertFalse(fullContent.contains("worker 输出\n" + noisy));
            assertFalse(fullContent.contains("产物内容\n" + noisy));
            assertFalse(fullContent.contains("Failure Summary"));
            assertFalse(fullContent.contains("Worker Output\n"));
            assertFalse(fullContent.contains("Artifact Content\n"));
            assertFalse(fullContent.contains(".github"));
            assertFalse(fullContent.contains("我会先把"));
        }
    }

    private TaskService service(DatabaseManager db, ControlNodeGraph graph) {
        return service(db, graph, false);
    }

    private TaskService service(DatabaseManager db, ControlNodeGraph graph, boolean withExperimentRunService) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
        ExperimentRunService experimentRunService = null;
        if (withExperimentRunService) {
            experimentRunService = new ExperimentRunService(
                db.jdbi().onDemand(ExperimentRunDao.class),
                db.jdbi().onDemand(DecisionDao.class),
                db.jdbi().onDemand(ArtifactDao.class),
                eventDao,
                db.jdbi().onDemand(ToolInvocationDao.class)
            );
        }
        return new TaskService(
            taskDao, sessionDao, eventDao, null, null, null, graph,
            null, null, null, null, null, sessionMessageDao, experimentRunService
        );
    }
}
