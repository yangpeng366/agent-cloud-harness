package com.agentcloud.engine;

import com.agentcloud.model.Event;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.TaskDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceControlActionProjectionTest {

    @TempDir
    Path tempDir;

    @Test
    void createTaskProjectsRequestMetadataToReceiptAndCreatedEvent() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-create-request-metadata.db"))) {
            TaskService service = service(db);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);

            Map<String, Object> requestMetadata = new LinkedHashMap<>();
            requestMetadata.put("requested_via", "http_api");
            requestMetadata.put("request_method", "POST");
            requestMetadata.put("request_path", "/api/v1/tasks");

            Task task = service.createTask(new TaskCreateRequest(
                "demo create", "continuation", "user", "high",
                "创建一个手动任务", "等待创建回执", null, null, Map.of(), false
            ), requestMetadata);

            SessionMessage receiptMessage = messageDao.listBySession(task.sessionId(), 20).stream()
                .filter(message -> "task_receipt".equals(message.messageType()))
                .findFirst()
                .orElseThrow();
            assertEquals("http_api", receiptMessage.metadata().get("requested_via"));
            assertEquals("POST", receiptMessage.metadata().get("request_method"));
            assertEquals("/api/v1/tasks", receiptMessage.metadata().get("request_path"));
            assertEquals("task_create", receiptMessage.metadata().get("action"));

            Event createdEvent = eventDao.listBySessionAndTask(task.sessionId(), task.id(), 20).stream()
                .filter(event -> "task_created".equals(event.eventType()))
                .findFirst()
                .orElseThrow();
            assertEquals("http_api", createdEvent.payload().get("requested_via"));
            assertEquals("POST", createdEvent.payload().get("request_method"));
            assertEquals("/api/v1/tasks", createdEvent.payload().get("request_path"));
            assertEquals(Boolean.FALSE, createdEvent.payload().get("auto_start"));
        }
    }

    @Test
    void updateTaskStateWritesConsistentLifecycleEventAndMessage() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-state-projection.db"))) {
            TaskService service = service(db);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "demo state", "continuation", "user", "high",
                "创建一个手动任务", "等待人工确认", null, null, Map.of(), false
            ));

            service.updateTaskState(task.id(), "paused", "waiting for review");

            List<SessionMessage> messages = messageDao.listBySession(task.sessionId(), 20);
            SessionMessage stateMessage = messages.get(1);
            assertEquals("task_state", stateMessage.messageType());
            assertEquals("task_state_update", stateMessage.metadata().get("action"));
            assertEquals("active", stateMessage.metadata().get("old_state"));
            assertEquals("paused", stateMessage.metadata().get("new_state"));
            assertEquals("active", stateMessage.metadata().get("previous_state"));
            assertEquals("paused", stateMessage.metadata().get("current_state"));
            assertEquals("paused", stateMessage.metadata().get("task_status"));
            assertEquals("intake", stateMessage.metadata().get("control_node"));
            assertEquals("waiting for review", stateMessage.metadata().get("reason"));
            assertEquals("task_service", stateMessage.metadata().get("source_surface"));
            assertFalse(stateMessage.metadata().containsKey("assigned_worker"));
            assertTrue(stateMessage.content().contains("状态已更新"));
            assertTrue(stateMessage.content().contains("active -> paused"));
            assertTrue(stateMessage.content().contains("当前：paused / intake"));

            Event stateEvent = eventDao.listBySessionAndTask(task.sessionId(), task.id(), 20).stream()
                .filter(event -> "task_state_changed".equals(event.eventType()))
                .findFirst()
                .orElseThrow();
            assertEquals("task_state_update", stateEvent.payload().get("action"));
            assertEquals("active", stateEvent.payload().get("old_state"));
            assertEquals("paused", stateEvent.payload().get("new_state"));
            assertEquals("active", stateEvent.payload().get("previous_state"));
            assertEquals("paused", stateEvent.payload().get("current_state"));
            assertEquals("paused", stateEvent.payload().get("task_status"));
            assertEquals("intake", stateEvent.payload().get("control_node"));
            assertEquals("waiting for review", stateEvent.payload().get("reason"));
            assertTrue(stateEvent.summary().contains("active -> paused"));
        }
    }

    @Test
    void updateTaskStateProjectsRequestMetadataWhenProvided() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-state-request-metadata.db"))) {
            TaskService service = service(db);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "demo http state", "continuation", "user", "high",
                "创建一个手动任务", "等待接口状态更新", null, null, Map.of(), false
            ));

            Map<String, Object> requestMetadata = new LinkedHashMap<>();
            requestMetadata.put("requested_via", "http_api");
            requestMetadata.put("request_method", "POST");
            requestMetadata.put("request_path", "/api/v1/tasks/" + task.id() + "/state");

            service.updateTaskState(task.id(), "done", "manual completion", requestMetadata);

            SessionMessage stateMessage = messageDao.listBySession(task.sessionId(), 20).stream()
                .filter(message -> "task_state".equals(message.messageType()))
                .filter(message -> "done".equals(message.metadata().get("current_state")))
                .findFirst()
                .orElseThrow();
            assertEquals("http_api", stateMessage.metadata().get("requested_via"));
            assertEquals("POST", stateMessage.metadata().get("request_method"));
            assertEquals("/api/v1/tasks/" + task.id() + "/state", stateMessage.metadata().get("request_path"));
            assertEquals("manual completion", stateMessage.metadata().get("reason"));

            Event stateEvent = eventDao.listBySessionAndTask(task.sessionId(), task.id(), 20).stream()
                .filter(event -> "task_state_changed".equals(event.eventType()))
                .filter(event -> "done".equals(event.payload().get("current_state")))
                .findFirst()
                .orElseThrow();
            assertEquals("http_api", stateEvent.payload().get("requested_via"));
            assertEquals("POST", stateEvent.payload().get("request_method"));
            assertEquals("/api/v1/tasks/" + task.id() + "/state", stateEvent.payload().get("request_path"));
            assertEquals("manual completion", stateEvent.payload().get("reason"));
        }
    }

    @Test
    void pauseTaskWritesControlActionProjectionWithStableMetadata() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-action-projection.db"))) {
            TaskService service = service(db);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "demo pause", "continuation", "user", "high",
                "创建一个手动任务", "等待暂停", null, null, Map.of(), false
            ));

            Map<String, Object> requestMetadata = new LinkedHashMap<>();
            requestMetadata.put("requested_via", "http_api");
            requestMetadata.put("request_method", "POST");
            requestMetadata.put("request_path", "/api/v1/tasks/" + task.id() + "/pause");

            service.pauseTask(task.id(), "needs review", requestMetadata);

            List<SessionMessage> messages = messageDao.listBySession(task.sessionId(), 20);
            SessionMessage actionMessage = messages.get(1);
            assertEquals("task_action", actionMessage.messageType());
            assertEquals("pause", actionMessage.metadata().get("action"));
            assertEquals("已暂停", actionMessage.metadata().get("action_label"));
            assertEquals("task_control", actionMessage.metadata().get("action_category"));
            assertEquals("paused", actionMessage.metadata().get("task_status"));
            assertEquals("packet", actionMessage.metadata().get("control_node"));
            assertEquals("codex", actionMessage.metadata().get("assigned_worker"));
            assertEquals("needs review", actionMessage.metadata().get("reason"));
            assertEquals("http_api", actionMessage.metadata().get("requested_via"));
            assertEquals("POST", actionMessage.metadata().get("request_method"));
            assertEquals("/api/v1/tasks/" + task.id() + "/pause", actionMessage.metadata().get("request_path"));
            assertTrue(actionMessage.content().contains("已暂停"));
            assertTrue(actionMessage.content().contains("当前：paused / packet"));

            Event actionEvent = eventDao.listBySessionAndTask(task.sessionId(), task.id(), 20).stream()
                .filter(event -> "task_control_action".equals(event.eventType()))
                .findFirst()
                .orElseThrow();
            assertEquals("pause", actionEvent.payload().get("action"));
            assertEquals("task_control", actionEvent.payload().get("action_category"));
            assertEquals("paused", actionEvent.payload().get("task_status"));
            assertEquals("packet", actionEvent.payload().get("control_node"));
            assertEquals("codex", actionEvent.payload().get("assigned_worker"));
            assertEquals("needs review", actionEvent.payload().get("reason"));
            assertEquals("http_api", actionEvent.payload().get("requested_via"));
            assertEquals("POST", actionEvent.payload().get("request_method"));
        }
    }

    @Test
    void resumeTaskWritesControlActionProjectionBeforeWorkerExecutionCompletes() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-resume-action-projection.db"))) {
            TaskService service = service(db);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);

            Task task = service.createTask(new TaskCreateRequest(
                "demo resume", "continuation", "user", "high",
                "创建一个暂停任务", "等待恢复", null, null, Map.of(), false
            ));

            Task paused = task.withStatus("paused")
                .withControlNode("packet")
                .withWaitingReason("manual pause");
            db.jdbi().onDemand(TaskDao.class).updateState(paused);

            Map<String, Object> requestMetadata = new LinkedHashMap<>();
            requestMetadata.put("requested_via", "http_api");
            requestMetadata.put("request_method", "POST");
            requestMetadata.put("request_path", "/api/v1/tasks/" + task.id() + "/resume");

            service.resumeTask(task.id(), requestMetadata);

            SessionMessage actionMessage = messageDao.listBySession(task.sessionId(), 20).stream()
                .filter(message -> "task_action".equals(message.messageType()))
                .filter(message -> "resume".equals(message.metadata().get("action")))
                .findFirst()
                .orElseThrow();
            assertEquals("POST", actionMessage.metadata().get("request_method"));
            assertEquals("http_api", actionMessage.metadata().get("requested_via"));
            assertEquals("/api/v1/tasks/" + task.id() + "/resume", actionMessage.metadata().get("request_path"));
            assertFalse(actionMessage.metadata().containsKey("legacy_control_route"));
            assertEquals("active", actionMessage.metadata().get("task_status"));
            assertEquals("scheduler", actionMessage.metadata().get("control_node"));

            SessionMessage stateMessage = messageDao.listBySession(task.sessionId(), 20).stream()
                .filter(message -> "task_state".equals(message.messageType()))
                .filter(message -> "active".equals(message.metadata().get("current_state")))
                .findFirst()
                .orElseThrow();
            assertEquals("paused", stateMessage.metadata().get("old_state"));
            assertEquals("active", stateMessage.metadata().get("new_state"));
            assertEquals("POST", stateMessage.metadata().get("request_method"));

            Event actionEvent = eventDao.listBySessionAndTask(task.sessionId(), task.id(), 20).stream()
                .filter(event -> "task_control_action".equals(event.eventType()))
                .filter(event -> "resume".equals(event.payload().get("action")))
                .findFirst()
                .orElseThrow();
            assertEquals("POST", actionEvent.payload().get("request_method"));
            assertEquals("active", actionEvent.payload().get("task_status"));
        }
    }

    private TaskService service(DatabaseManager db) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);

        ControlNodeGraph graph = new ControlNodeGraph(
            taskDao, eventDao, sessionDao, null, null, null, null,
            null, null, null, null, null, null
        ) {
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
                Task updated = task.withStatus("active")
                    .withControlNode("scheduler")
                    .withAssignedWorker("codex")
                    .withWaitingReason(null);
                taskDao.updateState(updated);
                return updated;
            }
        };

        TaskService service = new TaskService(
            taskDao, sessionDao, eventDao, null, null, null, graph,
            null, null, null, null, null, sessionMessageDao
        );
        assertNotNull(service);
        return service;
    }
}
