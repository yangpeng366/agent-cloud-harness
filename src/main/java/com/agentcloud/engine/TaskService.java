package com.agentcloud.engine;

import com.agentcloud.engine.memory.PacketBuilder;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.*;
import com.agentcloud.runtime.RuntimeCognitionSurfaceAssembler;
import com.agentcloud.runtime.RuntimeFactSetAssembler;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.runtime.context.MountedContextPromptBudgetSupport;
import com.agentcloud.runtime.model.RuntimeFactSet;
import com.agentcloud.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final int PROVIDER_RUN_FILE_READ_LIMIT_BYTES = 64 * 1024;
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
    private final AgentRunService agentRunService;
    private final TaskRecoveryJobDao recoveryJobDao;
    private final RuntimeFactSetAssembler runtimeFactSetAssembler;
    private final RuntimeCognitionSurfaceAssembler runtimeCognitionSurfaceAssembler;

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
        this(taskDao, sessionDao, eventDao, packetDao, router, packetBuilder, controlGraph, judgmentService,
            runtimeContextBuilder, consolidationService, learningMemoryService, toolInvocationDao,
            sessionMessageDao, experimentRunService, null);
    }

    public TaskService(TaskDao taskDao, SessionDao sessionDao, EventDao eventDao, ResumePacketDao packetDao,
                       WorkerRouter router, PacketBuilder packetBuilder, ControlNodeGraph controlGraph,
                       RuntimeJudgmentService judgmentService,
                       TaskRuntimeContextBuilder runtimeContextBuilder,
                       ConsolidationService consolidationService,
                       LearningMemoryService learningMemoryService,
                       ToolInvocationDao toolInvocationDao,
                       SessionMessageDao sessionMessageDao,
                       ExperimentRunService experimentRunService,
                       AgentRunService agentRunService) {
        this(taskDao, sessionDao, eventDao, packetDao, router, packetBuilder, controlGraph, judgmentService,
            runtimeContextBuilder, consolidationService, learningMemoryService, toolInvocationDao,
            sessionMessageDao, experimentRunService, agentRunService, null);
    }

    public TaskService(TaskDao taskDao, SessionDao sessionDao, EventDao eventDao, ResumePacketDao packetDao,
                       WorkerRouter router, PacketBuilder packetBuilder, ControlNodeGraph controlGraph,
                       RuntimeJudgmentService judgmentService,
                       TaskRuntimeContextBuilder runtimeContextBuilder,
                       ConsolidationService consolidationService,
                       LearningMemoryService learningMemoryService,
                       ToolInvocationDao toolInvocationDao,
                       SessionMessageDao sessionMessageDao,
                       ExperimentRunService experimentRunService,
                       AgentRunService agentRunService,
                       TaskRecoveryJobDao recoveryJobDao) {
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
        this.agentRunService = agentRunService;
        this.recoveryJobDao = recoveryJobDao;
        this.runtimeFactSetAssembler = new RuntimeFactSetAssembler(runtimeContextBuilder, toolInvocationDao, router);
        this.runtimeCognitionSurfaceAssembler = new RuntimeCognitionSurfaceAssembler();
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

        Map<String, Object> meta = req.metadata() != null ? new LinkedHashMap<>(req.metadata()) : new LinkedHashMap<>();
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
        ProviderTaskContractNormalizer.normalize(meta, req.intent(), req.goal(), req.title());

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

    public List<TaskRecoveryPlan> listRecoverableTasks(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return taskDao.listRecent(Math.max(safeLimit * 4, safeLimit)).stream()
            .filter(this::isRecoveryCandidate)
            .map(task -> buildRecoveryPlan(task, Map.of()))
            .limit(safeLimit)
            .toList();
    }

    public TaskRecoveryResult recoverTask(String taskId, Map<String, Object> request) {
        Task task = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        Map<String, Object> recoveryRequest = request != null ? request : Map.of();
        TaskRecoveryPlan plan = buildRecoveryPlan(task, recoveryRequest);
        if (!plan.recoverable()) {
            throw new IllegalArgumentException("task is not recoverable: " + plan.reason());
        }

        String requestedMode = firstNonBlank(metadataString(recoveryRequest, "mode"), "auto").toLowerCase();
        String action = resolveRecoveryAction(plan, requestedMode);
        LinkedHashMap<String, Object> actionMetadata = mergeActionMetadata(recoveryRequest);
        actionMetadata.put("manual_recovery_requested", true);
        actionMetadata.put("recovery_action", action);
        actionMetadata.put("recovery_execution_mode", "fresh_session");
        actionMetadata.put("recovery_reason", plan.reason());
        if (plan.failureClass() != null) {
            actionMetadata.put("failure_class", plan.failureClass());
        }
        if (plan.providerFailureClass() != null) {
            actionMetadata.put("provider_failure_class", plan.providerFailureClass());
        }

        if ("handoff".equals(action)) {
            String targetWorker = firstNonBlank(metadataString(recoveryRequest, "target_worker"), plan.targetWorker());
            if (targetWorker == null) {
                throw new IllegalArgumentException("target_worker is required for handoff recovery");
            }
            HandoffResult handoff = handoffTask(taskId, targetWorker, actionMetadata);
            return new TaskRecoveryResult(plan, null, handoff);
        }

        Task prepared = prepareFreshSessionRecovery(task, plan, recoveryRequest);
        taskDao.updateState(prepared);
        TaskControlResult control = "continue".equals(action)
            ? continueTask(taskId, actionMetadata)
            : resumeTask(taskId, actionMetadata);
        return new TaskRecoveryResult(plan, control, null);
    }

    public TaskRecoveryResult recoverTaskAsync(String taskId, Map<String, Object> request) {
        Task task = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        Map<String, Object> recoveryRequest = request != null ? new LinkedHashMap<>(request) : new LinkedHashMap<>();
        TaskRecoveryPlan plan = buildRecoveryPlan(task, recoveryRequest);
        if (!plan.recoverable()) {
            throw new IllegalArgumentException("task is not recoverable: " + plan.reason());
        }
        String requestId = IdGenerator.newId("recovery");
        String statusUrl = "/api/v1/tasks/" + taskId + "/live_flow";
        String requestedMode = firstNonBlank(metadataString(recoveryRequest, "mode"), "auto").toLowerCase();
        String action = resolveRecoveryAction(plan, requestedMode);
        insertRecoveryJob(task, plan, recoveryRequest, requestId, statusUrl, requestedMode, action);
        recoveryRequest.put("async_recovery", true);
        recoveryRequest.put("async_recovery_request_id", requestId);
        Thread.ofVirtual().name("agentcloud-recovery-", 0).start(() -> {
            Instant startedAt = Instant.now();
            updateRecoveryJob(requestId, "running", startedAt, null, null);
            try {
                recoverTask(taskId, recoveryRequest);
                updateRecoveryJob(requestId, "succeeded", startedAt, Instant.now(), null);
            } catch (Exception e) {
                String sanitizedError = sanitizeRecoveryJobError(e);
                updateRecoveryJob(requestId, "failed", startedAt, Instant.now(), sanitizedError);
                log.warn("Async recovery failed task={} requestId={} errorClass={} summary={}",
                    taskId, requestId, e.getClass().getSimpleName(), sanitizedError);
            }
        });
        return TaskRecoveryResult.accepted(plan, requestId, statusUrl);
    }

    public List<TaskRecoveryJob> listRecoveryJobs(String taskId, int limit) {
        taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return recoveryJobDao != null
            ? recoveryJobDao.listByTask(taskId, boundedLimit(limit))
            : List.of();
    }

    private void insertRecoveryJob(Task task,
                                   TaskRecoveryPlan plan,
                                   Map<String, Object> request,
                                   String requestId,
                                   String statusUrl,
                                   String requestedMode,
                                   String action) {
        if (recoveryJobDao == null) {
            return;
        }
        LinkedHashMap<String, Object> metadata = mergeActionMetadata(request);
        metadata.put("recovery_action", action);
        metadata.put("recovery_reason", plan.reason());
        metadata.put("failure_evidence_source", plan.failureEvidenceSource());
        metadata.put("failure_evidence", plan.failureEvidence());
        recoveryJobDao.insert(new TaskRecoveryJob(
            requestId,
            task.id(),
            task.sessionId(),
            "accepted",
            requestedMode,
            action,
            plan.targetWorker(),
            plan.recoveryExecutionMode(),
            plan.failureClass(),
            plan.providerFailureClass(),
            statusUrl,
            Instant.now(),
            null,
            null,
            null,
            metadata
        ));
    }

    private void updateRecoveryJob(String requestId,
                                   String status,
                                   Instant startedAt,
                                   Instant completedAt,
                                   String errorMessage) {
        if (recoveryJobDao == null) {
            return;
        }
        try {
            recoveryJobDao.updateStatus(requestId, status, startedAt, completedAt, truncate(errorMessage, 500));
        } catch (Exception e) {
            log.warn("Failed to update recovery job {} to {}: {}", requestId, status, e.toString());
        }
    }

    private String sanitizeRecoveryJobError(Exception error) {
        if (error == null) {
            return null;
        }
        String message = firstNonBlank(error.getMessage(), error.getClass().getSimpleName());
        if (message == null) {
            return null;
        }
        String compact = message.replaceAll("\\s+", " ").trim()
            .replaceAll("(?i)(api[_ -]?key|authorization|password|secret|token)\\s*[:=]\\s*\\S+", "$1=<redacted>");
        return truncate(compact, 220);
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
        return enrichRouteDiagnostics(t, router.selectWorker(t));
    }

    public TaskRuntimeContext getRuntimeContext(String taskId) {
        Task t = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return runtimeContextBuilder.build(t);
    }

    public JudgmentTraceView getJudgmentTrace(String taskId) {
        Task task = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        RuntimeFactSet facts = runtimeFactSetAssembler.assemble(task, 20);
        return buildJudgmentTraceView(task, facts);
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
        RuntimeFactSet facts = runtimeFactSetAssembler.assemble(task, boundedLimit);
        ResumePacket latestPacket = facts.latestPacket();
        var routePreview = enrichRouteDiagnostics(task, facts.routePreview());
        TaskRuntimeContext runtimeContext = facts.runtimeContext();
        JudgmentTraceView judgmentTrace = buildJudgmentTraceView(task, facts);
        List<Checkpoint> checkpoints = consolidationService.listByTask(taskId, boundedLimit);
        List<ResumePacket> resumePackets = packetDao != null
            ? nullToEmpty(packetDao.listByTask(task.sessionId(), task.id(), boundedLimit))
            : List.of();
        List<LearningMemory> learningMemories = learningMemoryService.listByTask(taskId, boundedLimit);
        List<ToolInvocationRecord> toolInvocations = facts.toolInvocations();
        RuntimeFactSet.ExecutionBoundary executionBoundary = facts.executionBoundary();
        List<SessionMessage> relatedMessages = sessionMessageDao != null
            ? mergeLiveFlowRelatedMessages(
                sessionMessageDao.listBySessionAndTask(task.sessionId(), task.id(), boundedLimit),
                sessionMessageDao.listBySession(task.sessionId(), boundedLimit),
                boundedLimit
            )
            : List.of();
        ExperimentRunRecord experimentRun = experimentRunService != null ? experimentRunService.refresh(task) : null;
        ProviderSelectionView providerSelection = agentRunService != null
            ? agentRunService.providerSelection(task, routePreview)
            : null;
        AgentRunRecord agentRun = agentRunService != null ? agentRunService.latestByTask(taskId) : null;
        List<AgentRunEventView> agentRunEvents = agentRunService != null && agentRun != null
            ? nullToEmpty(agentRunService.listEvents(agentRun.runId(), boundedLimit))
            : List.of();
        List<AgentRunArtifactView> agentArtifacts = agentRunService != null && agentRun != null
            ? nullToEmpty(agentRunService.listArtifacts(agentRun.runId(), boundedLimit))
            : List.of();
        return new TaskLiveFlowView(
            task,
            latestPacket,
            routePreview,
            runtimeContext,
            judgmentTrace,
            facts,
            buildRuntimeCognitionSurface(facts),
            buildRuntimeCognitionTimeline(task, facts, checkpoints, resumePackets),
            checkpoints,
            learningMemories,
            toolInvocations,
            executionBoundary,
            relatedMessages,
            experimentRun,
            providerSelection,
            agentRun,
            agentRunEvents,
            agentArtifacts
        );
    }

    public ProviderRunFileView getProviderRunFile(String taskId, String kind) {
        Task task = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        String normalizedKind = normalizeProviderRunFileKind(kind);
        RuntimeFactSet facts = runtimeFactSetAssembler.assemble(task, 5);
        Map<String, Object> metadata = facts.executionBoundary() != null && facts.executionBoundary().metadata() != null
            ? facts.executionBoundary().metadata()
            : Map.of();
        String pathText = metadataString(metadata, providerRunFileMetadataKey(normalizedKind));
        if (pathText == null || pathText.isBlank()) {
            throw new IllegalArgumentException("provider run file not found");
        }
        Path path = Path.of(pathText).toAbsolutePath().normalize();
        Path runDir = providerRunDir(metadata);
        if (runDir != null && !path.startsWith(runDir)) {
            throw new IllegalArgumentException("provider run file outside run directory");
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("provider run file not found");
        }
        try {
            long size = Files.size(path);
            byte[] bytes;
            boolean truncated = size > PROVIDER_RUN_FILE_READ_LIMIT_BYTES;
            try (var input = Files.newInputStream(path)) {
                bytes = input.readNBytes(PROVIDER_RUN_FILE_READ_LIMIT_BYTES);
            }
            return new ProviderRunFileView(
                task.id(),
                normalizedKind,
                path.toString(),
                size,
                PROVIDER_RUN_FILE_READ_LIMIT_BYTES,
                truncated,
                new String(bytes, StandardCharsets.UTF_8)
            );
        } catch (java.io.IOException e) {
            throw new IllegalStateException("provider run file unreadable");
        }
    }

    private String normalizeProviderRunFileKind(String kind) {
        String normalized = blankToNull(kind);
        if (normalized == null) {
            return "last_message";
        }
        normalized = normalized.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "last", "last_message", "message" -> "last_message";
            case "events", "event_log", "events_jsonl" -> "events";
            case "stdout", "output" -> "stdout";
            case "metadata", "meta" -> "metadata";
            case "prompt" -> "prompt";
            default -> throw new IllegalArgumentException("unsupported provider run file kind");
        };
    }

    private String providerRunFileMetadataKey(String kind) {
        return switch (kind) {
            case "last_message" -> "provider_last_message_path";
            case "events" -> "provider_event_log_path";
            case "stdout" -> "provider_stdout_path";
            case "metadata" -> "provider_run_metadata_path";
            case "prompt" -> "provider_prompt_path";
            default -> throw new IllegalArgumentException("unsupported provider run file kind");
        };
    }

    private Path providerRunDir(Map<String, Object> metadata) {
        String runDir = metadataString(metadata, "provider_run_dir");
        if (runDir == null || runDir.isBlank()) {
            return null;
        }
        return Path.of(runDir).toAbsolutePath().normalize();
    }

    private WorkerRouter.RouteResult enrichRouteDiagnostics(Task task, WorkerRouter.RouteResult route) {
        if (task == null || route == null) {
            return route;
        }
        WorkerRouter.RouteDiagnostic currentPinnedRoute = buildCurrentPinnedRoute(task, route);
        WorkerRouter.RouteDiagnostic recoveryUnpinnedRecommendation = buildRecoveryUnpinnedRecommendation(task, route);
        return new WorkerRouter.RouteResult(
            route.taskId(),
            route.selectedWorker(),
            route.fallbackWorkers(),
            route.routeReason(),
            route.routeSource(),
            route.taskType(),
            route.preferredWorkerHint(),
            route.learningHintApplied(),
            route.candidateWorkers(),
            route.selectedWorkerType(),
            route.selectedModelTier(),
            route.selectedExecutionRole(),
            route.selectionScope(),
            route.whySelected(),
            route.fallbackReason(),
            recoveryProviderDeprioritized(recoveryUnpinnedRecommendation),
            recoveryDeprioritizedProvider(recoveryUnpinnedRecommendation),
            recoveryDeprioritizationReason(recoveryUnpinnedRecommendation),
            recoveryExecutionMode(task),
            currentPinnedRoute,
            recoveryUnpinnedRecommendation,
            route.dispatchSkippedWorkers()
        );
    }

    private WorkerRouter.RouteDiagnostic buildCurrentPinnedRoute(Task task, WorkerRouter.RouteResult route) {
        if (task == null || route == null || blankToNull(task.assignedWorker()) == null) {
            return null;
        }
        return buildRouteDiagnostic(task, route);
    }

    private WorkerRouter.RouteDiagnostic buildRecoveryUnpinnedRecommendation(Task task, WorkerRouter.RouteResult currentRoute) {
        if (task == null || router == null) {
            return null;
        }
        Task unpinnedTask = task.withAssignedWorker(null).withMetadata(withMetadataMapEntries(
            task.metadata(),
            "assigned_worker", null,
            "target_worker", null,
            "preassigned_selection_reason", null
        ));
        WorkerRouter.RouteResult unpinned = router.selectWorker(unpinnedTask);
        if (unpinned == null) {
            return null;
        }
        if (currentRoute != null
            && java.util.Objects.equals(currentRoute.selectedWorker(), unpinned.selectedWorker())
            && java.util.Objects.equals(currentRoute.routeSource(), unpinned.routeSource())
            && blankToNull(task.assignedWorker()) == null) {
            return null;
        }
        return buildRecoveryRouteDiagnostic(task, currentRoute, unpinned);
    }

    private WorkerRouter.RouteDiagnostic buildRouteDiagnostic(Task task, WorkerRouter.RouteResult route) {
        if (route == null) {
            return null;
        }
        return new WorkerRouter.RouteDiagnostic(
            route.selectedWorker(),
            route.routeSource(),
            route.taskType(),
            route.selectedWorkerType(),
            route.selectedModelTier(),
            route.selectedExecutionRole(),
            route.selectionScope(),
            route.whySelected(),
            route.fallbackReason(),
            route.preferredWorkerHint(),
            route.learningHintApplied(),
            recoveryExecutionMode(task),
            null,
            null,
            null,
            route.candidateWorkers(),
            route.fallbackWorkers()
        );
    }

    private Boolean recoveryProviderDeprioritized(WorkerRouter.RouteDiagnostic diagnostic) {
        return diagnostic != null && Boolean.TRUE.equals(diagnostic.providerDeprioritized()) ? Boolean.TRUE : null;
    }

    private String recoveryDeprioritizedProvider(WorkerRouter.RouteDiagnostic diagnostic) {
        return diagnostic != null ? diagnostic.deprioritizedProvider() : null;
    }

    private String recoveryDeprioritizationReason(WorkerRouter.RouteDiagnostic diagnostic) {
        return diagnostic != null ? diagnostic.deprioritizationReason() : null;
    }

    private WorkerRouter.RouteDiagnostic buildRecoveryRouteDiagnostic(Task task,
                                                                     WorkerRouter.RouteResult currentRoute,
                                                                     WorkerRouter.RouteResult recoveryRoute) {
        if (recoveryRoute == null) {
            return null;
        }
        String currentWorker = firstNonBlank(
            currentRoute != null ? currentRoute.selectedWorker() : null,
            task != null ? task.assignedWorker() : null
        );
        String currentProvider = resolveProviderId(currentWorker, currentRoute != null ? currentRoute.selectedWorkerType() : null);
        boolean providerDeprioritized = currentProvider != null
            && agentRunService != null
            && agentRunService.shouldDeprioritizeProvider(currentProvider);
        return new WorkerRouter.RouteDiagnostic(
            recoveryRoute.selectedWorker(),
            recoveryRoute.routeSource(),
            recoveryRoute.taskType(),
            recoveryRoute.selectedWorkerType(),
            recoveryRoute.selectedModelTier(),
            recoveryRoute.selectedExecutionRole(),
            recoveryRoute.selectionScope(),
            recoveryRoute.whySelected(),
            recoveryRoute.fallbackReason(),
            recoveryRoute.preferredWorkerHint(),
            recoveryRoute.learningHintApplied(),
            recoveryExecutionMode(task),
            providerDeprioritized ? Boolean.TRUE : null,
            providerDeprioritized ? currentProvider : null,
            providerDeprioritized ? "recent transient provider failures" : null,
            recoveryRoute.candidateWorkers(),
            recoveryRoute.fallbackWorkers()
        );
    }

    private String recoveryExecutionMode(Task task) {
        return firstNonBlank(task != null ? metadataString(task.metadata(), "recovery_execution_mode") : null);
    }

    private Map<String, Object> withMetadataMapEntries(Map<String, Object> source, Object... entries) {
        Map<String, Object> metadata = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        if (entries == null || entries.length == 0) {
            return metadata;
        }
        for (int i = 0; i + 1 < entries.length; i += 2) {
            Object key = entries[i];
            if (key == null) {
                continue;
            }
            Object value = entries[i + 1];
            if (value == null) {
                metadata.remove(key.toString());
            } else {
                metadata.put(key.toString(), value);
            }
        }
        return metadata;
    }

    private JudgmentTraceView buildJudgmentTraceView(Task task, RuntimeFactSet facts) {
        RuntimeFactSet runtimeFacts = facts != null ? facts : RuntimeFactSet.empty(task);
        return new JudgmentTraceView(
            task.id(),
            task.status(),
            task.controlNode(),
            task.assignedWorker(),
            runtimeFacts.latestOutput(),
            runtimeFacts.recommendedAction(),
            runtimeFacts.recommendedNextStep(),
            runtimeFacts.executionJudgment(),
            runtimeFacts.completionJudgment(),
            runtimeFacts.executionBoundary(),
            runtimeFacts.runtimeContext(),
            runtimeFacts,
            buildRuntimeCognitionSurface(runtimeFacts)
        );
    }

    private RuntimeCognitionSurfaceView buildRuntimeCognitionSurface(RuntimeFactSet facts) {
        return runtimeCognitionSurfaceAssembler.assemble(facts);
    }

    private List<RuntimeCognitionTimelineEntryView> buildRuntimeCognitionTimeline(Task task,
                                                                                  RuntimeFactSet facts,
                                                                                  List<Checkpoint> checkpoints,
                                                                                  List<ResumePacket> resumePackets) {
        RuntimeFactSet runtimeFacts = facts != null ? facts : RuntimeFactSet.empty(null);
        RuntimeCognitionSurfaceView surface = buildRuntimeCognitionSurface(runtimeFacts);
        List<RuntimeCognitionTimelineEntryView> entries = new ArrayList<>();

        entries.addAll(buildContinuityTimelineEntries(task, checkpoints, resumePackets));

        RuntimeCognitionTimelineEntryView routeEntry = buildRouteTimelineEntry(runtimeFacts, surface);
        if (routeEntry != null) {
            entries.add(routeEntry);
        }
        RuntimeCognitionTimelineEntryView executionEntry = buildExecutionTimelineEntry(runtimeFacts, surface);
        if (executionEntry != null) {
            entries.add(executionEntry);
        }
        RuntimeCognitionTimelineEntryView executionJudgmentEntry = buildJudgmentTimelineEntry(
            "execution_judgment",
            "Execution Judgment",
            runtimeFacts.executionJudgment(),
            surface != null ? surface.executionJudgment() : null,
            surface != null ? surface.execution() : null
        );
        if (executionJudgmentEntry != null) {
            entries.add(executionJudgmentEntry);
        }
        RuntimeCognitionTimelineEntryView completionJudgmentEntry = buildJudgmentTimelineEntry(
            "completion_judgment",
            "Completion Judgment",
            runtimeFacts.completionJudgment(),
            surface != null ? surface.completionJudgment() : null,
            surface != null ? surface.execution() : null
        );
        if (completionJudgmentEntry != null) {
            entries.add(completionJudgmentEntry);
        }

        entries.sort(Comparator.comparing(
            RuntimeCognitionTimelineEntryView::occurredAt,
            Comparator.nullsLast(String::compareTo)
        ));
        return entries;
    }

    private RuntimeCognitionTimelineEntryView buildRouteTimelineEntry(RuntimeFactSet facts,
                                                                      RuntimeCognitionSurfaceView surface) {
        if (facts == null || surface == null || surface.route() == null) {
            return null;
        }
        RuntimeCognitionSurfaceView.RouteSurface route = surface.route();
        if (blankToNull(route.selectedWorker()) == null
            && blankToNull(route.routeSource()) == null
            && (route.candidateWorkers() == null || route.candidateWorkers().isEmpty())) {
            return null;
        }
        String occurredAt = resolveRouteOccurredAt(facts);
        String summary = firstNonBlank(
            summarizeRouteTimeline(route),
            facts.routePreview() != null ? blankToNull(facts.routePreview().whySelected()) : null,
            blankToNull(route.routeSource())
        );
        return new RuntimeCognitionTimelineEntryView(
            "route",
            "Route Selected",
            occurredAt,
            blankToNull(route.selectedWorker()),
            null,
            null,
            null,
            null,
            null,
            blankToNull(route.routeSource()),
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            route.candidateWorkers() == null ? List.of() : route.candidateWorkers(),
            List.of(),
            List.of(),
            List.of(),
            summary
        );
    }

    private RuntimeCognitionTimelineEntryView buildExecutionTimelineEntry(RuntimeFactSet facts,
                                                                          RuntimeCognitionSurfaceView surface) {
        if (facts == null || surface == null || surface.execution() == null) {
            return null;
        }
        RuntimeCognitionSurfaceView.ExecutionSurface execution = surface.execution();
        if (blankToNull(execution.workerId()) == null
            && blankToNull(execution.executionStatus()) == null
            && blankToNull(execution.executionId()) == null) {
            return null;
        }
        String summary = firstNonBlank(
            summarizeExecutionTimeline(execution),
            blankToNull(execution.traceSummary())
        );
        return new RuntimeCognitionTimelineEntryView(
            "execution",
            "Execution Boundary",
            resolveExecutionOccurredAt(facts.executionBoundary()),
            blankToNull(execution.workerId()),
            null,
            null,
            null,
            null,
            blankToNull(execution.promptMode()),
            null,
            blankToNull(execution.executionStatus()),
            execution.toolInvocationCount(),
            null,
            null,
            null,
            null,
            List.of(),
            null,
            blankToNull(execution.proofSummary()),
            execution.mountedContextRendered(),
            execution.mountedRenderUsed(),
            execution.mountedContextInjected(),
            execution.mountedContextPanelCount(),
            execution.mountedContextRenderedObjectCount(),
            execution.mountedContextHiddenObjectCount(),
            execution.mountedContextRenderedSelectionTraceCount(),
            execution.mountedContextHiddenSelectionTraceCount(),
            execution.mountedContextBudgetTruncated(),
            null,
            List.of(),
            execution.toolInvocationIds() == null ? List.of() : execution.toolInvocationIds(),
            execution.evidenceRefs() == null ? List.of() : execution.evidenceRefs(),
            execution.unfinishedItems() == null ? List.of() : execution.unfinishedItems(),
            summary
        );
    }

    private RuntimeCognitionTimelineEntryView buildJudgmentTimelineEntry(String stage,
                                                                         String label,
                                                                         Decision decision,
                                                                         RuntimeCognitionSurfaceView.JudgmentSurface surface,
                                                                         RuntimeCognitionSurfaceView.ExecutionSurface executionSurface) {
        if (decision == null || surface == null) {
            return null;
        }
        String executionPromptMode = executionSurface != null ? blankToNull(executionSurface.promptMode()) : null;
        String judgmentPromptMode = blankToNull(surface.promptMode());
        String summary = firstNonBlank(
            summarizeJudgmentTimeline(decision, surface),
            blankToNull(decision.summary()),
            blankToNull(decision.rationale())
        );
        return new RuntimeCognitionTimelineEntryView(
            stage,
            label,
            decision.createdAt() != null ? decision.createdAt().toString() : null,
            null,
            null,
            null,
            null,
            null,
            judgmentPromptMode,
            null,
            null,
            null,
            surface.needsContextReopen(),
            surface.evidenceGapDetected(),
            surface.needsArchiveRetrieval(),
            surface.needsExternalFactRefresh(),
            surface.reopenCandidatePaths() == null ? List.of() : surface.reopenCandidatePaths(),
            blankToNull(surface.reopenSummary()),
            blankToNull(surface.proofSummary()),
            surface.mountedContextRendered(),
            surface.mountedRenderUsed(),
            surface.mountedContextInjected(),
            surface.mountedContextPanelCount(),
            surface.mountedContextRenderedObjectCount(),
            surface.mountedContextHiddenObjectCount(),
            surface.mountedContextRenderedSelectionTraceCount(),
            surface.mountedContextHiddenSelectionTraceCount(),
            surface.mountedContextBudgetTruncated(),
            alignmentFlag(executionPromptMode, judgmentPromptMode),
            surface.candidateWorkers() == null ? List.of() : surface.candidateWorkers(),
            surface.toolInvocationIds() == null ? List.of() : surface.toolInvocationIds(),
            surface.evidenceRefs() == null ? List.of() : surface.evidenceRefs(),
            surface.unfinishedItems() == null ? List.of() : surface.unfinishedItems(),
            summary
        );
    }

    private List<RuntimeCognitionTimelineEntryView> buildContinuityTimelineEntries(Task task,
                                                                                   List<Checkpoint> checkpoints,
                                                                                   List<ResumePacket> resumePackets) {
        List<RuntimeCognitionTimelineEntryView> entries = new ArrayList<>();
        if (task == null) {
            return entries;
        }
        List<Event> events = eventDao == null
            ? List.of()
            : nullToEmpty(eventDao.listBySessionAndTask(task.sessionId(), task.id(), 20));
        for (Event event : events) {
            RuntimeCognitionTimelineEntryView entry = buildControlActionTimelineEntry(event);
            if (entry != null) {
                entries.add(entry);
            }
        }
        for (Checkpoint checkpoint : nullToEmpty(checkpoints)) {
            RuntimeCognitionTimelineEntryView entry = buildCheckpointTimelineEntry(checkpoint);
            if (entry != null) {
                entries.add(entry);
            }
        }
        for (ResumePacket resumePacket : nullToEmpty(resumePackets)) {
            RuntimeCognitionTimelineEntryView entry = buildResumePacketTimelineEntry(resumePacket);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private RuntimeCognitionTimelineEntryView buildControlActionTimelineEntry(Event event) {
        if (event == null || !"task_control_action".equals(event.eventType())) {
            return null;
        }
        Map<String, Object> payload = event.payload() == null ? Map.of() : event.payload();
        String action = metadataString(payload, "action");
        if (action == null) {
            return null;
        }
        Map<String, Object> runtimeCognitionSurface = metadataMap(payload, "runtime_cognition_surface");
        Map<String, Object> executionSurface = metadataMap(runtimeCognitionSurface, "execution");
        Map<String, Object> routeSurface = metadataMap(runtimeCognitionSurface, "route");
        Map<String, Object> judgmentSurface = metadataMap(runtimeCognitionSurface, "execution_judgment");
        String workerId = firstNonBlank(
            metadataString(executionSurface, "worker_id"),
            metadataString(routeSurface, "selected_worker"),
            metadataString(payload, "assigned_worker"),
            metadataString(payload, "current_worker"),
            metadataString(payload, "previous_worker")
        );
        String promptMode = firstNonBlank(
            metadataString(executionSurface, "prompt_mode"),
            metadataString(payload, "prompt_mode"),
            metadataString(payload, "mounted_context_mode"),
            metadataString(payload, "prompt_rendering_mode")
        );
        String targetWorker = metadataString(payload, "target_worker");
        String reason = metadataString(payload, "reason");
        List<String> reopenCandidatePaths = firstNonEmptyList(
            metadataStringList(judgmentSurface, "reopen_candidate_paths"),
            metadataStringList(payload, "reopen_candidate_paths")
        );
        Boolean evidenceGapDetected = metadataBoolean(judgmentSurface, "evidence_gap_detected", payload);
        Boolean needsArchiveRetrieval = metadataBoolean(judgmentSurface, "needs_archive_retrieval", payload);
        Boolean needsExternalFactRefresh = metadataBoolean(judgmentSurface, "needs_external_fact_refresh", payload);
        String reopenSummary = firstNonBlank(
            metadataString(judgmentSurface, "reopen_summary"),
            reopenSummary(reopenCandidatePaths)
        );
        String summary = firstNonBlank(
            summarizeControlActionTimeline(
                action,
                workerId,
                targetWorker,
                promptMode,
                reason,
                reopenSummary,
                evidenceGapDetected,
                needsArchiveRetrieval,
                needsExternalFactRefresh
            ),
            blankToNull(event.summary())
        );
        return new RuntimeCognitionTimelineEntryView(
            "continuity_action",
            continuityActionLabel(action),
            event.createdAt() != null ? event.createdAt().toString() : null,
            workerId,
            action,
            null,
            reason,
            targetWorker,
            promptMode,
            metadataString(routeSurface, "route_source"),
            metadataString(executionSurface, "execution_status"),
            firstNonNullInt(
                metadataInteger(executionSurface, "tool_invocation_count"),
                sizeOf(metadataStringList(executionSurface, "tool_invocation_ids")),
                sizeOf(metadataStringList(payload, "tool_invocation_ids"))
            ),
            metadataBoolean(judgmentSurface, "needs_context_reopen", payload),
            evidenceGapDetected,
            needsArchiveRetrieval,
            needsExternalFactRefresh,
            reopenCandidatePaths,
            reopenSummary,
            firstNonBlank(
                metadataString(executionSurface, "proof_summary"),
                proofSummary(
                    metadataStringList(executionSurface, "tool_invocation_ids"),
                    metadataStringList(executionSurface, "evidence_refs")
                ),
                proofSummary(metadataStringList(payload, "tool_invocation_ids"), metadataStringList(payload, "evidence_refs"))
            ),
            metadataBoolean(executionSurface, "mounted_context_rendered", payload),
            metadataBoolean(executionSurface, "mounted_render_used", payload),
            metadataBoolean(executionSurface, "mounted_context_injected", payload),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_panel_count"),
                metadataInteger(payload, "mounted_context_panel_count")
            ),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_rendered_object_count"),
                metadataInteger(payload, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_hidden_object_count"),
                metadataInteger(payload, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_rendered_selection_trace_count"),
                metadataInteger(payload, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_hidden_selection_trace_count"),
                metadataInteger(payload, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT)
            ),
            metadataBoolean(executionSurface, "mounted_context_budget_truncated", payload),
            null,
            firstNonEmptyList(
                metadataStringList(routeSurface, "candidate_workers"),
                metadataStringList(payload, "candidate_workers")
            ),
            firstNonEmptyList(
                metadataStringList(executionSurface, "tool_invocation_ids"),
                metadataStringList(payload, "tool_invocation_ids")
            ),
            firstNonEmptyList(
                metadataStringList(executionSurface, "evidence_refs"),
                metadataStringList(payload, "evidence_refs")
            ),
            firstNonEmptyList(
                metadataStringList(executionSurface, "unfinished_items"),
                metadataStringList(payload, "unfinished_items")
            ),
            summary
        );
    }

    private RuntimeCognitionTimelineEntryView buildCheckpointTimelineEntry(Checkpoint checkpoint) {
        if (checkpoint == null) {
            return null;
        }
        String checkpointType = blankToNull(checkpoint.checkpointType());
        if (checkpointType == null) {
            return null;
        }
        Map<String, Object> refinedPacket = checkpoint.refinedPacket() == null ? Map.of() : checkpoint.refinedPacket();
        Map<String, Object> metadata = checkpoint.metadata() == null ? Map.of() : checkpoint.metadata();
        Map<String, Object> runtimeCognitionSurface = metadataMap(refinedPacket, "runtime_cognition_surface");
        Map<String, Object> executionSurface = metadataMap(runtimeCognitionSurface, "execution");
        Map<String, Object> routeSurface = metadataMap(runtimeCognitionSurface, "route");
        Map<String, Object> judgmentSurface = metadataMap(runtimeCognitionSurface, "execution_judgment");
        String workerId = firstNonBlank(
            metadataString(executionSurface, "worker_id"),
            metadataString(routeSurface, "selected_worker"),
            metadataString(refinedPacket, "assigned_worker"),
            metadataString(metadata, "assigned_worker")
        );
        String promptMode = firstNonBlank(
            metadataString(executionSurface, "prompt_mode"),
            metadataString(refinedPacket, "prompt_mode"),
            metadataString(refinedPacket, "mounted_context_mode"),
            metadataString(refinedPacket, "prompt_rendering_mode")
        );
        List<String> reopenCandidatePaths = firstNonEmptyList(
            metadataStringList(judgmentSurface, "reopen_candidate_paths"),
            metadataStringList(refinedPacket, "reopen_candidate_paths")
        );
        Boolean evidenceGapDetected = metadataBoolean(judgmentSurface, "evidence_gap_detected", refinedPacket);
        Boolean needsArchiveRetrieval = metadataBoolean(judgmentSurface, "needs_archive_retrieval", refinedPacket);
        Boolean needsExternalFactRefresh = metadataBoolean(judgmentSurface, "needs_external_fact_refresh", refinedPacket);
        String reopenSummary = firstNonBlank(
            metadataString(judgmentSurface, "reopen_summary"),
            reopenSummary(reopenCandidatePaths)
        );
        String summary = firstNonBlank(
            summarizeCheckpointTimeline(
                checkpointType,
                workerId,
                promptMode,
                checkpoint.consolidationSummary(),
                reopenSummary,
                evidenceGapDetected,
                needsArchiveRetrieval,
                needsExternalFactRefresh
            ),
            blankToNull(checkpoint.consolidationSummary())
        );
        return new RuntimeCognitionTimelineEntryView(
            "checkpoint",
            checkpointLabel(checkpointType),
            checkpoint.createdAt() != null ? checkpoint.createdAt().toString() : null,
            workerId,
            null,
            checkpointType,
            null,
            null,
            promptMode,
            metadataString(routeSurface, "route_source"),
            metadataString(executionSurface, "execution_status"),
            firstNonNullInt(
                metadataInteger(executionSurface, "tool_invocation_count"),
                sizeOf(metadataStringList(executionSurface, "tool_invocation_ids"))
            ),
            metadataBoolean(judgmentSurface, "needs_context_reopen", refinedPacket),
            evidenceGapDetected,
            needsArchiveRetrieval,
            needsExternalFactRefresh,
            reopenCandidatePaths,
            reopenSummary,
            firstNonBlank(
                metadataString(executionSurface, "proof_summary"),
                proofSummary(
                    metadataStringList(executionSurface, "tool_invocation_ids"),
                    metadataStringList(executionSurface, "evidence_refs")
                ),
                proofSummary(
                    metadataStringList(refinedPacket, "tool_invocation_ids"),
                    metadataStringList(refinedPacket, "evidence_refs")
                )
            ),
            metadataBoolean(executionSurface, "mounted_context_rendered", refinedPacket),
            metadataBoolean(executionSurface, "mounted_render_used", refinedPacket),
            metadataBoolean(executionSurface, "mounted_context_injected", refinedPacket),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_panel_count"),
                metadataInteger(refinedPacket, "mounted_context_panel_count")
            ),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_rendered_object_count"),
                metadataInteger(refinedPacket, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_hidden_object_count"),
                metadataInteger(refinedPacket, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_rendered_selection_trace_count"),
                metadataInteger(refinedPacket, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_hidden_selection_trace_count"),
                metadataInteger(refinedPacket, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT)
            ),
            metadataBoolean(executionSurface, "mounted_context_budget_truncated", refinedPacket),
            null,
            firstNonEmptyList(
                metadataStringList(routeSurface, "candidate_workers"),
                metadataStringList(refinedPacket, "candidate_workers")
            ),
            firstNonEmptyList(
                metadataStringList(executionSurface, "tool_invocation_ids"),
                metadataStringList(refinedPacket, "tool_invocation_ids")
            ),
            firstNonEmptyList(
                metadataStringList(executionSurface, "evidence_refs"),
                metadataStringList(refinedPacket, "evidence_refs")
            ),
            firstNonEmptyList(
                metadataStringList(executionSurface, "unfinished_items"),
                metadataStringList(refinedPacket, "open_questions")
            ),
            summary
        );
    }

    private RuntimeCognitionTimelineEntryView buildResumePacketTimelineEntry(ResumePacket resumePacket) {
        if (resumePacket == null) {
            return null;
        }
        Map<String, Object> payload = resumePacket.payload() == null ? Map.of() : resumePacket.payload();
        Map<String, Object> runtimeCognitionSurface = metadataMap(payload, "runtime_cognition_surface");
        Map<String, Object> executionSurface = metadataMap(runtimeCognitionSurface, "execution");
        Map<String, Object> routeSurface = metadataMap(runtimeCognitionSurface, "route");
        Map<String, Object> judgmentSurface = metadataMap(runtimeCognitionSurface, "execution_judgment");
        String workerId = firstNonBlank(
            metadataString(executionSurface, "worker_id"),
            metadataString(routeSurface, "selected_worker"),
            blankToNull(resumePacket.assignedWorker()),
            metadataString(payload, "assigned_worker")
        );
        String promptMode = firstNonBlank(
            metadataString(executionSurface, "prompt_mode"),
            metadataString(payload, "prompt_mode"),
            metadataString(payload, "mounted_context_mode"),
            metadataString(payload, "prompt_rendering_mode")
        );
        String reason = firstNonBlank(
            metadataString(payload, "resume_hint"),
            blankToNull(resumePacket.nextStep())
        );
        List<String> reopenCandidatePaths = firstNonEmptyList(
            metadataStringList(judgmentSurface, "reopen_candidate_paths"),
            metadataStringList(payload, "reopen_candidate_paths")
        );
        Boolean evidenceGapDetected = metadataBoolean(judgmentSurface, "evidence_gap_detected", payload);
        Boolean needsArchiveRetrieval = metadataBoolean(judgmentSurface, "needs_archive_retrieval", payload);
        Boolean needsExternalFactRefresh = metadataBoolean(judgmentSurface, "needs_external_fact_refresh", payload);
        String reopenSummary = firstNonBlank(
            metadataString(judgmentSurface, "reopen_summary"),
            reopenSummary(reopenCandidatePaths)
        );
        String summary = firstNonBlank(
            summarizeResumePacketTimeline(
                resumePacket,
                workerId,
                promptMode,
                reopenSummary,
                evidenceGapDetected,
                needsArchiveRetrieval,
                needsExternalFactRefresh
            ),
            blankToNull(resumePacket.latestSummary()),
            blankToNull(resumePacket.activeTaskSummary())
        );
        return new RuntimeCognitionTimelineEntryView(
            "resume_packet",
            "Resume Packet",
            resumePacket.createdAt() != null ? resumePacket.createdAt().toString() : null,
            workerId,
            "resume_packet",
            null,
            reason,
            null,
            promptMode,
            metadataString(routeSurface, "route_source"),
            firstNonBlank(metadataString(executionSurface, "execution_status"), blankToNull(resumePacket.currentStatus())),
            firstNonNullInt(
                metadataInteger(executionSurface, "tool_invocation_count"),
                sizeOf(metadataStringList(executionSurface, "tool_invocation_ids"))
            ),
            metadataBoolean(judgmentSurface, "needs_context_reopen", payload),
            evidenceGapDetected,
            needsArchiveRetrieval,
            needsExternalFactRefresh,
            reopenCandidatePaths,
            reopenSummary,
            firstNonBlank(
                metadataString(executionSurface, "proof_summary"),
                proofSummary(
                    metadataStringList(executionSurface, "tool_invocation_ids"),
                    metadataStringList(executionSurface, "evidence_refs")
                ),
                proofSummary(metadataStringList(payload, "tool_invocation_ids"), metadataStringList(payload, "evidence_refs"))
            ),
            metadataBoolean(executionSurface, "mounted_context_rendered", payload),
            metadataBoolean(executionSurface, "mounted_render_used", payload),
            metadataBoolean(executionSurface, "mounted_context_injected", payload),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_panel_count"),
                metadataInteger(payload, "mounted_context_panel_count")
            ),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_rendered_object_count"),
                metadataInteger(payload, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_hidden_object_count"),
                metadataInteger(payload, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_rendered_selection_trace_count"),
                metadataInteger(payload, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(executionSurface, "mounted_context_hidden_selection_trace_count"),
                metadataInteger(payload, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT)
            ),
            metadataBoolean(executionSurface, "mounted_context_budget_truncated", payload),
            null,
            firstNonEmptyList(
                metadataStringList(routeSurface, "candidate_workers"),
                metadataStringList(payload, "candidate_workers")
            ),
            firstNonEmptyList(
                metadataStringList(executionSurface, "tool_invocation_ids"),
                metadataStringList(payload, "tool_invocation_ids")
            ),
            firstNonEmptyList(
                metadataStringList(executionSurface, "evidence_refs"),
                metadataStringList(payload, "evidence_refs")
            ),
            firstNonEmptyList(
                metadataStringList(executionSurface, "unfinished_items"),
                resumePacket.openQuestions() == null ? List.of() : resumePacket.openQuestions()
            ),
            summary
        );
    }

    private String resolveRouteOccurredAt(RuntimeFactSet facts) {
        if (facts == null || facts.runtimeContext() == null || facts.runtimeContext().recentArtifacts() == null) {
            return null;
        }
        return facts.runtimeContext().recentArtifacts().stream()
            .filter(Objects::nonNull)
            .map(Artifact::createdAt)
            .filter(Objects::nonNull)
            .max(Instant::compareTo)
            .map(Instant::toString)
            .orElse(null);
    }

    private String resolveExecutionOccurredAt(RuntimeFactSet.ExecutionBoundary executionBoundary) {
        if (executionBoundary == null) {
            return null;
        }
        return firstNonBlank(
            blankToNull(executionBoundary.finishedAt()),
            blankToNull(executionBoundary.startedAt())
        );
    }

    private String summarizeRouteTimeline(RuntimeCognitionSurfaceView.RouteSurface route) {
        if (route == null) {
            return null;
        }
        String worker = blankToNull(route.selectedWorker());
        String source = blankToNull(route.routeSource());
        String tier = blankToNull(route.selectedModelTier());
        String role = blankToNull(route.selectedExecutionRole());
        return firstNonBlank(
            joinSummary(worker, source, tier, role),
            joinSummary(worker, source),
            worker
        );
    }

    private String summarizeExecutionTimeline(RuntimeCognitionSurfaceView.ExecutionSurface execution) {
        if (execution == null) {
            return null;
        }
        String status = blankToNull(execution.executionStatus());
        String promptMode = blankToNull(execution.promptMode());
        String trace = blankToNull(execution.traceSummary());
        String proof = blankToNull(execution.proofSummary());
        String tools = execution.toolInvocationCount() == null ? null : execution.toolInvocationCount() + " tools";
        String budget = mountedBudgetSummary(
            execution.mountedContextRenderedObjectCount(),
            execution.mountedContextHiddenObjectCount(),
            execution.mountedContextRenderedSelectionTraceCount(),
            execution.mountedContextHiddenSelectionTraceCount(),
            execution.mountedContextBudgetTruncated()
        );
        return firstNonBlank(
            joinSummary(status, promptMode, tools, budget, proof, trace),
            joinSummary(status, promptMode, budget, proof, trace),
            status
        );
    }

    private String summarizeJudgmentTimeline(Decision decision,
                                             RuntimeCognitionSurfaceView.JudgmentSurface surface) {
        String promptMode = surface != null ? blankToNull(surface.promptMode()) : null;
        String action = decision != null ? metadataString(decision.metadata(), "action") : null;
        String status = decision != null ? metadataString(decision.metadata(), "status") : null;
        String reopen = surface != null ? blankToNull(surface.reopenSummary()) : null;
        String proof = surface != null ? blankToNull(surface.proofSummary()) : null;
        String lifecycleSignals = surface == null ? null : lifecycleSignalSummary(
            surface.evidenceGapDetected(),
            surface.needsArchiveRetrieval(),
            surface.needsExternalFactRefresh()
        );
        String budget = surface == null ? null : mountedBudgetSummary(
            surface.mountedContextRenderedObjectCount(),
            surface.mountedContextHiddenObjectCount(),
            surface.mountedContextRenderedSelectionTraceCount(),
            surface.mountedContextHiddenSelectionTraceCount(),
            surface.mountedContextBudgetTruncated()
        );
        return firstNonBlank(
            joinSummary(promptMode, action, status, budget, lifecycleSignals, reopen, proof),
            joinSummary(promptMode, status, budget, lifecycleSignals, reopen, proof),
            promptMode
        );
    }

    private String summarizeControlActionTimeline(String action,
                                                  String workerId,
                                                  String targetWorker,
                                                  String promptMode,
                                                  String reason,
                                                  String reopenSummary,
                                                  Boolean evidenceGapDetected,
                                                  Boolean needsArchiveRetrieval,
                                                  Boolean needsExternalFactRefresh) {
        String workerTransition = joinArrow(workerId, targetWorker);
        return firstNonBlank(
            joinSummary(
                action,
                workerTransition,
                promptMode,
                reason,
                lifecycleSignalSummary(evidenceGapDetected, needsArchiveRetrieval, needsExternalFactRefresh),
                reopenSummary
            ),
            joinSummary(action, workerTransition, promptMode),
            action
        );
    }

    private String summarizeCheckpointTimeline(String checkpointType,
                                               String workerId,
                                               String promptMode,
                                               String consolidationSummary,
                                               String reopenSummary,
                                               Boolean evidenceGapDetected,
                                               Boolean needsArchiveRetrieval,
                                               Boolean needsExternalFactRefresh) {
        return firstNonBlank(
            joinSummary(
                checkpointType,
                workerId,
                promptMode,
                lifecycleSignalSummary(evidenceGapDetected, needsArchiveRetrieval, needsExternalFactRefresh),
                reopenSummary
            ),
            joinSummary(checkpointType, workerId),
            blankToNull(consolidationSummary),
            checkpointType
        );
    }

    private String summarizeResumePacketTimeline(ResumePacket resumePacket,
                                                 String workerId,
                                                 String promptMode,
                                                 String reopenSummary,
                                                 Boolean evidenceGapDetected,
                                                 Boolean needsArchiveRetrieval,
                                                 Boolean needsExternalFactRefresh) {
        if (resumePacket == null) {
            return null;
        }
        String currentNode = blankToNull(resumePacket.currentNode());
        String currentStatus = blankToNull(resumePacket.currentStatus());
        String nextStep = blankToNull(resumePacket.nextStep());
        return firstNonBlank(
            joinSummary(
                "resume packet",
                workerId,
                currentNode,
                currentStatus,
                promptMode,
                nextStep,
                lifecycleSignalSummary(evidenceGapDetected, needsArchiveRetrieval, needsExternalFactRefresh),
                reopenSummary
            ),
            joinSummary("resume packet", workerId, currentNode, currentStatus, promptMode),
            joinSummary("resume packet", workerId, currentNode, currentStatus),
            joinSummary("resume packet", workerId, nextStep),
            "resume packet"
        );
    }

    private String lifecycleSignalSummary(Boolean evidenceGapDetected,
                                          Boolean needsArchiveRetrieval,
                                          Boolean needsExternalFactRefresh) {
        return joinSummary(
            Boolean.TRUE.equals(evidenceGapDetected) ? "evidence_gap_detected=true" : null,
            Boolean.TRUE.equals(needsArchiveRetrieval) ? "needs_archive_retrieval=true" : null,
            Boolean.TRUE.equals(needsExternalFactRefresh) ? "needs_external_fact_refresh=true" : null
        );
    }

    private String joinArrow(String left, String right) {
        String normalizedLeft = blankToNull(left);
        String normalizedRight = blankToNull(right);
        if (normalizedLeft == null && normalizedRight == null) {
            return null;
        }
        if (normalizedLeft == null) {
            return normalizedRight;
        }
        if (normalizedRight == null) {
            return normalizedLeft;
        }
        if (normalizedLeft.equals(normalizedRight)) {
            return normalizedLeft;
        }
        return normalizedLeft + " -> " + normalizedRight;
    }

    private String continuityActionLabel(String action) {
        return switch (action == null ? "" : action) {
            case "pause" -> "Paused";
            case "resume" -> "Resumed";
            case "continue" -> "Continued";
            case "handoff" -> "Handed Off";
            case "escalate" -> "Escalated";
            default -> "Continuity Action";
        };
    }

    private String checkpointLabel(String checkpointType) {
        return switch (checkpointType == null ? "" : checkpointType) {
            case "pause_before" -> "Pause Checkpoint";
            case "handoff_before" -> "Handoff Checkpoint";
            case "escalate_before" -> "Escalation Checkpoint";
            case "halt_before" -> "Halt Checkpoint";
            case "reopen_before" -> "Reopen Checkpoint";
            case "archive_retrieval_before" -> "Archive Retrieval Checkpoint";
            case "external_fact_refresh_before" -> "External Fact Refresh Checkpoint";
            case "session_end" -> "Session End Checkpoint";
            case "periodic" -> "Periodic Checkpoint";
            default -> "Checkpoint";
        };
    }

    private String mountedBudgetSummary(Integer renderedObjectCount,
                                        Integer hiddenObjectCount,
                                        Integer renderedSelectionTraceCount,
                                        Integer hiddenSelectionTraceCount,
                                        Boolean budgetTruncated) {
        String objects = renderedObjectCount == null && hiddenObjectCount == null
            ? null
            : firstNonNullInt(renderedObjectCount, 0) + "/" + firstNonNullInt(hiddenObjectCount, 0) + " objects";
        String traces = renderedSelectionTraceCount == null && hiddenSelectionTraceCount == null
            ? null
            : firstNonNullInt(renderedSelectionTraceCount, 0) + "/" + firstNonNullInt(hiddenSelectionTraceCount, 0)
                + " traces";
        String truncated = Boolean.TRUE.equals(budgetTruncated) ? "budget truncated" : null;
        return firstNonBlank(
            joinSummary(objects, traces, truncated),
            joinSummary(objects, traces),
            truncated
        );
    }

    private String joinSummary(String... parts) {
        if (parts == null || parts.length == 0) {
            return null;
        }
        return java.util.Arrays.stream(parts)
            .map(this::blankToNull)
            .filter(Objects::nonNull)
            .distinct()
            .reduce((left, right) -> left + " · " + right)
            .orElse(null);
    }

    private String proofSummary(List<String> toolInvocationIds, List<String> evidenceRefs) {
        List<String> parts = new ArrayList<>();
        appendProofSummaryParts(parts, prefixedValues("tool", toolInvocationIds));
        appendProofSummaryParts(parts, prefixedValues("evidence", evidenceRefs));
        if (parts.isEmpty()) {
            return null;
        }
        return "proof=" + String.join(", ", parts);
    }

    private void appendProofSummaryParts(List<String> target, List<String> values) {
        if (values == null || values.isEmpty() || target.size() >= 2) {
            return;
        }
        for (String value : values) {
            String normalized = truncateProofLabel(value);
            if (normalized == null) {
                continue;
            }
            target.add(normalized);
            if (target.size() >= 2) {
                return;
            }
        }
    }

    private List<String> prefixedValues(String prefix, List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized == null) {
                continue;
            }
            result.add(prefix + ":" + normalized);
        }
        return result;
    }

    private String truncateProofLabel(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        String compact = normalized.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 72) {
            return compact;
        }
        return compact.substring(0, 69) + "...";
    }

    private String truncate(String value, int limit) {
        if (value == null || limit <= 0) {
            return value;
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= limit ? compact : compact.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private String reopenSummary(List<String> reopenCandidatePaths) {
        if (reopenCandidatePaths == null || reopenCandidatePaths.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        appendProofSummaryParts(parts, prefixedValues("reopen", reopenCandidateLabels(reopenCandidatePaths)));
        if (parts.isEmpty()) {
            return null;
        }
        return "reopen=" + String.join(", ", parts);
    }

    public HarnessTraceView getHarnessTrace(String taskId, int limit) {
        Task task = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        int boundedLimit = boundedLimit(limit);
        RuntimeFactSet facts = runtimeFactSetAssembler.assemble(task, boundedLimit);
        var routePreview = facts.routePreview();
        TaskRuntimeContext runtimeContext = facts.runtimeContext();
        Decision executionJudgment = facts.executionJudgment();
        Decision completionJudgment = facts.completionJudgment();
        List<ToolInvocationRecord> toolInvocations = facts.toolInvocations();
        ExperimentRunRecord experimentRun = experimentRunService != null ? experimentRunService.refresh(task) : null;
        AgentRunRecord agentRun = agentRunService != null ? agentRunService.latestByTask(taskId) : null;
        List<AgentRunEventView> agentRunEvents = agentRunService != null && agentRun != null
            ? nullToEmpty(agentRunService.listEvents(agentRun.runId(), boundedLimit))
            : List.of();
        List<AgentRunArtifactView> agentArtifacts = agentRunService != null && agentRun != null
            ? nullToEmpty(agentRunService.listArtifacts(agentRun.runId(), boundedLimit))
            : List.of();
        Map<String, Object> harnessMetadata = new LinkedHashMap<>();
        if (experimentRun != null && experimentRun.metadata() != null) {
            harnessMetadata.putAll(experimentRun.metadata());
        }
        supplementHarnessMetadataFromToolInvocations(harnessMetadata, toolInvocations);
        harnessMetadata.put("tool_invocation_count", toolInvocations.size());
        harnessMetadata.put("agent_run_event_count", agentRunEvents.size());
        harnessMetadata.put("agent_artifact_count", agentArtifacts.size());
        return new HarnessTraceView(
            task.id(),
            task.status(),
            task.controlNode(),
            task.assignedWorker(),
            firstNonBlank(metadataString(harnessMetadata, "execution_status"), task.status()),
            metadataStringList(harnessMetadata, "evidence_refs"),
            metadataStringList(harnessMetadata, "unfinished_items"),
            executionJudgment != null && executionJudgment.metadata() != null
                ? stringValue(executionJudgment.metadata().get("action"))
                : null,
            firstNonBlank(
                executionJudgment != null && executionJudgment.metadata() != null
                    ? stringValue(executionJudgment.metadata().get("next_step"))
                    : null,
                completionJudgment != null && completionJudgment.metadata() != null
                    ? stringValue(completionJudgment.metadata().get("suggested_next_action"))
                    : null,
                task.nextStep()
            ),
            routePreview,
            experimentRun,
            agentRun,
            executionJudgment,
            completionJudgment,
            toolInvocations,
            agentRunEvents,
            agentArtifacts,
            harnessMetadata
        );
    }

    public List<ToolInvocationRecord> listToolInvocations(String taskId, int limit) {
        taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return toolInvocationDao != null ? toolInvocationDao.listByTask(taskId, boundedLimit(limit)) : List.of();
    }

    public ExperimentRunRecord getExperimentRun(String taskId) {
        Task task = taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return experimentRunService != null ? experimentRunService.refresh(task) : null;
    }

    public AgentRunRecord getLatestAgentRun(String taskId) {
        taskDao.findById(taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
        return agentRunService != null ? agentRunService.latestByTask(taskId) : null;
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
        Task accepted = t.withStatus("active").withControlNode("scheduler").withWaitingReason(null);
        recordControlActionEvent(accepted, "resume", null, actionMetadata);
        recordTaskActionMessage(accepted, "resume", null, actionMetadata);
        recordTaskStateProjection(t, accepted, null, actionMetadata);
        Task updated = controlGraph.triggerResume(t);
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
        attachHandoffPacketMetadata(metadata, handoffPacket);
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

    private boolean isRecoveryCandidate(Task task) {
        if (task == null) {
            return false;
        }
        String status = task.status();
        String controlNode = task.controlNode();
        return "waiting_human".equals(status)
            || "failed".equals(status)
            || "paused".equals(status)
            || "waiting".equals(status)
            || "human_gate".equals(controlNode);
    }

    private TaskRecoveryPlan buildRecoveryPlan(Task task, Map<String, Object> request) {
        Map<String, Object> taskMetadata = task.metadata() != null ? task.metadata() : Map.of();
        AgentRunRecord latestRun = agentRunService != null ? agentRunService.latestByTask(task.id()) : null;
        Map<String, Object> runMetadata = latestRun != null && latestRun.metadata() != null ? latestRun.metadata() : Map.of();
        RecoveryFailureEvidence providerFailureEvidence = resolveProviderFailureEvidence(
            request,
            task,
            taskMetadata,
            latestRun,
            runMetadata
        );
        String providerFailureClass = providerFailureEvidence.providerFailureClass();
        String failureClass = firstNonBlank(
            metadataString(request, "failure_class"),
            metadataString(taskMetadata, "failure_class"),
            metadataString(runMetadata, "failure_class"),
            metadataString(runMetadata, "worker_failure_class")
        );
        String targetWorker = firstNonBlank(
            metadataString(request, "target_worker"),
            metadataString(taskMetadata, "auto_handoff_target"),
            metadataString(taskMetadata, "target_worker")
        );
        boolean candidate = isRecoveryCandidate(task);
        boolean environmentBlocked = isEnvironmentBlockedFailure(providerFailureClass);
        boolean recoverable = candidate && !environmentBlocked;
        String requestedMode = firstNonBlank(metadataString(request, "mode"), "auto").toLowerCase();
        String recommendedAction = "handoff".equals(requestedMode) || targetWorker != null && !targetWorker.equals(task.assignedWorker())
            ? "handoff"
            : "resume";
        if ("continue".equals(requestedMode) || "resume".equals(requestedMode)) {
            recommendedAction = requestedMode;
        }
        String reason;
        if (!candidate) {
            reason = "task state is not recoverable";
        } else if (environmentBlocked) {
            reason = "provider environment requires manual repair: " + providerFailureClass;
        } else if (targetWorker != null && !targetWorker.equals(task.assignedWorker())) {
            reason = "recover by handoff to target worker";
        } else {
            reason = firstNonBlank(metadataString(request, "reason"), "recover with fresh session");
        }
        return new TaskRecoveryPlan(
            task.id(),
            task.status(),
            task.controlNode(),
            task.assignedWorker(),
            recoverable,
            recommendedAction,
            targetWorker,
            reason,
            failureClass,
            providerFailureClass,
            providerFailureEvidence.source(),
            providerFailureEvidence.evidence(),
            metadataString(taskMetadata, "recovery_stage"),
            recoverable ? "fresh_session" : metadataString(taskMetadata, "recovery_execution_mode")
        );
    }

    private RecoveryFailureEvidence resolveProviderFailureEvidence(Map<String, Object> request,
                                                                   Task task,
                                                                   Map<String, Object> taskMetadata,
                                                                   AgentRunRecord latestRun,
                                                                   Map<String, Object> runMetadata) {
        String requestClass = metadataString(request, "provider_failure_class");
        if (requestClass != null) {
            return providerFailureEvidenceWithReason(
                requestClass,
                request,
                "request"
            );
        }
        String taskClass = metadataString(taskMetadata, "provider_failure_class");
        if (taskClass != null) {
            return providerFailureEvidenceWithReason(
                taskClass,
                taskMetadata,
                "task.metadata"
            );
        }
        String runClass = metadataString(runMetadata, "provider_failure_class");
        if (runClass != null) {
            return providerFailureEvidenceWithReason(
                runClass,
                runMetadata,
                "agent_run.metadata"
            );
        }
        RecoveryFailureEvidence fromSummary = classifyProviderFailureEvidence(
            latestRun != null ? latestRun.summary() : null,
            "agent_run.summary"
        );
        if (fromSummary.providerFailureClass() != null) {
            return fromSummary;
        }
        RecoveryFailureEvidence fromWaitingReason = classifyProviderFailureEvidence(
            task != null ? task.waitingReason() : null,
            "task.waiting_reason"
        );
        if (fromWaitingReason.providerFailureClass() != null) {
            return fromWaitingReason;
        }
        RecoveryFailureEvidence fromTaskSummary = classifyProviderFailureEvidence(
            task != null ? task.summary() : null,
            "task.summary"
        );
        if (fromTaskSummary.providerFailureClass() != null) {
            return fromTaskSummary;
        }
        return classifyProviderFailureEvidence(
            task != null ? task.nextStep() : null,
            "task.next_step"
        );
    }

    private RecoveryFailureEvidence providerFailureEvidenceWithReason(String providerFailureClass,
                                                                      Map<String, Object> metadata,
                                                                      String sourcePrefix) {
        String providerError = metadataString(metadata, "provider_error");
        if (providerError != null) {
            return new RecoveryFailureEvidence(
                providerFailureClass,
                sourcePrefix + ".provider_error",
                compactEvidence(providerError)
            );
        }
        String providerFailureReason = metadataString(metadata, "provider_failure_reason");
        if (providerFailureReason != null) {
            return new RecoveryFailureEvidence(
                providerFailureClass,
                sourcePrefix + ".provider_failure_reason",
                compactEvidence(providerFailureReason)
            );
        }
        return new RecoveryFailureEvidence(
            providerFailureClass,
            sourcePrefix + ".provider_failure_class",
            providerFailureClass
        );
    }

    private RecoveryFailureEvidence classifyProviderFailureEvidence(String text, String source) {
        String providerFailureClass = classifyProviderFailureFromText(text);
        return new RecoveryFailureEvidence(providerFailureClass, providerFailureClass != null ? source : null, compactEvidence(text));
    }

    private String resolveRecoveryAction(TaskRecoveryPlan plan, String requestedMode) {
        return switch (requestedMode) {
            case "auto" -> plan.recommendedAction();
            case "resume", "continue", "handoff" -> requestedMode;
            default -> throw new IllegalArgumentException("unsupported recovery mode: " + requestedMode);
        };
    }

    private Task prepareFreshSessionRecovery(Task task, TaskRecoveryPlan plan, Map<String, Object> request) {
        Map<String, Object> metadata = task.metadata() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(task.metadata());
        metadata.remove("provider_session_id");
        metadata.remove("provider_thread_id");
        metadata.remove("codex_thread_id");
        metadata.remove("resume_provider_session_id");
        metadata.put("manual_recovery_requested", true);
        metadata.put("recovery_stage", "manual_recover_scheduled");
        metadata.put("recovery_execution_mode", "fresh_session");
        metadata.put("recovery_reason", firstNonBlank(metadataString(request, "reason"), plan.reason()));
        if (plan.failureClass() != null) {
            metadata.put("failure_class", plan.failureClass());
        }
        if (plan.providerFailureClass() != null) {
            metadata.put("provider_failure_class", plan.providerFailureClass());
        }
        return task.withMetadata(metadata)
            .withStatus("active")
            .withControlNode("scheduler")
            .withWaitingReason(null);
    }

    private boolean isEnvironmentBlockedFailure(String providerFailureClass) {
        if (providerFailureClass == null) {
            return false;
        }
        return providerFailureClass.contains("auth")
            || providerFailureClass.contains("not_installed")
            || providerFailureClass.contains("unsupported")
            || providerFailureClass.contains("permission")
            || providerFailureClass.contains("configuration");
    }

    private String classifyProviderFailureFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.toLowerCase();
        if (normalized.contains("thread not found")
            || normalized.contains("provider unavailable")
            || normalized.contains("timeout")
            || normalized.contains("failed to start")
            || normalized.contains("output too large")
            || normalized.contains("output exceeds")
            || normalized.contains("output exceeded")
            || normalized.contains("output limit")
            || normalized.contains("max output")
            || normalized.contains("maximum output")
            || normalized.contains("response too large")
            || normalized.contains("context length exceeded")
            || normalized.contains("context window exceeded")
            || normalized.contains("输出过大")
            || normalized.contains("输出超过")
            || normalized.contains("上下文超限")) {
            return "provider_runtime_transient";
        }
        if (normalized.contains("auth") || normalized.contains("unauthorized") || normalized.contains("api key")) {
            return "provider_auth_failed";
        }
        if (normalized.contains("not installed") || normalized.contains("command not found")) {
            return "provider_not_installed";
        }
        return null;
    }

    private String compactEvidence(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        int limit = 220;
        return compact.length() <= limit ? compact : compact.substring(0, limit) + "...";
    }

    private Decision latestDecision(TaskRuntimeContext runtimeContext, String decisionType) {
        if (runtimeContext == null || runtimeContext.recentDecisions() == null) {
            return null;
        }
        return runtimeContext.recentDecisions().stream()
            .filter(decision -> decision != null && decisionType.equals(decision.decisionType()))
            .findFirst()
            .orElse(null);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        return stringValue(metadata.get(key));
    }

    private Boolean metadataBoolean(Map<String, Object> primary, String key, Map<String, Object> fallback) {
        Boolean value = objectBoolean(primary != null ? primary.get(key) : null);
        return value != null ? value : objectBoolean(fallback != null ? fallback.get(key) : null);
    }

    private Integer metadataInteger(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataMap(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return Map.of();
        }
        Object value = metadata.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    typed.put(entry.getKey().toString(), entry.getValue());
                }
            }
            return typed;
        }
        return Map.of();
    }

    private List<String> firstNonEmptyList(List<String> primary, List<String> fallback) {
        if (primary != null && !primary.isEmpty()) {
            return primary;
        }
        return fallback != null ? fallback : List.of();
    }

    private Integer sizeOf(List<?> values) {
        return values == null || values.isEmpty() ? null : values.size();
    }

    private Boolean objectBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    private Integer firstNonNullInt(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Boolean alignmentFlag(String left, String right) {
        String normalizedLeft = blankToNull(left);
        String normalizedRight = blankToNull(right);
        if (normalizedLeft == null || normalizedRight == null) {
            return null;
        }
        return normalizedLeft.equals(normalizedRight);
    }

    private void supplementHarnessMetadataFromToolInvocations(Map<String, Object> target, List<ToolInvocationRecord> invocations) {
        if (target == null || invocations == null || invocations.isEmpty()) {
            return;
        }
        for (ToolInvocationRecord invocation : invocations) {
            if (invocation == null || invocation.metadata() == null || invocation.metadata().isEmpty()) {
                continue;
            }
            copyMetadataIfAbsent(invocation.metadata(), target, "execution_status");
            copyMetadataIfAbsent(invocation.metadata(), target, "evidence_refs");
            copyMetadataIfAbsent(invocation.metadata(), target, "unfinished_items");
            copyMetadataIfAbsent(invocation.metadata(), target, "tool_execution_mode");
            copyMetadataIfAbsent(invocation.metadata(), target, "tool_chain_step_count");
            copyMetadataIfAbsent(invocation.metadata(), target, "tool_chain_termination_reason");
            copyMetadataIfAbsent(invocation.metadata(), target, "tool_chain_trace_summary");
            copyMetadataIfAbsent(invocation.metadata(), target, "tool_chain_tools");
        }
    }

    private void copyMetadataIfAbsent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null || key.isBlank() || target.containsKey(key)) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> metadataStringList(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return List.of();
        }
        Object value = metadata.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(item -> item != null && !item.toString().isBlank())
                .map(Object::toString)
                .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private List<String> reopenCandidateLabels(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String label = reopenCandidateLabel(value);
            if (label != null) {
                result.add(label);
            }
        }
        return result;
    }

    private String reopenCandidateLabel(String targetPath) {
        String normalized = blankToNull(targetPath);
        if (normalized == null) {
            return null;
        }
        String[] tokens = normalized.split("/");
        if (tokens.length == 0) {
            return normalized;
        }
        String tail = tokens[tokens.length - 1];
        if (tail == null || tail.isBlank()) {
            return normalized;
        }
        if ("messages".equals(tail) || "artifacts".equals(tail) || "tool_invocations".equals(tail) || "decisions".equals(tail)) {
            return tail;
        }
        if (tokens.length >= 2) {
            String parent = tokens[tokens.length - 2];
            if ("checkpoints".equals(parent) || "packets".equals(parent)) {
                return parent + ":" + tail;
            }
        }
        return tail;
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

    private String resolveProviderId(String workerId, String workerType) {
        if (blankToNull(workerId) == null) {
            return null;
        }
        String resolvedWorkerType = firstNonBlank(workerType, routerWorkerType(workerId), workerId);
        return com.agentcloud.agent.AgentProviderResolver.providerIdForWorker(workerId, resolvedWorkerType);
    }

    private String routerWorkerType(String workerId) {
        if (router == null || blankToNull(workerId) == null) {
            return null;
        }
        Worker worker = router.getWorker(workerId);
        return worker != null ? worker.workerType() : null;
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

    private List<SessionMessage> mergeLiveFlowRelatedMessages(List<SessionMessage> taskMessages,
                                                              List<SessionMessage> sessionMessages,
                                                              int limit) {
        LinkedHashMap<String, SessionMessage> merged = new LinkedHashMap<>();
        for (SessionMessage message : nullToEmpty(taskMessages)) {
            if (message == null || blankToNull(message.id()) == null) {
                continue;
            }
            merged.put(message.id(), withContinuityScope(message, "task"));
        }
        for (SessionMessage message : nullToEmpty(sessionMessages)) {
            if (message == null || blankToNull(message.id()) == null || merged.containsKey(message.id())) {
                continue;
            }
            if (blankToNull(message.taskId()) != null) {
                continue;
            }
            merged.put(message.id(), withContinuityScope(message, "session"));
        }
        return merged.values().stream()
            .sorted(Comparator.comparing(SessionMessage::createdAt))
            .limit(Math.max(1, limit))
            .toList();
    }

    private SessionMessage withContinuityScope(SessionMessage message, String scope) {
        if (message == null || blankToNull(scope) == null) {
            return message;
        }
        LinkedHashMap<String, Object> metadata = message.metadata() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(message.metadata());
        metadata.putIfAbsent("continuity_scope", scope);
        return new SessionMessage(
            message.id(),
            message.sessionId(),
            message.taskId(),
            message.role(),
            message.messageType(),
            message.content(),
            message.createdAt(),
            metadata
        );
    }

    private int boundedLimit(int limit) {
        return Math.max(1, Math.min(limit, 20));
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private void recordTaskReceipt(Task task, boolean autoStart, boolean followup, Map<String, Object> extraMetadata) {
        String title = taskDisplayName(task);
        String content = autoStart
            ? "任务《" + title + "》已创建，并已自动进入 harness。当前：" + describeTaskSnapshot(task) + "。"
            : "任务《" + title + "》已创建。当前模式：manual-start。下一步：显式 /continue 后再进入 harness。";
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
            "任务《" + taskDisplayName(currentTask) + "》状态已更新："
                + firstNonBlank(previousState, "unknown")
                + " -> "
                + firstNonBlank(currentState, "unknown")
                + "。当前："
                + describeTaskSnapshot(currentTask)
                + appendReason(reason),
            metadata
        );
    }

    private void recordTaskActionMessage(Task task, String action, String reason, Map<String, Object> extraMetadata) {
        LinkedHashMap<String, Object> metadata = lifecycleMetadata(task);
        metadata.put("action", action);
        metadata.put("action_category", "task_control");
        String actionLabel = taskActionLabel(action);
        if (actionLabel != null) {
            metadata.put("action_label", actionLabel);
        }
        if (reason != null && !reason.isBlank()) {
            metadata.put("reason", reason);
        }
        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            metadata.putAll(extraMetadata);
        }
        String content = switch (action) {
            case "handoff" -> "任务《" + taskDisplayName(task) + "》已移交，当前：" + describeTaskSnapshot(task)
                + appendWorkerShift(extraMetadata);
            default -> "任务《" + taskDisplayName(task) + "》" + firstNonBlank(actionLabel, "已执行 " + action)
                + "。当前：" + describeTaskSnapshot(task)
                + appendReason(reason);
        };
        appendSessionMessage(task, "system", "task_action", content, metadata);
    }

    private void recordAssistantProgressMessage(Task task, String trigger, ExperimentRunRecord experimentRun) {
        if (task == null || sessionMessageDao == null) {
            return;
        }
        try {
            RuntimeFactSet facts = runtimeFactSetAssembler.assemble(task, 20);
            TaskRuntimeContext runtimeContext = facts.runtimeContext();
            Decision executionJudgment = facts.executionJudgment();
            Decision completionJudgment = facts.completionJudgment();
            Artifact latestArtifact = latestArtifact(runtimeContext);

            String progressSummary = summarizeProgress(task, facts, latestArtifact);
            String nextStep = summarizeNextStep(task, facts, latestArtifact);
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
            String fullContent = buildAssistantExpandedContent(task, facts, latestArtifact, progressSummary, nextStep);
            if (fullContent != null) {
                metadata.put("full_content", fullContent);
            }
            if (facts.recommendedAction() != null) {
                metadata.put("judgment_action", facts.recommendedAction());
            }
            appendRuntimeFactMessageMetadata(task, facts, latestArtifact, metadata);
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

    private String summarizeProgress(Task task, RuntimeFactSet facts, Artifact latestArtifact) {
        TaskRuntimeContext runtimeContext = facts != null ? facts.runtimeContext() : null;
        Decision executionJudgment = facts != null ? facts.executionJudgment() : null;
        Decision completionJudgment = facts != null ? facts.completionJudgment() : null;
        Map<String, Object> latestArtifactMetadata = latestArtifact != null ? latestArtifact.metadata() : null;
        Map<String, Object> taskMetadata = task != null ? task.metadata() : null;
        String providerFailure = hasProviderFailureDiagnostics(latestArtifactMetadata)
                || hasProviderFailureDiagnostics(taskMetadata)
            ? resolveProviderReadableFailure(
                latestArtifactMetadata,
                taskMetadata
            )
            : null;
        return sanitizeReadableProgressSummary(
            task,
            facts,
            latestArtifact,
            firstNonBlank(
                providerFailure,
                task.summary(),
                facts != null ? facts.latestOutput() : null,
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

    private String sanitizeReadableProgressSummary(Task task,
                                                   RuntimeFactSet facts,
                                                   Artifact latestArtifact,
                                                   String summary,
                                                   int maxLength) {
        String shortened = shorten(summary, maxLength);
        if (!looksLikeUnreadableWorkerOutput(shortened) || !isFailedExecutionBoundary(facts, latestArtifact)) {
            return shortened;
        }
        String worker = firstNonBlank(
            metadataString(latestArtifact != null ? latestArtifact.metadata() : null, "selected_worker"),
            metadataString(latestArtifact != null ? latestArtifact.metadata() : null, "worker_id"),
            task != null ? task.assignedWorker() : null
        );
        return worker == null
            ? "当前 worker 返回了不可读错误输出；请检查 details / live_flow。"
            : "worker " + worker + " 返回了不可读错误输出；请检查 details / live_flow。";
    }

    private boolean isFailedExecutionBoundary(RuntimeFactSet facts, Artifact latestArtifact) {
        String executionStatus = firstNonBlank(
            facts != null && facts.executionBoundary() != null ? facts.executionBoundary().executionStatus() : null,
            metadataString(latestArtifact != null ? latestArtifact.metadata() : null, "execution_status")
        );
        if (executionStatus == null) {
            return false;
        }
        return List.of("failed", "error", "timeout", "cancelled").contains(executionStatus.toLowerCase());
    }

    private boolean looksLikeUnreadableWorkerOutput(String value) {
        String text = blankToNull(value);
        if (text == null) {
            return false;
        }
        long replacementCount = text.chars().filter(ch -> ch == '\uFFFD').count();
        return replacementCount >= 2 || text.contains("����") || (text.contains("û") && text.contains("��"));
    }

    private String summarizeNextStep(Task task, RuntimeFactSet facts, Artifact latestArtifact) {
        TaskRuntimeContext runtimeContext = facts != null ? facts.runtimeContext() : null;
        Decision completionJudgment = facts != null ? facts.completionJudgment() : null;
        return shorten(
            firstNonBlank(
                task.nextStep(),
                facts != null ? facts.recommendedNextStep() : null,
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
            .append("任务《").append(taskDisplayName(task)).append("》已完成一轮推进。进展：")
            .append(firstNonBlank(progressSummary, describeTaskSnapshot(task), "已完成一轮推进"));
        if (nextStep != null) {
            sb.append("。下一步：").append(nextStep);
        }
        sb.append("。当前：").append(describeTaskSnapshot(task)).append("。");
        return sb.toString();
    }

    private String buildAssistantResultMessage(Task task, String progressSummary, String nextStep) {
        StringBuilder sb = new StringBuilder()
            .append("任务《").append(taskDisplayName(task)).append("》已形成当前结果。结果：")
            .append(firstNonBlank(progressSummary, describeTaskSnapshot(task), "任务已结束"));
        if (nextStep != null) {
            sb.append("。残留下一步：").append(nextStep);
        }
        sb.append("。当前：").append(describeTaskSnapshot(task)).append("。");
        return sb.toString();
    }

    private void appendRuntimeFactMessageMetadata(Task task,
                                                  RuntimeFactSet facts,
                                                  Artifact latestArtifact,
                                                  Map<String, Object> target) {
        if (target == null) {
            return;
        }
        if (task != null && task.metadata() != null) {
            copyMetadataKey(task.metadata(), target, "model_mode");
            copyMetadataKey(task.metadata(), target, "task_type");
            copyMetadataKey(task.metadata(), target, "failure_class");
            copyMetadataKey(task.metadata(), target, "failure_summary_readable");
            copyMetadataKey(task.metadata(), target, "recovery_policy");
            copyMetadataKey(task.metadata(), target, "recovery_stage");
            copyMetadataKey(task.metadata(), target, "recovery_execution_mode");
            copyMetadataKey(task.metadata(), target, "auto_same_worker_retry_count");
            copyMetadataKey(task.metadata(), target, "auto_handoff_count");
            copyMetadataKey(task.metadata(), target, "auto_handoff_target");
            copyMetadataKey(task.metadata(), target, "previous_worker");
            copyProviderDiagnostics(task.metadata(), target);
        }
        if (facts == null) {
            return;
        }
        Map<String, Object> factMetadata = facts.metadata();
        copyMetadataKey(factMetadata, target, "selected_worker");
        copyMetadataKey(factMetadata, target, "selected_worker_type");
        copyMetadataKey(factMetadata, target, "selected_model_tier");
        copyMetadataKey(factMetadata, target, "why_selected");
        copyMetadataKey(factMetadata, target, "route_source");
        copyMetadataKey(factMetadata, target, "preferred_worker_hint");
        copyMetadataKey(factMetadata, target, "learning_hint_applied");
        copyMetadataKey(factMetadata, target, "fallback_reason");
        copyMetadataKey(factMetadata, target, "orchestration_stage");
        copyMetadataKey(factMetadata, target, "planner_worker");
        copyMetadataKey(factMetadata, target, "executor_worker");
        copyMetadataKey(factMetadata, target, "evaluation_reason");
        copyMetadataKey(factMetadata, target, "execution_status");
        copyMetadataKey(factMetadata, target, "evidence_refs");
        copyMetadataKey(factMetadata, target, "unfinished_items");
        copyMetadataKey(factMetadata, target, "proof_summary");
        copyMetadataKey(factMetadata, target, "tool_execution_mode");
        copyMetadataKey(factMetadata, target, "tool_chain_step_count");
        copyMetadataKey(factMetadata, target, "tool_chain_termination_reason");
        copyMetadataKey(factMetadata, target, "tool_chain_trace_summary");
        copyMetadataKey(factMetadata, target, "tool_chain_tools");
        copyMetadataKey(factMetadata, target, "needs_context_reopen");
        copyMetadataKey(factMetadata, target, "needs_archive_retrieval");
        copyMetadataKey(factMetadata, target, "needs_external_fact_refresh");
        copyMetadataKey(factMetadata, target, "evidence_gap_detected");
        copyMetadataKey(factMetadata, target, "reopen_candidate_paths");
        copyMetadataKey(factMetadata, target, "reopen_summary");
        copyProviderDiagnostics(factMetadata, target);

        WorkerRouter.RouteResult routePreview = facts.routePreview();
        if (routePreview != null) {
            putIfNonBlank(target, "selected_worker", routePreview.selectedWorker());
            putIfNonBlank(target, "selected_worker_type", routePreview.selectedWorkerType());
            putIfNonBlank(target, "route_source", routePreview.routeSource());
            putIfNonBlank(target, "preferred_worker_hint", routePreview.preferredWorkerHint());
            putIfNonBlank(target, "why_selected", routePreview.whySelected());
            putIfPresent(target, "learning_hint_applied", routePreview.learningHintApplied());
            putIfNonBlank(target, "fallback_reason", routePreview.fallbackReason());
            if (routePreview.dispatchSkippedWorkers() != null && !routePreview.dispatchSkippedWorkers().isEmpty()) {
                target.put("dispatch_skipped_workers", routeSkippedWorkerMetadata(routePreview.dispatchSkippedWorkers()));
            }
        }

        RuntimeFactSet.ExecutionBoundary executionBoundary = facts.executionBoundary();
        if (executionBoundary != null) {
            putIfNonBlank(target, "execution_status", executionBoundary.executionStatus());
            putIfPresent(target, "tool_invocation_count", executionBoundary.toolInvocationCount());
            putIfPresent(target, "tool_invocation_ids", executionBoundary.toolInvocationIds());
            putIfNonBlank(target, "execution_trace_summary", executionBoundary.traceSummary());
        }
        supplementHarnessMetadataFromToolInvocations(target, facts.toolInvocations());
        if (latestArtifact != null && latestArtifact.metadata() != null) {
            copyMetadataKey(latestArtifact.metadata(), target, "suggested_next_step");
            copyMetadataKey(latestArtifact.metadata(), target, "proof_summary");
            copyMetadataKey(latestArtifact.metadata(), target, "output_text");
            copyMetadataKey(latestArtifact.metadata(), target, "artifact_content");
            copyMetadataKey(latestArtifact.metadata(), target, "failure_class");
            copyMetadataKey(latestArtifact.metadata(), target, "failure_summary_readable");
            copyMetadataKey(latestArtifact.metadata(), target, "recovery_policy");
            copyMetadataKey(latestArtifact.metadata(), target, "recovery_stage");
            copyMetadataKey(latestArtifact.metadata(), target, "recovery_execution_mode");
            copyMetadataKey(latestArtifact.metadata(), target, "auto_same_worker_retry_count");
            copyMetadataKey(latestArtifact.metadata(), target, "auto_handoff_count");
            copyMetadataKey(latestArtifact.metadata(), target, "auto_handoff_target");
            copyProviderDiagnostics(latestArtifact.metadata(), target);
        }
    }

    private void copyProviderDiagnostics(Map<String, Object> source, Map<String, Object> target) {
        copyMetadataKey(source, target, "provider_session_id");
        copyMetadataKey(source, target, "provider_thread_id");
        copyMetadataKey(source, target, "resume_provider_session_id");
        copyMetadataKey(source, target, "provider_error");
        copyMetadataKey(source, target, "provider_turn_status");
        copyMetadataKey(source, target, "provider_failure_class");
        copyMetadataKey(source, target, "provider_failure_reason");
        copyMetadataKey(source, target, "provider_retryable");
        copyProviderProtocolTraceSummary(source, target);
    }

    private void copyProviderProtocolTraceSummary(Map<String, Object> source, Map<String, Object> target) {
        if (source == null || target == null) {
            return;
        }
        Object trace = source.get("provider_protocol_trace");
        if (!(trace instanceof List<?> values) || values.isEmpty()) {
            return;
        }
        target.put("provider_protocol_trace_count", values.size());
        target.put("provider_protocol_trace_preview", values.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .limit(20)
            .toList());
    }

    private String buildAssistantExpandedContent(Task task,
                                                RuntimeFactSet facts,
                                                Artifact latestArtifact,
                                                String progressSummary,
                                                String nextStep) {
        List<String> parts = new ArrayList<>();
        String summary = blankToNull(progressSummary);
        if (summary != null) {
            parts.add(summary);
        }
        Map<String, Object> latestArtifactMetadata = latestArtifact != null ? latestArtifact.metadata() : null;
        Map<String, Object> taskMetadata = task != null ? task.metadata() : null;
        if (latestArtifactMetadata != null || taskMetadata != null) {
            String outputText = firstNonBlank(
                blankToNull(metadataString(latestArtifactMetadata, "output_text")),
                blankToNull(metadataString(taskMetadata, "output_text"))
            );
            String artifactContent = firstNonBlank(
                blankToNull(metadataString(latestArtifactMetadata, "artifact_content")),
                blankToNull(metadataString(taskMetadata, "artifact_content"))
            );
            String readableFailure = resolveProviderReadableFailure(latestArtifactMetadata, taskMetadata);
            boolean failedExecutionBoundary = isFailedExecutionBoundary(facts, latestArtifact);
            boolean suppressUnreadableOutput = failedExecutionBoundary
                && readableFailure != null
                && (looksLikeUnreadableWorkerOutput(outputText) || looksLikeUnreadableWorkerOutput(artifactContent));
            String recoveryExecutionMode = firstNonBlank(
                metadataString(latestArtifactMetadata, "recovery_execution_mode"),
                metadataString(taskMetadata, "recovery_execution_mode")
            );
            if (outputText != null && !suppressUnreadableOutput) {
                parts.add("Worker Output\n" + outputText);
            }
            if (artifactContent != null && !suppressUnreadableOutput) {
                parts.add("Artifact Content\n" + artifactContent);
            }
            if (outputText == null && artifactContent == null && readableFailure != null) {
                parts.add("Failure Summary\n" + readableFailure);
            }
            if (readableFailure != null) {
                putIfAbsent(parts, "Failure Summary\n" + readableFailure);
            }
            if ("fresh_session".equalsIgnoreCase(recoveryExecutionMode)) {
                parts.add("Recovery Mode\nfresh session");
            }
        }
        if (facts != null && facts.executionBoundary() != null && facts.executionBoundary().traceSummary() != null) {
            parts.add("Execution Trace\n" + facts.executionBoundary().traceSummary());
        }
        String normalizedNextStep = blankToNull(nextStep);
        if (normalizedNextStep != null) {
            parts.add("下一步\n" + normalizedNextStep);
        }
        if (parts.isEmpty()) {
            return null;
        }
        return String.join("\n\n", parts);
    }

    private String resolveProviderReadableFailure(Map<String, Object> latestArtifactMetadata,
                                                  Map<String, Object> taskMetadata) {
        return firstNonBlank(
            blankToNull(metadataString(latestArtifactMetadata, "provider_error")),
            blankToNull(metadataString(taskMetadata, "provider_error")),
            blankToNull(metadataString(latestArtifactMetadata, "provider_failure_reason")),
            blankToNull(metadataString(taskMetadata, "provider_failure_reason")),
            blankToNull(metadataString(latestArtifactMetadata, "failure_summary_readable")),
            blankToNull(metadataString(taskMetadata, "failure_summary_readable"))
        );
    }

    private boolean hasProviderFailureDiagnostics(Map<String, Object> metadata) {
        return blankToNull(metadataString(metadata, "provider_error")) != null
            || blankToNull(metadataString(metadata, "provider_failure_class")) != null
            || blankToNull(metadataString(metadata, "provider_failure_reason")) != null;
    }

    private void putIfAbsent(List<String> parts, String value) {
        String normalized = blankToNull(value);
        if (normalized == null || parts.contains(normalized)) {
            return;
        }
        parts.add(normalized);
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

    private String taskActionLabel(String action) {
        String normalized = blankToNull(action);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "pause" -> "已暂停";
            case "resume" -> "已恢复执行";
            case "continue" -> "已继续推进";
            case "escalate" -> "已升级到人工确认";
            case "handoff" -> "已移交";
            default -> "已执行 " + normalized;
        };
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

    private void attachHandoffPacketMetadata(Map<String, Object> target, HandoffPacket handoffPacket) {
        if (target == null || handoffPacket == null || handoffPacket.metadata() == null || handoffPacket.metadata().isEmpty()) {
            return;
        }
        copyMetadataKey(handoffPacket.metadata(), target, "prompt_rendering_mode");
        copyMetadataKey(handoffPacket.metadata(), target, "mounted_context_mode");
        copyMetadataKey(handoffPacket.metadata(), target, "prompt_mode");
        copyMetadataKey(handoffPacket.metadata(), target, "runtime_facts");
        copyMetadataKey(handoffPacket.metadata(), target, "runtime_cognition_surface");
        Map<String, Object> runtimeCognitionSurface = metadataMap(handoffPacket.metadata(), "runtime_cognition_surface");
        Map<String, Object> routeSurface = metadataMap(runtimeCognitionSurface, "route");
        Map<String, Object> executionSurface = metadataMap(runtimeCognitionSurface, "execution");
        copyMetadataKey(routeSurface, target, "route_source");
        copyMetadataKey(routeSurface, target, "candidate_workers");
        copyMetadataKey(executionSurface, target, "execution_status");
        copyMetadataKey(executionSurface, target, "tool_invocation_ids");
        copyMetadataKey(executionSurface, target, "evidence_refs");
        copyMetadataKey(executionSurface, target, "unfinished_items");
        copyMetadataKey(executionSurface, target, "proof_summary");
        copyMetadataKey(executionSurface, target, "mounted_context_rendered");
        copyMetadataKey(executionSurface, target, "mounted_render_used");
        copyMetadataKey(executionSurface, target, "mounted_context_injected");
        copyMetadataKey(executionSurface, target, "mounted_context_panel_count");
        copyMetadataKey(executionSurface, target, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT);
        copyMetadataKey(executionSurface, target, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT);
        copyMetadataKey(executionSurface, target, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT);
        copyMetadataKey(executionSurface, target, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT);
        copyMetadataKey(executionSurface, target, MountedContextPromptBudgetSupport.BUDGET_TRUNCATED);
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
        copyMetadataKey(metadata, target, "evaluator_role");
        copyMetadataKey(metadata, target, "evaluator_model_tier");
        copyMetadataKey(metadata, target, "evaluator_reason");
        copyMetadataKey(metadata, target, "evaluation_reason");
        copyMetadataKey(metadata, target, "orchestration_closed_loop_observed");
        copyMetadataKey(metadata, target, "orchestration_proof_summary");
        copyMetadataKey(metadata, target, "tool_execution_mode");
        copyMetadataKey(metadata, target, "tool_chain_step_count");
        copyMetadataKey(metadata, target, "tool_chain_termination_reason");
        copyMetadataKey(metadata, target, "tool_chain_trace_summary");
        copyMetadataKey(metadata, target, "tool_chain_tools");
    }

    private List<Map<String, Object>> routeSkippedWorkerMetadata(List<WorkerRouter.RouteSkippedWorker> skippedWorkers) {
        if (skippedWorkers == null || skippedWorkers.isEmpty()) {
            return List.of();
        }
        return skippedWorkers.stream()
            .map(skipped -> {
                LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
                putIfNonBlank(metadata, "worker_id", skipped.workerId());
                putIfNonBlank(metadata, "reason", skipped.reason());
                putIfNonBlank(metadata, "provider_failure_class", skipped.providerFailureClass());
                putIfNonBlank(metadata, "provider_failure_reason", skipped.providerFailureReason());
                if (skipped.providerRetryable() != null) {
                    metadata.put("provider_retryable", skipped.providerRetryable());
                }
                return (Map<String, Object>) metadata;
            })
            .filter(metadata -> !metadata.isEmpty())
            .toList();
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

    private record RecoveryFailureEvidence(
        String providerFailureClass,
        String source,
        String evidence
    ) {}
}
