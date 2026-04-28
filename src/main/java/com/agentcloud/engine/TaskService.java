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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private final SessionMessageDao sessionMessageDao;
    private final ExperimentRunService experimentRunService;

    public TaskService(TaskDao taskDao, SessionDao sessionDao, EventDao eventDao, ResumePacketDao packetDao,
                       WorkerRouter router, PacketBuilder packetBuilder, ControlNodeGraph controlGraph,
                       RuntimeJudgmentService judgmentService,
                       TaskRuntimeContextBuilder runtimeContextBuilder,
                       ConsolidationService consolidationService,
                       LearningMemoryService learningMemoryService,
                       ToolInvocationDao toolInvocationDao) {
        this(taskDao, sessionDao, eventDao, packetDao, router, packetBuilder, controlGraph, judgmentService,
            runtimeContextBuilder, consolidationService, learningMemoryService, toolInvocationDao, null, null);
    }

    public TaskService(TaskDao taskDao, SessionDao sessionDao, EventDao eventDao, ResumePacketDao packetDao,
                       WorkerRouter router, PacketBuilder packetBuilder, ControlNodeGraph controlGraph,
                       RuntimeJudgmentService judgmentService,
                       TaskRuntimeContextBuilder runtimeContextBuilder,
                       ConsolidationService consolidationService,
                       LearningMemoryService learningMemoryService,
                       ToolInvocationDao toolInvocationDao,
                       SessionMessageDao sessionMessageDao) {
        this(taskDao, sessionDao, eventDao, packetDao, router, packetBuilder, controlGraph, judgmentService,
            runtimeContextBuilder, consolidationService, learningMemoryService, toolInvocationDao, sessionMessageDao, null);
    }

    public TaskService(TaskDao taskDao, SessionDao sessionDao, EventDao eventDao, ResumePacketDao packetDao,
                       WorkerRouter router, PacketBuilder packetBuilder, ControlNodeGraph controlGraph,
                       RuntimeJudgmentService judgmentService,
                       TaskRuntimeContextBuilder runtimeContextBuilder,
                       ConsolidationService consolidationService,
                       LearningMemoryService learningMemoryService,
                       ToolInvocationDao toolInvocationDao,
                       SessionMessageDao sessionMessageDao,
                       ExperimentRunService experimentRunService) {
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
        this.sessionMessageDao = sessionMessageDao;
        this.experimentRunService = experimentRunService;
    }

    public Task createTask(TaskCreateRequest req) {
        return createTask(req, null);
    }

    public Task createTask(TaskCreateRequest req, Map<String, Object> requestMetadata) {
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

        if (parentTask != null && sessionId != null && !sessionId.isBlank()
            && !sessionId.equals(parentTask.sessionId())) {
            throw new IllegalArgumentException("parent task must belong to the same session");
        }

        Session currentSession = null;
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = IdGenerator.newId("session");
            Session s = Session.create(sessionId, req.title(), "active");
            sessionDao.insert(s);
            currentSession = s;
            log.info("Auto-created session {} for task {}", sessionId, taskId);
        } else {
            currentSession = sessionDao.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found"));
            ensureSessionAcceptsTasks(currentSession);
        }

        Map<String, Object> meta = req.metadata() != null ? new java.util.HashMap<>(req.metadata()) : new java.util.HashMap<>();
        String modelMode = normalizeModelMode(stringValue(meta.get("model_mode")));
        meta.put("task_type", req.taskType());
        meta.put("source", req.source());
        meta.put("intent", req.intent());
        meta.put("auto_start", autoStart);
        meta.put("start_mode", autoStart ? "auto" : "manual");
        meta.put("model_mode", modelMode);
        if ("orchestrated".equals(modelMode)) {
            meta.putIfAbsent("orchestration_stage", "plan_pending");
        }
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
        if (requestMetadata != null && !requestMetadata.isEmpty()) {
            eventMetadata.putAll(requestMetadata);
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

        syncSessionCurrentTask(currentSession, taskId);
        Task persisted = taskDao.findById(taskId).orElse(result);
        recordTaskReceipt(persisted, autoStart, parentTaskId != null, requestMetadata);
        ExperimentRunRecord experimentRun = refreshExperimentRunRecord(persisted);
        if (autoStart) {
            recordAssistantProgressMessage(persisted, "auto_start", experimentRun);
        }
        return taskDao.findById(taskId).orElse(persisted);
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
        return updateTaskState(taskId, newState, reason, null);
    }

    public Task updateTaskState(String taskId, String newState, String reason, Map<String, Object> actionMetadata) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        Task updated = t.withStatus(newState);
        taskDao.updateState(updated);

        log.info("Task {} state: {} -> {}, reason: {}", taskId, t.status(), newState, reason);
        Task persisted = taskDao.findById(taskId).orElse(updated);
        recordTaskStateProjection(t, persisted, reason, actionMetadata);
        return refreshExperimentRun(persisted);
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
        HandoffPacket handoffPacket = packetBuilder.buildHandoffPacket(t, s, fromWorker, targetWorker);
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
        List<SessionMessage> relatedMessages = sessionMessageDao != null
            ? sessionMessageDao.listBySessionAndTask(task.sessionId(), task.id(), boundedLimit)
            : List.of();
        ExperimentRunRecord experimentRun = experimentRunService != null ? experimentRunService.refresh(task) : null;
        return new TaskLiveFlowView(
            task,
            latestPacket,
            routePreview,
            runtimeContext,
            judgmentTrace,
            checkpoints,
            learningMemories,
            toolInvocations,
            relatedMessages,
            experimentRun
        );
    }

    public List<ToolInvocationRecord> listToolInvocations(String taskId, int limit) {
        taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return toolInvocationDao.listByTask(taskId, boundedLimit(limit));
    }

    public ExperimentRunRecord getExperimentRun(String taskId) {
        Task task = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return experimentRunService != null ? experimentRunService.refresh(task) : null;
    }

    public TaskControlResult pauseTask(String taskId, String reason) {
        return pauseTask(taskId, reason, null);
    }

    public TaskControlResult pauseTask(String taskId, String reason, Map<String, Object> actionMetadata) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        Task updated = controlGraph.triggerPause(t, reason);
        recordControlActionEvent(updated, "pause", reason, actionMetadata);
        recordTaskActionMessage(updated, "pause", reason, actionMetadata);
        recordTaskStateProjection(t, updated, reason, actionMetadata);
        refreshExperimentRunRecord(updated);
        return controlResult(updated, "pause", reason);
    }

    public TaskControlResult resumeTask(String taskId) {
        return resumeTask(taskId, null);
    }

    public TaskControlResult resumeTask(String taskId, Map<String, Object> actionMetadata) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        Task updated = controlGraph.triggerResume(t);
        recordControlActionEvent(updated, "resume", null, actionMetadata);
        recordTaskActionMessage(updated, "resume", null, actionMetadata);
        recordTaskStateProjection(t, updated, null, actionMetadata);
        ExperimentRunRecord experimentRun = refreshExperimentRunRecord(updated);
        recordAssistantProgressMessage(updated, "resume", experimentRun);
        return controlResult(updated, "resume", null);
    }

    public TaskControlResult continueTask(String taskId) {
        return continueTask(taskId, null);
    }

    public TaskControlResult continueTask(String taskId, Map<String, Object> actionMetadata) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        // Phase 1: judgment 已下沉到 ControlNodeGraph.continueNode，直接 enter 让控制图自行判断与迁移
        Task updated = controlGraph.enter(t);
        recordControlActionEvent(updated, "continue", null, actionMetadata);
        recordTaskActionMessage(updated, "continue", null, actionMetadata);
        recordTaskStateProjection(t, updated, null, actionMetadata);
        ExperimentRunRecord experimentRun = refreshExperimentRunRecord(updated);
        recordAssistantProgressMessage(updated, "continue", experimentRun);
        return controlResult(updated, updated.controlNode(), null);
    }

    public TaskControlResult escalateTask(String taskId, String reason) {
        return escalateTask(taskId, reason, null);
    }

    public TaskControlResult escalateTask(String taskId, String reason, Map<String, Object> actionMetadata) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        Task updated = controlGraph.triggerEscalate(t, reason);
        recordControlActionEvent(updated, "escalate", reason, actionMetadata);
        recordTaskActionMessage(updated, "escalate", reason, actionMetadata);
        recordTaskStateProjection(t, updated, reason, actionMetadata);
        refreshExperimentRunRecord(updated);
        return controlResult(updated, "escalate", reason);
    }

    public HandoffResult handoffTask(String taskId, String targetWorker) {
        return handoffTask(taskId, targetWorker, null);
    }

    public HandoffResult handoffTask(String taskId, String targetWorker, Map<String, Object> actionMetadata) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        Session s = sessionDao.findById(t.sessionId()).orElseThrow(() -> new IllegalStateException("session not found"));
        String previousWorker = t.assignedWorker();
        HandoffPacket handoffPacket = packetBuilder.buildHandoffPacket(
            t,
            s,
            previousWorker != null && !previousWorker.isBlank() ? previousWorker : "unassigned",
            targetWorker
        );
        Task updated = controlGraph.triggerHandoff(t, targetWorker);
        LinkedHashMap<String, Object> metadata = mergeActionMetadata(actionMetadata);
        metadata.put("previous_worker", firstNonBlank(previousWorker, "unassigned"));
        metadata.put("target_worker", firstNonBlank(updated.assignedWorker(), targetWorker, "unassigned"));
        recordControlActionEvent(updated, "handoff", null, metadata);
        recordTaskActionMessage(updated, "handoff", null, metadata);
        recordTaskStateProjection(t, updated, null, metadata);
        ExperimentRunRecord experimentRun = refreshExperimentRunRecord(updated);
        recordAssistantProgressMessage(updated, "handoff", experimentRun);
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
        ResumePacket packet = packetDao != null
            ? packetDao.getLatestByTask(task.sessionId(), task.id()).orElse(null)
            : null;
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

    private String normalizeModelMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "orchestrated";
        }
        return switch (raw.trim().toLowerCase()) {
            case "strong_only", "small_only", "orchestrated" -> raw.trim().toLowerCase();
            default -> "orchestrated";
        };
    }

    private int boundedLimit(int limit) {
        return Math.max(1, Math.min(limit, 20));
    }

    private void recordTaskReceipt(Task task, boolean autoStart, boolean followup, Map<String, Object> extraMetadata) {
        String title = taskDisplayName(task);
        String content = autoStart
            ? "任务《" + title + "》已创建，并已自动进入 harness。当前：" + describeTaskSnapshot(task) + "。"
            : "任务《" + title + "》已创建，当前为 manual-start。等待显式 /continue 后再进入 harness。";
        LinkedHashMap<String, Object> metadata = lifecycleMetadata(task);
        metadata.put("action", "task_create");
        metadata.put("auto_start", autoStart);
        metadata.put("start_mode", autoStart ? "auto" : "manual");
        if (followup) {
            metadata.put("followup", true);
        }
        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            metadata.putAll(extraMetadata);
        }
        appendSessionMessage(task, "assistant", "task_receipt", content, metadata);
    }

    private void recordTaskStateProjection(Task previousTask, Task currentTask, String reason, Map<String, Object> extraMetadata) {
        if (!statusChanged(previousTask, currentTask)) {
            return;
        }
        recordTaskStateEvent(previousTask, currentTask, reason, extraMetadata);
        recordTaskStateMessage(previousTask, currentTask, reason, extraMetadata);
    }

    private void recordTaskStateMessage(Task previousTask, Task currentTask, String reason, Map<String, Object> extraMetadata) {
        if (currentTask == null) {
            return;
        }
        String previousState = previousTask != null ? previousTask.status() : null;
        String currentState = currentTask.status();
        LinkedHashMap<String, Object> metadata = lifecycleMetadata(currentTask);
        metadata.put("action", "task_state_update");
        metadata.put("old_state", previousState);
        metadata.put("new_state", currentState);
        metadata.put("previous_state", previousState);
        metadata.put("current_state", currentState);
        String previousControlNode = previousTask != null ? blankToNull(previousTask.controlNode()) : null;
        String currentControlNode = blankToNull(currentTask.controlNode());
        if (previousControlNode != null) {
            metadata.put("previous_control_node", previousControlNode);
        }
        if (currentControlNode != null) {
            metadata.put("current_control_node", currentControlNode);
        }
        if (reason != null && !reason.isBlank()) {
            metadata.put("reason", reason);
        }
        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            metadata.putAll(extraMetadata);
        }
        appendSessionMessage(
            currentTask,
            "system",
            "task_state",
            "任务《" + taskDisplayName(currentTask) + "》状态已从 " + firstNonBlank(previousState, "unknown")
                + " 更新为 " + firstNonBlank(currentState, "unknown")
                + appendReason(reason),
            metadata
        );
    }

    private void recordTaskActionMessage(Task task, String action, String reason, Map<String, Object> extraMetadata) {
        LinkedHashMap<String, Object> metadata = lifecycleMetadata(task);
        metadata.put("action", action);
        metadata.put("action_category", "task_control");
        if (reason != null && !reason.isBlank()) {
            metadata.put("reason", reason);
        }
        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            metadata.putAll(extraMetadata);
        }
        String content = switch (action) {
            case "handoff" -> "任务《" + taskDisplayName(task) + "》已执行 handoff，当前：" + describeTaskSnapshot(task)
                + appendWorkerShift(extraMetadata);
            default -> "任务《" + taskDisplayName(task) + "》已执行 " + action + "，当前：" + describeTaskSnapshot(task)
                + appendReason(reason);
        };
        appendSessionMessage(task, "system", "task_action", content, metadata);
    }

    private void recordAssistantProgressMessage(Task task, String trigger, ExperimentRunRecord experimentRun) {
        if (task == null || sessionMessageDao == null) {
            return;
        }
        try {
            TaskRuntimeContext runtimeContext = runtimeContextBuilder != null ? runtimeContextBuilder.build(task) : null;
            Decision executionJudgment = latestDecision(runtimeContext, "execution_judgment");
            Decision completionJudgment = latestDecision(runtimeContext, "completion_judgment");
            Artifact latestArtifact = latestArtifact(runtimeContext);

            String progressSummary = summarizeProgress(task, runtimeContext, executionJudgment, completionJudgment, latestArtifact);
            String nextStep = summarizeNextStep(task, runtimeContext, executionJudgment, completionJudgment, latestArtifact);
            boolean terminal = isTerminalStatus(task.status());
            if (progressSummary == null && nextStep == null && !terminal) {
                return;
            }

            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("trigger", trigger);
            metadata.put("task_status", task.status());
            metadata.put("control_node", task.controlNode());
            if (task.assignedWorker() != null && !task.assignedWorker().isBlank()) {
                metadata.put("assigned_worker", task.assignedWorker());
            }
            if (progressSummary != null) {
                metadata.put("summary_preview", progressSummary);
            }
            if (nextStep != null) {
                metadata.put("next_step", nextStep);
            }
            if (executionJudgment != null && executionJudgment.metadata() != null) {
                String action = stringValue(executionJudgment.metadata().get("action"));
                if (action != null) {
                    metadata.put("judgment_action", action);
                }
            }
            if (completionJudgment != null && completionJudgment.metadata() != null) {
                String completionStatus = stringValue(completionJudgment.metadata().get("status"));
                if (completionStatus != null) {
                    metadata.put("completion_status", completionStatus);
                }
            }
            if (latestArtifact != null) {
                if (latestArtifact.title() != null && !latestArtifact.title().isBlank()) {
                    metadata.put("artifact_title", latestArtifact.title());
                }
                if (latestArtifact.artifactType() != null && !latestArtifact.artifactType().isBlank()) {
                    metadata.put("artifact_type", latestArtifact.artifactType());
                }
            }
            appendExperimentProjectionMetadata(experimentRun, metadata);

            String content = terminal
                ? buildAssistantResultMessage(task, progressSummary, nextStep)
                : buildAssistantProgressMessage(task, progressSummary, nextStep);
            appendSessionMessage(task, "assistant", terminal ? "task_result" : "task_progress", content, metadata);
        } catch (Exception e) {
            log.warn("Failed to append assistant progress message for task {}", task.id(), e);
        }
    }

    private void appendSessionMessage(Task task, String role, String messageType, String content, Map<String, Object> metadata) {
        if (sessionMessageDao == null || task == null) {
            return;
        }
        try {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("source_surface", "task_service");
            payload.put("created_via", "task_service");
            if (metadata != null && !metadata.isEmpty()) {
                payload.putAll(metadata);
            }
            sessionMessageDao.insert(new SessionMessage(
                IdGenerator.newId("msg"),
                task.sessionId(),
                task.id(),
                role,
                messageType,
                content,
                Instant.now(),
                payload
            ));
            sessionDao.touch(task.sessionId(), Instant.now());
        } catch (Exception e) {
            log.warn("Failed to append session message for task {}", task.id(), e);
        }
    }

    private void recordTaskStateEvent(Task previousTask, Task currentTask, String reason, Map<String, Object> extraMetadata) {
        if (eventDao == null || currentTask == null) {
            return;
        }
        try {
            String previousState = previousTask != null ? previousTask.status() : null;
            String currentState = currentTask.status();
            LinkedHashMap<String, Object> payload = lifecycleMetadata(currentTask);
            payload.put("action", "task_state_update");
            payload.put("old_state", previousState);
            payload.put("new_state", currentState);
            payload.put("previous_state", previousState);
            payload.put("current_state", currentState);
            String previousControlNode = previousTask != null ? blankToNull(previousTask.controlNode()) : null;
            String currentControlNode = blankToNull(currentTask.controlNode());
            if (previousControlNode != null) {
                payload.put("previous_control_node", previousControlNode);
            }
            if (currentControlNode != null) {
                payload.put("current_control_node", currentControlNode);
            }
            if (reason != null && !reason.isBlank()) {
                payload.put("reason", reason);
            }
            if (extraMetadata != null && !extraMetadata.isEmpty()) {
                payload.putAll(extraMetadata);
            }
            eventDao.insert(new Event(
                IdGenerator.newId("evt"),
                currentTask.sessionId(),
                currentTask.id(),
                Instant.now(),
                "task_state_changed",
                "system",
                null,
                "Task state changed: " + firstNonBlank(previousState, "unknown") + " -> "
                    + firstNonBlank(currentState, "unknown") + appendReason(reason),
                payload
            ));
        } catch (Exception e) {
            log.warn("Failed to append task state event for task {}", currentTask.id(), e);
        }
    }

    private void recordControlActionEvent(Task task, String action, String reason, Map<String, Object> extraMetadata) {
        if (eventDao == null || task == null || action == null || action.isBlank()) {
            return;
        }
        try {
            LinkedHashMap<String, Object> payload = lifecycleMetadata(task);
            payload.put("action", action);
            payload.put("action_category", "task_control");
            if (reason != null && !reason.isBlank()) {
                payload.put("reason", reason);
            }
            if (extraMetadata != null && !extraMetadata.isEmpty()) {
                payload.putAll(extraMetadata);
            }
            eventDao.insert(new Event(
                IdGenerator.newId("evt"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "task_control_action",
                "task_service",
                null,
                "Task control action: " + action,
                payload
            ));
        } catch (Exception e) {
            log.warn("Failed to append control action event for task {}", task.id(), e);
        }
    }

    private String taskDisplayName(Task task) {
        return firstNonBlank(task.title(), task.id());
    }

    private void ensureSessionAcceptsTasks(Session session) {
        if (session != null && "closed".equalsIgnoreCase(blankToNull(session.status()))) {
            throw new IllegalArgumentException("session is closed");
        }
    }

    private void syncSessionCurrentTask(Session session, String taskId) {
        String sessionId = session != null ? session.id() : null;
        String status = session != null && blankToNull(session.status()) != null ? session.status() : "active";
        Instant closedAt = session != null ? session.closedAt() : null;
        sessionDao.updateState(sessionId, status, Instant.now(), closedAt, taskId, null);
    }

    private Task refreshExperimentRun(Task task) {
        ExperimentRunRecord record = refreshExperimentRunRecord(task);
        if (task == null || record == null) {
            return task;
        }
        return taskDao.findById(task.id()).orElse(task);
    }

    private ExperimentRunRecord refreshExperimentRunRecord(Task task) {
        if (task == null || experimentRunService == null) {
            return null;
        }
        return experimentRunService.refresh(task);
    }

    private Artifact latestArtifact(TaskRuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.recentArtifacts() == null || runtimeContext.recentArtifacts().isEmpty()) {
            return null;
        }
        return runtimeContext.recentArtifacts().get(0);
    }

    private String describeTaskSnapshot(Task task) {
        String snapshot = firstNonBlank(task.status(), "unknown") + " / " + firstNonBlank(task.controlNode(), "intake");
        if (task.assignedWorker() != null && !task.assignedWorker().isBlank()) {
            snapshot += " · worker " + task.assignedWorker();
        }
        return snapshot;
    }

    private String summarizeProgress(Task task, TaskRuntimeContext runtimeContext,
                                     Decision executionJudgment, Decision completionJudgment,
                                     Artifact latestArtifact) {
        return shorten(
            firstNonBlank(
                task.summary(),
                latestArtifact != null ? latestArtifact.summary() : null,
                runtimeContext != null && runtimeContext.activeContext() != null
                    ? runtimeContext.activeContext().continuitySummary()
                    : null,
                executionJudgment != null ? executionJudgment.rationale() : null,
                completionJudgment != null ? completionJudgment.rationale() : null,
                completionJudgment != null ? completionJudgment.summary() : null,
                executionJudgment != null ? executionJudgment.summary() : null
            ),
            260
        );
    }

    private String summarizeNextStep(Task task, TaskRuntimeContext runtimeContext,
                                     Decision executionJudgment, Decision completionJudgment,
                                     Artifact latestArtifact) {
        return shorten(
            firstNonBlank(
                task.nextStep(),
                executionJudgment != null && executionJudgment.metadata() != null
                    ? stringValue(executionJudgment.metadata().get("next_step"))
                    : null,
                completionJudgment != null && completionJudgment.metadata() != null
                    ? stringValue(completionJudgment.metadata().get("suggested_next_action"))
                    : null,
                latestArtifact != null && latestArtifact.metadata() != null
                    ? stringValue(latestArtifact.metadata().get("suggested_next_step"))
                    : null,
                runtimeContext != null && runtimeContext.activeContext() != null
                        && runtimeContext.activeContext().nextCandidates() != null
                        && !runtimeContext.activeContext().nextCandidates().isEmpty()
                    ? runtimeContext.activeContext().nextCandidates().get(0)
                    : null
            ),
            220
        );
    }

    private boolean isTerminalStatus(String status) {
        if (status == null) {
            return false;
        }
        return List.of("done", "failed").contains(status.toLowerCase());
    }

    private String buildAssistantProgressMessage(Task task, String progressSummary, String nextStep) {
        StringBuilder sb = new StringBuilder()
            .append("任务《").append(taskDisplayName(task)).append("》本轮进展：")
            .append(firstNonBlank(progressSummary, describeTaskSnapshot(task), "已完成一轮推进"));
        if (nextStep != null) {
            sb.append("。下一步：").append(nextStep);
        }
        sb.append("。当前：").append(describeTaskSnapshot(task)).append("。");
        return sb.toString();
    }

    private String buildAssistantResultMessage(Task task, String progressSummary, String nextStep) {
        StringBuilder sb = new StringBuilder()
            .append("任务《").append(taskDisplayName(task)).append("》已形成当前结果：")
            .append(firstNonBlank(progressSummary, describeTaskSnapshot(task), "任务已结束"));
        if (nextStep != null) {
            sb.append("。残留下一步：").append(nextStep);
        }
        sb.append("。当前：").append(describeTaskSnapshot(task)).append("。");
        return sb.toString();
    }

    private String shorten(String value, int maxLength) {
        String normalized = blankToNull(value);
        if (normalized == null || normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private String appendReason(String reason) {
        String normalized = blankToNull(reason);
        return normalized == null ? "。" : "。原因：" + normalized + "。";
    }

    private String appendWorkerShift(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "。";
        }
        String previousWorker = stringValue(metadata.get("previous_worker"));
        String targetWorker = stringValue(metadata.get("target_worker"));
        if (previousWorker == null && targetWorker == null) {
            return "。";
        }
        return "。worker: " + firstNonBlank(previousWorker, "unassigned") + " -> "
            + firstNonBlank(targetWorker, "unassigned") + "。";
    }

    private boolean statusChanged(Task previousTask, Task currentTask) {
        if (previousTask == null || currentTask == null) {
            return false;
        }
        return !Objects.equals(blankToNull(previousTask.status()), blankToNull(currentTask.status()));
    }

    private LinkedHashMap<String, Object> lifecycleMetadata(Task task) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (task == null) {
            return metadata;
        }
        metadata.put("task_status", task.status());
        metadata.put("control_node", task.controlNode());
        if (task.assignedWorker() != null && !task.assignedWorker().isBlank()) {
            metadata.put("assigned_worker", task.assignedWorker());
        }
        return metadata;
    }

    private void appendExperimentProjectionMetadata(ExperimentRunRecord experimentRun, Map<String, Object> target) {
        if (experimentRun == null || target == null) {
            return;
        }
        putIfNonBlank(target, "experiment_name", experimentRun.experimentName());
        putIfNonBlank(target, "task_case_key", experimentRun.taskCaseKey());
        putIfNonBlank(target, "task_length_bucket", experimentRun.taskLengthBucket());
        putIfNonBlank(target, "model_mode", experimentRun.modelMode());
        putIfNonBlank(target, "completion_status", experimentRun.completionStatus());
        putIfNonBlank(target, "acceptance_result", experimentRun.acceptanceResult());
        putIfNonBlank(target, "failure_reason", experimentRun.failureReason());
        putIfNonBlank(target, "evaluation_result", experimentRun.finalArtifactQualityNote());
        putIfPresent(target, "total_steps", experimentRun.totalSteps());
        putIfPresent(target, "total_cost", experimentRun.totalCost());
        putIfPresent(target, "handoff_count", experimentRun.handoffCount());
        putIfPresent(target, "resume_count", experimentRun.resumeCount());
        putIfPresent(target, "human_gate_count", experimentRun.humanGateCount());
        putIfPresent(target, "recovery_success", experimentRun.recoverySuccess());
        Map<String, Object> metadata = experimentRun.metadata();
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        copyMetadataKey(metadata, target, "selected_worker");
        copyMetadataKey(metadata, target, "selected_model_tier");
        copyMetadataKey(metadata, target, "why_selected");
        copyMetadataKey(metadata, target, "route_source");
        copyMetadataKey(metadata, target, "preferred_worker_hint");
        copyMetadataKey(metadata, target, "learning_hint_applied");
        copyMetadataKey(metadata, target, "fallback_reason");
        copyMetadataKey(metadata, target, "orchestration_stage");
        copyMetadataKey(metadata, target, "planner_worker");
        copyMetadataKey(metadata, target, "executor_worker");
        copyMetadataKey(metadata, target, "tool_execution_mode");
        copyMetadataKey(metadata, target, "tool_chain_step_count");
        copyMetadataKey(metadata, target, "tool_chain_termination_reason");
        copyMetadataKey(metadata, target, "tool_chain_trace_summary");
        copyMetadataKey(metadata, target, "tool_chain_tools");
    }

    private void copyMetadataKey(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || key.isBlank() || value == null) {
            return;
        }
        target.put(key, value);
    }

    private void putIfNonBlank(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    private LinkedHashMap<String, Object> mergeActionMetadata(Map<String, Object> extraMetadata) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            merged.putAll(extraMetadata);
        }
        return merged;
    }
}
