package com.agentcloud.engine;

import com.agentcloud.engine.memory.PacketBuilder;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.*;
import com.agentcloud.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private final TaskDao taskDao;
    private final SessionDao sessionDao;
    private final EventDao eventDao;
    private final ResumePacketDao packetDao;
    private final WorkerRouter router;
    private final PacketBuilder packetBuilder;
    private final ControlNodeGraph controlGraph;

    public TaskService(TaskDao taskDao, SessionDao sessionDao, EventDao eventDao, ResumePacketDao packetDao,
                       WorkerRouter router, PacketBuilder packetBuilder, ControlNodeGraph controlGraph) {
        this.taskDao = taskDao;
        this.sessionDao = sessionDao;
        this.eventDao = eventDao;
        this.packetDao = packetDao;
        this.router = router;
        this.packetBuilder = packetBuilder;
        this.controlGraph = controlGraph;
    }

    public Task createTask(TaskCreateRequest req) {
        String taskId = IdGenerator.newId("task");
        String sessionId = req.sessionId();

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = IdGenerator.newId("session");
            Session s = Session.create(sessionId, req.title(), "active");
            sessionDao.insert(s);
            log.info("Auto-created session {} for task {}", sessionId, taskId);
        }

        Map<String, Object> meta = req.metadata() != null ? req.metadata() : new java.util.HashMap<>();
        meta.put("task_type", req.taskType());
        meta.put("source", req.source());
        meta.put("intent", req.intent());

        Task t = new Task(taskId, sessionId, null, req.title(), "active", req.priority(),
            Instant.now(), Instant.now(), Instant.now(), null, null, null, req.intent(), null, null, "intake", null, meta);
        taskDao.insert(t);

        eventDao.insert(new Event(IdGenerator.newId("evt"), sessionId, taskId, Instant.now(),
            "task_created", "system", null, "Task created: " + req.title(), Map.of("task_type", req.taskType())));

        // 进入控制节点图
        Task result = controlGraph.enter(t);
        sessionDao.updateState(sessionId, "active", Instant.now(), result.id(), null);
        return taskDao.findById(taskId).orElse(result);
    }

    public Task getTask(String taskId) {
        return taskDao.findById(taskId).orElse(null);
    }

    public List<Task> listTasks(String status, String taskType, String assignedWorker) {
        List<Task> list = status != null ? taskDao.listByStatus(status) : taskDao.listRecent(100);
        return list.stream()
            .filter(t -> taskType == null || (t.metadata() != null && taskType.equals(t.metadata().get("task_type"))))
            .filter(t -> assignedWorker == null || assignedWorker.equals(t.assignedWorker()))
            .toList();
    }

    public Task updateTaskState(String taskId, String newState, String reason) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        Task updated = t.withStatus(newState);
        taskDao.updateState(updated);

        eventDao.insert(new Event(IdGenerator.newId("evt"), t.sessionId(), taskId, Instant.now(),
            "task_state_changed", "system", null,
            "State changed to " + newState + ": " + reason,
            Map.of("old_state", t.status(), "new_state", newState)));

        log.info("Task {} state: {} -> {}, reason: {}", taskId, t.status(), newState, reason);
        return taskDao.findById(taskId).orElse(updated);
    }

    public ResumePacket refreshResumePacket(String taskId) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        Session s = sessionDao.findById(t.sessionId()).orElseThrow(() -> new IllegalStateException("session not found"));
        ResumePacket packet = packetBuilder.buildResumePacket(t, s);
        packetDao.insert(packet);
        log.info("Resume packet refreshed for task {}", taskId);
        return packet;
    }

    public ResumePacket getLatestPacket(String taskId) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return packetDao.getLatestByTask(t.sessionId(), taskId).orElse(null);
    }

    public Task pauseTask(String taskId, String reason) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return controlGraph.triggerPause(t, reason);
    }

    public Task resumeTask(String taskId) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return controlGraph.triggerResume(t);
    }

    public Task continueTask(String taskId) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return controlGraph.enter(t);
    }

    public Task escalateTask(String taskId, String reason) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return controlGraph.triggerEscalate(t, reason);
    }

    public Task handoffTask(String taskId, String targetWorker) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return controlGraph.triggerHandoff(t, targetWorker);
    }
}
