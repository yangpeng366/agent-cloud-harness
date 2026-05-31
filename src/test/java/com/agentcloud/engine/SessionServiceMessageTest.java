package com.agentcloud.engine;

import com.agentcloud.model.Session;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.SessionMessageCreateRequest;
import com.agentcloud.model.Task;
import com.agentcloud.model.Artifact;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.TaskDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionServiceMessageTest {

    @TempDir
    Path tempDir;

    @Test
    void addMessagePersistsAndListsBySession() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-message.db"))) {
            SessionService service = service(db);
            Session session = service.createSession("message session");

            SessionMessage created = service.addMessage(session.id(), new SessionMessageCreateRequest(
                "user",
                "note",
                "先整理选题，再决定是否发布任务。",
                null,
                Map.of("source_surface", "test")
            ));

            List<SessionMessage> messages = service.listMessages(session.id(), 20);
            SessionMessage note = messages.stream()
                .filter(message -> created.id().equals(message.id()))
                .findFirst()
                .orElseThrow();
            assertEquals("note", note.messageType());
            assertEquals("先整理选题，再决定是否发布任务。", note.content());
        }
    }

    @Test
    void addMessageRejectsTaskFromAnotherSession() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-message-mismatch.db"))) {
            SessionService service = service(db);
            Session sessionA = service.createSession("session a");
            Session sessionB = service.createSession("session b");

            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            taskDao.insert(Task.create("task_test", sessionA.id(), "root task", "active", "high"));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.addMessage(sessionB.id(), new SessionMessageCreateRequest(
                    "user",
                    "note",
                    "这条消息错误地引用了别的 session 任务。",
                    "task_test",
                    Map.of()
                ))
            );

            assertEquals("task must belong to the same session", error.getMessage());
        }
    }

    @Test
    void listMessagesFiltersByTask() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-message-filter.db"))) {
            SessionService service = service(db);
            Session session = service.createSession("message filter");

            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            taskDao.insert(Task.create("task_filter", session.id(), "task filter", "active", "high"));

            service.addMessage(session.id(), new SessionMessageCreateRequest(
                "user",
                "note",
                "这是一条普通备注。",
                null,
                Map.of("source_surface", "test")
            ));
            service.addMessage(session.id(), new SessionMessageCreateRequest(
                "user",
                "task_brief",
                "这是与任务关联的 brief。",
                "task_filter",
                Map.of("source_surface", "test")
            ));

            List<SessionMessage> messages = service.listMessages(session.id(), 20, "task_filter");
            assertEquals(1, messages.size());
            assertEquals("task_filter", messages.get(0).taskId());
            assertEquals("task_brief", messages.get(0).messageType());
        }
    }

    @Test
    void canFindProjectedWorkerRoundByArtifactId() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-worker-round-artifact.db"))) {
            SessionService service = service(db);
            Session session = service.createSession("worker round artifact");
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);
            taskDao.insert(Task.create("task_1", session.id(), "worker task", "active", "medium"));

            messageDao.insert(new SessionMessage(
                "msg_worker_round",
                session.id(),
                "task_1",
                "assistant",
                "worker_round",
                "Codex 执行了一轮。",
                Instant.now(),
                Map.of(
                    "created_via", "worker_round_projection",
                    "artifact_id", "art_1",
                    "worker_id", "codex"
                )
            ));

            SessionMessage found = messageDao.findWorkerRoundByArtifactId(session.id(), "task_1", "art_1");
            assertEquals("msg_worker_round", found.id());
            assertEquals("worker_round", found.messageType());
            assertEquals("art_1", found.metadata().get("artifact_id"));
        }
    }

    @Test
    void listMessagesBackfillsMissingWorkerRoundMessagesFromArtifacts() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-worker-round-backfill.db"))) {
            SessionService service = service(db);
            Session session = service.createSession("worker round backfill");
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            taskDao.insert(Task.create("task_backfill", session.id(), "worker task", "active", "medium"));

            artifactDao.insert(new Artifact(
                "art_backfill",
                session.id(),
                "task_backfill",
                Instant.now(),
                "worker_output",
                "Worker Output",
                null,
                null,
                "Codex produced a partial diagnostic result.",
                Map.of(
                    "selected_worker", "codex",
                    "execution_status", "partial_timeout",
                    "provider_id", "codex",
                    "provider_thread_id", "thread-codex-001",
                    "provider_failure_reason", "codex turn completion timed out",
                    "provider_retryable", true,
                    "provider_protocol_trace", List.of("thread/started", "turn/started", "item/agentMessage/delta"),
                    "latest_worker_metadata", Map.of(
                        "execution_backend", "provider_app_server",
                        "provider_timeout_kind", "activity_timeout",
                        "provider_abort_reason", "user_interrupted",
                        "partial_timeout_min_output_chars", 200,
                        "provider_output_parser", "codex_json_rpc"
                    )
                )
            ));

            List<SessionMessage> messages = service.listMessages(session.id(), 20, "task_backfill");
            SessionMessage workerRound = messages.stream()
                .filter(message -> "worker_round".equals(message.messageType()))
                .findFirst()
                .orElseThrow();

            assertEquals("art_backfill", workerRound.metadata().get("artifact_id"));
            assertEquals("worker_round_backfill_projection", workerRound.metadata().get("created_via"));
            assertEquals("codex", workerRound.metadata().get("provider_id"));
            assertEquals("provider_app_server", workerRound.metadata().get("execution_backend"));
            assertEquals("activity_timeout", workerRound.metadata().get("provider_timeout_kind"));
            assertEquals("user_interrupted", workerRound.metadata().get("provider_abort_reason"));
            assertEquals(200, ((Number) workerRound.metadata().get("partial_timeout_min_output_chars")).intValue());
            assertEquals("codex_json_rpc", workerRound.metadata().get("provider_output_parser"));
            assertEquals(3, workerRound.metadata().get("provider_protocol_trace_count"));
            assertEquals(List.of("thread/started", "turn/started", "item/agentMessage/delta"),
                workerRound.metadata().get("provider_protocol_trace_preview"));
            assertEquals(false, workerRound.metadata().containsKey("provider_protocol_trace"));

            List<SessionMessage> secondRead = service.listMessages(session.id(), 20, "task_backfill");
            long workerRoundCount = secondRead.stream()
                .filter(message -> "worker_round".equals(message.messageType()))
                .count();
            assertEquals(1, workerRoundCount);
        }
    }

    @Test
    void listSessionMessagesBackfillsWorkerRoundMessagesWithoutTaskFilter() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-worker-round-session-backfill.db"))) {
            SessionService service = service(db);
            Session session = service.createSession("session level worker round backfill");
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            taskDao.insert(Task.create("task_session_backfill", session.id(), "worker task", "active", "medium"));

            artifactDao.insert(new Artifact(
                "art_session_backfill",
                session.id(),
                "task_session_backfill",
                Instant.now(),
                "worker_output",
                "Worker Output",
                null,
                null,
                "Codex timed out after producing useful diagnostics.",
                Map.of(
                    "selected_worker", "codex",
                    "execution_status", "timeout",
                    "provider_id", "codex",
                    "provider_error", "codex turn completion timed out"
                )
            ));

            List<SessionMessage> messages = service.listMessages(session.id(), 20);
            SessionMessage workerRound = messages.stream()
                .filter(message -> "worker_round".equals(message.messageType()))
                .findFirst()
                .orElseThrow();

            assertEquals("task_session_backfill", workerRound.taskId());
            assertEquals("art_session_backfill", workerRound.metadata().get("artifact_id"));
            assertEquals("codex", workerRound.metadata().get("provider_id"));
        }
    }

    @Test
    void listMessagesCompactsExistingWorkerRoundProtocolTraceMetadata() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-worker-round-trace-compact.db"))) {
            SessionService service = service(db);
            Session session = service.createSession("worker round trace compact");
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionMessageDao messageDao = db.jdbi().onDemand(SessionMessageDao.class);
            taskDao.insert(Task.create("task_trace_compact", session.id(), "worker task", "active", "medium"));

            messageDao.insert(new SessionMessage(
                "msg_trace_compact",
                session.id(),
                "task_trace_compact",
                "assistant",
                "worker_round",
                "worker codex timed out",
                Instant.now(),
                Map.of(
                    "artifact_id", "art_trace_compact",
                    "provider_id", "codex",
                    "provider_protocol_trace", List.of("thread/started", "turn/started", "item/agentMessage/delta")
                )
            ));

            SessionMessage compacted = service.listMessages(session.id(), 20).stream()
                .filter(message -> "msg_trace_compact".equals(message.id()))
                .findFirst()
                .orElseThrow();

            assertEquals(false, compacted.metadata().containsKey("provider_protocol_trace"));
            assertEquals(3, compacted.metadata().get("provider_protocol_trace_count"));
            assertEquals(List.of("thread/started", "turn/started", "item/agentMessage/delta"),
                compacted.metadata().get("provider_protocol_trace_preview"));

            SessionMessage persisted = messageDao.findById("msg_trace_compact");
            assertEquals(false, persisted.metadata().containsKey("provider_protocol_trace"));
            assertEquals(3, persisted.metadata().get("provider_protocol_trace_count"));
        }
    }

    @Test
    void addMessageRejectsClosedSession() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-message-closed.db"))) {
            SessionService service = service(db);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);

            Session session = service.createSession("closed session");
            Instant closedAt = Instant.now();
            sessionDao.updateState(session.id(), "closed", closedAt, closedAt, null, "closed for follow-up");

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.addMessage(session.id(), new SessionMessageCreateRequest(
                    "user",
                    "note",
                    "这条消息不应写入 closed session。",
                    null,
                    Map.of()
                ))
            );

            assertEquals("session is closed", error.getMessage());
        }
    }

    @Test
    void listMessagesRejectsTaskFromAnotherSession() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("session-message-list-mismatch.db"))) {
            SessionService service = service(db);
            Session sessionA = service.createSession("session a");
            Session sessionB = service.createSession("session b");

            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            taskDao.insert(Task.create("task_list_test", sessionA.id(), "list task", "active", "high"));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.listMessages(sessionB.id(), 20, "task_list_test")
            );

            assertEquals("task must belong to the same session", error.getMessage());
        }
    }

    private SessionService service(DatabaseManager db) {
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
        return new SessionService(sessionDao, taskDao, sessionMessageDao, eventDao, artifactDao);
    }
}
