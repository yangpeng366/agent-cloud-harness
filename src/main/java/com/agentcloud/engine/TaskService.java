package com.agentcloud.engine;

import com.agentcloud.engine.memory.PacketBuilder;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.*;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
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
    private final RuntimeJudgmentService judgmentService;
    private final TaskRuntimeContextBuilder runtimeContextBuilder;
    private final ConsolidationService consolidationService;
    private final LearningMemoryService learningMemoryService;
    private final ToolInvocationDao toolInvocationDao;

    public TaskService(TaskDao taskDao, SessionDao sessionDao, EventDao eventDao, ResumePacketDao packetDao,
                       WorkerRouter router, PacketBuilder packetBuilder, ControlNodeGraph controlGraph,
                       RuntimeJudgmentService judgmentService,
                       TaskRuntimeContextBuilder runtimeContextBuilder,
                       ConsolidationService consolidationService,
                       LearningMemoryService learningMemoryService,
                       ToolInvocationDao toolInvocationDao) {
        this.taskDao = taskDao;
        this.sessionDao = sessionDao;
        this.eventDao = eventDao;
        this.packetDao = packetDao;
        this.router = router;
        this.packetBuilder = packetBuilder;
        this.controlGraph = controlGraph;
        this.judgmentService = judgmentService;
        this.runtimeContextBuilder = runtimeContextBuilder;
        this.consolidationService = consolidationService;
        this.learningMemoryService = learningMemoryService;
        this.toolInvocationDao = toolInvocationDao;
    }

    public Task createTask(TaskCreateRequest req) {
        String taskId = IdGenerator.newId("task");
        String parentTaskId = blankToNull(req.parentTaskId());
        Task parentTask = parentTaskId == null
            ? null
            : taskDao.findById(parentTaskId).orElseThrow(() -> new IllegalArgumentException("parent task not found"));
        String sessionId = blankToNull(req.sessionId());
        String goal = firstNonBlank(req.goal(), req.intent());
        boolean autoStart = shouldAutoStart(req);

        if (sessionId == null && parentTask != null) {
            sessionId = parentTask.sessionId();
        }

        if (sessionId == null || sessionId.isBlank()) {
            sessionId = IdGenerator.newId("session");
            Session s = Session.create(sessionId, req.title(), "active");
            sessionDao.insert(s);
            log.info("Auto-created session {} for task {}", sessionId, taskId);
        }

        if (parentTask != null && !sessionId.equals(parentTask.sessionId())) {
            throw new IllegalArgumentException("parent task must belong to the same session");
        }

        Map<String, Object> meta = req.metadata() != null ? new java.util.HashMap<>(req.metadata()) : new java.util.HashMap<>();
        meta.put("task_type", req.taskType());
        meta.put("source", req.source());
        meta.put("intent", req.intent());
        meta.put("auto_start", autoStart);
        meta.put("start_mode", autoStart ? "auto" : "manual");
        if (req.goal() != null && !req.goal().isBlank()) {
            meta.put("goal", req.goal());
        }
        if (parentTaskId != null) {
            meta.put("parent_task_id", parentTaskId);
        }

        Task t = new Task(taskId, sessionId, parentTaskId, req.title(), "active", req.priority(),
            Instant.now(), Instant.now(), Instant.now(), null, null, null, goal, null, null, "intake", null, meta);
        taskDao.insert(t);

        Map<String, Object> eventMetadata = new java.util.HashMap<>();
        eventMetadata.put("task_type", req.taskType());
        eventMetadata.put("auto_start", autoStart);
        eventMetadata.put("start_mode", autoStart ? "auto" : "manual");
        if (parentTaskId != null) {
            eventMetadata.put("parent_task_id", parentTaskId);
        }
        eventDao.insert(new Event(IdGenerator.newId("evt"), sessionId, taskId, Instant.now(),
            "task_created", "system", null, "Task created: " + req.title(), eventMetadata));

        Task result = t;
        if (autoStart) {
            // 默认仍保持历史行为：创建后立即进入控制节点图
            result = controlGraph.enter(t);
        } else {
            log.info("Task {} created with autoStart=false, waiting for explicit /continue", taskId);
        }

        sessionDao.updateState(sessionId, "active", Instant.now(), taskId, null);
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

    public WorkerRouter.RouteResult selectWorker(String taskId) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return router.selectWorker(t);
    }

    public TaskRuntimeContext getRuntimeContext(String taskId) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return runtimeContextBuilder.build(t);
    }

    public JudgmentTraceView getJudgmentTrace(String taskId) {
        Task task = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        TaskRuntimeContext runtimeContext = runtimeContextBuilder.build(task);
        Decision executionJudgment = latestDecision(runtimeContext, "execution_judgment");
        Decision completionJudgment = latestDecision(runtimeContext, "completion_judgment");
        String latestOutput = runtimeContext.recentArtifacts().isEmpty()
            ? ""
            : firstNonBlank(
                runtimeContext.recentArtifacts().get(0).summary(),
                runtimeContext.recentArtifacts().get(0).title()
            );
        String recommendedAction = executionJudgment != null && executionJudgment.metadata() != null
            ? stringValue(executionJudgment.metadata().get("action"))
            : null;
        String recommendedNextStep = firstNonBlank(
            executionJudgment != null && executionJudgment.metadata() != null
                ? stringValue(executionJudgment.metadata().get("next_step"))
                : null,
            completionJudgment != null && completionJudgment.metadata() != null
                ? stringValue(completionJudgment.metadata().get("suggested_next_action"))
                : null,
            task.nextStep()
        );
        return new JudgmentTraceView(
            task.id(),
            task.status(),
            task.controlNode(),
            task.assignedWorker(),
            latestOutput,
            recommendedAction,
            recommendedNextStep,
            executionJudgment,
            completionJudgment,
            runtimeContext
        );
    }

    public HandoffPacketView getHandoffPacket(String taskId, String targetWorker) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        Session s = sessionDao.findById(t.sessionId()).orElseThrow(() -> new IllegalStateException("session not found"));
        String fromWorker = t.assignedWorker() != null && !t.assignedWorker().isBlank() ? t.assignedWorker() : "unassigned";
        Map<String, Object> handoffPacket = packetBuilder.buildHandoffPacket(t, s, fromWorker, targetWorker);
        return new HandoffPacketView(t.id(), fromWorker, targetWorker, handoffPacket);
    }

    public TaskLiveFlowView getLiveFlow(String taskId, int limit) {
        Task task = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        int boundedLimit = boundedLimit(limit);
        ResumePacket latestPacket = packetDao.getLatestByTask(task.sessionId(), task.id()).orElse(null);
        var routePreview = router.selectWorker(task);
        TaskRuntimeContext runtimeContext = runtimeContextBuilder.build(task);
        JudgmentTraceView judgmentTrace = getJudgmentTrace(taskId);
        List<Checkpoint> checkpoints = consolidationService.listByTask(taskId, boundedLimit);
        List<LearningMemory> learningMemories = learningMemoryService.listByTask(taskId, boundedLimit);
        List<ToolInvocationRecord> toolInvocations = toolInvocationDao.listByTask(taskId, boundedLimit);
        return new TaskLiveFlowView(
            task,
            latestPacket,
            routePreview,
            runtimeContext,
            judgmentTrace,
            checkpoints,
            learningMemories,
            toolInvocations
        );
    }

    public List<ToolInvocationRecord> listToolInvocations(String taskId, int limit) {
        taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return toolInvocationDao.listByTask(taskId, boundedLimit(limit));
    }

    public TaskControlResult pauseTask(String taskId, String reason) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        Task updated = controlGraph.triggerPause(t, reason);
        return controlResult(updated, "pause", reason);
    }

    public TaskControlResult resumeTask(String taskId) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        Task updated = controlGraph.triggerResume(t);
        return controlResult(updated, "resume", null);
    }

    public TaskControlResult continueTask(String taskId) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        // Phase 1: judgment 已下沉到 ControlNodeGraph.continueNode，直接 enter 让控制图自行判断与迁移
        Task updated = controlGraph.enter(t);
        return controlResult(updated, updated.controlNode(), null);
    }

    public TaskControlResult escalateTask(String taskId, String reason) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        Task updated = controlGraph.triggerEscalate(t, reason);
        return controlResult(updated, "escalate", reason);
    }

    public HandoffResult handoffTask(String taskId, String targetWorker) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        Session s = sessionDao.findById(t.sessionId()).orElseThrow(() -> new IllegalStateException("session not found"));
        String previousWorker = t.assignedWorker();
        Map<String, Object> handoffPacket = packetBuilder.buildHandoffPacket(
            t,
            s,
            previousWorker != null && !previousWorker.isBlank() ? previousWorker : "unassigned",
            targetWorker
        );
        Task updated = controlGraph.triggerHandoff(t, targetWorker);
        return new HandoffResult(
            updated.id(),
            updated.status(),
            updated.controlNode(),
            previousWorker,
            updated.assignedWorker(),
            true,
            "handoff",
            handoffPacket
        );
    }

    private TaskControlResult controlResult(Task task, String decision, String reason) {
        ResumePacket packet = packetDao.getLatestByTask(task.sessionId(), task.id()).orElse(null);
        boolean packetExpected = "pause".equals(decision) || "resume".equals(decision)
            || "escalate".equals(decision) || "halt".equals(decision) || "handoff".equals(decision);
        return new TaskControlResult(
            task.id(),
            task.status(),
            task.controlNode(),
            task.assignedWorker(),
            decision,
            reason,
            packetExpected && packet != null,
            packet != null ? packet.id() : null
        );
    }

    private Decision latestDecision(TaskRuntimeContext runtimeContext, String decisionType) {
        if (runtimeContext == null || runtimeContext.recentDecisions() == null) {
            return null;
        }
        return runtimeContext.recentDecisions().stream()
            .filter(decision -> decisionType.equals(decision.decisionType()))
            .findFirst()
            .orElse(null);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean shouldAutoStart(TaskCreateRequest req) {
        return req == null || req.autoStart() == null || req.autoStart();
    }

    private int boundedLimit(int limit) {
        return Math.max(1, Math.min(limit, 20));
    }
}
