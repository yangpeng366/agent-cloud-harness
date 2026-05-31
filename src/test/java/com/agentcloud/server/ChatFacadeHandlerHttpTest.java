package com.agentcloud.server;

import com.agentcloud.engine.ChatFacadeService;
import com.agentcloud.engine.ControlNodeGraph;
import com.agentcloud.engine.SessionService;
import com.agentcloud.engine.TaskService;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.store.ArtifactDao;
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
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFacadeHandlerHttpTest {

    @TempDir
    Path tempDir;

    @Test
    void getModelsReturnsFacadeModelCards() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-models.db"))) {
            ApiCall response = fixture.get("/v1/models");

            assertEquals(200, response.statusCode());
            assertEquals("list", response.body().path("object").asText());
            assertEquals(3, response.body().path("data").size());
            assertEquals("agentcloud-default", response.body().path("data").get(0).path("id").asText());
            assertEquals("model", response.body().path("data").get(0).path("object").asText());
        }
    }

    @Test
    void postChatCompletionMessageOnlyCreatesSessionMessagesWithoutTask() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-message-only.db"))) {
            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "先记一条会话草稿，不进入任务执行。")),
                "stream", false,
                "metadata", Map.of("task_mode", "message_only")
            ));

            assertEquals(200, response.statusCode());
            JsonNode agentcloud = response.body().path("agentcloud");
            String sessionId = agentcloud.path("session_id").asText();
            assertFalse(sessionId.isBlank());
            assertTrue(agentcloud.path("task_id").isMissingNode() || agentcloud.path("task_id").isNull());
            assertEquals("chat_reply", agentcloud.path("reply_type").asText());
            assertEquals("session_ack", agentcloud.path("reply_source").asText());
            assertTrue(response.body().path("choices").get(0).path("message").path("content").asText()
                .contains("已记录到当前会话"));

            List<SessionMessage> messages = fixture.sessionMessageDao.listBySession(sessionId, 10);
            assertEquals(3, messages.size());
            assertEquals("session_receipt", messages.get(0).messageType());
            assertEquals("user_note", messages.get(1).messageType());
            assertEquals("chat_reply", messages.get(2).messageType());
            assertEquals("chat_facade", messages.get(1).metadata().get("source_surface"));
            assertEquals("message_only", messages.get(1).metadata().get("task_mode"));
        }
    }

    @Test
    void postChatCompletionTaskRequiredCreatesAndRunsTask() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-task-required.db"))) {
            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-strong",
                "messages", List.of(Map.of("role", "user", "content", "继续整理方案，并给出下一步。")),
                "stream", false,
                "metadata", Map.of(
                    "task_mode", "task_required",
                    "assigned_worker", "codex",
                    "task_type", "continuation"
                )
            ));

            assertEquals(200, response.statusCode());
            JsonNode agentcloud = response.body().path("agentcloud");
            String sessionId = agentcloud.path("session_id").asText();
            String taskId = agentcloud.path("task_id").asText();
            assertFalse(sessionId.isBlank());
            assertFalse(taskId.isBlank());
            assertEquals("/api/v1/tasks/" + taskId + "/live_flow", agentcloud.path("live_flow_path").asText());
            assertEquals("active", agentcloud.path("task_status").asText());
            assertEquals("task_progress", agentcloud.path("reply_type").asText());
            assertEquals("task_progress", agentcloud.path("reply_source").asText());
            assertTrue(response.body().path("choices").get(0).path("message").path("content").asText()
                .contains("下一步：继续扩写第二段"));

            Task task = fixture.taskDao.findById(taskId).orElseThrow();
            assertEquals(sessionId, task.sessionId());
            assertEquals("strong_only", task.metadata().get("model_mode"));
            assertEquals("codex", task.assignedWorker());

            List<SessionMessage> messages = fixture.sessionMessageDao.listBySession(sessionId, 12);
            assertTrue(messages.stream().anyMatch(message ->
                "task_brief".equals(message.messageType())
                    && "chat_facade".equals(message.metadata().get("source_surface"))));
            assertTrue(messages.stream().anyMatch(message ->
                "task_progress".equals(message.messageType())
                    && taskId.equals(message.taskId())));
        }
    }

    @Test
    void chatFacadeReplySourceKeepsWorkerRoundSemanticType() throws Exception {
        ChatFacadeService service = new ChatFacadeService(null, null);
        Method method = ChatFacadeService.class.getDeclaredMethod("replySource", SessionMessage.class, Task.class);
        method.setAccessible(true);

        Object replySource = method.invoke(service, new SessionMessage(
            "msg_worker_round_facade",
            "session_worker_round_facade",
            "task_worker_round_facade",
            "assistant",
            "worker_round",
            "Codex 执行回合已截断，保留部分输出。",
            Instant.now(),
            Map.of("execution_status", "partial_timeout")
        ), Task.create("task_worker_round_facade", "session_worker_round_facade", "worker round task", "active", "high"));

        assertEquals("worker_round", replySource);
    }

    @Test
    void postChatCompletionInfersCodingTaskTypeForRepoModificationRequests() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-infer-coding.db"))) {
            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of(
                    "role", "user",
                    "content", "根据文档修改 D:\\gitAll\\articleeditor 里的 ArticleThirdService.java，并补测试。"
                )),
                "stream", false,
                "metadata", Map.of(
                    "task_mode", "task_required"
                )
            ));

            assertEquals(200, response.statusCode());
            String taskId = response.body().path("agentcloud").path("task_id").asText();
            assertFalse(taskId.isBlank());

            Task task = fixture.taskDao.findById(taskId).orElseThrow();
            assertEquals("coding", String.valueOf(task.metadata().get("task_type")));
            assertEquals("coding", task.goal().contains("ArticleThirdService.java") ? String.valueOf(task.metadata().get("task_type")) : "coding");
            assertEquals("D:\\gitAll\\articleeditor", task.metadata().get("workspace_root"));
            assertEquals("D:\\gitAll\\articleeditor", task.metadata().get("cwd"));
            assertEquals("D:\\gitAll\\articleeditor", task.metadata().get("repo_path"));
            assertTrue(((List<?>) task.metadata().get("reference_paths")).contains("D:\\gitAll\\articleeditor"));
            assertTrue(((List<?>) task.metadata().get("target_paths")).contains("D:\\gitAll\\articleeditor"));
        }
    }

    @Test
    void postChatCompletionPreservesProviderExecutionContractMetadata() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-provider-contract.db"))) {
            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of(
                    "role", "user",
                    "content", "按本地代码任务合同推进，不要等 harness 预读文件。"
                )),
                "stream", false,
                "metadata", Map.of(
                    "task_mode", "task_required",
                    "task_type", "coding",
                    "repo_path", "D:\\gitAll\\agent-cloud-harness",
                    "reference_paths", List.of("D:\\gitAll\\agent-cloud-harness\\docs\\AGENT_PROVIDER_TECHNICAL_DESIGN.md"),
                    "validation_commands", List.of("mvn -Dtest=ProviderTaskPromptBuilderTest test"),
                    "write_scope", List.of("src/main/java/com/agentcloud/worker", "docs"),
                    "acceptance_criteria", List.of("worker reports validation result")
                )
            ));

            assertEquals(200, response.statusCode());
            String taskId = response.body().path("agentcloud").path("task_id").asText();
            Task task = fixture.taskDao.findById(taskId).orElseThrow();
            assertEquals("D:\\gitAll\\agent-cloud-harness", task.metadata().get("repo_path"));
            assertEquals("D:\\gitAll\\agent-cloud-harness", task.metadata().get("workspace_root"));
            assertEquals(List.of("mvn -Dtest=ProviderTaskPromptBuilderTest test"), task.metadata().get("validation_commands"));
            assertEquals(List.of("src/main/java/com/agentcloud/worker", "docs"), task.metadata().get("write_scope"));
            assertEquals(List.of("worker reports validation result"), task.metadata().get("acceptance_criteria"));
            assertEquals(
                List.of("D:\\gitAll\\agent-cloud-harness\\docs\\AGENT_PROVIDER_TECHNICAL_DESIGN.md"),
                task.metadata().get("reference_paths")
            );
        }
    }

    @Test
    void postChatCompletionSplitsMultipleLocalWorkspacesIntoChildTasks() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-split-workspaces.db"))) {
            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of(
                    "role", "user",
                    "content", "同时检查 D:\\gitAll\\articleeditor\\src 和 D:\\gitAll\\agent-cloud-harness\\docs，各自补测试。"
                )),
                "stream", false,
                "metadata", Map.of(
                    "task_mode", "task_required"
                )
            ));

            assertEquals(200, response.statusCode());
            String parentTaskId = response.body().path("agentcloud").path("task_id").asText();
            assertFalse(parentTaskId.isBlank());

            Task parent = fixture.taskDao.findById(parentTaskId).orElseThrow();
            assertEquals(Boolean.TRUE, parent.metadata().get("split_parent"));
            assertEquals("multiple_local_workspaces", parent.metadata().get("split_reason"));
            assertEquals("manual", parent.metadata().get("start_mode"));
            assertEquals("coding", parent.metadata().get("task_type"));

            List<Task> tasks = fixture.taskDao.listBySession(parent.sessionId());
            List<Task> children = tasks.stream()
                .filter(task -> parentTaskId.equals(task.parentTaskId()))
                .toList();
            assertEquals(2, children.size());
            assertTrue(children.stream().allMatch(task -> "coding".equals(task.metadata().get("task_type"))));
            assertTrue(children.stream().anyMatch(task ->
                "D:\\gitAll\\articleeditor".equals(task.metadata().get("cwd"))
                    && Boolean.TRUE.equals(task.metadata().get("split_child"))));
            assertTrue(children.stream().anyMatch(task ->
                "D:\\gitAll\\agent-cloud-harness".equals(task.metadata().get("cwd"))
                    && Boolean.TRUE.equals(task.metadata().get("split_child"))));
        }
    }

    @Test
    void postChatCompletionTaskRequiredCanReturnTerminalTaskResultReply() throws Exception {
        try (HttpFixture fixture = new HttpFixture(
            tempDir.resolve("chat-facade-task-result.db"),
            StubExecutionMode.TERMINAL_DONE
        )) {
            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "直接完成这条任务，并返回当前结果。")),
                "stream", false,
                "metadata", Map.of(
                    "task_mode", "task_required",
                    "task_type", "continuation"
                )
            ));

            assertEquals(200, response.statusCode());
            JsonNode agentcloud = response.body().path("agentcloud");
            String taskId = agentcloud.path("task_id").asText();
            assertFalse(taskId.isBlank());
            assertEquals("done", agentcloud.path("task_status").asText());
            assertEquals("end", agentcloud.path("control_node").asText());
            assertEquals("task_result", agentcloud.path("reply_type").asText());
            assertEquals("task_result", agentcloud.path("reply_source").asText());
            assertTrue(response.body().path("choices").get(0).path("message").path("content").asText()
                .contains("当前任务已完成"));

            Task task = fixture.taskDao.findById(taskId).orElseThrow();
            assertEquals("done", task.status());
            assertEquals("end", task.controlNode());

            List<SessionMessage> messages = fixture.sessionMessageDao.listBySession(task.sessionId(), 12);
            assertTrue(messages.stream().anyMatch(message ->
                "task_result".equals(message.messageType())
                    && taskId.equals(message.taskId())));
        }
    }

    @Test
    void postChatCompletionTaskRequiredCanCreateManualStartFollowupTask() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-manual-followup.db"))) {
            Task parentTask = fixture.taskService.createTask(new com.agentcloud.model.TaskCreateRequest(
                "parent task",
                "continuation",
                "user",
                "high",
                "先准备一个父任务。",
                "验证 follow-up contract。",
                null,
                null,
                Map.of("assigned_worker", "codex"),
                false
            ));

            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "基于上一轮补一个手动 follow-up。")),
                "stream", false,
                "metadata", Map.of(
                    "task_mode", "task_required",
                    "session_id", parentTask.sessionId(),
                    "parent_task_id", parentTask.id(),
                    "auto_start", false,
                    "task_type", "continuation",
                    "priority", "medium",
                    "title", "manual followup"
                )
            ));

            assertEquals(200, response.statusCode());
            String taskId = response.body().path("agentcloud").path("task_id").asText();
            assertEquals("active", response.body().path("agentcloud").path("task_status").asText());
            assertEquals("intake", response.body().path("agentcloud").path("control_node").asText());
            assertEquals("task_receipt", response.body().path("agentcloud").path("reply_type").asText());
            assertEquals("task_receipt", response.body().path("agentcloud").path("reply_source").asText());
            assertTrue(response.body().path("choices").get(0).path("message").path("content").asText()
                .contains("manual-start"));
            assertTrue(response.body().path("choices").get(0).path("message").path("content").asText()
                .contains("显式 /continue"));
            Task task = fixture.taskDao.findById(taskId).orElseThrow();
            assertEquals(parentTask.sessionId(), task.sessionId());
            assertEquals(parentTask.id(), task.parentTaskId());
            assertEquals("active", task.status());
            assertEquals("intake", task.controlNode());
            assertEquals(Boolean.FALSE, task.metadata().get("auto_start"));
            assertEquals("manual", task.metadata().get("start_mode"));

            List<SessionMessage> messages = fixture.sessionMessageDao.listBySession(parentTask.sessionId(), 20);
            assertTrue(messages.stream().anyMatch(message ->
                "task_followup".equals(message.messageType())
                    && parentTask.id().equals(String.valueOf(message.metadata().get("parent_task_id")))
                    && Boolean.FALSE.equals(message.metadata().get("auto_start"))));
            assertFalse(messages.stream().anyMatch(message -> "task_progress".equals(message.messageType())));
        }
    }

    @Test
    void postChatCompletionTaskIdContinuesExistingTask() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-task-id.db"))) {
            Task task = fixture.taskService.createTask(new com.agentcloud.model.TaskCreateRequest(
                "existing followup task",
                "continuation",
                "user",
                "high",
                "先准备一个可继续的任务。",
                "验证 task_id continuity。",
                null,
                null,
                Map.of("assigned_worker", "codex"),
                false
            ));

            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "继续这条任务，补下一段。")),
                "stream", false,
                "metadata", Map.of(
                    "session_id", task.sessionId(),
                    "task_id", task.id(),
                    "task_mode", "task_auto"
                )
            ));

            assertEquals(200, response.statusCode());
            assertEquals(task.sessionId(), response.body().path("agentcloud").path("session_id").asText());
            assertEquals(task.id(), response.body().path("agentcloud").path("task_id").asText());
            assertEquals("task_progress", response.body().path("agentcloud").path("reply_type").asText());
            assertEquals("task_progress", response.body().path("agentcloud").path("reply_source").asText());

            List<SessionMessage> taskMessages = fixture.sessionMessageDao.listBySessionAndTask(task.sessionId(), task.id(), 12);
            assertTrue(taskMessages.stream().anyMatch(message ->
                "task_note".equals(message.messageType())
                    && "chat_facade".equals(message.metadata().get("source_surface"))));
            assertTrue(taskMessages.stream().anyMatch(message -> "task_progress".equals(message.messageType())));
        }
    }

    @Test
    void postChatCompletionMessageOnlyWithTaskIdWritesTaskNoteWithoutContinuation() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-message-only-task-note.db"))) {
            Task task = fixture.taskService.createTask(new com.agentcloud.model.TaskCreateRequest(
                "message attach task",
                "continuation",
                "user",
                "high",
                "先准备一个 task-bound message attach 目标。",
                "验证 message_only + task_id contract。",
                null,
                null,
                Map.of("assigned_worker", "codex"),
                false
            ));

            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "这轮先作为 task note 附着，不推进执行链。")),
                "stream", false,
                "metadata", Map.of(
                    "session_id", task.sessionId(),
                    "task_id", task.id(),
                    "task_mode", "message_only"
                )
            ));

            assertEquals(200, response.statusCode());
            assertEquals(task.sessionId(), response.body().path("agentcloud").path("session_id").asText());
            assertEquals(task.id(), response.body().path("agentcloud").path("task_id").asText());
            assertEquals("chat_reply", response.body().path("agentcloud").path("reply_type").asText());
            assertEquals("session_ack", response.body().path("agentcloud").path("reply_source").asText());
            assertTrue(response.body().path("choices").get(0).path("message").path("content").asText()
                .contains("已记录到当前任务上下文"));

            Task persisted = fixture.taskDao.findById(task.id()).orElseThrow();
            assertEquals("intake", persisted.controlNode());
            assertEquals("active", persisted.status());

            List<SessionMessage> taskMessages = fixture.sessionMessageDao.listBySessionAndTask(task.sessionId(), task.id(), 12);
            assertTrue(taskMessages.stream().anyMatch(message ->
                "task_note".equals(message.messageType())
                    && "message_only".equals(message.metadata().get("task_mode"))
                    && "chat_facade".equals(message.metadata().get("source_surface"))));
            assertFalse(taskMessages.stream().anyMatch(message -> "task_progress".equals(message.messageType())));
            assertFalse(taskMessages.stream().anyMatch(message -> "task_followup".equals(message.messageType())));
        }
    }

    @Test
    void postChatCompletionTaskIdWithAutoStartFalseOnlyRecordsTaskNote() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-task-id-manual.db"))) {
            Task task = fixture.taskService.createTask(new com.agentcloud.model.TaskCreateRequest(
                "manual continuation task",
                "continuation",
                "user",
                "high",
                "先准备一个需要手动继续的任务。",
                "验证 auto_start=false 在 task_id continuity 上也会生效。",
                null,
                null,
                Map.of("assigned_worker", "codex"),
                false
            ));

            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "先记录这轮输入，但不要立刻继续执行。")),
                "stream", false,
                "metadata", Map.of(
                    "session_id", task.sessionId(),
                    "task_id", task.id(),
                    "task_mode", "task_auto",
                    "auto_start", false
                )
            ));

            assertEquals(200, response.statusCode());
            assertEquals(task.id(), response.body().path("agentcloud").path("task_id").asText());
            assertEquals("chat_reply", response.body().path("agentcloud").path("reply_type").asText());
            assertEquals("session_ack", response.body().path("agentcloud").path("reply_source").asText());
            assertTrue(response.body().path("choices").get(0).path("message").path("content").asText()
                .contains("等待手动继续"));

            Task persisted = fixture.taskDao.findById(task.id()).orElseThrow();
            assertEquals("intake", persisted.controlNode());
            assertEquals("active", persisted.status());

            List<SessionMessage> taskMessages = fixture.sessionMessageDao.listBySessionAndTask(task.sessionId(), task.id(), 12);
            assertTrue(taskMessages.stream().anyMatch(message ->
                "task_note".equals(message.messageType())
                    && Boolean.FALSE.equals(message.metadata().get("auto_start"))));
            assertFalse(taskMessages.stream().anyMatch(message -> "task_progress".equals(message.messageType())));
            assertFalse(taskMessages.stream().anyMatch(message -> "task_followup".equals(message.messageType())));
        }
    }

    @Test
    void postChatCompletionTaskRequiredWithTaskIdContinuesExistingTask() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-task-required-task-id.db"))) {
            Task task = fixture.taskService.createTask(new com.agentcloud.model.TaskCreateRequest(
                "required continuity task",
                "continuation",
                "user",
                "high",
                "先准备一个显式 task_required continuity 目标。",
                "验证 task_required + task_id auto-start contract。",
                null,
                null,
                Map.of("assigned_worker", "codex"),
                false
            ));

            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "按 task_required 继续这条任务。")),
                "stream", false,
                "metadata", Map.of(
                    "session_id", task.sessionId(),
                    "task_id", task.id(),
                    "task_mode", "task_required"
                )
            ));

            assertEquals(200, response.statusCode());
            assertEquals(task.id(), response.body().path("agentcloud").path("task_id").asText());
            assertEquals("task_progress", response.body().path("agentcloud").path("reply_type").asText());
            assertEquals("task_progress", response.body().path("agentcloud").path("reply_source").asText());

            List<SessionMessage> taskMessages = fixture.sessionMessageDao.listBySessionAndTask(task.sessionId(), task.id(), 12);
            assertTrue(taskMessages.stream().anyMatch(message ->
                "task_note".equals(message.messageType())
                    && "chat_facade".equals(message.metadata().get("source_surface"))
                    && "task_required".equals(message.metadata().get("task_mode"))));
            assertTrue(taskMessages.stream().anyMatch(message -> "task_progress".equals(message.messageType())));
        }
    }

    @Test
    void postChatCompletionTaskRequiredWithTaskIdAndAutoStartFalseOnlyRecordsTaskNote() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-task-required-task-id-manual.db"))) {
            Task task = fixture.taskService.createTask(new com.agentcloud.model.TaskCreateRequest(
                "required manual continuity task",
                "continuation",
                "user",
                "high",
                "先准备一个 task_required 手动 continuity 目标。",
                "验证 task_required + task_id + auto_start=false contract。",
                null,
                null,
                Map.of("assigned_worker", "codex"),
                false
            ));

            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "先记一轮 task_required continuity，但不要继续执行。")),
                "stream", false,
                "metadata", Map.of(
                    "session_id", task.sessionId(),
                    "task_id", task.id(),
                    "task_mode", "task_required",
                    "auto_start", false
                )
            ));

            assertEquals(200, response.statusCode());
            assertEquals(task.id(), response.body().path("agentcloud").path("task_id").asText());
            assertEquals("chat_reply", response.body().path("agentcloud").path("reply_type").asText());
            assertEquals("session_ack", response.body().path("agentcloud").path("reply_source").asText());
            assertTrue(response.body().path("choices").get(0).path("message").path("content").asText()
                .contains("等待手动继续"));

            List<SessionMessage> taskMessages = fixture.sessionMessageDao.listBySessionAndTask(task.sessionId(), task.id(), 12);
            assertTrue(taskMessages.stream().anyMatch(message ->
                "task_note".equals(message.messageType())
                    && Boolean.FALSE.equals(message.metadata().get("auto_start"))
                    && "task_required".equals(message.metadata().get("task_mode"))));
            assertFalse(taskMessages.stream().anyMatch(message -> "task_progress".equals(message.messageType())));
        }
    }

    @Test
    void postChatCompletionTaskAutoWithActiveTaskAndAutoStartFalseOnlyRecordsTaskNote() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-active-task-manual.db"))) {
            Task task = fixture.taskService.createTask(new com.agentcloud.model.TaskCreateRequest(
                "active session task",
                "continuation",
                "user",
                "high",
                "先准备一个 session 当前任务。",
                "验证 task_auto 会复用 active task，但 manual-start 不会自动继续。",
                null,
                null,
                Map.of("assigned_worker", "codex"),
                false
            ));

            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "复用当前任务，但先只记录上下文。")),
                "stream", false,
                "metadata", Map.of(
                    "session_id", task.sessionId(),
                    "task_mode", "task_auto",
                    "auto_start", false
                )
            ));

            assertEquals(200, response.statusCode());
            assertEquals(task.id(), response.body().path("agentcloud").path("task_id").asText());
            assertEquals("chat_reply", response.body().path("agentcloud").path("reply_type").asText());
            assertEquals("session_ack", response.body().path("agentcloud").path("reply_source").asText());
            assertTrue(response.body().path("choices").get(0).path("message").path("content").asText()
                .contains("等待手动继续"));

            Task persisted = fixture.taskDao.findById(task.id()).orElseThrow();
            assertEquals("intake", persisted.controlNode());
            assertEquals("active", persisted.status());

            List<SessionMessage> taskMessages = fixture.sessionMessageDao.listBySessionAndTask(task.sessionId(), task.id(), 12);
            assertTrue(taskMessages.stream().anyMatch(message ->
                "task_note".equals(message.messageType())
                    && Boolean.FALSE.equals(message.metadata().get("auto_start"))));
            assertFalse(taskMessages.stream().anyMatch(message -> "task_progress".equals(message.messageType())));
            assertFalse(taskMessages.stream().anyMatch(message -> "task_followup".equals(message.messageType())));
        }
    }

    @Test
    void postChatCompletionTaskAutoWithActiveTaskAutoStartsContinuation() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-active-task-auto.db"))) {
            Task task = fixture.taskService.createTask(new com.agentcloud.model.TaskCreateRequest(
                "active auto task",
                "continuation",
                "user",
                "high",
                "先准备一个会被 task_auto 复用的 active task。",
                "验证 task_auto active-task auto-start contract。",
                null,
                null,
                Map.of("assigned_worker", "codex"),
                false
            ));

            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "复用当前 active task，并直接继续。")),
                "stream", false,
                "metadata", Map.of(
                    "session_id", task.sessionId(),
                    "task_mode", "task_auto"
                )
            ));

            assertEquals(200, response.statusCode());
            assertEquals(task.id(), response.body().path("agentcloud").path("task_id").asText());
            assertEquals("task_progress", response.body().path("agentcloud").path("reply_type").asText());
            assertEquals("task_progress", response.body().path("agentcloud").path("reply_source").asText());

            List<SessionMessage> taskMessages = fixture.sessionMessageDao.listBySessionAndTask(task.sessionId(), task.id(), 12);
            assertTrue(taskMessages.stream().anyMatch(message ->
                "task_note".equals(message.messageType())
                    && "task_auto".equals(message.metadata().get("task_mode"))));
            assertTrue(taskMessages.stream().anyMatch(message -> "task_progress".equals(message.messageType())));
        }
    }

    @Test
    void postChatCompletionTaskAutoWithoutActiveTaskCreatesAndRunsTask() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-task-auto-create.db"))) {
            String sessionId = fixture.sessionService.createSession("task auto create", Map.of()).id();

            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "当前没有 active task，请自动建一个并继续。")),
                "stream", false,
                "metadata", Map.of(
                    "session_id", sessionId,
                    "task_mode", "task_auto",
                    "task_type", "continuation"
                )
            ));

            assertEquals(200, response.statusCode());
            String taskId = response.body().path("agentcloud").path("task_id").asText();
            assertFalse(taskId.isBlank());
            assertEquals("task_progress", response.body().path("agentcloud").path("reply_type").asText());
            assertEquals("task_progress", response.body().path("agentcloud").path("reply_source").asText());

            Task task = fixture.taskDao.findById(taskId).orElseThrow();
            assertEquals(sessionId, task.sessionId());

            List<Task> tasks = fixture.taskDao.listBySession(sessionId);
            assertEquals(1, tasks.size());
            List<SessionMessage> messages = fixture.sessionMessageDao.listBySession(sessionId, 12);
            assertTrue(messages.stream().anyMatch(message ->
                "task_brief".equals(message.messageType())
                    && "task_auto".equals(message.metadata().get("task_mode"))));
            assertTrue(messages.stream().anyMatch(message ->
                "task_progress".equals(message.messageType())
                    && taskId.equals(message.taskId())));
        }
    }

    @Test
    void postChatCompletionRejectsTaskAndSessionMismatch() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-task-session-mismatch.db"))) {
            Task task = fixture.taskService.createTask(new com.agentcloud.model.TaskCreateRequest(
                "existing task",
                "continuation",
                "user",
                "high",
                "先准备一个带 session 的任务。",
                "验证 task/session mismatch。",
                null,
                null,
                Map.of("assigned_worker", "codex"),
                false
            ));
            String otherSessionId = fixture.sessionService.createSession("other session", Map.of()).id();

            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "这轮 session_id 应该和 task 保持一致。")),
                "stream", false,
                "metadata", Map.of(
                    "session_id", otherSessionId,
                    "task_id", task.id(),
                    "task_mode", "task_auto"
                )
            ));

            assertEquals(400, response.statusCode());
            assertEquals("task must belong to the same session", response.body().path("message").asText());
        }
    }

    @Test
    void postChatCompletionRejectsClosedSession() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-closed-session.db"))) {
            String sessionId = fixture.sessionService.createSession("closed chat facade session", Map.of()).id();
            fixture.sessionService.closeSession(sessionId, Map.of());

            ApiCall response = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "关闭后的 session 不应继续接收 façade 消息。")),
                "stream", false,
                "metadata", Map.of(
                    "session_id", sessionId,
                    "task_mode", "message_only"
                )
            ));

            assertEquals(400, response.statusCode());
            assertEquals("session is closed", response.body().path("message").asText());
        }
    }

    @Test
    void postChatCompletionSupportsMinimalSseStream() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-stream-supported.db"))) {
            RawApiCall response = fixture.postRawJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "stream task progress test")),
                "stream", true,
                "metadata", Map.of(
                    "task_mode", "task_required",
                    "task_type", "continuation"
                )
            ));

            assertEquals(200, response.statusCode());
            assertTrue(response.contentType().startsWith("text/event-stream"));
            assertTrue(response.bodyText().contains("data: {"));
            assertTrue(response.bodyText().contains("\"object\":\"chat.completion.chunk\""));
            assertTrue(response.bodyText().contains("\"reply_type\":\"task_progress\""));
            assertTrue(response.bodyText().contains("\"reply_source\":\"task_progress\""));
            assertTrue(response.bodyText().contains("\"finish_reason\":\"stop\""));
            assertTrue(response.bodyText().contains("data: [DONE]"));
        }
    }

    @Test
    void postResponsesCreatesTaskRequiredResponseEnvelope() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("responses-facade-task-required.db"))) {
            ApiCall response = fixture.postJson("/v1/responses", Map.of(
                "model", "agentcloud-default",
                "input", List.of(Map.of(
                    "role", "user",
                    "content", List.of(Map.of(
                        "type", "input_text",
                        "text", "按 Responses API 方式创建一个新任务，并返回当前摘要。"
                    ))
                )),
                "stream", false,
                "metadata", Map.of(
                    "task_mode", "task_required",
                    "task_type", "continuation"
                )
            ));

            assertEquals(200, response.statusCode());
            assertEquals("response", response.body().path("object").asText());
            assertEquals("completed", response.body().path("status").asText());
            assertTrue(response.body().path("id").asText().startsWith("resp_"));
            assertEquals("assistant", response.body().path("output").get(0).path("role").asText());
            assertEquals("output_text", response.body().path("output").get(0).path("content").get(0).path("type").asText());
            assertFalse(response.body().path("output_text").asText().isBlank());
            assertEquals("task_progress", response.body().path("agentcloud").path("reply_type").asText());
            assertEquals("task_progress", response.body().path("agentcloud").path("reply_source").asText());
            String taskId = response.body().path("agentcloud").path("task_id").asText();
            assertFalse(taskId.isBlank());

            String sessionId = response.body().path("agentcloud").path("session_id").asText();
            List<SessionMessage> messages = fixture.sessionMessageDao.listBySession(sessionId, 12);
            assertTrue(messages.stream().anyMatch(message ->
                taskId.equals(message.taskId())
                    && "/v1/responses".equals(message.metadata().get("request_path"))
                    && "chat_facade".equals(message.metadata().get("source_surface"))));
        }
    }

    @Test
    void postResponsesSupportsMinimalSseStream() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("responses-facade-stream.db"))) {
            RawApiCall response = fixture.postRawJson("/v1/responses", Map.of(
                "model", "agentcloud-default",
                "input", "用 Responses API 走一条最小 stream task progress。",
                "stream", true,
                "metadata", Map.of(
                    "task_mode", "task_required",
                    "task_type", "continuation"
                )
            ));

            assertEquals(200, response.statusCode());
            assertTrue(response.contentType().startsWith("text/event-stream"));
            assertTrue(response.bodyText().contains("\"type\":\"response.created\""));
            assertTrue(response.bodyText().contains("\"type\":\"response.output_text.delta\""));
            assertTrue(response.bodyText().contains("\"type\":\"response.completed\""));
            assertTrue(response.bodyText().contains("\"reply_type\":\"task_progress\""));
            assertTrue(response.bodyText().contains("data: [DONE]"));
        }
    }

    @Test
    void chatFacadeAcceptanceFlowCoversMessageTaskNoteAndManualFollowupInOneSession() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("chat-facade-acceptance-flow.db"))) {
            ApiCall noteResponse = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "先记一条会话草稿，稍后再整理成任务。")),
                "stream", false,
                "metadata", Map.of("task_mode", "message_only")
            ));

            assertEquals(200, noteResponse.statusCode());
            String sessionId = noteResponse.body().path("agentcloud").path("session_id").asText();
            assertFalse(sessionId.isBlank());
            assertTrue(noteResponse.body().path("agentcloud").path("task_id").isMissingNode()
                || noteResponse.body().path("agentcloud").path("task_id").isNull());

            ApiCall taskResponse = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "把刚才的草稿整理成一个 manual-start 新任务。")),
                "stream", false,
                "metadata", Map.of(
                    "session_id", sessionId,
                    "task_mode", "task_required",
                    "auto_start", false,
                    "task_type", "continuation",
                    "title", "manual start task"
                )
            ));

            assertEquals(200, taskResponse.statusCode());
            String taskId = taskResponse.body().path("agentcloud").path("task_id").asText();
            assertFalse(taskId.isBlank());
            assertEquals("task_receipt", taskResponse.body().path("agentcloud").path("reply_type").asText());
            assertEquals("task_receipt", taskResponse.body().path("agentcloud").path("reply_source").asText());

            ApiCall taskNoteResponse = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "补一条 task note，但先不要继续执行。")),
                "stream", false,
                "metadata", Map.of(
                    "session_id", sessionId,
                    "task_id", taskId,
                    "task_mode", "message_only"
                )
            ));

            assertEquals(200, taskNoteResponse.statusCode());
            assertEquals("chat_reply", taskNoteResponse.body().path("agentcloud").path("reply_type").asText());
            assertEquals("session_ack", taskNoteResponse.body().path("agentcloud").path("reply_source").asText());

            ApiCall followupResponse = fixture.postJson("/v1/chat/completions", Map.of(
                "model", "agentcloud-default",
                "messages", List.of(Map.of("role", "user", "content", "基于当前任务，再建一个 manual-start follow-up。")),
                "stream", false,
                "metadata", Map.of(
                    "session_id", sessionId,
                    "task_mode", "task_required",
                    "parent_task_id", taskId,
                    "auto_start", false,
                    "task_type", "continuation",
                    "title", "child followup"
                )
            ));

            assertEquals(200, followupResponse.statusCode());
            String followupTaskId = followupResponse.body().path("agentcloud").path("task_id").asText();
            assertFalse(followupTaskId.isBlank());
            assertEquals("task_receipt", followupResponse.body().path("agentcloud").path("reply_type").asText());
            assertEquals("task_receipt", followupResponse.body().path("agentcloud").path("reply_source").asText());

            List<Task> tasks = fixture.taskDao.listBySession(sessionId);
            assertEquals(2, tasks.size());
            Task childTask = fixture.taskDao.findById(followupTaskId).orElseThrow();
            assertEquals(taskId, childTask.parentTaskId());
            assertEquals("manual", childTask.metadata().get("start_mode"));

            List<SessionMessage> allMessages = fixture.sessionMessageDao.listBySession(sessionId, 30);
            assertTrue(allMessages.stream().anyMatch(message -> "user_note".equals(message.messageType())));
            assertTrue(allMessages.stream().anyMatch(message ->
                "task_brief".equals(message.messageType())
                    && taskId.equals(message.taskId())));
            assertTrue(allMessages.stream().anyMatch(message ->
                "task_note".equals(message.messageType())
                    && taskId.equals(message.taskId())
                    && "message_only".equals(message.metadata().get("task_mode"))));
            assertTrue(allMessages.stream().anyMatch(message ->
                "task_followup".equals(message.messageType())
                    && followupTaskId.equals(message.taskId())
                    && taskId.equals(String.valueOf(message.metadata().get("parent_task_id")))));
            assertFalse(allMessages.stream().anyMatch(message ->
                followupTaskId.equals(message.taskId()) && "task_progress".equals(message.messageType())));
        }
    }

    private static final class HttpFixture implements AutoCloseable {
        private final DatabaseManager db;
        private final TaskDao taskDao;
        private final SessionMessageDao sessionMessageDao;
        private final SessionService sessionService;
        private final TaskService taskService;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final String baseUrl;

        private HttpFixture(Path dbPath) throws IOException {
            this(dbPath, StubExecutionMode.ACTIVE_PROGRESS);
        }

        private HttpFixture(Path dbPath, StubExecutionMode executionMode) throws IOException {
            this.db = new DatabaseManager(dbPath);
            this.taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            this.sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
            ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);
            LearningMemoryDao learningMemoryDao = db.jdbi().onDemand(LearningMemoryDao.class);
            ExperimentRunDao experimentRunDao = db.jdbi().onDemand(ExperimentRunDao.class);

            WorkerRouter workerRouter = new WorkerRouter(new WorkerRegistry());
            ControlNodeGraph controlGraph = new ControlNodeGraph(
                taskDao, null, null, null, null, null, null,
                null, null, null, null, null, null
            ) {
                @Override
                public Task enter(Task task) {
                    Task updated = executionMode == StubExecutionMode.TERMINAL_DONE
                        ? new Task(
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
                            "当前任务已完成，输出已经收口成最终结果。",
                            task.goal(),
                            null,
                            "codex",
                            "end",
                            task.waitingReason(),
                            task.metadata()
                        )
                        : new Task(
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
                            "packet",
                            task.waitingReason(),
                            task.metadata()
                        );
                    taskDao.updateState(updated);
                    return updated;
                }
            };

            this.sessionService = new SessionService(sessionDao, taskDao, sessionMessageDao, eventDao);
            this.taskService = new TaskService(
                taskDao,
                sessionDao,
                eventDao,
                packetDao,
                workerRouter,
                null,
                controlGraph,
                null,
                null,
                new com.agentcloud.engine.ConsolidationService(decisionDao, artifactDao, eventDao, checkpointDao, taskDao),
                new com.agentcloud.engine.LearningMemoryService(learningMemoryDao),
                toolInvocationDao,
                sessionMessageDao,
                new com.agentcloud.engine.ExperimentRunService(experimentRunDao, decisionDao, artifactDao, eventDao, toolInvocationDao)
            );
            ChatFacadeService chatFacadeService = new ChatFacadeService(sessionService, taskService);

            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            this.server.setExecutor(executor);
            ChatFacadeHandler handler = new ChatFacadeHandler(chatFacadeService, NioHttpServer.SHARED_MAPPER);
            this.server.createContext("/v1/chat/completions", handler);
            this.server.createContext("/v1/models", handler);
            this.server.createContext("/v1/responses", handler);
            this.server.start();
            this.client = HttpClient.newHttpClient();
            this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private ApiCall get(String path) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiCall(response.statusCode(), NioHttpServer.SHARED_MAPPER.readTree(response.body()));
        }

        private ApiCall postJson(String path, Map<String, Object> body) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(NioHttpServer.SHARED_MAPPER.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiCall(response.statusCode(), NioHttpServer.SHARED_MAPPER.readTree(response.body()));
        }

        private RawApiCall postRawJson(String path, Map<String, Object> body) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(NioHttpServer.SHARED_MAPPER.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            return new RawApiCall(response.statusCode(), contentType, response.body());
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            db.close();
        }
    }

    private record ApiCall(int statusCode, JsonNode body) {
    }

    private record RawApiCall(int statusCode, String contentType, String bodyText) {
    }

    private enum StubExecutionMode {
        ACTIVE_PROGRESS,
        TERMINAL_DONE
    }
}
