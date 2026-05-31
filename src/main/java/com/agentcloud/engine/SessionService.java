package com.agentcloud.engine;

import com.agentcloud.model.Event;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Session;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.SessionMessageCreateRequest;
import com.agentcloud.model.Task;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.JsonMapper;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private final SessionDao sessionDao;
    private final TaskDao taskDao;
    private final SessionMessageDao sessionMessageDao;
    private final EventDao eventDao;
    private final ArtifactDao artifactDao;

    public SessionService(SessionDao sessionDao, TaskDao taskDao) {
        this(sessionDao, taskDao, null, null, null);
    }

    public SessionService(SessionDao sessionDao, TaskDao taskDao, SessionMessageDao sessionMessageDao) {
        this(sessionDao, taskDao, sessionMessageDao, null, null);
    }

    public SessionService(SessionDao sessionDao, TaskDao taskDao, SessionMessageDao sessionMessageDao, EventDao eventDao) {
        this(sessionDao, taskDao, sessionMessageDao, eventDao, null);
    }

    public SessionService(SessionDao sessionDao, TaskDao taskDao, SessionMessageDao sessionMessageDao,
                          EventDao eventDao, ArtifactDao artifactDao) {
        this.sessionDao = sessionDao;
        this.taskDao = taskDao;
        this.sessionMessageDao = sessionMessageDao;
        this.eventDao = eventDao;
        this.artifactDao = artifactDao;
    }

    public Session createSession(String title) {
        return createSession(title, null);
    }

    public Session createSession(String title, Map<String, Object> requestMetadata) {
        String id = IdGenerator.newId("session");
        Session s = Session.create(id, title, "active");
        sessionDao.insert(s);
        recordSessionCreatedEvent(s, requestMetadata);
        recordSessionReceiptMessage(s, requestMetadata);
        log.info("Session created: {} - {}", id, title);
        return s;
    }

    public Session getSession(String id) {
        return sessionDao.findById(id).orElse(null);
    }

    public List<Session> listSessions() {
        return sessionDao.listAll();
    }

    public List<Session> listActiveSessions() {
        return sessionDao.listActive();
    }

    public Session closeSession(String id) {
        return closeSession(id, null);
    }

    public Session pauseSession(String id) {
        return pauseSession(id, null);
    }

    public Session resumeSession(String id) {
        return resumeSession(id, null);
    }

    public Session closeSession(String id, Map<String, Object> requestMetadata) {
        Session previous = requireSession(id);
        if ("closed".equalsIgnoreCase(firstNonBlank(previous.status(), ""))) {
            return previous;
        }
        ensureSessionHasNoOpenTasks(id);
        Instant closedAt = Instant.now();
        sessionDao.updateState(id, "closed", closedAt, closedAt, previous.currentTaskId(), previous.summary());
        Session current = requireSession(id);
        recordSessionStateChangedEvent(previous, current, requestMetadata);
        recordSessionStateMessage(previous, current, requestMetadata);
        log.info("Session closed: {}", id);
        return current;
    }

    public Session pauseSession(String id, Map<String, Object> requestMetadata) {
        return transitionSessionState(id, "paused", requestMetadata);
    }

    public Session resumeSession(String id, Map<String, Object> requestMetadata) {
        return transitionSessionState(id, "active", requestMetadata);
    }

    public Session updateCurrentTask(String sessionId, String taskId) {
        Session current = requireSession(sessionId);
        ensureSessionOpen(current);
        String status = firstNonBlank(current.status(), "active");
        sessionDao.updateState(sessionId, status, Instant.now(), current.closedAt(), taskId, null);
        return requireSession(sessionId);
    }

    public List<Task> getSessionTasks(String sessionId) {
        return taskDao.listBySession(sessionId);
    }

    public SessionMessage addMessage(String sessionId, SessionMessageCreateRequest request) {
        Session session = requireSession(sessionId);
        ensureSessionOpen(session);
        String content = trimToNull(request.content());
        if (content == null) {
            throw new IllegalArgumentException("message content is required");
        }

        String taskId = trimToNull(request.taskId());
        if (taskId != null) {
            Task task = taskDao.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("task not found"));
            if (!session.id().equals(task.sessionId())) {
                throw new IllegalArgumentException("task must belong to the same session");
            }
        }
        ensureMessageStoreAvailable();

        String role = normalizeRole(request.role());
        String messageType = trimToNull(request.messageType());
        if (messageType == null) {
            messageType = "note";
        }

        Map<String, Object> metadata = request.metadata() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(request.metadata());
        if (taskId != null) {
            metadata.putIfAbsent("task_id", taskId);
        }

        SessionMessage message = new SessionMessage(
            IdGenerator.newId("msg"),
            session.id(),
            taskId,
            role,
            messageType,
            content,
            Instant.now(),
            metadata.isEmpty() ? null : metadata
        );
        sessionMessageDao.insert(message);
        sessionDao.touch(session.id(), Instant.now());
        log.info("Session message added: session={} message={} role={} type={}", session.id(), message.id(), role, messageType);
        return message;
    }

    public List<SessionMessage> listMessages(String sessionId, int limit) {
        return listMessages(sessionId, limit, null);
    }

    public List<SessionMessage> listMessages(String sessionId, int limit, String taskId) {
        requireSession(sessionId);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String normalizedTaskId = trimToNull(taskId);
        if (normalizedTaskId != null) {
            requireTaskInSession(sessionId, normalizedTaskId);
        }
        ensureMessageStoreAvailable();
        if (normalizedTaskId == null) {
            backfillWorkerRoundMessagesForSession(sessionId);
            return compactWorkerRoundMessageMetadata(sessionMessageDao.listBySession(sessionId, safeLimit));
        }
        backfillWorkerRoundMessages(sessionId, normalizedTaskId);
        return compactWorkerRoundMessageMetadata(sessionMessageDao.listBySessionAndTask(sessionId, normalizedTaskId, safeLimit));
    }

    public SessionMessage bindMessageToTask(String sessionId, String messageId, String taskId) {
        requireSession(sessionId);
        requireTaskInSession(sessionId, taskId);
        ensureMessageStoreAvailable();
        SessionMessage existing = sessionMessageDao.findById(messageId);
        if (existing == null || !sessionId.equals(existing.sessionId())) {
            throw new IllegalArgumentException("session message not found");
        }
        Map<String, Object> metadata = existing.metadata() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(existing.metadata());
        metadata.put("task_id", taskId);
        sessionMessageDao.updateBinding(existing.id(), taskId, JsonMapper.toJson(metadata));
        return sessionMessageDao.findById(existing.id());
    }

    private Session requireSession(String sessionId) {
        Session session = getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("session not found");
        }
        return session;
    }

    private void ensureSessionOpen(Session session) {
        if (session != null && "closed".equalsIgnoreCase(firstNonBlank(session.status(), ""))) {
            throw new IllegalArgumentException("session is closed");
        }
    }

    private void ensureSessionHasNoOpenTasks(String sessionId) {
        List<Task> tasks = taskDao.listBySession(sessionId);
        boolean hasOpenTasks = tasks.stream().anyMatch(task -> !isTerminalTaskStatus(task.status()));
        if (hasOpenTasks) {
            throw new IllegalArgumentException("session has unfinished tasks");
        }
    }

    private Session transitionSessionState(String id, String targetStatus, Map<String, Object> requestMetadata) {
        Session previous = requireSession(id);
        ensureSessionOpen(previous);
        String normalizedTargetStatus = firstNonBlank(trimToNull(targetStatus), "active").toLowerCase(Locale.ROOT);
        String currentStatus = firstNonBlank(trimToNull(previous.status()), "active");
        if (normalizedTargetStatus.equalsIgnoreCase(currentStatus)) {
            return previous;
        }
        sessionDao.updateState(
            id,
            normalizedTargetStatus,
            Instant.now(),
            previous.closedAt(),
            previous.currentTaskId(),
            previous.summary()
        );
        Session current = requireSession(id);
        recordSessionStateChangedEvent(previous, current, requestMetadata);
        recordSessionStateMessage(previous, current, requestMetadata);
        log.info("Session state updated: {} {} -> {}", id, currentStatus, normalizedTargetStatus);
        return current;
    }

    private boolean isTerminalTaskStatus(String status) {
        String normalized = firstNonBlank(trimToNull(status), "");
        return "done".equalsIgnoreCase(normalized) || "failed".equalsIgnoreCase(normalized);
    }

    private Task requireTaskInSession(String sessionId, String taskId) {
        Task task = taskDao.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("task not found"));
        if (!sessionId.equals(task.sessionId())) {
            throw new IllegalArgumentException("task must belong to the same session");
        }
        return task;
    }

    private void ensureMessageStoreAvailable() {
        if (sessionMessageDao == null) {
            throw new IllegalStateException("session message store is not configured");
        }
    }

    private void backfillWorkerRoundMessages(String sessionId, String taskId) {
        if (artifactDao == null || sessionMessageDao == null) {
            return;
        }
        try {
            List<Artifact> artifacts = artifactDao.listBySessionAndTask(sessionId, taskId, 100);
            for (int i = artifacts.size() - 1; i >= 0; i--) {
                Artifact artifact = artifacts.get(i);
                if (!isWorkerRoundArtifact(artifact)) {
                    continue;
                }
                if (sessionMessageDao.findWorkerRoundByArtifactId(sessionId, taskId, artifact.id()) != null) {
                    continue;
                }
                sessionMessageDao.insert(projectWorkerRoundMessage(artifact));
            }
        } catch (Exception e) {
            log.warn("Failed to backfill worker_round messages for session={} task={}", sessionId, taskId, e);
        }
    }

    private void backfillWorkerRoundMessagesForSession(String sessionId) {
        if (artifactDao == null || sessionMessageDao == null || taskDao == null) {
            return;
        }
        try {
            List<Task> tasks = taskDao.listBySession(sessionId);
            for (Task task : tasks) {
                if (task != null && sessionId.equals(task.sessionId())) {
                    backfillWorkerRoundMessages(sessionId, task.id());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to backfill worker_round messages for session={}", sessionId, e);
        }
    }

    private boolean isWorkerRoundArtifact(Artifact artifact) {
        if (artifact == null) {
            return false;
        }
        Map<String, Object> metadata = metadataOrEmpty(artifact.metadata());
        String type = firstNonBlank(trimToNull(artifact.artifactType()), "");
        if ("worker_output".equalsIgnoreCase(type) || "worker_round".equalsIgnoreCase(type)) {
            return true;
        }
        return metadata.containsKey("latest_worker_metadata")
            || metadata.containsKey("selected_worker")
            || metadata.containsKey("execution_status")
            || metadata.containsKey("provider_id")
            || metadata.containsKey("provider_thread_id");
    }

    private List<SessionMessage> compactWorkerRoundMessageMetadata(List<SessionMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        return messages.stream()
            .map(this::compactWorkerRoundMessageMetadata)
            .toList();
    }

    private SessionMessage compactWorkerRoundMessageMetadata(SessionMessage message) {
        if (message == null || !"worker_round".equalsIgnoreCase(firstNonBlank(message.messageType(), ""))) {
            return message;
        }
        Map<String, Object> metadata = metadataOrEmpty(message.metadata());
        Object trace = metadata.get("provider_protocol_trace");
        if (!(trace instanceof List<?> values) || values.isEmpty()) {
            return message;
        }
        LinkedHashMap<String, Object> compacted = new LinkedHashMap<>(metadata);
        compacted.remove("provider_protocol_trace");
        compacted.putIfAbsent("provider_protocol_trace_count", values.size());
        compacted.putIfAbsent("provider_protocol_trace_preview", values.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::valueOf)
            .limit(20)
            .toList());
        persistCompactedWorkerRoundMetadata(message, compacted);
        return new SessionMessage(
            message.id(),
            message.sessionId(),
            message.taskId(),
            message.role(),
            message.messageType(),
            message.content(),
            message.createdAt(),
            compacted
        );
    }

    private void persistCompactedWorkerRoundMetadata(SessionMessage message, Map<String, Object> metadata) {
        if (sessionMessageDao == null || message == null || metadata == null) {
            return;
        }
        try {
            sessionMessageDao.updateBinding(message.id(), message.taskId(), JsonMapper.toJson(metadata));
        } catch (Exception e) {
            log.warn("Failed to compact worker_round message metadata for message={}", message.id(), e);
        }
    }

    private SessionMessage projectWorkerRoundMessage(Artifact artifact) {
        Map<String, Object> artifactMetadata = metadataOrEmpty(artifact.metadata());
        Map<String, Object> latestWorkerMetadata = nestedMetadata(artifactMetadata, "latest_worker_metadata");
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source_surface", "session_service");
        metadata.put("created_via", "worker_round_backfill_projection");
        metadata.put("artifact_id", artifact.id());
        metadata.put("artifact_type", artifact.artifactType());
        metadata.put("artifact_title", artifact.title());
        metadata.put("worker_id", firstNonBlank(
            metadataString(artifactMetadata, "worker_id"),
            metadataString(artifactMetadata, "selected_worker"),
            metadataString(latestWorkerMetadata, "selected_worker")
        ));
        metadata.put("execution_status", firstNonBlank(
            metadataString(artifactMetadata, "execution_status"),
            metadataString(latestWorkerMetadata, "execution_status"),
            "completed"
        ));
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_id");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_thread_id");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_session_id");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_error");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_turn_status");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_timeout_kind");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_abort_reason");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_turn_activity_timeout_ms");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_turn_max_duration_ms");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_failure_class");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_failure_reason");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_retryable");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_output_parser");
        copyProviderProtocolTraceSummary(metadata, artifactMetadata, latestWorkerMetadata);
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "execution_backend");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_run_dir");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_prompt_path");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_event_log_path");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_last_message_path");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_run_metadata_path");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "partial_output");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "partial_output_chars");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "truncated");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_output_truncated");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "unfinished_items");
        copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "suggested_next_step");
        String preview = previewText(firstNonBlank(
            metadataString(artifactMetadata, "summary"),
            metadataString(artifactMetadata, "output_text"),
            artifact.summary()
        ), 1_000);
        metadata.put("output_preview", preview);
        String content = buildWorkerRoundMessageContent(artifact, metadata);
        return new SessionMessage(
            IdGenerator.newId("msg"),
            artifact.sessionId(),
            artifact.taskId(),
            "assistant",
            "worker_round",
            content,
            artifact.createdAt() == null ? Instant.now() : artifact.createdAt(),
            metadata
        );
    }

    private String buildWorkerRoundMessageContent(Artifact artifact, Map<String, Object> metadata) {
        String worker = firstNonBlank(stringValue(metadata.get("worker_id")), "worker");
        String executionStatus = firstNonBlank(stringValue(metadata.get("execution_status")), "completed");
        String preview = firstNonBlank(stringValue(metadata.get("output_preview")), artifact != null ? artifact.summary() : null);
        String failure = firstNonBlank(
            metadataString(metadata, "provider_failure_reason"),
            metadataString(metadata, "provider_error")
        );
        if ("partial_timeout".equalsIgnoreCase(executionStatus)) {
            return previewText("Codex 执行回合已截断，保留部分输出。摘要：" + firstNonBlank(preview, failure, ""), 320);
        }
        if ("timeout".equalsIgnoreCase(executionStatus)
            || "failed".equalsIgnoreCase(executionStatus)
            || "error".equalsIgnoreCase(executionStatus)
            || "cancelled".equalsIgnoreCase(executionStatus)) {
            return previewText("worker " + worker + " 执行异常。原因：" + firstNonBlank(failure, preview, "unknown"), 320);
        }
        return previewText("worker " + worker + " 完成一轮执行。摘要：" + firstNonBlank(preview, artifact != null ? artifact.summary() : ""), 320);
    }

    private Map<String, Object> metadataOrEmpty(Map<String, Object> metadata) {
        return metadata == null ? Map.of() : metadata;
    }

    private void copyFromArtifactOrLatest(Map<String, Object> target,
                                          Map<String, Object> artifactMetadata,
                                          Map<String, Object> latestWorkerMetadata,
                                          String key) {
        copyIfPresent(target, artifactMetadata, key);
        if (!target.containsKey(key)) {
            copyIfPresent(target, latestWorkerMetadata, key);
        }
    }

    private void copyProviderProtocolTraceSummary(Map<String, Object> target,
                                                  Map<String, Object> artifactMetadata,
                                                  Map<String, Object> latestWorkerMetadata) {
        Object trace = firstNonNull(
            artifactMetadata != null ? artifactMetadata.get("provider_protocol_trace") : null,
            latestWorkerMetadata != null ? latestWorkerMetadata.get("provider_protocol_trace") : null
        );
        if (!(trace instanceof List<?> values) || values.isEmpty()) {
            return;
        }
        target.put("provider_protocol_trace_count", values.size());
        target.put("provider_protocol_trace_preview", values.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::valueOf)
            .limit(20)
            .toList());
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (target == null || source == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private Map<String, Object> nestedMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return Map.of();
        }
        Object value = metadata.get(key);
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        map.forEach((nestedKey, nestedValue) -> {
            if (nestedKey != null && nestedValue != null) {
                normalized.put(String.valueOf(nestedKey), nestedValue);
            }
        });
        return normalized.isEmpty() ? Map.of() : normalized;
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        return stringValue(metadata.get(key));
    }

    private String stringValue(Object value) {
        return value == null ? null : trimToNull(String.valueOf(value));
    }

    private String previewText(String value, int limit) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        return normalized.length() > limit ? normalized.substring(0, limit).trim() + "..." : normalized;
    }

    private void recordSessionReceiptMessage(Session session, Map<String, Object> extraMetadata) {
        LinkedHashMap<String, Object> metadata = lifecycleMetadata(session);
        metadata.put("action", "session_create");
        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            metadata.putAll(extraMetadata);
        }
        appendSessionMessage(
            session,
            "assistant",
            "session_receipt",
            "会话《" + sessionDisplayName(session) + "》已创建，当前状态为 " + session.status() + "。",
            metadata
        );
    }

    private void recordSessionStateMessage(Session previous, Session current, Map<String, Object> extraMetadata) {
        if (!statusChanged(previous, current)) {
            return;
        }
        LinkedHashMap<String, Object> metadata = lifecycleMetadata(current);
        metadata.put("action", sessionStateAction(previous, current));
        metadata.put("old_state", previous != null ? previous.status() : null);
        metadata.put("new_state", current.status());
        metadata.put("previous_state", previous != null ? previous.status() : null);
        metadata.put("current_state", current.status());
        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            metadata.putAll(extraMetadata);
        }
        appendSessionMessage(
            current,
            "system",
            "session_state",
            "会话《" + sessionDisplayName(current) + "》状态已从 " + firstNonBlank(previous != null ? previous.status() : null, "unknown")
                + " 更新为 " + firstNonBlank(current.status(), "unknown") + "。",
            metadata
        );
    }

    private void appendSessionMessage(Session session, String role, String messageType, String content, Map<String, Object> metadata) {
        if (sessionMessageDao == null || session == null) {
            return;
        }
        try {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("source_surface", "session_service");
            payload.put("created_via", "session_service");
            if (metadata != null && !metadata.isEmpty()) {
                payload.putAll(metadata);
            }
            sessionMessageDao.insert(new SessionMessage(
                IdGenerator.newId("msg"),
                session.id(),
                null,
                role,
                messageType,
                content,
                Instant.now(),
                payload
            ));
            sessionDao.touch(session.id(), Instant.now());
        } catch (Exception e) {
            log.warn("Failed to append session lifecycle message for session {}", session.id(), e);
        }
    }

    private void recordSessionCreatedEvent(Session session, Map<String, Object> extraMetadata) {
        if (eventDao == null || session == null) {
            return;
        }
        try {
            LinkedHashMap<String, Object> payload = lifecycleMetadata(session);
            payload.put("action", "session_create");
            if (extraMetadata != null && !extraMetadata.isEmpty()) {
                payload.putAll(extraMetadata);
            }
            eventDao.insert(new Event(
                IdGenerator.newId("evt"),
                session.id(),
                null,
                Instant.now(),
                "session_created",
                "system",
                null,
                "Session created: " + sessionDisplayName(session),
                payload
            ));
        } catch (Exception e) {
            log.warn("Failed to append session created event for session {}", session.id(), e);
        }
    }

    private void recordSessionStateChangedEvent(Session previous, Session current, Map<String, Object> extraMetadata) {
        if (eventDao == null || current == null || !statusChanged(previous, current)) {
            return;
        }
        try {
            LinkedHashMap<String, Object> payload = lifecycleMetadata(current);
            payload.put("action", sessionStateAction(previous, current));
            payload.put("old_state", previous != null ? previous.status() : null);
            payload.put("new_state", current.status());
            payload.put("previous_state", previous != null ? previous.status() : null);
            payload.put("current_state", current.status());
            if (extraMetadata != null && !extraMetadata.isEmpty()) {
                payload.putAll(extraMetadata);
            }
            eventDao.insert(new Event(
                IdGenerator.newId("evt"),
                current.id(),
                null,
                Instant.now(),
                "session_state_changed",
                "system",
                null,
                "Session state changed: " + firstNonBlank(previous != null ? previous.status() : null, "unknown")
                    + " -> " + firstNonBlank(current.status(), "unknown"),
                payload
            ));
        } catch (Exception e) {
            log.warn("Failed to append session state event for session {}", current.id(), e);
        }
    }

    private LinkedHashMap<String, Object> lifecycleMetadata(Session session) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (session == null) {
            return metadata;
        }
        metadata.put("session_status", session.status());
        if (session.title() != null && !session.title().isBlank()) {
            metadata.put("session_title", session.title());
        }
        if (session.currentTaskId() != null && !session.currentTaskId().isBlank()) {
            metadata.put("current_task_id", session.currentTaskId());
        }
        if (session.closedAt() != null) {
            metadata.put("closed_at", session.closedAt());
        }
        return metadata;
    }

    private boolean statusChanged(Session previous, Session current) {
        if (current == null) {
            return false;
        }
        return previous == null || !java.util.Objects.equals(previous.status(), current.status());
    }

    private String sessionStateAction(Session previous, Session current) {
        String currentStatus = firstNonBlank(trimToNull(current != null ? current.status() : null), "unknown");
        if ("closed".equalsIgnoreCase(currentStatus)) {
            return "session_close";
        }
        if ("paused".equalsIgnoreCase(currentStatus)) {
            return "session_pause";
        }
        String previousStatus = firstNonBlank(trimToNull(previous != null ? previous.status() : null), "");
        if ("paused".equalsIgnoreCase(previousStatus) && "active".equalsIgnoreCase(currentStatus)) {
            return "session_resume";
        }
        return "session_state_update";
    }

    private String sessionDisplayName(Session session) {
        if (session == null) {
            return "unknown";
        }
        return firstNonBlank(trimToNull(session.title()), session.id());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeRole(String role) {
        String normalized = trimToNull(role);
        if (normalized == null) {
            return "user";
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
