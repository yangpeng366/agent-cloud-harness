package com.agentcloud.engine;

import com.agentcloud.engine.memory.PacketBuilder;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.*;
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
    private final AgentRunService agentRunService;
    private final RuntimeFactSetAssembler runtimeFactSetAssembler;

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
        this.runtimeFactSetAssembler = new RuntimeFactSetAssembler(runtimeContextBuilder, toolInvocationDao, router);
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
        var routePreview = facts.routePreview();
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
            ? sessionMessageDao.listBySessionAndTask(task.sessionId(), task.id(), boundedLimit)
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
        RuntimeFactSet runtimeFacts = facts != null ? facts : RuntimeFactSet.empty(null);
        WorkerRouter.RouteResult routePreview = runtimeFacts.routePreview();
        RuntimeFactSet.ExecutionBoundary executionBoundary = runtimeFacts.executionBoundary();
        Decision executionJudgment = runtimeFacts.executionJudgment();
        Decision completionJudgment = runtimeFacts.completionJudgment();
        Map<String, Object> runtimeMetadata = runtimeFacts.metadata();
        Map<String, Object> executionMetadata = executionBoundary != null ? executionBoundary.metadata() : Map.of();

        RuntimeCognitionSurfaceView.RouteSurface routeSurface = routePreview == null ? null
            : new RuntimeCognitionSurfaceView.RouteSurface(
                blankToNull(routePreview.selectedWorker()),
                blankToNull(routePreview.routeSource()),
                blankToNull(routePreview.selectedModelTier()),
                blankToNull(routePreview.selectedExecutionRole()),
                blankToNull(routePreview.selectionScope()),
                routePreview.candidateWorkers() == null ? List.of() : routePreview.candidateWorkers(),
                blankToNull(routePreview.preferredWorkerHint()),
                routePreview.learningHintApplied(),
                blankToNull(routePreview.fallbackReason())
            );

        RuntimeCognitionSurfaceView.ExecutionSurface executionSurface = executionBoundary == null ? null
            : new RuntimeCognitionSurfaceView.ExecutionSurface(
                firstNonBlank(
                    blankToNull(executionBoundary.workerId()),
                    metadataString(executionMetadata, "selected_worker")
                ),
                blankToNull(executionBoundary.executionId()),
                blankToNull(executionBoundary.executionStatus()),
                executionBoundary.durationMs(),
                executionBoundary.toolInvocationCount(),
                blankToNull(executionBoundary.traceSummary()),
                firstNonBlank(
                    metadataString(executionMetadata, "prompt_mode"),
                    metadataString(runtimeMetadata, "prompt_mode")
                ),
                metadataBoolean(executionMetadata, "mounted_context_rendered", runtimeMetadata),
                metadataBoolean(executionMetadata, "mounted_render_used", runtimeMetadata),
                metadataBoolean(executionMetadata, "mounted_context_injected", runtimeMetadata),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_context_panel_count"),
                    metadataInteger(runtimeMetadata, "mounted_context_panel_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_context_non_empty_panel_count"),
                    metadataInteger(runtimeMetadata, "mounted_context_non_empty_panel_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_context_selection_trace_count"),
                    metadataInteger(runtimeMetadata, "mounted_context_selection_trace_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, MountedContextPromptBudgetSupport.RENDERED_PANEL_COUNT),
                    metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.RENDERED_PANEL_COUNT)
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, MountedContextPromptBudgetSupport.HIDDEN_PANEL_COUNT),
                    metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.HIDDEN_PANEL_COUNT)
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT),
                    metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT)
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT),
                    metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT)
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT),
                    metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT)
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT),
                    metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT)
                ),
                metadataBoolean(executionMetadata, MountedContextPromptBudgetSupport.BUDGET_TRUNCATED, runtimeMetadata),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_pinned_count"),
                    metadataInteger(runtimeMetadata, "mounted_pinned_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_active_count"),
                    metadataInteger(runtimeMetadata, "mounted_active_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_ancestor_count"),
                    metadataInteger(runtimeMetadata, "mounted_ancestor_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_sibling_count"),
                    metadataInteger(runtimeMetadata, "mounted_sibling_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_evidence_count"),
                    metadataInteger(runtimeMetadata, "mounted_evidence_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_index_count"),
                    metadataInteger(runtimeMetadata, "mounted_index_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_archive_count"),
                    metadataInteger(runtimeMetadata, "mounted_archive_count")
                ),
                metadataStringList(executionMetadata, "evidence_refs").isEmpty()
                    ? metadataStringList(runtimeMetadata, "evidence_refs")
                    : metadataStringList(executionMetadata, "evidence_refs"),
                metadataStringList(executionMetadata, "unfinished_items").isEmpty()
                    ? metadataStringList(runtimeMetadata, "unfinished_items")
                    : metadataStringList(executionMetadata, "unfinished_items")
            );

        RuntimeCognitionSurfaceView.JudgmentSurface executionJudgmentSurface =
            buildJudgmentSurface(executionJudgment, runtimeMetadata);
        RuntimeCognitionSurfaceView.JudgmentSurface completionJudgmentSurface =
            buildJudgmentSurface(completionJudgment, runtimeMetadata);

        String routedWorker = routeSurface != null ? routeSurface.selectedWorker() : null;
        String executedWorker = executionSurface != null ? executionSurface.workerId() : null;
        String executionPromptMode = executionSurface != null ? executionSurface.promptMode() : null;
        String executionJudgmentPromptMode = executionJudgmentSurface != null ? executionJudgmentSurface.promptMode() : null;
        String completionJudgmentPromptMode = completionJudgmentSurface != null ? completionJudgmentSurface.promptMode() : null;

        RuntimeCognitionSurfaceView.AlignmentSurface alignment = new RuntimeCognitionSurfaceView.AlignmentSurface(
            alignmentFlag(routedWorker, executedWorker),
            alignmentFlag(executionPromptMode, executionJudgmentPromptMode),
            alignmentFlag(executionPromptMode, completionJudgmentPromptMode)
        );

        return new RuntimeCognitionSurfaceView(
            routeSurface,
            executionSurface,
            executionJudgmentSurface,
            completionJudgmentSurface,
            alignment
        );
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
            null,
            null,
            null,
            null,
            null,
            route.candidateWorkers() == null ? List.of() : route.candidateWorkers(),
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
            execution.mountedContextRendered(),
            execution.mountedContextInjected(),
            execution.mountedContextPanelCount(),
            execution.mountedContextRenderedObjectCount(),
            execution.mountedContextHiddenObjectCount(),
            execution.mountedContextRenderedSelectionTraceCount(),
            execution.mountedContextHiddenSelectionTraceCount(),
            execution.mountedContextBudgetTruncated(),
            null,
            List.of(),
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
            surface.mountedContextRendered(),
            surface.mountedContextInjected(),
            surface.mountedContextPanelCount(),
            surface.mountedContextRenderedObjectCount(),
            surface.mountedContextHiddenObjectCount(),
            surface.mountedContextRenderedSelectionTraceCount(),
            surface.mountedContextHiddenSelectionTraceCount(),
            surface.mountedContextBudgetTruncated(),
            alignmentFlag(executionPromptMode, judgmentPromptMode),
            surface.candidateWorkers() == null ? List.of() : surface.candidateWorkers(),
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
        String workerId = firstNonBlank(
            metadataString(payload, "assigned_worker"),
            metadataString(payload, "current_worker"),
            metadataString(payload, "previous_worker")
        );
        String promptMode = firstNonBlank(
            metadataString(payload, "prompt_mode"),
            metadataString(payload, "mounted_context_mode"),
            metadataString(payload, "prompt_rendering_mode")
        );
        String targetWorker = metadataString(payload, "target_worker");
        String reason = metadataString(payload, "reason");
        String summary = firstNonBlank(
            summarizeControlActionTimeline(action, workerId, targetWorker, promptMode, reason),
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
            null,
            null,
            null,
            metadataBoolean(payload, "mounted_context_rendered", Map.of()),
            metadataBoolean(payload, "mounted_context_injected", Map.of()),
            metadataInteger(payload, "mounted_context_panel_count"),
            metadataInteger(payload, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT),
            metadataInteger(payload, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT),
            metadataInteger(payload, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT),
            metadataInteger(payload, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT),
            metadataBoolean(payload, MountedContextPromptBudgetSupport.BUDGET_TRUNCATED, Map.of()),
            null,
            metadataStringList(payload, "candidate_workers"),
            metadataStringList(payload, "evidence_refs"),
            metadataStringList(payload, "unfinished_items"),
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
        String workerId = firstNonBlank(
            metadataString(refinedPacket, "assigned_worker"),
            metadataString(metadata, "assigned_worker")
        );
        String promptMode = firstNonBlank(
            metadataString(refinedPacket, "prompt_mode"),
            metadataString(refinedPacket, "mounted_context_mode"),
            metadataString(refinedPacket, "prompt_rendering_mode")
        );
        String summary = firstNonBlank(
            summarizeCheckpointTimeline(checkpointType, workerId, promptMode, checkpoint.consolidationSummary()),
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
            metadataStringList(refinedPacket, "candidate_workers"),
            metadataStringList(refinedPacket, "evidence_refs"),
            metadataStringList(refinedPacket, "open_questions"),
            summary
        );
    }

    private RuntimeCognitionTimelineEntryView buildResumePacketTimelineEntry(ResumePacket resumePacket) {
        if (resumePacket == null) {
            return null;
        }
        Map<String, Object> payload = resumePacket.payload() == null ? Map.of() : resumePacket.payload();
        String workerId = firstNonBlank(
            blankToNull(resumePacket.assignedWorker()),
            metadataString(payload, "assigned_worker")
        );
        String promptMode = firstNonBlank(
            metadataString(payload, "prompt_mode"),
            metadataString(payload, "mounted_context_mode"),
            metadataString(payload, "prompt_rendering_mode")
        );
        String reason = firstNonBlank(
            metadataString(payload, "resume_hint"),
            blankToNull(resumePacket.nextStep())
        );
        String summary = firstNonBlank(
            summarizeResumePacketTimeline(resumePacket, workerId, promptMode),
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
            null,
            blankToNull(resumePacket.currentStatus()),
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
            metadataStringList(payload, "candidate_workers"),
            metadataStringList(payload, "evidence_refs"),
            resumePacket.openQuestions() == null ? List.of() : resumePacket.openQuestions(),
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
        String tools = execution.toolInvocationCount() == null ? null : execution.toolInvocationCount() + " tools";
        String budget = mountedBudgetSummary(
            execution.mountedContextRenderedObjectCount(),
            execution.mountedContextHiddenObjectCount(),
            execution.mountedContextRenderedSelectionTraceCount(),
            execution.mountedContextHiddenSelectionTraceCount(),
            execution.mountedContextBudgetTruncated()
        );
        return firstNonBlank(
            joinSummary(status, promptMode, tools, budget, trace),
            joinSummary(status, promptMode, budget, trace),
            status
        );
    }

    private String summarizeJudgmentTimeline(Decision decision,
                                             RuntimeCognitionSurfaceView.JudgmentSurface surface) {
        String promptMode = surface != null ? blankToNull(surface.promptMode()) : null;
        String action = decision != null ? metadataString(decision.metadata(), "action") : null;
        String status = decision != null ? metadataString(decision.metadata(), "status") : null;
        String budget = surface == null ? null : mountedBudgetSummary(
            surface.mountedContextRenderedObjectCount(),
            surface.mountedContextHiddenObjectCount(),
            surface.mountedContextRenderedSelectionTraceCount(),
            surface.mountedContextHiddenSelectionTraceCount(),
            surface.mountedContextBudgetTruncated()
        );
        return firstNonBlank(
            joinSummary(promptMode, action, status, budget),
            joinSummary(promptMode, status, budget),
            promptMode
        );
    }

    private String summarizeControlActionTimeline(String action,
                                                  String workerId,
                                                  String targetWorker,
                                                  String promptMode,
                                                  String reason) {
        String workerTransition = joinArrow(workerId, targetWorker);
        return firstNonBlank(
            joinSummary(action, workerTransition, promptMode, reason),
            joinSummary(action, workerTransition, promptMode),
            action
        );
    }

    private String summarizeCheckpointTimeline(String checkpointType,
                                               String workerId,
                                               String promptMode,
                                               String consolidationSummary) {
        return firstNonBlank(
            joinSummary(checkpointType, workerId, promptMode),
            joinSummary(checkpointType, workerId),
            blankToNull(consolidationSummary),
            checkpointType
        );
    }

    private String summarizeResumePacketTimeline(ResumePacket resumePacket,
                                                 String workerId,
                                                 String promptMode) {
        if (resumePacket == null) {
            return null;
        }
        String currentNode = blankToNull(resumePacket.currentNode());
        String currentStatus = blankToNull(resumePacket.currentStatus());
        String nextStep = blankToNull(resumePacket.nextStep());
        return firstNonBlank(
            joinSummary("resume packet", workerId, currentNode, currentStatus, promptMode, nextStep),
            joinSummary("resume packet", workerId, currentNode, currentStatus, promptMode),
            joinSummary("resume packet", workerId, currentNode, currentStatus),
            joinSummary("resume packet", workerId, nextStep),
            "resume packet"
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

    private RuntimeCognitionSurfaceView.JudgmentSurface buildJudgmentSurface(Decision decision,
                                                                             Map<String, Object> runtimeMetadata) {
        if (decision == null) {
            return null;
        }
        Map<String, Object> decisionMetadata = decision.metadata() == null ? Map.of() : decision.metadata();
        return new RuntimeCognitionSurfaceView.JudgmentSurface(
            firstNonBlank(
                metadataString(decisionMetadata, "prompt_mode"),
                metadataString(runtimeMetadata, "prompt_mode")
            ),
            metadataBoolean(decisionMetadata, "mounted_context_rendered", runtimeMetadata),
            metadataBoolean(decisionMetadata, "mounted_render_used", runtimeMetadata),
            metadataBoolean(decisionMetadata, "mounted_context_injected", runtimeMetadata),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_context_panel_count"),
                metadataInteger(runtimeMetadata, "mounted_context_panel_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_context_non_empty_panel_count"),
                metadataInteger(runtimeMetadata, "mounted_context_non_empty_panel_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_context_selection_trace_count"),
                metadataInteger(runtimeMetadata, "mounted_context_selection_trace_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, MountedContextPromptBudgetSupport.RENDERED_PANEL_COUNT),
                metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.RENDERED_PANEL_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, MountedContextPromptBudgetSupport.HIDDEN_PANEL_COUNT),
                metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.HIDDEN_PANEL_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT),
                metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT),
                metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT),
                metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT),
                metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT)
            ),
            metadataBoolean(decisionMetadata, MountedContextPromptBudgetSupport.BUDGET_TRUNCATED, runtimeMetadata),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_pinned_count"),
                metadataInteger(runtimeMetadata, "mounted_pinned_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_active_count"),
                metadataInteger(runtimeMetadata, "mounted_active_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_ancestor_count"),
                metadataInteger(runtimeMetadata, "mounted_ancestor_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_sibling_count"),
                metadataInteger(runtimeMetadata, "mounted_sibling_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_evidence_count"),
                metadataInteger(runtimeMetadata, "mounted_evidence_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_index_count"),
                metadataInteger(runtimeMetadata, "mounted_index_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_archive_count"),
                metadataInteger(runtimeMetadata, "mounted_archive_count")
            ),
            metadataStringList(decisionMetadata, "candidate_workers").isEmpty()
                ? metadataStringList(runtimeMetadata, "candidate_workers")
                : metadataStringList(decisionMetadata, "candidate_workers"),
            metadataStringList(decisionMetadata, "evidence_refs").isEmpty()
                ? metadataStringList(runtimeMetadata, "evidence_refs")
                : metadataStringList(decisionMetadata, "evidence_refs"),
            metadataStringList(decisionMetadata, "unfinished_items").isEmpty()
                ? metadataStringList(runtimeMetadata, "unfinished_items")
                : metadataStringList(decisionMetadata, "unfinished_items")
        );
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

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
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
            if (facts.recommendedAction() != null) {
                metadata.put("judgment_action", facts.recommendedAction());
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

    private String summarizeProgress(Task task, RuntimeFactSet facts, Artifact latestArtifact) {
        TaskRuntimeContext runtimeContext = facts != null ? facts.runtimeContext() : null;
        Decision executionJudgment = facts != null ? facts.executionJudgment() : null;
        Decision completionJudgment = facts != null ? facts.completionJudgment() : null;
        return shorten(
            firstNonBlank(
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
