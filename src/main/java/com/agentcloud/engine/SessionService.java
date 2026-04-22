package com.agentcloud.engine;

import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

public class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private final SessionDao sessionDao;
    private final TaskDao taskDao;

    public SessionService(SessionDao sessionDao, TaskDao taskDao) {
        this.sessionDao = sessionDao;
        this.taskDao = taskDao;
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
}
