package com.agentcloud.engine;

import com.agentcloud.model.Session;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.SessionMessageCreateRequest;
import com.agentcloud.model.Task;
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

    public SessionService(SessionDao sessionDao, TaskDao taskDao) {
        this(sessionDao, taskDao, null);
    }

    public SessionService(SessionDao sessionDao, TaskDao taskDao, SessionMessageDao sessionMessageDao) {
        this.sessionDao = sessionDao;
        this.taskDao = taskDao;
        this.sessionMessageDao = sessionMessageDao;
    }

    public Session createSession(String title) {
        String id = IdGenerator.newId("session");
        Session s = Session.create(id, title, "active");
        sessionDao.insert(s);
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
        sessionDao.updateState(id, "closed", Instant.now(), null, "Session closed");
        log.info("Session closed: {}", id);
        return sessionDao.findById(id).orElse(null);
    }

    public Session updateCurrentTask(String sessionId, String taskId) {
        sessionDao.updateState(sessionId, "active", Instant.now(), taskId, null);
        return sessionDao.findById(sessionId).orElse(null);
    }

    public List<Task> getSessionTasks(String sessionId) {
        return taskDao.listBySession(sessionId);
    }

    public SessionMessage addMessage(String sessionId, SessionMessageCreateRequest request) {
        ensureMessageStoreAvailable();
        Session session = requireSession(sessionId);
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
        ensureMessageStoreAvailable();
        requireSession(sessionId);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String normalizedTaskId = trimToNull(taskId);
        if (normalizedTaskId == null) {
            return sessionMessageDao.listBySession(sessionId, safeLimit);
        }
        return sessionMessageDao.listBySessionAndTask(sessionId, normalizedTaskId, safeLimit);
    }

    private Session requireSession(String sessionId) {
        Session session = getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("session not found");
        }
        return session;
    }

    private void ensureMessageStoreAvailable() {
        if (sessionMessageDao == null) {
            throw new IllegalStateException("session message store is not configured");
        }
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
