package com.agentcloud.engine;

import com.agentcloud.engine.memory.PacketBuilder;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.judgment.JudgmentContext;
import com.agentcloud.judgment.JudgmentService;
import com.agentcloud.model.*;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.context.MountedContextPromptRenderResult;
import com.agentcloud.runtime.context.MountedContextPromptBudgetSupport;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.runtime.context.MountedContextPanelName;
import com.agentcloud.runtime.context.MountedContextPromptMetrics;
import com.agentcloud.runtime.context.MountedContextPromptRenderer;
import com.agentcloud.runtime.context.PromptRenderingMode;
import com.agentcloud.runtime.model.RuntimeFactSet;
import com.agentcloud.store.*;
import com.agentcloud.worker.WorkerExecutor;
import com.agentcloud.worker.WorkerExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runtime Control Node Graph
 * 6 个最小控制节点：Intake → Scheduler → Continue → [Packet / Human Gate / Handoff]
 */
public class ControlNodeGraph {
    private static final Logger log = LoggerFactory.getLogger(ControlNodeGraph.class);
    private static final MountedContextPromptRenderer JUDGMENT_PROMPT_RENDERER = new MountedContextPromptRenderer();
    private static final int FAILURE_SUMMARY_LIMIT = 220;
    private static final int PLANNER_DELEGATION_OUTPUT_LIMIT = 12_000;
    private static final long DEFAULT_WORKER_EXECUTION_TIMEOUT_SECONDS = 300;
    /**
     * strong tier worker（如 codex 经 CCX 网关做复杂多文件编码）的单轮默认超时（秒）。
     * 本地 agent_runs 历史依据（2026-07-27）：codex n=43，p50=111s / p90=163s / p95=331s / max=761s，
     * 旧 120s 硬超时砍掉 49% 的 codex 轮次；600s 覆盖 p95+margin，仅约 5% 的超长复杂轮次进入回收。
     */
    private static final long STRONG_TIER_WORKER_TIMEOUT_SECONDS = 600;
    private static final long MIN_WORKER_EXECUTION_TIMEOUT_SECONDS = 30;
    /**
     * 单轮 worker 执行超时（秒）。可通过 {@code -Dharness.worker.timeout.seconds} 或环境变量
     * {@code HARNESS_WORKER_TIMEOUT_SECONDS} 显式覆盖（最小 30s，绝对权威，忽略 tier 校准）；
     * 未显式覆盖时由 {@link #effectiveWorkerTimeoutSeconds(String)} 按 worker model_tier 取数据校准默认。
     */
    static long resolveWorkerExecutionTimeoutSeconds() {
        long override = explicitWorkerTimeoutOverrideSeconds();
        return override >= 0 ? override : DEFAULT_WORKER_EXECUTION_TIMEOUT_SECONDS;
    }
    /**
     * @return 显式覆盖值（>= {@value #MIN_WORKER_EXECUTION_TIMEOUT_SECONDS}）；未设置/非法/低于下限时返回 -1，
     *         由调用方回退到 tier 校准默认。
     */
    static long explicitWorkerTimeoutOverrideSeconds() {
        String raw = System.getProperty("harness.worker.timeout.seconds");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("HARNESS_WORKER_TIMEOUT_SECONDS");
        }
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed >= MIN_WORKER_EXECUTION_TIMEOUT_SECONDS ? parsed : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
    /**
     * 运行时实际生效的单轮超时：显式覆盖绝对优先；否则按 worker model_tier 取数据校准默认
     * （strong tier=600s，其余=300s）。无 worker 注册信息时回退通用默认。
     */
    long effectiveWorkerTimeoutSeconds(String workerId) {
        long override = explicitWorkerTimeoutOverrideSeconds();
        if (override >= 0) {
            return override;
        }
        Worker worker = router != null ? router.getWorker(workerId) : null;
        String tier = worker != null ? metadataString(worker.metadata(), "model_tier") : null;
        if ("strong".equalsIgnoreCase(tier)) {
            return STRONG_TIER_WORKER_TIMEOUT_SECONDS;
        }
        return DEFAULT_WORKER_EXECUTION_TIMEOUT_SECONDS;
    }
    private static final int MAX_HANDOFF_DEPTH = 3;
    private static final Pattern THREAD_NOT_FOUND_EN_WITH_PARENS = Pattern.compile("thread not found\\s*\\(([\\w-]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern THREAD_NOT_FOUND_EN = Pattern.compile("thread not found\\s*[:：]?\\s*([\\w-]+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern THREAD_NOT_FOUND_ZH = Pattern.compile("(没找到线程|未找到线程)\\s*[\"“]?([\\w-]+)[\"”]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_THREAD_ID = Pattern.compile("[\"“]([\\w-]+)[\"”]");
    private static final Pattern PROVIDER_UNAVAILABLE = Pattern.compile("provider unavailable", Pattern.CASE_INSENSITIVE);
    private static final Pattern SESSION_EXPIRED = Pattern.compile("session expired", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONNECTION_RESET = Pattern.compile("connection reset", Pattern.CASE_INSENSITIVE);
    private static final Pattern FAILED_TO_START = Pattern.compile("failed to start(?:\\s+[\\w-]+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMEOUT = Pattern.compile("\\btimeout\\b|timed out", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_NOT_FOUND = Pattern.compile(
        "\\b(no such file|file not found|directory not found|path not found|enoent|not recognized as an internal or external command|command not found|permission denied|access is denied|access denied|权限不足|拒绝访问|文件不存在|目录不存在|命令不存在)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BACKEND_UNSUPPORTED = Pattern.compile(
        "\\b(unsupported|not supported|capability missing|missing capability|tool unsupported|does not support|provider does not support|backend does not support|model does not support|工具不支持|能力不足|不支持当前模式|不支持该工具)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private final TaskDao taskDao;
    private final EventDao eventDao;
    private final SessionDao sessionDao;
    private final SessionMessageDao sessionMessageDao;
    private final ResumePacketDao packetDao;
    private final WorkerRouter router;
    private final PacketBuilder packetBuilder;
    private final ConsolidationService consolidation;
    private final WorkerExecutor workerExecutor;
    private final TaskRuntimeContextBuilder runtimeContextBuilder;
    private final JudgmentService judgmentService;
    private final ArtifactDao artifactDao;
    private final DecisionDao decisionDao;
    private final LearningMemoryService learningMemoryService;
    private final AgentRunService agentRunService;
    private final AgentActionReconciler agentActionReconciler;
    private final LlmSubgoalJudgmentService llmSubgoalJudgmentService;

    public ControlNodeGraph(TaskDao taskDao, EventDao eventDao, SessionDao sessionDao,
                            ResumePacketDao packetDao, WorkerRouter router, PacketBuilder packetBuilder,
                            ConsolidationService consolidation,
                            WorkerExecutor workerExecutor, TaskRuntimeContextBuilder runtimeContextBuilder,
                            JudgmentService judgmentService,
                            ArtifactDao artifactDao, DecisionDao decisionDao,
                            LearningMemoryService learningMemoryService) {
        this(taskDao, eventDao, sessionDao, null, packetDao, router, packetBuilder, consolidation,
            workerExecutor, runtimeContextBuilder, judgmentService, artifactDao, decisionDao,
            learningMemoryService, null, null);
    }

    public ControlNodeGraph(TaskDao taskDao, EventDao eventDao, SessionDao sessionDao,
                            ResumePacketDao packetDao, WorkerRouter router, PacketBuilder packetBuilder,
                            ConsolidationService consolidation,
                            WorkerExecutor workerExecutor, TaskRuntimeContextBuilder runtimeContextBuilder,
                            JudgmentService judgmentService,
                            ArtifactDao artifactDao, DecisionDao decisionDao,
                            LearningMemoryService learningMemoryService,
                            AgentRunService agentRunService) {
        this(taskDao, eventDao, sessionDao, null, packetDao, router, packetBuilder, consolidation,
            workerExecutor, runtimeContextBuilder, judgmentService, artifactDao, decisionDao,
            learningMemoryService, agentRunService, null);
    }

    public ControlNodeGraph(TaskDao taskDao, EventDao eventDao, SessionDao sessionDao,
                            ResumePacketDao packetDao, WorkerRouter router, PacketBuilder packetBuilder,
                            ConsolidationService consolidation,
                            WorkerExecutor workerExecutor, TaskRuntimeContextBuilder runtimeContextBuilder,
                            JudgmentService judgmentService,
                            ArtifactDao artifactDao, DecisionDao decisionDao,
                            LearningMemoryService learningMemoryService,
                            AgentRunService agentRunService,
                            AgentActionReconciler agentActionReconciler) {
        this(taskDao, eventDao, sessionDao, null, packetDao, router, packetBuilder, consolidation,
            workerExecutor, runtimeContextBuilder, judgmentService, artifactDao, decisionDao,
            learningMemoryService, agentRunService, agentActionReconciler);
    }

    public ControlNodeGraph(TaskDao taskDao, EventDao eventDao, SessionDao sessionDao,
                            SessionMessageDao sessionMessageDao,
                            ResumePacketDao packetDao, WorkerRouter router, PacketBuilder packetBuilder,
                            ConsolidationService consolidation,
                            WorkerExecutor workerExecutor, TaskRuntimeContextBuilder runtimeContextBuilder,
                            JudgmentService judgmentService,
                            ArtifactDao artifactDao, DecisionDao decisionDao,
                            LearningMemoryService learningMemoryService) {
        this(taskDao, eventDao, sessionDao, sessionMessageDao, packetDao, router, packetBuilder, consolidation,
            workerExecutor, runtimeContextBuilder, judgmentService, artifactDao, decisionDao,
            learningMemoryService, null, null);
    }

    public ControlNodeGraph(TaskDao taskDao, EventDao eventDao, SessionDao sessionDao,
                            SessionMessageDao sessionMessageDao,
                            ResumePacketDao packetDao, WorkerRouter router, PacketBuilder packetBuilder,
                            ConsolidationService consolidation,
                            WorkerExecutor workerExecutor, TaskRuntimeContextBuilder runtimeContextBuilder,
                            JudgmentService judgmentService,
                            ArtifactDao artifactDao, DecisionDao decisionDao,
                            LearningMemoryService learningMemoryService,
                            AgentRunService agentRunService) {
        this(taskDao, eventDao, sessionDao, sessionMessageDao, packetDao, router, packetBuilder, consolidation,
            workerExecutor, runtimeContextBuilder, judgmentService, artifactDao, decisionDao,
            learningMemoryService, agentRunService, null);
    }

    public ControlNodeGraph(TaskDao taskDao, EventDao eventDao, SessionDao sessionDao,
                            SessionMessageDao sessionMessageDao,
                            ResumePacketDao packetDao, WorkerRouter router, PacketBuilder packetBuilder,
                            ConsolidationService consolidation,
                            WorkerExecutor workerExecutor, TaskRuntimeContextBuilder runtimeContextBuilder,
                            JudgmentService judgmentService,
                            ArtifactDao artifactDao, DecisionDao decisionDao,
                            LearningMemoryService learningMemoryService,
                            AgentRunService agentRunService,
                            AgentActionReconciler agentActionReconciler) {
        this.taskDao = taskDao;
        this.eventDao = eventDao;
        this.sessionDao = sessionDao;
        this.sessionMessageDao = sessionMessageDao;
        this.packetDao = packetDao;
        this.router = router;
        this.packetBuilder = packetBuilder;
        this.consolidation = consolidation;
        this.workerExecutor = workerExecutor;
        this.runtimeContextBuilder = runtimeContextBuilder;
        this.judgmentService = judgmentService;
        this.artifactDao = artifactDao;
        this.decisionDao = decisionDao;
        this.learningMemoryService = learningMemoryService;
        this.agentRunService = agentRunService;
        this.agentActionReconciler = agentActionReconciler;
        this.llmSubgoalJudgmentService = null;
    }

    public Task enter(Task task) {
        String node = task.controlNode();
        if (node == null) node = "intake";
        return switch (node) {
            case "intake" -> intakeNode(task);
            case "scheduler" -> schedulerNode(task);
            case "continue" -> continueNode(task);
            case "packet" -> packetNode(task);
            case "human_gate" -> humanGateNode(task);
            case "handoff" -> handoffNode(task);
            default -> {
                log.warn("Unknown control node: {}, task={}", node, task.id());
                yield task;
            }
        };
    }

    // === Intake Node ===
    private Task intakeNode(Task task) {
        log.info("[Intake] task={}", task.id());
        emitEvent(task, "node_intake", "Enter intake node");
        Task moved = task.withControlNode("scheduler");
        taskDao.updateState(moved);
        return schedulerNode(moved);
    }

    // === Scheduler Node ===
    private Task schedulerNode(Task task) {
        Task normalizedTask = normalizeWorkerAssignmentMetadata(task);
        if (!sameState(task, normalizedTask)) {
            log.info(
                "[Scheduler] task={} normalized worker metadata assignedWorker={} metadataAssignedWorker={} metadataTargetWorker={}",
                task != null ? task.id() : null,
                normalizedTask != null ? normalizedTask.assignedWorker() : null,
                metadataString(normalizedTask != null ? normalizedTask.metadata() : null, "assigned_worker"),
                metadataString(normalizedTask != null ? normalizedTask.metadata() : null, "target_worker")
            );
            taskDao.updateState(normalizedTask);
            task = normalizedTask;
        }
        log.info("[Scheduler] task={}", task.id());
        emitEvent(task, "node_scheduler", "Scheduling task");
        WorkerExecutionResult executionResult = null;
        WorkerRouter.RouteResult route = null;

        // 分发前做主动验活，失败后临时摘除该 worker 并重新路由。
        for (int dispatchAttempt = 0; dispatchAttempt < 3; dispatchAttempt++) {
            if (task.assignedWorker() == null || task.assignedWorker().isBlank()) {
                route = router.selectWorker(task);
                if (route.selectedWorker() != null) {
                    task = syncAssignedWorkerMetadata(task.withAssignedWorker(route.selectedWorker()));
                    log.info("[Scheduler] task={} routed to worker={}", task.id(), route.selectedWorker());
                } else if (route.manualWindowRequired()) {
                    Task gated = applyManualWindowGate(task, route);
                    taskDao.updateState(gated);
                    return humanGateNode(gated);
                }
            }
            Task checkedTask = ensureDispatchReadyBeforeExecution(task);
            if (checkedTask.assignedWorker() != null && !checkedTask.assignedWorker().isBlank()) {
                task = checkedTask;
                break;
            }
            boolean changedByPreflight = !sameState(task, checkedTask);
            task = checkedTask;
            if (!changedByPreflight) {
                break;
            }
            route = null;
        }

        // 执行一轮 worker work
        if (task.assignedWorker() != null) {
            Worker selectedWorker = router.getWorker(task.assignedWorker());
            String selectedWorkerType = selectedWorker != null ? selectedWorker.workerType() : null;
            String selectedModelTier = workerMetadata(selectedWorker, "model_tier");
            String executionRole = workerMetadata(selectedWorker, "primary_role");
            String whySelected = route != null
                ? firstNonBlank(route.whySelected(), route.routeReason())
                : resolvePreassignedWorkerReason(task);
            String fallbackReason = route != null ? route.fallbackReason() : null;
            log.info("[Scheduler] task={} building runtime context for worker={}", task.id(), task.assignedWorker());
            TaskRuntimeContext ctx = runtimeContextBuilder.build(task);
            log.info("[Scheduler] task={} executing one round with worker={}", task.id(), task.assignedWorker());
            Instant runStartedAt = Instant.now();
            AgentRunRecord agentRun = null;
            long entryInstance = execInstance(task);
            try {
                executionResult = executeOneRoundWithTimeout(ctx, task.assignedWorker());
                Task currentTaskState = taskDao.findById(task.id()).orElse(task);
                if (execInstance(currentTaskState) != entryInstance) {
                    log.info("[Scheduler] task={} stale worker round discarded (success): exec_instance {} -> {} (concurrent control action)",
                        task.id(), entryInstance, execInstance(currentTaskState));
                    emitEvent(currentTaskState, "worker_round",
                        "Stale worker round discarded: task state changed by concurrent control action",
                        metadataOf(
                            "control_node", "worker_round",
                            "stale_round_discarded", true,
                            "entry_instance", entryInstance,
                            "current_instance", execInstance(currentTaskState),
                            "selected_worker", task.assignedWorker()
                        ));
                    return currentTaskState;
                }
                executionResult = enrichCurrentRoundWorkerMetadata(
                    task,
                    route,
                    selectedWorker,
                    selectedWorkerType,
                    selectedModelTier,
                    executionRole,
                    whySelected,
                    fallbackReason,
                    executionResult
                );
                Task updatedTask = mergeProviderContinuationMetadata(task, executionResult);
                if (!sameState(task, updatedTask)) {
                    taskDao.updateState(updatedTask);
                    task = updatedTask;
                }
                agentRun = recordCompletedAgentRun(task, route, selectedWorker, executionResult, runStartedAt, Instant.now());
            } catch (RuntimeException e) {
                Task currentTaskState = taskDao.findById(task.id()).orElse(task);
                if (execInstance(currentTaskState) != entryInstance) {
                    log.info("[Scheduler] task={} stale worker round discarded (failure): exec_instance {} -> {} (concurrent control action)",
                        task.id(), entryInstance, execInstance(currentTaskState));
                    emitEvent(currentTaskState, "worker_round",
                        "Stale worker round failure discarded: task state changed by concurrent control action",
                        metadataOf(
                            "control_node", "worker_round",
                            "stale_round_discarded", true,
                            "entry_instance", entryInstance,
                            "current_instance", execInstance(currentTaskState),
                            "selected_worker", task.assignedWorker()
                        ));
                    return currentTaskState;
                }
                log.warn("Worker round post-processing failed. task={} worker={}", task.id(), task.assignedWorker(), e);
                AgentRunRecord failedRun = recordFailedAgentRun(task, route, selectedWorker, runStartedAt, Instant.now(), e);
                emitEvent(task, "worker_round_failed",
                    "Worker round failed. worker=" + task.assignedWorker()
                        + " errorType=" + e.getClass().getSimpleName(),
                    metadataOf(
                        "control_node", "worker_round_failed",
                        "agent_run_id", failedRun != null ? failedRun.runId() : null,
                        "provider_id", failedRun != null ? failedRun.providerId() : null,
                        "selected_worker", task.assignedWorker(),
                        "error_type", e.getClass().getSimpleName()
                    ));
                executionResult = synthesizeFailedExecutionResult(
                    task,
                    route,
                    selectedWorker,
                    selectedWorkerType,
                    selectedModelTier,
                    executionRole,
                    whySelected,
                    fallbackReason,
                    e
                );
            }

            emitEvent(task, "worker_round",
                "Worker round completed. worker=" + task.assignedWorker()
                    + " outputLength=" + executionResult.outputText().length()
                    + " durationMs=" + executionResult.durationMs(),
                metadataOf(
                    "control_node", "worker_round",
                    "agent_run_id", agentRun != null ? agentRun.runId() : null,
                    "provider_id", agentRun != null ? agentRun.providerId() : null,
                    "selected_worker", task.assignedWorker()
                ));

            ActionReconciliationOutcome actionOutcome = reconcileAgentActions(task, executionResult);
            executionResult = actionOutcome.executionResult();
            task = applyAcceptedAgentActionFlow(task, actionOutcome.reconciliation());

            // 将输出写入 artifact
            if (hasMeaningfulOutput(executionResult) || hasAgentActionSurface(executionResult)) {
                String summary = firstNonBlank(
                    executionResult.summary(),
                    executionResult.outputText(),
                    executionResult.artifactContent(),
                    agentActionSummary(executionResult)
                );
                Artifact artifact = new Artifact(
                    IdGenerator.newId("art"), task.sessionId(), task.id(), Instant.now(),
                    executionResult.producedArtifact() ? "worker_artifact" : "worker_output",
                    executionResult.producedArtifact() && !executionResult.artifactTitle().isBlank()
                        ? executionResult.artifactTitle() : "Worker Output",
                    null, null, summary,
                    buildWorkerArtifactMetadata(
                        executionResult,
                        "worker_id", task.assignedWorker(),
                        "agent_run_id", agentRun != null ? agentRun.runId() : null,
                        "provider_id", agentRun != null ? agentRun.providerId() : null,
                        "agent_run_status", agentRun != null ? agentRun.status() : null,
                        "selected_worker", task.assignedWorker(),
                        "selected_worker_type", selectedWorkerType,
                        "selected_model_tier", selectedModelTier,
                        "execution_role", executionRole,
                        "why_selected", whySelected,
                        "preferred_worker_hint", route != null ? route.preferredWorkerHint() : null,
                        "learning_hint_applied", route != null ? route.learningHintApplied() : null,
                        "fallback_reason", fallbackReason,
                        "route_source", route != null ? route.routeSource() : "preassigned",
                        "model_mode", metadataString(task.metadata(), "model_mode"),
                        "orchestration_stage", metadataString(task.metadata(), "orchestration_stage"),
                        "planner_worker", metadataString(task.metadata(), "planner_worker"),
                        "executor_worker", metadataString(task.metadata(), "executor_worker"),
                        "target_worker", metadataString(task.metadata(), "target_worker"),
                        "candidate_workers", route != null ? route.candidateWorkers() : null,
                        "duration_ms", executionResult.durationMs(),
                        "confidence", executionResult.confidence(),
                        "suggested_next_step", executionResult.suggestedNextStep(),
                        "execution_status", executionResult.executionStatus(),
                        "evidence_refs", executionResult.evidenceRefs(),
                        "unfinished_items", executionResult.unfinishedItems(),
                        "proposed_actions", executionResult.metadata().get("proposed_actions"),
                        "accepted_actions", executionResult.metadata().get("accepted_actions"),
                        "rejected_actions", executionResult.metadata().get("rejected_actions"),
                        "approval_needed_actions", executionResult.metadata().get("approval_needed_actions"),
                        "context_requests", executionResult.contextRequests(),
                        "completion_claim", executionResult.completionClaim(),
                        "handoff_target", executionResult.handoffTarget(),
                        "risk_flags", executionResult.riskFlags(),
                        "output_text", artifactOutputText(executionResult),
                        "artifact_content", executionResult.artifactContent(),
                        "parser", executionResult.metadata().getOrDefault("parser", "unknown")
                    )
                );
                artifactDao.insert(artifact);
                appendWorkerRoundMessage(task, artifact, agentRun, executionResult);
            }
        } else {
            log.warn("[Scheduler] task={} has no assigned worker, skipping execution", task.id());
            if (route != null && route.manualWindowRequired()) {
                Task gated = applyManualWindowGate(task, route);
                taskDao.updateState(gated);
                emitEvent(gated, "worker_round",
                    "No automatic worker assigned; manual window provider required",
                    metadataOf(
                        "control_node", "worker_round",
                        "manual_window_required", true,
                        "recommended_manual_provider", route.recommendedManualProvider(),
                        "manual_window_candidates", route.manualWindowCandidates(),
                        "cost_route_stage", route.costRouteStage()
                    ));
                return humanGateNode(gated);
            }
            emitEvent(task, "worker_round", "No worker assigned, execution skipped");
        }

        Task moved = task.withControlNode("continue");
        taskDao.updateState(moved);
        return continueNode(moved, executionResult);
    }

    // === Continue Node ===
    private Task continueNode(Task task) {
        return continueNode(task, null);
    }

    private Task continueNode(Task task, WorkerExecutionResult executionResult) {
        log.info("[Continue] task={} status={}", task.id(), task.status());
        emitEvent(task, "node_continue", "Continue evaluation");

        // 如果任务已被暂停/完成/失败，不走继续逻辑
        if (List.of("paused", "waiting", "waiting_human", "done", "failed").contains(task.status())) {
            log.info("[Continue] task={} is {}, halting continue loop", task.id(), task.status());
            return task;
        }

        // 构造 JudgmentContext
        TaskRuntimeContext ctx = runtimeContextBuilder.build(task);
        String latestOutput = resolveLatestOutput(ctx, executionResult);
        Map<String, Object> latestWorkerMetadata = resolveLatestWorkerMetadata(ctx, executionResult);
        String plannerRecoveryReason = plannerOutputRecoveryReason(task, latestWorkerMetadata, latestOutput);
        if (plannerRecoveryReason != null) {
            task = withMetadataEntries(task,
                "planner_delegation_gate", "rejected",
                "planner_delegation_gate_reason", plannerRecoveryReason
            );
        }
        RecoveryDirective recoveryDirective = maybePlanFailureRecovery(task, latestWorkerMetadata, latestOutput);
        Worker selectedWorker = router != null ? router.getWorker(task.assignedWorker()) : null;
        String selectedWorkerId = firstNonBlank(task.assignedWorker(), stringValue(latestWorkerMetadata.get("selected_worker")));
        String selectedModelTier = firstNonBlank(
            stringValue(latestWorkerMetadata.get("selected_model_tier")),
            workerMetadata(selectedWorker, "model_tier")
        );
        String executionRole = firstNonBlank(
            stringValue(latestWorkerMetadata.get("execution_role")),
            workerMetadata(selectedWorker, "primary_role")
        );
        String whySelected = firstNonBlank(
            stringValue(latestWorkerMetadata.get("why_selected")),
            selectedWorkerId != null ? "task assigned to worker=" + selectedWorkerId : null
        );
        String fallbackReason = stringValue(latestWorkerMetadata.get("fallback_reason"));
        String selectionScope = resolveSelectionScope(task, executionRole);
        RuntimeFactSet factSet = buildJudgmentFactSet(task, ctx, latestOutput, latestWorkerMetadata);
        JudgmentContext jctx = new JudgmentContext(task, ctx, latestOutput, null, latestWorkerMetadata, factSet);
        MountedContextPromptMetrics judgmentPromptMetrics = buildJudgmentPromptMetrics(task, ctx);

        // Execution Judgment / Failure Recovery
        com.agentcloud.judgment.model.ExecutionDecision execDecision;
        com.agentcloud.judgment.model.CompletionDecision completionDecision;
        if (recoveryDirective != null) {
            if (recoveryDirective.sameWorkerRetry()) {
                execDecision = new com.agentcloud.judgment.model.ExecutionDecision(
                    "continue",
                    "Auto recovery scheduled same-worker cold retry after transient worker failure.",
                    "Retry current worker with a fresh execution session.",
                    false,
                    false,
                    false,
                    null
                );
                completionDecision = new com.agentcloud.judgment.model.CompletionDecision(
                    "partially_done",
                    "low",
                    "Worker round failed with transient runtime/provider error; same-worker retry scheduled.",
                    "Retry current worker once with fresh provider continuity."
                );
            } else if (recoveryDirective.autoHandoff()) {
                execDecision = new com.agentcloud.judgment.model.ExecutionDecision(
                    "handoff",
                    "Auto recovery scheduled worker handoff after transient worker failure.",
                    "Handoff to fallback worker " + firstNonBlank(recoveryDirective.handoffTarget(), "candidate") + ".",
                    false,
                    false,
                    false,
                    recoveryDirective.handoffTarget()
                );
                completionDecision = new com.agentcloud.judgment.model.CompletionDecision(
                    "partially_done",
                    "low",
                    "Worker round failed with transient runtime/provider error; auto handoff scheduled.",
                    "Continue on fallback worker."
                );
            } else {
                execDecision = new com.agentcloud.judgment.model.ExecutionDecision(
                    "wait",
                    "Automatic recovery budget exhausted; require human intervention.",
                    "Inspect failure trace and decide whether to retry or handoff manually.",
                    false,
                    false,
                    true,
                    null
                );
                completionDecision = new com.agentcloud.judgment.model.CompletionDecision(
                    "incomplete",
                    "low",
                    "Worker round failed and automatic recovery budget is exhausted.",
                    "Open human gate and inspect failure trace."
                );
            }
            task = applyRecoveryDirective(task, recoveryDirective);
        } else {
            execDecision = judgmentService.judgeExecution(jctx);
            completionDecision = judgmentService.judgeCompletion(jctx);
            OrchestrationJudgment orchestrationJudgment = applyOrchestrationJudgment(
                task, latestWorkerMetadata, executionResult, execDecision, completionDecision
            );
            task = orchestrationJudgment.task();
            execDecision = orchestrationJudgment.executionDecision();
            completionDecision = orchestrationJudgment.completionDecision();
        }
        String evaluatorRole = resolveEvaluatorRole(task);
        String evaluatorModelTier = resolveEvaluatorModelTier(task);
        String evaluatorReason = resolveEvaluatorReason(task, completionDecision.reason());
        boolean orchestrationClosedLoopObserved = isOrchestrationClosedLoopObserved(task, selectedModelTier);
        log.info("[Continue] task={} executionDecision action={} reason={}",
            task.id(), execDecision.action(), execDecision.reason());
        log.info("[Continue] task={} completionDecision status={} alignment={} reason={}",
            task.id(), completionDecision.status(), completionDecision.alignmentLevel(), completionDecision.reason());

        List<String> reopenCandidatePaths = execDecision.needsContextReopen()
            ? reopenCandidatePaths(ctx)
            : List.of();

        // 记录 judgment 决策
        Map<String, Object> executionJudgmentMetadata = withJudgmentPromptMetadata(metadataOf(
            "action", execDecision.action(),
            "judgment_actor", "judgment_service",
            "judgment_stage", "execution",
            "selected_worker", selectedWorkerId,
            "selected_model_tier", selectedModelTier,
            "execution_role", executionRole,
            "selection_scope", selectionScope,
            "why_selected", whySelected,
            "fallback_reason", fallbackReason,
            "evaluator_role", evaluatorRole,
            "evaluator_model_tier", evaluatorModelTier,
            "evaluator_reason", resolveEvaluatorReason(task, execDecision.reason()),
            "next_step", firstNonBlank(execDecision.nextStep(), executionResult != null ? executionResult.suggestedNextStep() : null),
            "needs_checkpoint", execDecision.needsCheckpoint(),
            "needs_context_reopen", execDecision.needsContextReopen(),
            "evidence_gap_detected", execDecision.evidenceGapDetected(),
            "needs_archive_retrieval", execDecision.needsArchiveRetrieval(),
            "needs_external_fact_refresh", execDecision.needsExternalFactRefresh(),
            "reopen_candidate_paths", reopenCandidatePaths,
            "reopen_candidate_count", reopenCandidatePaths.size(),
            "reopen_summary", buildReopenSummary(reopenCandidatePaths),
            "needs_human", execDecision.needsHuman(),
            "target_worker", firstNonBlank(execDecision.targetWorker(), task.assignedWorker()),
            "retry_decision", execDecision.retryDecision(),
            "escalation_decision", execDecision.escalationDecision()
        ), judgmentPromptMetrics);
        Decision judgmentRecord = new Decision(
            IdGenerator.newId("dec"), task.sessionId(), task.id(), Instant.now(),
            "execution_judgment",
            appendEvidenceSummary("Execution judgment: " + execDecision.action(), latestWorkerMetadata, executionJudgmentMetadata),
            execDecision.reason(),
            "medium", null,
            executionJudgmentMetadata
        );
        decisionDao.insert(judgmentRecord);

        Decision completionRecord = new Decision(
            IdGenerator.newId("dec"), task.sessionId(), task.id(), Instant.now(),
            "completion_judgment",
            appendProofSummary("Completion judgment: " + completionDecision.status(), latestWorkerMetadata),
            completionDecision.reason(),
            "medium", null,
            withJudgmentPromptMetadata(metadataOf(
                "judgment_actor", "judgment_service",
                "judgment_stage", "completion",
                "selected_worker", selectedWorkerId,
                "selected_model_tier", selectedModelTier,
                "execution_role", executionRole,
                "selection_scope", selectionScope,
                "why_selected", whySelected,
                "fallback_reason", fallbackReason,
                "status", completionDecision.status(),
                "alignment_level", completionDecision.alignmentLevel(),
                "suggested_next_action", completionDecision.suggestedNextAction(),
                "evaluation_result", completionDecision.status() + ":" + completionDecision.alignmentLevel(),
                "evaluation_reason", completionDecision.reason(),
                "evaluator_role", evaluatorRole,
                "evaluator_model_tier", evaluatorModelTier,
                "evaluator_reason", evaluatorReason,
                "orchestration_closed_loop_observed", orchestrationClosedLoopObserved,
                "retry_decision", execDecision.retryDecision(),
                "escalation_decision", execDecision.escalationDecision()
            ), judgmentPromptMetrics)
        );
        decisionDao.insert(completionRecord);

        if (learningMemoryService != null) {
            learningMemoryService.captureFromExecution(task, ctx, executionResult, execDecision, completionDecision);
        }

        Task enrichedTask = enrichTaskFromJudgment(
            task,
            executionResult,
            latestOutput,
            execDecision.nextStep(),
            completionDecision.suggestedNextAction()
        );
        // P1 Goal progress auto-update: 根据 worker 执行结果自动迁移 subgoal_status
        enrichedTask = autoUpdateSubgoalStatus(enrichedTask, executionResult);
        // P3 Loop activity: 每次 continueNode 完成后更新 last_loop_tick
        enrichedTask = withMetadataEntries(enrichedTask, "last_loop_tick", Instant.now().toString());
        if (!sameState(task, enrichedTask)) {
            taskDao.updateState(enrichedTask);
            task = enrichedTask;
        }

        // 根据 decision 选择下一状态迁移
        Object subgoalStatus = task != null && task.metadata() != null ? task.metadata().get("subgoal_status") : null;
        String goalProgressReason = resolveGoalProgressReason(subgoalStatus);
        String resolvedAction = resolveAction(
            execDecision.action(),
            completionDecision.status(),
            completionDecision.alignmentLevel(),
            execDecision.needsContextReopen(),
            execDecision.needsArchiveRetrieval(),
            execDecision.needsExternalFactRefresh(),
            subgoalStatus
        );
        if (List.of("done", "checkpoint_then_done").contains(resolvedAction)) {
            Task completedStage = markOrchestrationCompleted(task);
            if (!sameState(task, completedStage)) {
                taskDao.updateState(completedStage);
                task = completedStage;
            }
        }
        return switch (resolvedAction) {
            case "done" -> {
                Task moved = finalizeCompletedTask(task);
                taskDao.updateState(moved);
                yield moved;
            }
            case "checkpoint" -> packetNode(task, "periodic");
            case "reopen" -> packetNode(task, "reopen_before");
            case "archive_retrieval" -> packetNode(task, "archive_retrieval_before");
            case "external_fact_refresh" -> packetNode(task, "external_fact_refresh_before");
            case "checkpoint_then_done" -> checkpointThenDone(task, "periodic");
            case "handoff" -> {
                String target = firstNonBlank(
                    execDecision.targetWorker(),
                    metadataString(task.metadata(), "target_worker"),
                    task.assignedWorker()
                );
                Task moved = task.withAssignedWorker(target != null ? target : task.assignedWorker())
                    .withControlNode("handoff");
                taskDao.updateState(moved);
                if (recoveryDirective != null && recoveryDirective.autoHandoff()) {
                    yield handoffNode(moved, true);
                }
                if (shouldAutoContinueHandoff(moved)) {
                    yield handoffNode(moved, true);
                }
                yield moved;
            }
            case "escalate", "wait", "human_gate" -> {
                // P2 Advisory Handoff: small-tier worker ESCALATE 时优先 handoff 给 strong-tier advisory worker
                String currentModelTier = metadataString(latestWorkerMetadata, "selected_model_tier");
                if (currentModelTier == null || currentModelTier.isBlank()) {
                    currentModelTier = workerMetadata(task.assignedWorker(), "model_tier");
                }
                int currentHandoffDepth = handoffDepth(task);
                if (currentHandoffDepth >= MAX_HANDOFF_DEPTH) {
                    log.info("[Handoff] task={} handoff_depth={} >= max, entering human_gate instead of advisory handoff",
                        task.id(), currentHandoffDepth);
                    Task depthMoved = task.withStatus("waiting_human")
                        .withControlNode("human_gate")
                        .withWaitingReason("handoff depth limit reached (" + currentHandoffDepth + ")");
                    taskDao.updateState(depthMoved);
                    yield depthMoved;
                }
                String advisoryWorker = resolveAdvisoryHandoff(task, currentModelTier);
                if (advisoryWorker != null) {
                    log.info("[Advisory] task={} small-tier worker={} escalates, advisory handoff to strong-tier worker={}",
                        task.id(), task.assignedWorker(), advisoryWorker);
                    Task advisoryTask = withMetadataEntries(
                        task.withAssignedWorker(advisoryWorker).withControlNode("handoff"),
                        "handoff_reason", "advisory_consult",
                        "handoff_depth", currentHandoffDepth + 1,
                        "advisory_source_worker", firstNonBlank(task.assignedWorker(), "unassigned"),
                        "advisory_target_worker", advisoryWorker,
                        "advisory_trigger", "escalate_from_small_tier"
                    );
                    taskDao.updateState(advisoryTask);
                    yield handoffNode(advisoryTask, true);
                }
                // Fallback: no strong-tier advisory worker available, enter human gate
                Task moved = task.withStatus("waiting_human")
                    .withControlNode("human_gate")
                    .withWaitingReason(firstNonBlank(goalProgressReason, task.waitingReason(), "human gate required"));
                taskDao.updateState(moved);
                yield moved;
            }
            case "continue" -> {
                Task moved = task.withControlNode("scheduler");
                taskDao.updateState(moved);
                if (recoveryDirective != null && recoveryDirective.sameWorkerRetry()) {
                    yield schedulerNode(moved);
                }
                if (shouldAutoContinueTask(moved, latestWorkerMetadata, executionResult, resolvedAction)) {
                    yield schedulerNode(incrementAutoContinueBurst(moved));
                }
                yield moved;
            }
            default -> {
                log.warn("[Continue] unknown action {}, fallback to scheduler", execDecision.action());
                Task moved = task.withControlNode("scheduler");
                taskDao.updateState(moved);
                if (shouldAutoContinueTask(moved, latestWorkerMetadata, executionResult, "continue")) {
                    yield schedulerNode(incrementAutoContinueBurst(moved));
                }
                yield moved;
            }
        };
    }

    private String resolveAction(String executionAction,
                                 String completionStatus,
                                 String alignmentLevel,
                                 boolean needsContextReopen,
                                 boolean needsArchiveRetrieval,
                                 boolean needsExternalFactRefresh,
                                 Object subgoalStatus) {
        // Goal progress priority: 如果 subgoal_status 存在，优先于单轮 execution result
        // 验收标准 #2: decide 必须消费 goal 进度，而非只看单轮执行结果
        String goalAction = resolveGoalProgressAction(subgoalStatus);
        if (goalAction != null) {
            // blocked subgoal -> human_gate, 优先于 done/continue
            if ("human_gate".equals(goalAction)) {
                return "human_gate";
            }
            // all subgoals done -> done, 优先于单轮 result 说 continue
            if ("done".equals(goalAction)) {
                return "done";
            }
            // open subgoals -> 不允许直接 done/halt，必须继续
            // 如果 execution 说 done 但 goal 说 continue -> checkpoint（保存进度但不标记完成）
            if ("continue".equals(goalAction)) {
                if ("done".equals(executionAction) || "checkpoint_then_done".equals(executionAction)) {
                    return "checkpoint";
                }
                // 其他 execution action 由下面的 execution result 逻辑细分
            }
        }

        if ("checkpoint".equals(executionAction)
            && isDoneStatus(completionStatus)
            && !"low".equalsIgnoreCase(alignmentLevel)) {
            return "checkpoint_then_done";
        }
        if ("done".equals(executionAction)) {
            if (isDoneStatus(completionStatus) && !"low".equalsIgnoreCase(alignmentLevel)) {
                return "done";
            }
            return "checkpoint";
        }
        if ("continue".equals(executionAction) && isDoneStatus(completionStatus)
            && !"low".equalsIgnoreCase(alignmentLevel)) {
            return "done";
        }
        if ("continue".equals(executionAction) && needsArchiveRetrieval) {
            return "archive_retrieval";
        }
        if ("continue".equals(executionAction) && needsContextReopen) {
            return "reopen";
        }
        if ("continue".equals(executionAction) && needsExternalFactRefresh) {
            return "external_fact_refresh";
        }
        if ("continue".equals(executionAction) && isMisaligned(completionStatus)) {
            return "checkpoint";
        }
        return executionAction;
    }

    private boolean isDoneStatus(String completionStatus) {
        if (completionStatus == null) {
            return false;
        }
        return List.of("done", "complete").contains(completionStatus.toLowerCase());
    }

    private boolean isMisaligned(String completionStatus) {
        if (completionStatus == null) {
            return false;
        }
        return List.of("misaligned", "needs_clarification").contains(completionStatus.toLowerCase());
    }

    private String resolveGoalProgressReason(Object rawSubgoalStatus) {
        List<String> statuses = readSubgoalStatuses(rawSubgoalStatus);
        if (statuses.isEmpty()) {
            return null;
        }
        return statuses.stream().anyMatch(this::isBlockedSubgoalStatus)
            ? "subgoal blocked requires human gate"
            : null;
    }

    private String resolveGoalProgressAction(Object rawSubgoalStatus) {
        List<String> statuses = readSubgoalStatuses(rawSubgoalStatus);
        if (statuses.isEmpty()) {
            return null;
        }
        boolean anyBlocked = statuses.stream().anyMatch(this::isBlockedSubgoalStatus);
        if (anyBlocked) {
            return "human_gate";
        }
        boolean allDone = statuses.stream().allMatch(this::isDoneSubgoalStatus);
        return allDone ? "done" : "continue";
    }

    private List<String> readSubgoalStatuses(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> statuses = new ArrayList<>();
        if (raw instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                String status = readSubgoalStatus(value);
                if (status != null) {
                    statuses.add(status);
                }
            }
        } else if (raw instanceof List<?> values) {
            for (Object value : values) {
                String status = readSubgoalStatus(value);
                if (status != null) {
                    statuses.add(status);
                }
            }
        } else {
            String status = readSubgoalStatus(raw);
            if (status != null) {
                statuses.add(status);
            }
        }
        return statuses;
    }

    private String readFirstInProgressSubgoalDescription(Object rawSubgoalStatus) {
        if (rawSubgoalStatus instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String status = readSubgoalStatus(item);
                    if ("in_progress".equals(status) || "pending".equals(status)) {
                        Object desc = map.get("description");
                        if (desc != null) {
                            return desc.toString();
                        }
                        Object title = map.get("title");
                        if (title != null) {
                            return title.toString();
                        }
                    }
                }
            }
        }
        return "";
    }

    private String readSubgoalStatus(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Object status = map.get("status");
            if (status == null) {
                status = map.get("state");
            }
            return normalizeSubgoalStatus(status);
        }
        return normalizeSubgoalStatus(raw);
    }

    private String normalizeSubgoalStatus(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim().toLowerCase();
        return text.isBlank() ? null : text;
    }

    private boolean isDoneSubgoalStatus(String status) {
        return List.of("done", "complete", "completed", "accepted").contains(status);
    }

    private boolean isBlockedSubgoalStatus(String status) {
        return List.of("blocked", "waiting_human", "human_gate").contains(status);
    }

    private Task enrichTaskFromJudgment(Task task, WorkerExecutionResult executionResult, String latestOutput,
                                        String executionNextStep, String completionNextAction) {
        Task updated = task;
        String summarySource = firstNonBlank(
            metadataString(executionResult != null ? executionResult.metadata() : null, "failure_summary_readable"),
            executionResult != null ? executionResult.summary() : null,
            latestOutput
        );
        if (summarySource != null && !summarySource.isBlank()) {
            String sanitizedSummary = sanitizeReadableFailureSummary(
                firstNonBlank(
                    task != null ? task.assignedWorker() : null,
                    metadataString(executionResult != null ? executionResult.metadata() : null, "selected_worker"),
                    metadataString(executionResult != null ? executionResult.metadata() : null, "worker_id")
                ),
                summarySource
            );
            updated = updated.withSummary(sanitizedSummary);
        }

        String nextStep = firstNonBlank(
            executionNextStep,
            completionNextAction,
            executionResult != null ? executionResult.suggestedNextStep() : null,
            task.nextStep()
        );
        if (nextStep != null && !nextStep.isBlank() && !nextStep.equals(task.nextStep())) {
            updated = updated.withNextStep(nextStep);
        }
        return updated;
    }

    /**
     * P1 Goal progress auto-update: 根据 worker 执行结果自动迁移 subgoal_status。
     * 初版规则：
     * - execution completed 且无 error -> 当前 in_progress subgoal 标为 done
     * - execution failed -> 当前 in_progress subgoal 标为 blocked
     * - execution 无 subgoal_status 或为空 -> 不更新
     */
    private Task autoUpdateSubgoalStatus(Task task, WorkerExecutionResult executionResult) {
        if (task == null || task.metadata() == null) {
            return task;
        }
        Object rawSubgoalStatus = task.metadata().get("subgoal_status");
        if (rawSubgoalStatus == null) {
            return task;
        }

        // 判断 worker 执行结果
        boolean executionCompleted = executionResult != null
            && "completed".equals(executionResult.executionStatus());
        boolean executionFailed = executionResult != null
            && "failed".equals(executionResult.executionStatus());

        boolean executionRunning = executionResult != null
            && "running".equals(executionResult.executionStatus());

        if (!executionCompleted && !executionFailed && !executionRunning) {
            // Ambiguous execution status: try LLM-assisted subgoal judgment
            if (llmSubgoalJudgmentService != null && llmSubgoalJudgmentService.isEnabled()
                && LlmSubgoalJudgmentService.isAmbiguousExecution(
                    executionResult.executionStatus(), executionResult.outputText())) {
                String firstInProgressDescription = readFirstInProgressSubgoalDescription(rawSubgoalStatus);
                String llmStatus = llmSubgoalJudgmentService.judgeSubgoalStatus(
                    firstInProgressDescription, executionResult.outputText(),
                    "in_progress", task.metadata());
                if (llmStatus != null) {
                    Map<String, Object> updatedMetadata = new LinkedHashMap<>(task.metadata());
                    Object updatedSubgoalStatus = migrateFirstInProgressSubgoal(rawSubgoalStatus, llmStatus);
                    if (updatedSubgoalStatus != null) {
                        updatedMetadata.put("subgoal_status", updatedSubgoalStatus);
                        updatedMetadata.put("subgoal_judgment_source", "llm_fallback");
                        List<String> newStatuses = readSubgoalStatuses(updatedSubgoalStatus);
                        int total = newStatuses.size();
                        long doneCount = newStatuses.stream().filter(this::isDoneSubgoalStatus).count();
                        updatedMetadata.put("progress_summary", doneCount + "/" + total + " subgoals done");
                        log.info("[LLM Subgoal] task={} ambiguous execution, LLM judged subgoal as {}", task.id(), llmStatus);
                        return task.withMetadata(updatedMetadata);
                    }
                }
            }
            return task;
        }

        // execution running: mark first pending subgoal as in_progress
        if (executionRunning) {
            Map<String, Object> updatedMetadata = new LinkedHashMap<>(task.metadata());
            Object updatedSubgoalStatus = migrateFirstPendingToInProgress(rawSubgoalStatus);
            if (updatedSubgoalStatus != null) {
                updatedMetadata.put("subgoal_status", updatedSubgoalStatus);
                return task.withMetadata(updatedMetadata);
            }
            return task;
        }

        // False-done guardrail: 若任务目标期望工具执行（写入/创建/修改/删除/运行命令），
        // 但 worker 声称 completed 却没有产出任何 tool/artifact 证据，
        // 则不把 subgoal 标 done，保持 in_progress，交由后续 loop 重试或 handoff。
        // 修复 openclaw-native 伪完成：只规划不执行却误标 done。
        if (executionCompleted && expectsToolExecution(task) && !hasExecutionProof(executionResult)) {
            Map<String, Object> guardedMetadata = new LinkedHashMap<>(task.metadata());
            guardedMetadata.put("subgoal_judgment_source", "evidence_gap_no_tool_proof");
            log.warn("[Subgoal Guard] task={} execution completed but no tool/artifact proof for action-expecting goal; kept subgoal in_progress", task.id());
            return task.withMetadata(guardedMetadata);
        }

        // 构建 updated subgoal_status: 将第一个 in_progress subgoal 迁移
        Map<String, Object> updatedMetadata = new LinkedHashMap<>(task.metadata());
        Object updatedSubgoalStatus = migrateFirstInProgressSubgoal(rawSubgoalStatus,
            executionCompleted ? "done" : "blocked");
        if (updatedSubgoalStatus == null) {
            return task;
        }
        updatedMetadata.put("subgoal_status", updatedSubgoalStatus);

        // 更新 progress_summary
        List<String> newStatuses = readSubgoalStatuses(updatedSubgoalStatus);
        int total = newStatuses.size();
        long doneCount = newStatuses.stream().filter(this::isDoneSubgoalStatus).count();
        updatedMetadata.put("progress_summary", doneCount + "/" + total + " subgoals done");

        return task.withMetadata(updatedMetadata);
    }

    /**
     * 判断任务目标是否期望工具执行（文件写入/创建/修改/删除、命令执行等）。
     * 用于 false-done guardrail：这类任务需要 tool/artifact 证据才能标 done。
     */
    private boolean expectsToolExecution(Task task) {
        if (task == null) {
            return false;
        }
        String goal = task.goal();
        String metaGoal = task.metadata() != null ? metadataString(task.metadata(), "goal") : null;
        String intent = task.metadata() != null ? metadataString(task.metadata(), "intent") : null;
        StringBuilder text = new StringBuilder();
        if (goal != null && !goal.isBlank()) text.append(goal).append(' ');
        if (metaGoal != null && !metaGoal.isBlank()) text.append(metaGoal).append(' ');
        if (intent != null && !intent.isBlank()) text.append(intent).append(' ');
        String lower = text.toString().toLowerCase(Locale.ROOT);
        if (lower.isBlank()) {
            return false;
        }
        return lower.contains("写入") || lower.contains("写到") || lower.contains("保存到")
            || lower.contains("输出到") || lower.contains("创建") || lower.contains("新建")
            || lower.contains("修改") || lower.contains("删除") || lower.contains("运行")
            || lower.contains("执行命令") || lower.contains("create file") || lower.contains("write file")
            || lower.contains("write to") || lower.contains("create a") || lower.contains("modify ")
            || lower.contains("delete ") || lower.contains("run ") || lower.contains("mkdir");
    }

    /**
     * 判断 worker 执行结果是否携带可验证的执行证据（artifact / tool invocation）。
     * 用于 false-done guardrail：无证据时不允许把期望工具执行的 subgoal 标 done。
     */
    private boolean hasExecutionProof(WorkerExecutionResult result) {
        if (result == null) {
            return false;
        }
        if (result.producedArtifact()) {
            return true;
        }
        if (result.artifactContent() != null && !result.artifactContent().isBlank()) {
            return true;
        }
        if (result.evidenceRefs() != null && !result.evidenceRefs().isEmpty()) {
            return true;
        }
        Map<String, Object> md = result.metadata();
        if (md != null) {
            if (!metadataStringList(md, "tool_invocation_ids").isEmpty()) {
                return true;
            }
            if (metadataBoolean(md, "produced_artifact")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将 subgoal_status 中第一个 in_progress 的 subgoal 迁移为 targetStatus。
     * 返回 null 表示没有找到 in_progress subgoal 或输入格式不支持。
     */
    private Object migrateFirstInProgressSubgoal(Object rawSubgoalStatus, String targetStatus) {
        if (rawSubgoalStatus instanceof List<?> list) {
            List<Object> updated = new ArrayList<>();
            boolean migrated = false;
            for (Object item : list) {
                if (!migrated && item instanceof Map<?, ?> map) {
                    String status = readSubgoalStatus(item);
                    if ("in_progress".equals(status) || "pending".equals(status)) {
                        Map<String, Object> updatedItem = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            updatedItem.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                        updatedItem.put("status", targetStatus);
                        updated.add(updatedItem);
                        migrated = true;
                        continue;
                    }
                }
                if (!migrated && item instanceof String statusStr
                    && ("in_progress".equals(statusStr) || "pending".equals(statusStr))) {
                    updated.add(Map.of("status", targetStatus));
                    migrated = true;
                    continue;
                }
                updated.add(item);
            }
            return migrated ? updated : null;
        }
        if (rawSubgoalStatus instanceof Map<?, ?> map) {
            // Map 形式的 subgoal_status: key -> {status: ...}
            Map<String, Object> updated = new LinkedHashMap<>();
            boolean migrated = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (!migrated && value instanceof Map<?, ?> valueMap) {
                    String status = readSubgoalStatus(value);
                    if ("in_progress".equals(status) || "pending".equals(status)) {
                        Map<String, Object> updatedValue = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> ve : valueMap.entrySet()) {
                            updatedValue.put(String.valueOf(ve.getKey()), ve.getValue());
                        }
                        updatedValue.put("status", targetStatus);
                        updated.put(key, updatedValue);
                        migrated = true;
                        continue;
                    }
                }
                updated.put(key, value);
            }
            return migrated ? updated : null;
        }
        return null;
    }

    /**
     * 将 subgoal_status 中第一个 pending 的 subgoal 迁移为 in_progress。
     * 返回 null 表示没有找到 pending subgoal 或输入格式不支持。
     */
    private Object migrateFirstPendingToInProgress(Object rawSubgoalStatus) {
        if (rawSubgoalStatus instanceof List<?> list) {
            List<Object> updated = new ArrayList<>();
            boolean migrated = false;
            for (Object item : list) {
                if (!migrated && item instanceof Map<?, ?> map) {
                    String status = readSubgoalStatus(item);
                    if ("pending".equals(status)) {
                        Map<String, Object> updatedItem = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            updatedItem.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                        updatedItem.put("status", "in_progress");
                        updated.add(updatedItem);
                        migrated = true;
                        continue;
                    }
                }
                if (!migrated && item instanceof String statusStr
                    && "pending".equals(statusStr)) {
                    updated.add(Map.of("status", "in_progress"));
                    migrated = true;
                    continue;
                }
                updated.add(item);
            }
            return migrated ? updated : null;
        }
        if (rawSubgoalStatus instanceof Map<?, ?> map) {
            Map<String, Object> updated = new LinkedHashMap<>();
            boolean migrated = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (!migrated && value instanceof Map<?, ?> valueMap) {
                    String status = readSubgoalStatus(value);
                    if ("pending".equals(status)) {
                        Map<String, Object> updatedValue = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> ve : valueMap.entrySet()) {
                            updatedValue.put(String.valueOf(ve.getKey()), ve.getValue());
                        }
                        updatedValue.put("status", "in_progress");
                        updated.put(key, updatedValue);
                        migrated = true;
                        continue;
                    }
                }
                updated.put(key, value);
            }
            return migrated ? updated : null;
        }
        return null;
    }

    private OrchestrationJudgment applyOrchestrationJudgment(Task task,
                                                             Map<String, Object> latestWorkerMetadata,
                                                             WorkerExecutionResult executionResult,
                                                             com.agentcloud.judgment.model.ExecutionDecision executionDecision,
                                                             com.agentcloud.judgment.model.CompletionDecision completionDecision) {
        if (!isOrchestrated(task)) {
            return new OrchestrationJudgment(task, executionDecision, completionDecision);
        }

        String stage = orchestrationStage(task);
        String selectedWorkerId = firstNonBlank(
            stringValue(latestWorkerMetadata.get("selected_worker")),
            task.assignedWorker()
        );
        String selectedModelTier = firstNonBlank(
            stringValue(latestWorkerMetadata.get("selected_model_tier")),
            workerMetadata(router != null ? router.getWorker(selectedWorkerId) : null, "model_tier")
        );
        String nextStep = firstNonBlank(
            executionDecision.nextStep(),
            executionResult != null ? executionResult.suggestedNextStep() : null,
            task.nextStep()
        );

        if (isPlannerStage(stage)) {
            if (!plannerOutputValidForDelegation(task, latestWorkerMetadata, executionResult, completionDecision)) {
                String invalidReason = plannerDelegationInvalidReason(task, latestWorkerMetadata, executionResult, completionDecision);
                log.warn(
                    "[Orchestration] task={} stage={} planner delegation rejected reason={} worker={} executionStatus={}",
                    task != null ? task.id() : null,
                    stage,
                    invalidReason,
                    selectedWorkerId,
                    metadataString(latestWorkerMetadata, "execution_status")
                );
                return new OrchestrationJudgment(
                    withMetadataEntries(task,
                        "planner_delegation_gate", "rejected",
                        "planner_delegation_gate_reason", invalidReason
                    ),
                    executionDecision,
                    new com.agentcloud.judgment.model.CompletionDecision(
                        firstNonBlank(completionDecision.status(), "incomplete"),
                        normalizeAlignment(completionDecision.alignmentLevel()),
                        mergeReasons("planner output rejected as delegation brief: " + invalidReason, completionDecision.reason()),
                        firstNonBlank(
                            completionDecision.suggestedNextAction(),
                            executionDecision.nextStep(),
                            executionResult != null ? executionResult.suggestedNextStep() : null,
                            task.nextStep()
                        )
                    )
                );
            }
            WorkerRouter.RouteResult executionRoute = selectExecutionWorker(task);
            String targetWorker = firstNonBlank(
                executionRoute != null ? executionRoute.selectedWorker() : null,
                selectedWorkerId
            );
            String delegationReason = "orchestrated planner round completed; delegated execution to worker="
                + firstNonBlank(targetWorker, "unassigned");
            Task moved = withMetadataEntries(task,
                "planner_worker", selectedWorkerId,
                "planner_model_tier", selectedModelTier,
                "orchestration_stage", "execution_pending",
                "planner_delegation_gate", "accepted",
                "planner_delegation_gate_reason", "planner brief accepted",
                "target_worker", targetWorker,
                "preassigned_selection_reason", firstNonBlank(
                    executionRoute != null ? executionRoute.whySelected() : null,
                    executionRoute != null ? executionRoute.routeReason() : null,
                    delegationReason
                ),
                "orchestration_reason", delegationReason,
                "auto_continue_handoff", true
            );
            return new OrchestrationJudgment(
                moved,
                new com.agentcloud.judgment.model.ExecutionDecision(
                    "handoff",
                    mergeReasons(delegationReason, executionDecision.reason()),
                    nextStep,
                    executionDecision.needsCheckpoint(),
                    executionDecision.needsContextReopen(),
                    false,
                    targetWorker
                ),
                new com.agentcloud.judgment.model.CompletionDecision(
                    "partially_done",
                    normalizeAlignment(completionDecision.alignmentLevel()),
                    mergeReasons("planner output accepted as delegation brief, not terminal completion", completionDecision.reason()),
                    firstNonBlank(completionDecision.suggestedNextAction(), nextStep)
                )
            );
        }

        if (isExecutionStage(stage)) {
            String nextStage = "execution_pending".equalsIgnoreCase(stage) ? "execution_active" : stage;
            Task moved = withMetadataEntries(task,
                "executor_worker", selectedWorkerId,
                "executor_model_tier", selectedModelTier,
                "orchestration_stage", nextStage
            );
            return new OrchestrationJudgment(moved, executionDecision, completionDecision);
        }

        return new OrchestrationJudgment(task, executionDecision, completionDecision);
    }

    private ActionReconciliationOutcome reconcileAgentActions(Task task, WorkerExecutionResult executionResult) {
        if (executionResult == null) {
            return new ActionReconciliationOutcome(null, AgentActionReconciliationResult.empty());
        }
        AgentActionReconciliationResult reconciliation = agentActionReconciler != null
            ? agentActionReconciler.reconcile(task, executionResult)
            : AgentActionReconciliationResult.empty();
        if (reconciliation.decisions().isEmpty()
            && executionResult.proposedActions().isEmpty()
            && executionResult.contextRequests().isEmpty()
            && executionResult.completionClaim().isBlank()
            && executionResult.handoffTarget().isBlank()
            && executionResult.riskFlags().isEmpty()) {
            return new ActionReconciliationOutcome(executionResult, reconciliation);
        }

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (executionResult.metadata() != null && !executionResult.metadata().isEmpty()) {
            metadata.putAll(executionResult.metadata());
        }
        metadata.put("proposed_actions", AgentActionReconciler.draftMaps(executionResult.proposedActions()));
        metadata.put("accepted_actions", AgentActionReconciler.actionMaps(reconciliation.acceptedActions()));
        metadata.put("rejected_actions", AgentActionReconciler.actionMaps(reconciliation.rejectedActions()));
        metadata.put("approval_needed_actions", AgentActionReconciler.actionMaps(reconciliation.approvalNeededActions()));
        metadata.put("agent_action_decision_count", reconciliation.decisions().size());
        metadata.put("agent_action_accepted_count", reconciliation.acceptedActions().size());
        metadata.put("agent_action_rejected_count", reconciliation.rejectedActions().size());
        metadata.put("agent_action_approval_needed_count", reconciliation.approvalNeededActions().size());
        if (!executionResult.contextRequests().isEmpty()) {
            metadata.put("context_requests", executionResult.contextRequests());
        }
        if (!executionResult.completionClaim().isBlank()) {
            metadata.put("completion_claim", executionResult.completionClaim());
        }
        if (!executionResult.handoffTarget().isBlank()) {
            metadata.put("handoff_target", executionResult.handoffTarget());
        }
        if (!executionResult.riskFlags().isEmpty()) {
            metadata.put("risk_flags", executionResult.riskFlags());
        }
        WorkerExecutionResult enriched = new WorkerExecutionResult(
            executionResult.summary(),
            executionResult.outputText(),
            executionResult.producedArtifact(),
            executionResult.artifactTitle(),
            executionResult.artifactContent(),
            executionResult.suggestedNextStep(),
            executionResult.confidence(),
            executionResult.executionStatus(),
            executionResult.evidenceRefs(),
            executionResult.unfinishedItems(),
            executionResult.proposedActions(),
            executionResult.contextRequests(),
            executionResult.completionClaim(),
            executionResult.handoffTarget(),
            executionResult.riskFlags(),
            executionResult.tokenUsage(),
            executionResult.durationMs(),
            metadata
        );
        return new ActionReconciliationOutcome(enriched, reconciliation);
    }

    private Task applyAcceptedAgentActionFlow(Task task, AgentActionReconciliationResult reconciliation) {
        if (task == null || reconciliation == null || reconciliation.acceptedActions().isEmpty()) {
            return task;
        }
        Task updated = task;
        for (AgentAction action : reconciliation.acceptedActions()) {
            updated = switch (action.actionType()) {
                case "MARK_COMPLETE" -> applyMarkCompleteAction(updated, action);
                case "HANDOFF" -> applyHandoffAction(updated, action);
                case "ASK_HUMAN" -> applyAskHumanAction(updated, action);
                case "MARK_BLOCKED" -> applyMarkBlockedAction(updated, action);
                default -> appendAcceptedAgentActionMetadata(updated, action);
            };
        }
        return updated;
    }

    private Task applyMarkCompleteAction(Task task, AgentAction action) {
        Task marked = finalizeCompletedTask(task);
        return appendAcceptedAgentActionMetadata(marked, action);
    }

    private Task applyHandoffAction(Task task, AgentAction action) {
        String targetWorker = firstNonBlank(
            metadataString(action.payload(), "to_worker"),
            metadataString(action.payload(), "target_worker")
        );
        Task handedOff = targetWorker == null ? task : task.withAssignedWorker(targetWorker);
        persistTransitionPacket(handedOff, "handoff_before");
        return appendAcceptedAgentActionMetadata(
            handedOff.withControlNode("handoff"),
            action,
            "target_worker", targetWorker,
            "auto_continue_handoff", true
        );
    }

    private Task applyAskHumanAction(Task task, AgentAction action) {
        return appendAcceptedAgentActionMetadata(
            task.withStatus("waiting_human")
                .withControlNode("human_gate")
                .withWaitingReason(firstNonBlank(metadataString(action.payload(), "reason"), action.summary(), "agent action requested human input")),
            action
        );
    }

    private Task applyMarkBlockedAction(Task task, AgentAction action) {
        return appendAcceptedAgentActionMetadata(
            task.withStatus("waiting_human")
                .withControlNode("human_gate")
                .withWaitingReason(firstNonBlank(metadataString(action.payload(), "reason"), action.summary(), "agent action marked task blocked")),
            action,
            "blocked_by_agent_action", true
        );
    }

    private Task appendAcceptedAgentActionMetadata(Task task, AgentAction action, Object... extraEntries) {
        List<Map<String, Object>> accepted = new ArrayList<>();
        Object existing = task.metadata() != null ? task.metadata().get("accepted_agent_actions") : null;
        if (existing instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    LinkedHashMap<String, Object> copied = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() != null) {
                            copied.put(entry.getKey().toString(), entry.getValue());
                        }
                    }
                    accepted.add(copied);
                }
            }
        }
        accepted.add(actionMapForTaskMetadata(action));
        ArrayList<Object> entries = new ArrayList<>();
        entries.add("last_agent_action_id");
        entries.add(action.id());
        entries.add("last_agent_action_type");
        entries.add(action.actionType());
        entries.add("last_agent_action_status");
        entries.add(action.status());
        entries.add("accepted_agent_actions");
        entries.add(accepted);
        if (extraEntries != null) {
            for (Object entry : extraEntries) {
                entries.add(entry);
            }
        }
        return withMetadataEntries(task, entries.toArray());
    }

    private Map<String, Object> actionMapForTaskMetadata(AgentAction action) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        putIfNonBlank(map, "id", action.id());
        putIfNonBlank(map, "action_type", action.actionType());
        putIfNonBlank(map, "status", action.status());
        putIfNonBlank(map, "summary", action.summary());
        if (action.payload() != null && !action.payload().isEmpty()) {
            map.put("payload", action.payload());
        }
        putIfNonBlank(map, "risk_level", action.riskLevel());
        if (action.requiresApproval() != null) {
            map.put("requires_approval", action.requiresApproval());
        }
        return map;
    }

    private boolean plannerOutputValidForDelegation(Task task,
                                                    Map<String, Object> latestWorkerMetadata,
                                                    WorkerExecutionResult executionResult,
                                                    com.agentcloud.judgment.model.CompletionDecision completionDecision) {
        return plannerDelegationInvalidReason(task, latestWorkerMetadata, executionResult, completionDecision) == null;
    }

    private String plannerOutputRecoveryReason(Task task,
                                               Map<String, Object> latestWorkerMetadata,
                                               String latestOutput) {
        if (task == null || !isOrchestrated(task) || !isPlannerStage(orchestrationStage(task))) {
            return null;
        }
        if (successfulCurrentRound(task, latestWorkerMetadata, latestOutput)) {
            return null;
        }
        String failureSummary = firstNonBlank(
            metadataString(latestWorkerMetadata, "failure_summary_readable"),
            metadataString(latestWorkerMetadata, "output_text"),
            metadataString(latestWorkerMetadata, "artifact_content"),
            latestOutput
        );
        if (looksLikeLocalWorkspaceAccessRefusal(task, latestWorkerMetadata, failureSummary)) {
            return "local_workspace_access_refusal";
        }
        if (!looksLikeTransientWorkerRuntimeFailure(failureSummary)) {
            return null;
        }
        String output = blankToNull(firstNonBlank(
            metadataString(latestWorkerMetadata, "output_text"),
            metadataString(latestWorkerMetadata, "artifact_content"),
            latestOutput
        ));
        if (output != null && output.length() > PLANNER_DELEGATION_OUTPUT_LIMIT) {
            return "oversized_runtime_failure_output";
        }
        return "runtime_failure_signal";
    }

    private String plannerDelegationInvalidReason(Task task,
                                                  Map<String, Object> latestWorkerMetadata,
                                                  WorkerExecutionResult executionResult,
                                                  com.agentcloud.judgment.model.CompletionDecision completionDecision) {
        if (task == null || !isOrchestrated(task) || !isPlannerStage(orchestrationStage(task))) {
            return null;
        }
        String executionStatus = firstNonBlank(
            metadataString(latestWorkerMetadata, "execution_status"),
            executionResult != null ? executionResult.executionStatus() : null
        );
        if (isFailedExecutionStatus(executionStatus)) {
            return "failed_execution_status:" + executionStatus;
        }

        String plannerRecoveryReason = plannerOutputRecoveryReason(
            task,
            latestWorkerMetadata,
            firstNonBlank(
                executionResult != null ? executionResult.outputText() : null,
                metadataString(latestWorkerMetadata, "output_text"),
                executionResult != null ? executionResult.artifactContent() : null,
                metadataString(latestWorkerMetadata, "artifact_content"),
                executionResult != null ? executionResult.summary() : null
            )
        );
        if (plannerRecoveryReason != null) {
            return plannerRecoveryReason;
        }

        String outputText = firstNonBlank(
            executionResult != null ? executionResult.outputText() : null,
            metadataString(latestWorkerMetadata, "output_text"),
            executionResult != null ? executionResult.artifactContent() : null,
            metadataString(latestWorkerMetadata, "artifact_content"),
            executionResult != null ? executionResult.summary() : null
        );
        String compactOutput = blankToNull(outputText);
        if (compactOutput == null) {
            return "missing_delegation_output";
        }
        if (compactOutput.length() > PLANNER_DELEGATION_OUTPUT_LIMIT
            && looksLikeTransientWorkerRuntimeFailure(compactOutput)) {
            return "oversized_runtime_failure_output";
        }
        if (compactOutput.length() > PLANNER_DELEGATION_OUTPUT_LIMIT
            && !looksLikeCompactPlannerBrief(executionResult, latestWorkerMetadata, completionDecision)) {
            return "oversized_non_brief_output";
        }
        if (!looksLikeCompactPlannerBrief(executionResult, latestWorkerMetadata, completionDecision)) {
            return "missing_compact_brief";
        }
        return null;
    }

    private boolean looksLikeCompactPlannerBrief(WorkerExecutionResult executionResult,
                                                 Map<String, Object> latestWorkerMetadata,
                                                 com.agentcloud.judgment.model.CompletionDecision completionDecision) {
        String summary = firstNonBlank(
            executionResult != null ? executionResult.summary() : null,
            metadataString(latestWorkerMetadata, "summary")
        );
        String nextStep = firstNonBlank(
            executionResult != null ? executionResult.suggestedNextStep() : null,
            completionDecision != null ? completionDecision.suggestedNextAction() : null,
            metadataString(latestWorkerMetadata, "suggested_next_step"),
            metadataString(latestWorkerMetadata, "next_step")
        );
        String toolReason = firstNonBlank(
            metadataString(latestWorkerMetadata, "tool_chain_termination_reason"),
            metadataString(latestWorkerMetadata, "tool_plan_reason")
        );
        String artifactContent = firstNonBlank(
            executionResult != null ? executionResult.artifactContent() : null,
            metadataString(latestWorkerMetadata, "artifact_content")
        );
        boolean hasCompactSummary = blankToNull(summary) != null && summary.trim().length() <= 500;
        boolean hasActionableNextStep = blankToNull(nextStep) != null;
        boolean hasPlannerSignal = "planner_brief_ready".equalsIgnoreCase(blankToNull(toolReason))
            || "planner_no_additional_tool".equalsIgnoreCase(blankToNull(toolReason))
            || metadataStringList(latestWorkerMetadata, "tool_invocation_ids").size() > 0;
        boolean hasCompactArtifact = blankToNull(artifactContent) != null && artifactContent.length() <= PLANNER_DELEGATION_OUTPUT_LIMIT;
        return (hasCompactSummary || hasCompactArtifact) && hasActionableNextStep && hasPlannerSignal;
    }

    private String resolveLatestOutput(TaskRuntimeContext ctx, WorkerExecutionResult executionResult) {
        if (executionResult != null) {
            String output = firstNonBlank(executionResult.outputText(), executionResult.summary(), executionResult.artifactContent());
            if (output != null && !output.isBlank()) {
                return output;
            }
        }
        return ctx.recentArtifacts().isEmpty() ? "" : ctx.recentArtifacts().get(0).summary();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveLatestWorkerMetadata(TaskRuntimeContext ctx, WorkerExecutionResult executionResult) {
        Map<String, Object> currentRoundMetadata = selectLatestWorkerMetadata(executionResult != null ? executionResult.metadata() : null);
        if (executionResult != null) {
            currentRoundMetadata = augmentLatestWorkerMetadata(currentRoundMetadata, executionResult);
        }
        Map<String, Object> latestArtifactMetadata = extractLatestWorkerMetadataFromArtifacts(ctx);
        if (!currentRoundMetadata.isEmpty()) {
            if (!latestArtifactMetadata.isEmpty()) {
                return mergeLatestWorkerMetadata(latestArtifactMetadata, currentRoundMetadata);
            }
            return currentRoundMetadata;
        }
        return latestArtifactMetadata;
    }

    private Map<String, Object> augmentLatestWorkerMetadata(Map<String, Object> metadata,
                                                            WorkerExecutionResult executionResult) {
        if (executionResult == null) {
            return metadata == null ? Map.of() : metadata;
        }
        Map<String, Object> enriched = metadata == null || metadata.isEmpty()
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(metadata);
        if (executionResult.executionStatus() != null && !executionResult.executionStatus().isBlank()) {
            enriched.putIfAbsent("execution_status", executionResult.executionStatus());
        }
        if (executionResult.durationMs() != null) {
            enriched.putIfAbsent("duration_ms", executionResult.durationMs());
        }
        if (executionResult.evidenceRefs() != null && !executionResult.evidenceRefs().isEmpty()) {
            enriched.putIfAbsent("evidence_refs", executionResult.evidenceRefs());
        }
        if (executionResult.unfinishedItems() != null && !executionResult.unfinishedItems().isEmpty()) {
            enriched.putIfAbsent("unfinished_items", executionResult.unfinishedItems());
        }
        copyMetadataKey(executionResult.metadata(), enriched, "failure_class");
        copyMetadataKey(executionResult.metadata(), enriched, "failure_summary_readable");
        copyMetadataKey(executionResult.metadata(), enriched, "recovery_policy");
        copyMetadataKey(executionResult.metadata(), enriched, "recovery_stage");
        copyMetadataKey(executionResult.metadata(), enriched, "auto_same_worker_retry_count");
        copyMetadataKey(executionResult.metadata(), enriched, "auto_handoff_count");
        copyMetadataKey(executionResult.metadata(), enriched, "auto_handoff_target");
        copyMetadataKey(executionResult.metadata(), enriched, "previous_worker");
        copyMetadataKey(executionResult.metadata(), enriched, "provider_error");
        copyMetadataKey(executionResult.metadata(), enriched, "provider_turn_status");
        copyMetadataKey(executionResult.metadata(), enriched, "provider_failure_class");
        copyMetadataKey(executionResult.metadata(), enriched, "provider_failure_reason");
        copyMetadataKey(executionResult.metadata(), enriched, "provider_retryable");
        return enriched.isEmpty() ? Map.of() : enriched;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractLatestWorkerMetadataFromArtifacts(TaskRuntimeContext ctx) {
        if (ctx == null || ctx.recentArtifacts() == null || ctx.recentArtifacts().isEmpty()) {
            return Map.of();
        }
        for (Artifact artifact : ctx.recentArtifacts()) {
            if (artifact == null || artifact.metadata() == null || artifact.metadata().isEmpty()) {
                continue;
            }
            Object nested = artifact.metadata().get("latest_worker_metadata");
            if (nested instanceof Map<?, ?> nestedMap) {
                Map<String, Object> extracted = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : nestedMap.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        extracted.put(entry.getKey().toString(), entry.getValue());
                    }
                }
                Map<String, Object> normalized = mergeLatestWorkerMetadata(artifact.metadata(), extracted);
                if (!normalized.isEmpty()) {
                    return normalized;
                }
            }
            Map<String, Object> fallback = selectLatestWorkerMetadata(artifact.metadata());
            if (!fallback.isEmpty()) {
                return fallback;
            }
        }
        return Map.of();
    }

    private RuntimeFactSet buildJudgmentFactSet(Task task,
                                                TaskRuntimeContext runtimeContext,
                                                String latestOutput,
                                                Map<String, Object> latestWorkerMetadata) {
        RuntimeFactSet.ExecutionBoundary executionBoundary = buildExecutionBoundary(latestWorkerMetadata);
        WorkerRouter.RouteResult routePreview = buildRoutePreview(task, latestWorkerMetadata);
        Decision executionJudgment = latestDecision(runtimeContext, "execution_judgment");
        Decision completionJudgment = latestDecision(runtimeContext, "completion_judgment");
        return new RuntimeFactSet(
            task != null ? task.id() : "",
            task != null ? task.sessionId() : "",
            task != null ? task.status() : "",
            task != null ? task.controlNode() : "",
            task != null ? task.assignedWorker() : "",
            firstNonBlank(latestOutput),
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
                task != null ? task.nextStep() : null
            ),
            runtimeContext,
            runtimeContext != null ? runtimeContext.latestPacket() : null,
            runtimeContext != null ? runtimeContext.latestCheckpoint() : null,
            executionJudgment,
            completionJudgment,
            List.of(),
            executionBoundary,
            routePreview,
            buildJudgmentFactMetadata(runtimeContext, latestWorkerMetadata, executionBoundary, routePreview)
        );
    }

    private RuntimeFactSet.ExecutionBoundary buildExecutionBoundary(Map<String, Object> latestWorkerMetadata) {
        if (latestWorkerMetadata == null || latestWorkerMetadata.isEmpty()) {
            return null;
        }
        String executionId = firstNonBlank(
            metadataString(latestWorkerMetadata, "execution_id"),
            metadataString(latestWorkerMetadata, "tool_invocation_id")
        );
        String executionStatus = firstNonBlank(
            metadataString(latestWorkerMetadata, "execution_status"),
            metadataString(latestWorkerMetadata, "tool_chain_termination_reason")
        );
        Long durationMs = metadataLong(latestWorkerMetadata, "execution_duration_ms");
        if (durationMs == null) {
            durationMs = metadataLong(latestWorkerMetadata, "duration_ms");
        }
        List<String> toolInvocationIds = metadataStringList(latestWorkerMetadata, "tool_invocation_ids");
        Integer toolInvocationCount = metadataInt(latestWorkerMetadata, "tool_chain_step_count");
        if ((executionId == null || executionId.isBlank())
            && (executionStatus == null || executionStatus.isBlank())
            && toolInvocationIds.isEmpty()
            && toolInvocationCount == null) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        copyMetadataKey(latestWorkerMetadata, metadata, "prompt_rendering_mode");
        copyMetadataKey(latestWorkerMetadata, metadata, "mounted_context_mode");
        copyMetadataKey(latestWorkerMetadata, metadata, "prompt_mode");
        copyMetadataKey(latestWorkerMetadata, metadata, "mounted_context_rendered");
        copyMetadataKey(latestWorkerMetadata, metadata, "mounted_render_used");
        copyMetadataKey(latestWorkerMetadata, metadata, "mounted_context_injected");
        copyMetadataKey(latestWorkerMetadata, metadata, "mounted_context_panel_count");
        copyMetadataKey(latestWorkerMetadata, metadata, "mounted_context_non_empty_panel_count");
        copyMetadataKey(latestWorkerMetadata, metadata, "mounted_context_selection_trace_count");
        copyMetadataKey(latestWorkerMetadata, metadata, MountedContextPromptBudgetSupport.RENDERED_PANEL_COUNT);
        copyMetadataKey(latestWorkerMetadata, metadata, MountedContextPromptBudgetSupport.HIDDEN_PANEL_COUNT);
        copyMetadataKey(latestWorkerMetadata, metadata, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT);
        copyMetadataKey(latestWorkerMetadata, metadata, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT);
        copyMetadataKey(latestWorkerMetadata, metadata, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT);
        copyMetadataKey(latestWorkerMetadata, metadata, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT);
        copyMetadataKey(latestWorkerMetadata, metadata, MountedContextPromptBudgetSupport.BUDGET_TRUNCATED);
        copyMetadataKey(latestWorkerMetadata, metadata, "mounted_pinned_count");
        copyMetadataKey(latestWorkerMetadata, metadata, "mounted_active_count");
        copyMetadataKey(latestWorkerMetadata, metadata, "mounted_ancestor_count");
        copyMetadataKey(latestWorkerMetadata, metadata, "mounted_sibling_count");
        copyMetadataKey(latestWorkerMetadata, metadata, "mounted_evidence_count");
        copyMetadataKey(latestWorkerMetadata, metadata, "mounted_index_count");
        copyMetadataKey(latestWorkerMetadata, metadata, "mounted_archive_count");
        copyMetadataKey(latestWorkerMetadata, metadata, "selected_worker");
        copyMetadataKey(latestWorkerMetadata, metadata, "execution_backend");
        copyMetadataKey(latestWorkerMetadata, metadata, "provider_id");
        copyMetadataKey(latestWorkerMetadata, metadata, "provider_session_id");
        copyMetadataKey(latestWorkerMetadata, metadata, "provider_thread_id");
        copyMetadataKey(latestWorkerMetadata, metadata, "resume_provider_session_id");
        copyMetadataKey(latestWorkerMetadata, metadata, "provider_error");
        copyMetadataKey(latestWorkerMetadata, metadata, "provider_turn_status");
        copyMetadataKey(latestWorkerMetadata, metadata, "provider_failure_class");
        copyMetadataKey(latestWorkerMetadata, metadata, "provider_failure_reason");
        copyMetadataKey(latestWorkerMetadata, metadata, "provider_retryable");
        copyMetadataKey(latestWorkerMetadata, metadata, "tool_execution_mode");
        copyMetadataKey(latestWorkerMetadata, metadata, "tool_chain_step_count");
        copyMetadataKey(latestWorkerMetadata, metadata, "tool_chain_termination_reason");
        copyMetadataKey(latestWorkerMetadata, metadata, "tool_chain_trace");
        copyMetadataKey(latestWorkerMetadata, metadata, "evidence_refs");
        copyMetadataKey(latestWorkerMetadata, metadata, "unfinished_items");
        copyMetadataKey(latestWorkerMetadata, metadata, "grounded_output_present");
        copyMetadataKey(latestWorkerMetadata, metadata, "missing_required_current_round_write");
        return new RuntimeFactSet.ExecutionBoundary(
            firstNonBlank(executionId, ""),
            firstNonBlank(executionStatus, ""),
            metadataString(latestWorkerMetadata, "execution_started_at"),
            metadataString(latestWorkerMetadata, "execution_finished_at"),
            durationMs,
            firstNonBlank(
                metadataString(latestWorkerMetadata, "selected_worker"),
                metadataString(latestWorkerMetadata, "executor_worker")
            ),
            toolInvocationIds,
            toolInvocationCount,
            buildExecutionTraceSummary(latestWorkerMetadata),
            metadata
        );
    }

    private WorkerRouter.RouteResult buildRoutePreview(Task task, Map<String, Object> latestWorkerMetadata) {
        if ((latestWorkerMetadata == null || latestWorkerMetadata.isEmpty()) && router == null) {
            return null;
        }
        String selectedWorker = firstNonBlank(
            metadataString(latestWorkerMetadata, "selected_worker"),
            metadataString(latestWorkerMetadata, "executor_worker"),
            task != null ? task.assignedWorker() : null
        );
        String routeSource = firstNonBlank(
            metadataString(latestWorkerMetadata, "route_source"),
            selectedWorker != null ? "judgment_context" : null
        );
        String routeReason = firstNonBlank(
            metadataString(latestWorkerMetadata, "why_selected"),
            metadataString(latestWorkerMetadata, "preassigned_selection_reason")
        );
        String selectedModelTier = metadataString(latestWorkerMetadata, "selected_model_tier");
        String selectedExecutionRole = metadataString(latestWorkerMetadata, "execution_role");
        String selectionScope = resolveSelectionScope(task, selectedExecutionRole);
        List<String> candidateWorkers = metadataStringList(latestWorkerMetadata, "candidate_workers");
        if (selectedWorker == null && routeSource == null && routeReason == null
            && selectedModelTier == null && selectedExecutionRole == null && candidateWorkers.isEmpty()) {
            return router != null && task != null ? router.selectWorker(task) : null;
        }
        return new WorkerRouter.RouteResult(
            task != null ? task.id() : null,
            selectedWorker,
            List.of(),
            routeReason,
            routeSource,
            metadataString(task != null ? task.metadata() : null, "task_type"),
            metadataString(latestWorkerMetadata, "preferred_worker_hint"),
            Boolean.parseBoolean(metadataString(latestWorkerMetadata, "learning_hint_applied")),
            candidateWorkers,
            metadataString(latestWorkerMetadata, "selected_worker_type"),
            selectedModelTier,
            selectedExecutionRole,
            selectionScope,
            routeReason,
            metadataString(latestWorkerMetadata, "fallback_reason"),
            null,
            null,
            null,
            metadataString(latestWorkerMetadata, "recovery_execution_mode"),
            false,
            List.of(),
            List.of(),
            metadataString(latestWorkerMetadata, "cost_route_stage"),
            Boolean.parseBoolean(firstNonBlank(metadataString(latestWorkerMetadata, "manual_window_required"), "false")),
            metadataString(latestWorkerMetadata, "recommended_manual_provider"),
            metadataStringList(latestWorkerMetadata, "manual_window_candidates"),
            null,
            null,
            routeSkippedWorkers(latestWorkerMetadata.get("dispatch_skipped_workers"))
        );
    }

    private Map<String, Object> buildJudgmentFactMetadata(TaskRuntimeContext runtimeContext,
                                                          Map<String, Object> latestWorkerMetadata,
                                                          RuntimeFactSet.ExecutionBoundary executionBoundary,
                                                          WorkerRouter.RouteResult routePreview) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("has_runtime_context", runtimeContext != null);
        metadata.put("has_latest_packet", runtimeContext != null && runtimeContext.latestPacket() != null);
        metadata.put("has_latest_checkpoint", runtimeContext != null && runtimeContext.latestCheckpoint() != null);
        metadata.put("has_execution_boundary", executionBoundary != null);
        metadata.put("has_route_preview", routePreview != null);
        if (latestWorkerMetadata != null && !latestWorkerMetadata.isEmpty()) {
            metadata.put("has_latest_worker_metadata", true);
        }
        if (executionBoundary != null) {
            metadata.put("execution_id", executionBoundary.executionId());
            metadata.put("execution_status", executionBoundary.executionStatus());
            metadata.put("execution_duration_ms", executionBoundary.durationMs());
            metadata.put("execution_tool_invocation_count", executionBoundary.toolInvocationCount());
            metadata.put("execution_trace_summary", executionBoundary.traceSummary());
        }
        if (routePreview != null) {
            putIfNonBlank(metadata, "route_source", routePreview.routeSource());
            putIfNonBlank(metadata, "selected_worker", routePreview.selectedWorker());
            putIfNonBlank(metadata, "selected_model_tier", routePreview.selectedModelTier());
            putIfNonBlank(metadata, "execution_role", routePreview.selectedExecutionRole());
            putIfNonBlank(metadata, "selection_scope", routePreview.selectionScope());
            putIfNonBlank(metadata, "why_selected", routePreview.whySelected());
            putIfNonBlank(metadata, "fallback_reason", routePreview.fallbackReason());
        }
        return metadata;
    }

    private Decision latestDecision(TaskRuntimeContext runtimeContext, String decisionType) {
        if (runtimeContext == null || runtimeContext.recentDecisions() == null || decisionType == null || decisionType.isBlank()) {
            return null;
        }
        return runtimeContext.recentDecisions().stream()
            .filter(decision -> decision != null && decisionType.equals(decision.decisionType()))
            .findFirst()
            .orElse(null);
    }

    private String buildExecutionTraceSummary(Map<String, Object> latestWorkerMetadata) {
        Integer stepCount = metadataInt(latestWorkerMetadata, "tool_chain_step_count");
        String terminationReason = metadataString(latestWorkerMetadata, "tool_chain_termination_reason");
        List<String> toolNames = toolNamesFromTrace(latestWorkerMetadata != null
            ? latestWorkerMetadata.get("tool_chain_trace")
            : null);
        if (stepCount == null && terminationReason == null && toolNames.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (stepCount != null) {
            sb.append(stepCount).append(" step").append(stepCount == 1 ? "" : "s");
        }
        if (terminationReason != null && !terminationReason.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(terminationReason);
        }
        if (!toolNames.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(String.join(" -> ", toolNames));
        }
        return sb.toString();
    }

    private List<String> toolNamesFromTrace(Object traceValue) {
        if (!(traceValue instanceof List<?> rawTrace) || rawTrace.isEmpty()) {
            return List.of();
        }
        List<String> toolNames = new java.util.ArrayList<>();
        for (Object entry : rawTrace) {
            if (entry instanceof Map<?, ?> map) {
                Object toolName = map.get("tool_name");
                if (toolName != null && !toolName.toString().isBlank()) {
                    toolNames.add(toolName.toString());
                }
            }
        }
        return toolNames;
    }

    private boolean hasMeaningfulOutput(WorkerExecutionResult result) {
        if (result == null) {
            return false;
        }
        return firstNonBlank(result.summary(), result.outputText(), result.artifactContent()) != null;
    }

    private boolean hasAgentActionSurface(WorkerExecutionResult result) {
        if (result == null) {
            return false;
        }
        Map<String, Object> metadata = result.metadata();
        return !result.proposedActions().isEmpty()
            || !result.contextRequests().isEmpty()
            || !result.completionClaim().isBlank()
            || !result.handoffTarget().isBlank()
            || !result.riskFlags().isEmpty()
            || metadataInt(metadata, "agent_action_decision_count") != null;
    }

    private String agentActionSummary(WorkerExecutionResult result) {
        if (result == null) {
            return null;
        }
        Map<String, Object> metadata = result.metadata();
        Integer accepted = metadataInt(metadata, "agent_action_accepted_count");
        Integer rejected = metadataInt(metadata, "agent_action_rejected_count");
        Integer approval = metadataInt(metadata, "agent_action_approval_needed_count");
        Integer total = metadataInt(metadata, "agent_action_decision_count");
        if (total == null) {
            total = result.proposedActions().size()
                + result.contextRequests().size()
                + (!result.completionClaim().isBlank() ? 1 : 0)
                + (!result.handoffTarget().isBlank() ? 1 : 0);
        }
        if (total <= 0 && result.riskFlags().isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add("Agent actions proposed=" + total);
        if (accepted != null) {
            parts.add("accepted=" + accepted);
        }
        if (rejected != null) {
            parts.add("rejected=" + rejected);
        }
        if (approval != null) {
            parts.add("needs_approval=" + approval);
        }
        if (!result.riskFlags().isEmpty()) {
            parts.add("risk_flags=" + String.join(",", result.riskFlags()));
        }
        return String.join("; ", parts);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean sameState(Task left, Task right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return java.util.Objects.equals(left.summary(), right.summary())
            && java.util.Objects.equals(left.nextStep(), right.nextStep())
            && java.util.Objects.equals(left.status(), right.status())
            && java.util.Objects.equals(left.controlNode(), right.controlNode())
            && java.util.Objects.equals(left.assignedWorker(), right.assignedWorker())
            && java.util.Objects.equals(left.waitingReason(), right.waitingReason())
            && java.util.Objects.equals(left.metadata(), right.metadata());
    }

    private Task ensureDispatchReadyBeforeExecution(Task task) {
        if (task == null || router == null || task.assignedWorker() == null || task.assignedWorker().isBlank()) {
            return task;
        }
        WorkerRegistry.ReadinessCheck readiness = router.checkDispatchReadiness(task.assignedWorker());
        if (readiness == null || readiness.ready()) {
            return task;
        }
        String previousWorker = task.assignedWorker();
        String reason = firstNonBlank(readiness.reason(), "dispatch preflight failed");
        log.warn(
            "[Scheduler] task={} worker={} dispatch preflight failed reason={}",
            task.id(),
            previousWorker,
            reason
        );
        emitEvent(task, "worker_dispatch_preflight_failed",
            "Worker dispatch preflight failed. worker=" + previousWorker,
            metadataOf(
                "control_node", "worker_dispatch_preflight_failed",
                "worker_id", previousWorker,
                "readiness_mode", readiness.mode(),
                "dispatch_preflight_ready", readiness.dispatchPreflightReady(),
                "dispatch_preflight_reason", readiness.dispatchPreflightReason(),
                "dispatch_preflight_cached", readiness.dispatchPreflightCached(),
                "dispatch_preflight_mode", readiness.dispatchPreflightMode(),
                "dispatch_preflight_active_probe", readiness.dispatchPreflightActiveProbe(),
                "dispatch_preflight_metadata", readiness.dispatchPreflightMetadata(),
                "reason", reason
            ));
        Task unassigned = withMetadataEntries(
            task.withAssignedWorker(null),
            "assigned_worker", null,
            "target_worker", null,
            "previous_worker", previousWorker,
            "dispatch_preflight_failed_worker", previousWorker,
            "dispatch_preflight_reason", reason
        );
        taskDao.updateState(unassigned);
        return unassigned;
    }

    // === Packet Node ===
    private Task packetNode(Task task) {
        return packetNode(task, "pause_before");
    }

    private Task packetNode(Task task, String checkpointType) {
        log.info("[Packet] task={}", task.id());
        emitEvent(task, "node_packet", "Generating packet before transition");
        Task packetBoundaryTask = "packet".equals(task.controlNode()) ? task : task.withControlNode("packet");

        Session session = sessionDao.findById(task.sessionId()).orElse(null);
        if (session != null) {
            ResumePacket packet = packetBuilder.buildResumePacket(packetBoundaryTask, session);
            packetDao.insert(packet);
        }

        // 在关键转移前触发 consolidation
        consolidation.consolidate(packetBoundaryTask, checkpointType);

        Task moved = task.withControlNode("scheduler");
        taskDao.updateState(moved);
        return moved;
    }

    private Task checkpointThenDone(Task task, String checkpointType) {
        log.info("[CheckpointThenDone] task={} checkpointType={}", task.id(), checkpointType);
        Task moved = finalizeCompletedTask(task);
        persistTransitionPacket(moved, checkpointType);
        taskDao.updateState(moved);
        return moved;
    }

    // === Human Gate Node ===
    private Task humanGateNode(Task task) {
        log.info("[HumanGate] task={}", task.id());
        emitEvent(task, "node_human_gate", "Awaiting human confirmation");

        Task moved = task.withStatus("waiting_human").withControlNode("human_gate");
        taskDao.updateState(moved);
        return moved;
    }

    // === Handoff Node ===
    private Task handoffNode(Task task) {
        return handoffNode(task, false);
    }

    private Task handoffNode(Task task, boolean continueImmediately) {
        log.info("[Handoff] task={}", task.id());
        emitEvent(task, "node_handoff", "Executing handoff");

        Task moved = clearAutoContinueHandoff(task).withControlNode("scheduler");
        taskDao.updateState(moved);
        if (continueImmediately) {
            return schedulerNode(moved);
        }
        return moved;
    }

    // === 外部触发方法 ===

    public Task triggerPause(Task task, String reason) {
        log.info("[Trigger] pause task={} reason={}", task.id(), reason);
        Task t = bumpExecInstance(task.withStatus("paused").withControlNode("packet").withWaitingReason(reason));
        taskDao.updateState(t);
        return packetNode(t);
    }

    public Task triggerEscalate(Task task, String reason) {
        log.info("[Trigger] escalate task={} reason={}", task.id(), reason);
        persistTransitionPacket(task, "escalate_before");
        Task t = bumpExecInstance(task.withStatus("waiting_human").withControlNode("human_gate").withWaitingReason(reason));
        taskDao.updateState(t);
        return humanGateNode(t);
    }

    public Task triggerHandoff(Task task, String targetWorker) {
        log.info("[Trigger] handoff task={} to worker={}", task.id(), targetWorker);
        persistTransitionPacket(task.withAssignedWorker(targetWorker), "handoff_before");
        Task t = bumpExecInstance(withMetadataEntries(clearAutoContinueBurst(task.withAssignedWorker(targetWorker)).withControlNode("handoff"), "handoff_depth", handoffDepth(task) + 1));
        taskDao.updateState(t);
        return handoffNode(t);
    }

    public Task triggerResume(Task task) {
        log.info("[Trigger] resume task={}", task.id());
        Task t = bumpExecInstance(clearAutoContinueBurst(task.withStatus("active").withControlNode("scheduler").withWaitingReason(null)));
        taskDao.updateState(t);
        return schedulerNode(t);
    }

    public Task triggerHalt(Task task, String reason) {
        log.info("[Trigger] halt task={} reason={}", task.id(), reason);
        persistTransitionPacket(task, "halt_before");
        Task t = finalizeCompletedTask(task).withWaitingReason(reason);
        taskDao.updateState(t);
        return t;
    }

    /**
     * Execute one worker round with a timeout. If the worker execution exceeds
     * {@link #effectiveWorkerTimeoutSeconds(String)}（显式覆盖绝对优先，否则按 worker tier 取 300s/600s），
     * a RuntimeException is thrown so the catch block in schedulerNode can handle it through the failure recovery path.
     */
    private WorkerExecutionResult executeOneRoundWithTimeout(TaskRuntimeContext ctx, String workerId) {
        long timeoutSeconds = effectiveWorkerTimeoutSeconds(workerId);
        CompletableFuture<WorkerExecutionResult> future = CompletableFuture.supplyAsync(
            () -> workerExecutor.executeOneRound(ctx, workerId)
        );
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("Worker execution timed out after " + timeoutSeconds + "s: worker=" + workerId, e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("Worker execution failed: worker=" + workerId, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Worker execution interrupted: worker=" + workerId, e);
        }
    }

    private Task finalizeCompletedTask(Task task) {
        // 如果 subgoal_status 存在且部分完成（有 done 但不是全部 done），标记为 partial 而非 done
        Object subgoalStatus = task.metadata() != null ? task.metadata().get("subgoal_status") : null;
        List<String> statuses = readSubgoalStatuses(subgoalStatus);
        if (!statuses.isEmpty()) {
            long doneCount = statuses.stream().filter(this::isDoneSubgoalStatus).count();
            if (doneCount > 0 && doneCount < statuses.size()) {
                return task.withStatus("partial")
                    .withControlNode("end")
                    .withCompletedAt(Instant.now())
                    .withNextStep(null);
            }
        }
        return task.withStatus("done")
            .withControlNode("end")
            .withCompletedAt(Instant.now())
            .withNextStep(null);
    }

    private void persistTransitionPacket(Task task, String checkpointType) {
        Session session = sessionDao.findById(task.sessionId()).orElse(null);
        if (session != null) {
            ResumePacket packet = packetBuilder.buildResumePacket(task, session);
            packetDao.insert(packet);
        }
        consolidation.consolidate(task, checkpointType);
    }

    private WorkerRouter.RouteResult selectExecutionWorker(Task task) {
        if (router == null) {
            return null;
        }
        // 规划阶段当前的 assignedWorker 是 planner，本轮为 executor 选型时不能把它当成显式 pin。
        Task executionSelectionTask = task.withAssignedWorker(null);
        return router.selectWorker(withMetadataEntries(
            executionSelectionTask,
            "orchestration_stage", "execution_pending",
            "assigned_worker", null,
            "target_worker", null,
            "preassigned_selection_reason", null
        ));
    }

    private Task markOrchestrationCompleted(Task task) {
        if (!isOrchestrated(task)) {
            return task;
        }
        return withMetadataEntries(task,
            "orchestration_stage", "completed",
            "auto_continue_handoff", false
        );
    }

    private Task clearAutoContinueHandoff(Task task) {
        if (task == null || task.metadata() == null || !task.metadata().containsKey("auto_continue_handoff")) {
            return task;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(task.metadata());
        metadata.remove("auto_continue_handoff");
        return task.withMetadata(metadata);
    }

    private Task clearAutoContinueBurst(Task task) {
        if (task == null || task.metadata() == null || !task.metadata().containsKey("auto_continue_burst_count")) {
            return task;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(task.metadata());
        metadata.remove("auto_continue_burst_count");
        return task.withMetadata(metadata);
    }

    private int handoffDepth(Task task) {
        if (task == null || task.metadata() == null) {
            return 0;
        }
        Object depth = task.metadata().get("handoff_depth");
        if (depth instanceof Number) {
            return ((Number) depth).intValue();
        }
        if (depth != null) {
            try {
                return Integer.parseInt(depth.toString());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private Task clearProviderContinuationMetadata(Task task) {
        if (task == null || task.metadata() == null || task.metadata().isEmpty()) {
            return task;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(task.metadata());
        metadata.remove("provider_session_id");
        metadata.remove("provider_thread_id");
        metadata.remove("codex_thread_id");
        metadata.remove("resume_provider_session_id");
        return task.withMetadata(metadata);
    }

    private Task withMetadataEntries(Task task, Object... entries) {
        if (task == null || entries == null || entries.length == 0) {
            return task;
        }
        Map<String, Object> metadata = task.metadata() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(task.metadata());
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
        return task.withMetadata(metadata);
    }

    private boolean shouldAutoContinueHandoff(Task task) {
        return task != null
            && Boolean.parseBoolean(metadataString(task.metadata(), "auto_continue_handoff"))
            && isOrchestrated(task)
            && isExecutionStage(orchestrationStage(task));
    }

    private boolean shouldAutoContinueTask(Task task,
                                           Map<String, Object> latestWorkerMetadata,
                                           WorkerExecutionResult executionResult,
                                           String resolvedAction) {
        if (task == null || latestWorkerMetadata == null || latestWorkerMetadata.isEmpty()) {
            return false;
        }
        if (!"continue".equalsIgnoreCase(firstNonBlank(resolvedAction, ""))) {
            return false;
        }
        if (!isAutoContinuableExecutionRound(latestWorkerMetadata)) {
            return false;
        }
        String terminationReason = stringValue(latestWorkerMetadata.get("tool_chain_termination_reason"));
        if ("repeated_tool_guard".equalsIgnoreCase(terminationReason)
            || "no_progress_guard".equalsIgnoreCase(terminationReason)) {
            log.info("[AutoContinue] task={} rejected: tool chain terminated by guard, reason={}", task.id(), terminationReason);
            return false;
        }
        if (Boolean.parseBoolean(stringValue(latestWorkerMetadata.get("missing_required_current_round_write")))) {
            log.info("[AutoContinue] task={} rejected: missing required current round write", task.id());
            return false;
        }
        boolean moreDeclaredRoundsRemain = Boolean.parseBoolean(stringValue(latestWorkerMetadata.get("more_declared_rounds_remain")));
        if (Boolean.parseBoolean(stringValue(latestWorkerMetadata.get("grounded_output_present"))) && !moreDeclaredRoundsRemain) {
            log.info("[AutoContinue] task={} rejected: grounded output already present", task.id());
            return false;
        }
        
        // 获取 LLM 返回的下一步想法
        String nextStep = firstNonBlank(
            stringValue(latestWorkerMetadata.get("suggested_next_action")),
            stringValue(latestWorkerMetadata.get("next_step")),
            executionResult != null ? executionResult.suggestedNextStep() : null,
            task.nextStep()
        );
        boolean hasNextStep = nextStep != null && !nextStep.isBlank();
        
        // 检查是否有显式的 goal（目标）
        String goal = firstNonBlank(task.goal(), metadataString(task.metadata(), "goal"));
        boolean hasGoal = goal != null && !goal.isBlank();

        // 检查是否启用了自动多轮模式
        boolean autoMultiRoundEnabled = Boolean.parseBoolean(metadataString(task.metadata(), "auto_multi_round"));
        List<String> unfinishedItems = metadataStringList(latestWorkerMetadata, "unfinished_items");
        boolean hasUnfinishedItems = unfinishedItems != null && !unfinishedItems.isEmpty();

        // 记录任务类型信息用于调试
        String orchestrationStage = orchestrationStage(task);
        String modelMode = metadataString(task.metadata(), "model_mode");
        boolean isPlanningStage = isPlannerStage(orchestrationStage);
        boolean hasOutputRequirement = isGroundedOutputRequired(latestWorkerMetadata);
        log.info("[AutoContinue] task={} orchestration_stage={} model_mode={} is_planning_stage={} has_output_requirement={} has_next_step={} has_goal={} auto_multi_round={} more_declared_rounds_remain={} has_unfinished_items={}",
            task.id(), orchestrationStage, modelMode, isPlanningStage, hasOutputRequirement, hasNextStep, hasGoal, autoMultiRoundEnabled, moreDeclaredRoundsRemain, hasUnfinishedItems);

        // 判断是否继续：
        // 1. 如果后面还有声明的轮次，继续
        // 2. 如果有明确的 next_step，继续
        // 3. 如果有 unfinished_items，继续
        // 4. 如果没有 next_step，但有显式 goal 或启用了 auto_multi_round，也继续（更积极的策略）
        boolean shouldContinue = moreDeclaredRoundsRemain || hasNextStep || hasUnfinishedItems;
        if (!shouldContinue && (hasGoal || autoMultiRoundEnabled)) {
            log.info("[AutoContinue] task={} continuing despite no explicit next step: has_goal={} auto_multi_round={}", task.id(), hasGoal, autoMultiRoundEnabled);
            shouldContinue = true;
        }

        if (!shouldContinue) {
            log.info("[AutoContinue] task={} rejected: no declared next round, next step, unfinished items, goal, or auto_multi_round enabled", task.id());
            return false;
        }
        
        // 允许所有任务类型自动继续到下一轮
        // 仅保留突发计数限制，防止无限循环
        boolean canContinue = autoContinueBurstCount(task) < autoContinueBurstLimit(task, executionResult, latestWorkerMetadata);
        log.info("[AutoContinue] task={} can_continue={} burst_count={} burst_limit={}",
            task.id(), canContinue, autoContinueBurstCount(task), autoContinueBurstLimit(task, executionResult, latestWorkerMetadata));
        return canContinue;
    }

    private boolean isAutoContinuableExecutionRound(Map<String, Object> latestWorkerMetadata) {
        if (latestWorkerMetadata == null || latestWorkerMetadata.isEmpty()) {
            return false;
        }
        boolean toolAwareRound = Boolean.parseBoolean(stringValue(latestWorkerMetadata.get("tool_aware_executor")))
            && "multi_tool_round".equalsIgnoreCase(stringValue(latestWorkerMetadata.get("tool_execution_mode")));
        if (toolAwareRound) {
            return true;
        }
        String executionBackend = stringValue(latestWorkerMetadata.get("execution_backend"));
        if ("provider_app_server".equalsIgnoreCase(executionBackend)
            || "provider_native_cli".equalsIgnoreCase(executionBackend)) {
            return true;
        }
        return firstNonBlank(
            stringValue(latestWorkerMetadata.get("provider_id")),
            stringValue(latestWorkerMetadata.get("provider_session_id")),
            stringValue(latestWorkerMetadata.get("provider_thread_id"))
        ) != null;
    }

    private boolean isOrchestrated(Task task) {
        return "orchestrated".equalsIgnoreCase(metadataString(task != null ? task.metadata() : null, "model_mode"));
    }

    private String orchestrationStage(Task task) {
        return metadataString(task != null ? task.metadata() : null, "orchestration_stage");
    }

    private boolean isPlannerStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return false;
        }
        return "plan_pending".equalsIgnoreCase(stage) || "planner_active".equalsIgnoreCase(stage);
    }

    private boolean isExecutionStage(String stage) {
        return stage != null && stage.toLowerCase().startsWith("execution");
    }

    private boolean isGroundedOutputRequired(Map<String, Object> metadata) {
        return Boolean.parseBoolean(stringValue(metadata.get("output_file_required")))
            || Boolean.parseBoolean(stringValue(metadata.get("output_dir_required")));
    }

    private int autoContinueBurstCount(Task task) {
        String value = metadataString(task != null ? task.metadata() : null, "auto_continue_burst_count");
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int autoContinueBurstLimit(Task task,
                                       WorkerExecutionResult executionResult,
                                       Map<String, Object> latestWorkerMetadata) {
        if (Boolean.parseBoolean(stringValue(latestWorkerMetadata.get("more_declared_rounds_remain")))) {
            Integer declaredRoundCount = metadataInt(latestWorkerMetadata, "declared_round_count");
            if (declaredRoundCount != null && declaredRoundCount > 1) {
                return Math.min(Math.max(declaredRoundCount, 2), 6);
            }
            return 3;
        }
        if (Boolean.parseBoolean(metadataString(task != null ? task.metadata() : null, "auto_multi_round"))) {
            return 3;
        }
        if (Boolean.parseBoolean(stringValue(latestWorkerMetadata.get("output_dir_required")))) {
            return Boolean.parseBoolean(stringValue(latestWorkerMetadata.get("image_input_used"))) ? 3 : 2;
        }
        if (Boolean.parseBoolean(stringValue(latestWorkerMetadata.get("output_file_required")))) {
            return 2;
        }
        return 1;
    }

    private Task incrementAutoContinueBurst(Task task) {
        return withMetadataEntries(task,
            "auto_continue_burst_count", autoContinueBurstCount(task) + 1
        );
    }

    private String resolveSelectionScope(Task task, String executionRole) {
        if (isOrchestrated(task)) {
            String stage = orchestrationStage(task);
            if (isPlannerStage(stage)) {
                return "planner";
            }
            if (isExecutionStage(stage)) {
                return "executor";
            }
            if ("completed".equalsIgnoreCase(stage)) {
                return "evaluator";
            }
        }
        String normalizedRole = firstNonBlank(executionRole, "executor");
        if ("planner".equalsIgnoreCase(normalizedRole) || normalizedRole.toLowerCase().contains("planner")) {
            return "planner";
        }
        return "executor";
    }

    private String resolveEvaluatorRole(Task task) {
        return isOrchestrated(task) ? "strong_evaluator" : "evaluator";
    }

    private String resolveEvaluatorModelTier(Task task) {
        return isOrchestrated(task) ? "strong" : firstNonBlank(
            metadataString(task != null ? task.metadata() : null, "review_model_tier"),
            metadataString(task != null ? task.metadata() : null, "planner_model_tier"),
            "strong"
        );
    }

    private String resolveEvaluatorReason(Task task, String decisionReason) {
        if (isOrchestrated(task)) {
            return firstNonBlank(
                "orchestrated mode uses strong-tier judgment to review delegated execution output",
                decisionReason
            );
        }
        return firstNonBlank(
            decisionReason,
            "judgment service reviewed the latest worker output"
        );
    }

    private boolean isOrchestrationClosedLoopObserved(Task task, String selectedModelTier) {
        if (!isOrchestrated(task)) {
            return false;
        }
        String plannerWorker = metadataString(task.metadata(), "planner_worker");
        String executorWorker = metadataString(task.metadata(), "executor_worker");
        String executorModelTier = firstNonBlank(
            metadataString(task.metadata(), "executor_model_tier"),
            selectedModelTier
        );
        return plannerWorker != null
            && !plannerWorker.isBlank()
            && executorWorker != null
            && !executorWorker.isBlank()
            && "small".equalsIgnoreCase(executorModelTier)
            && "strong".equalsIgnoreCase(resolveEvaluatorModelTier(task));
    }

    private String normalizeAlignment(String alignmentLevel) {
        String normalized = firstNonBlank(alignmentLevel, "medium");
        return "low".equalsIgnoreCase(normalized) ? "medium" : normalized;
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private Integer metadataInt(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean metadataBoolean(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return false;
        }
        Object value = metadata.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            String normalized = text.trim().toLowerCase();
            return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized);
        }
        return false;
    }

    private Long metadataLong(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final String EXEC_INSTANCE_KEY = "exec_instance";

    private long execInstance(Task task) {
        Long value = metadataLong(task != null ? task.metadata() : null, EXEC_INSTANCE_KEY);
        return value != null ? value : 0L;
    }

    Task bumpExecInstance(Task task) {
        return withMetadataEntries(task, EXEC_INSTANCE_KEY, execInstance(task) + 1L);
    }

    @SuppressWarnings("unchecked")
    private List<String> metadataStringList(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return List.of();
        }
        Object value = metadata.get(key);
        if (!(value instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }
        return rawList.stream()
            .filter(java.util.Objects::nonNull)
            .map(Object::toString)
            .filter(item -> !item.isBlank())
            .toList();
    }

    private String mergeReasons(String left, String right) {
        String normalizedLeft = firstNonBlank(left);
        String normalizedRight = firstNonBlank(right);
        if (normalizedLeft == null) {
            return normalizedRight;
        }
        if (normalizedRight == null) {
            return normalizedLeft;
        }
        return normalizedLeft + "; " + normalizedRight;
    }

    private String resolvePreassignedWorkerReason(Task task) {
        return firstNonBlank(
            metadataString(task != null ? task.metadata() : null, "preassigned_selection_reason"),
            metadataString(task != null ? task.metadata() : null, "orchestration_reason"),
            task != null && task.assignedWorker() != null
                ? "task already assigned to worker=" + task.assignedWorker()
                : null
        );
    }

    private MountedContextPromptMetrics buildJudgmentPromptMetrics(Task task, TaskRuntimeContext context) {
        PromptRenderingMode renderingMode = context != null
            ? PromptRenderingMode.resolve(context)
            : PromptRenderingMode.resolve(task);
        MountedContextPromptRenderResult mountedRenderResult = renderingMode.shouldRenderMountedPrompt()
            ? JUDGMENT_PROMPT_RENDERER.renderResult(context)
            : MountedContextPromptRenderResult.empty();
        return MountedContextPromptMetrics.from(context, renderingMode, mountedRenderResult);
    }

    private Map<String, Object> withJudgmentPromptMetadata(Map<String, Object> metadata,
                                                           MountedContextPromptMetrics metrics) {
        if (metrics == null) {
            return metadata;
        }
        Map<String, Object> enriched = metadata == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(metadata);
        for (Map.Entry<String, Object> entry : metrics.toMetadata().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                enriched.put(entry.getKey(), entry.getValue());
            }
        }
        return enriched;
    }

    private String appendProofSummary(String baseSummary, Map<String, Object> metadata) {
        String summary = firstNonBlank(baseSummary);
        String proof = buildProofSummary(metadata);
        if (summary == null) {
            return proof;
        }
        if (proof == null) {
            return summary;
        }
        return joinSummary(summary, proof);
    }

    private String appendEvidenceSummary(String baseSummary, Map<String, Object> proofMetadata, Map<String, Object> reopenMetadata) {
        return joinSummary(
            baseSummary,
            buildProofSummary(proofMetadata),
            buildReopenSummary(reopenMetadata)
        );
    }

    private String buildProofSummary(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        appendProofSummaryParts(parts, prefixedValues("tool", metadataStringList(metadata, "tool_invocation_ids")));
        appendProofSummaryParts(parts, prefixedValues("evidence", metadataStringList(metadata, "evidence_refs")));
        if (parts.isEmpty()) {
            return null;
        }
        return "proof=" + String.join(", ", parts);
    }

    private String buildReopenSummary(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        return buildReopenSummary(metadataStringList(metadata, "reopen_candidate_paths"));
    }

    private String buildReopenSummary(List<String> reopenCandidatePaths) {
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

    private List<String> reopenCandidatePaths(TaskRuntimeContext context) {
        if (context == null || context.mountedContextView() == null) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (var object : context.mountedContextView().objects(MountedContextPanelName.ARCHIVE_HANDLES)) {
            if (object == null) {
                continue;
            }
            for (String targetPath : reopenCandidatePaths(object)) {
                if (targetPath == null || targetPath.isBlank() || paths.contains(targetPath)) {
                    continue;
                }
                paths.add(targetPath);
                if (paths.size() >= 3) {
                    return List.copyOf(paths);
                }
            }
        }
        return List.copyOf(paths);
    }

    private List<String> reopenCandidatePaths(com.agentcloud.runtime.context.ContextObject object) {
        if (object == null) {
            return List.of();
        }
        List<String> paths = metadataStringList(object.metadata(), "reopen_candidate_paths");
        if (!paths.isEmpty()) {
            return paths;
        }
        String targetPath = stringValue(object.metadata().get("target_path"));
        if (targetPath != null && !targetPath.isBlank()) {
            return List.of(targetPath);
        }
        if (object.refs() == null || object.refs().isEmpty()) {
            return List.of();
        }
        List<String> refPaths = new ArrayList<>();
        for (var ref : object.refs()) {
            if (ref == null || ref.targetPath() == null || ref.targetPath().isBlank()) {
                continue;
            }
            refPaths.add(ref.targetPath());
        }
        return List.copyOf(refPaths);
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

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Map<String, Object> buildWorkerArtifactMetadata(WorkerExecutionResult executionResult, Object... entries) {
        Map<String, Object> metadata = metadataOf(entries);
        Map<String, Object> latestWorkerMetadata = mergeLatestWorkerMetadata(
            metadata,
            executionResult != null ? executionResult.metadata() : null
        );
        if (!latestWorkerMetadata.isEmpty()) {
            copyProviderDiagnosticMetadata(latestWorkerMetadata, metadata);
            metadata.put("latest_worker_metadata", latestWorkerMetadata);
        }
        return metadata;
    }

    private String artifactOutputText(WorkerExecutionResult executionResult) {
        if (executionResult == null) {
            return "";
        }
        Map<String, Object> metadata = executionResult.metadata();
        boolean providerOutputTruncated = Boolean.parseBoolean(String.valueOf(
            metadata != null ? metadata.get("provider_output_truncated") : null
        ));
        String outputText = executionResult.outputText() == null ? "" : executionResult.outputText();
        if (!providerOutputTruncated) {
            return outputText;
        }
        Object limit = metadata != null ? metadata.get("provider_output_sqlite_limit_chars") : null;
        String suffix = "\n\n[provider output truncated; full output is available via provider_stdout_path]";
        if (limit != null) {
            suffix = "\n\n[provider output truncated at " + limit + " chars; full output is available via provider_stdout_path]";
        }
        return outputText + suffix;
    }

    private void copyProviderDiagnosticMetadata(Map<String, Object> source, Map<String, Object> target) {
        copyMetadataKey(source, target, "provider_id");
        copyMetadataKey(source, target, "execution_backend");
        copyMetadataKey(source, target, "provider_session_id");
        copyMetadataKey(source, target, "provider_thread_id");
        copyMetadataKey(source, target, "resume_provider_session_id");
        copyMetadataKey(source, target, "provider_error");
        copyMetadataKey(source, target, "provider_turn_status");
        copyMetadataKey(source, target, "provider_timeout_kind");
        copyMetadataKey(source, target, "provider_abort_reason");
        copyMetadataKey(source, target, "provider_activity_timeout_ms");
        copyMetadataKey(source, target, "provider_turn_activity_timeout_ms");
        copyMetadataKey(source, target, "provider_turn_max_duration_ms");
        copyMetadataKey(source, target, "provider_failure_class");
        copyMetadataKey(source, target, "provider_failure_reason");
        copyMetadataKey(source, target, "provider_retryable");
        copyMetadataKey(source, target, "provider_output_parser");
        copyMetadataKey(source, target, "provider_protocol_trace");
        copyMetadataKey(source, target, "provider_output_truncated");
        copyMetadataKey(source, target, "provider_output_total_bytes");
        copyMetadataKey(source, target, "provider_output_capture_limit_bytes");
        copyMetadataKey(source, target, "provider_output_sqlite_limit_chars");
        copyMetadataKey(source, target, "provider_run_dir");
        copyMetadataKey(source, target, "provider_prompt_path");
        copyMetadataKey(source, target, "provider_stdout_path");
        copyMetadataKey(source, target, "provider_event_log_path");
        copyMetadataKey(source, target, "provider_last_message_path");
        copyMetadataKey(source, target, "provider_run_metadata_path");
    }

    private WorkerExecutionResult enrichCurrentRoundWorkerMetadata(Task task,
                                                                   WorkerRouter.RouteResult route,
                                                                   Worker selectedWorker,
                                                                   String selectedWorkerType,
                                                                   String selectedModelTier,
                                                                   String executionRole,
                                                                   String whySelected,
                                                                   String fallbackReason,
                                                                   WorkerExecutionResult executionResult) {
        if (executionResult == null) {
            return null;
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (executionResult.metadata() != null && !executionResult.metadata().isEmpty()) {
            metadata.putAll(executionResult.metadata());
        }
        putIfNonBlank(metadata, "selected_worker", task != null ? task.assignedWorker() : null);
        putIfNonBlank(metadata, "selected_worker_type", selectedWorkerType);
        putIfNonBlank(metadata, "selected_model_tier", selectedModelTier);
        putIfNonBlank(metadata, "execution_role", executionRole);
        putIfNonBlank(metadata, "why_selected", whySelected);
        putIfNonBlank(metadata, "preferred_worker_hint", route != null ? route.preferredWorkerHint() : null);
        if (route != null) {
            metadata.put("learning_hint_applied", route.learningHintApplied());
        }
        putIfNonBlank(metadata, "fallback_reason", fallbackReason);
        putIfNonBlank(metadata, "route_source", route != null ? route.routeSource() : "preassigned");
        putIfNonBlank(metadata, "model_mode", metadataString(task != null ? task.metadata() : null, "model_mode"));
        putIfNonBlank(metadata, "orchestration_stage", metadataString(task != null ? task.metadata() : null, "orchestration_stage"));
        putIfNonBlank(metadata, "planner_worker", metadataString(task != null ? task.metadata() : null, "planner_worker"));
        putIfNonBlank(metadata, "planner_model_tier", metadataString(task != null ? task.metadata() : null, "planner_model_tier"));
        putIfNonBlank(metadata, "executor_worker", metadataString(task != null ? task.metadata() : null, "executor_worker"));
        putIfNonBlank(metadata, "executor_model_tier", metadataString(task != null ? task.metadata() : null, "executor_model_tier"));
        putIfNonBlank(metadata, "target_worker", metadataString(task != null ? task.metadata() : null, "target_worker"));
        String taskRecoveryStage = metadataString(task != null ? task.metadata() : null, "recovery_stage");
        boolean currentRoundFailed = isFailedExecutionStatus(executionResult.executionStatus());
        putIfNonBlank(metadata, "execution_status", executionResult.executionStatus());
        if (currentRoundFailed) {
            copyMetadataKey(task != null ? task.metadata() : null, metadata, "failure_class");
            copyMetadataKey(task != null ? task.metadata() : null, metadata, "failure_summary_readable");
            copyMetadataKey(task != null ? task.metadata() : null, metadata, "recovery_policy");
            copyMetadataKey(task != null ? task.metadata() : null, metadata, "recovery_execution_mode");
            copyMetadataKey(task != null ? task.metadata() : null, metadata, "auto_same_worker_retry_count");
            copyMetadataKey(task != null ? task.metadata() : null, metadata, "auto_handoff_count");
            copyMetadataKey(task != null ? task.metadata() : null, metadata, "auto_handoff_target");
            copyMetadataKey(task != null ? task.metadata() : null, metadata, "previous_worker");
        }
        if (!currentRoundFailed && taskRecoveryStage != null) {
            metadata.put("recovery_stage", taskRecoveryStage + "_succeeded");
            metadata.remove("recovery_execution_mode");
        } else {
            putIfNonBlank(metadata, "recovery_stage", taskRecoveryStage);
        }
        if (route != null && route.candidateWorkers() != null && !route.candidateWorkers().isEmpty()) {
            metadata.putIfAbsent("candidate_workers", route.candidateWorkers());
        }
        if (route != null && route.fallbackWorkers() != null && !route.fallbackWorkers().isEmpty()) {
            metadata.putIfAbsent("fallback_workers", route.fallbackWorkers());
        }
        if (route != null && route.dispatchSkippedWorkers() != null && !route.dispatchSkippedWorkers().isEmpty()) {
            metadata.putIfAbsent("dispatch_skipped_workers", routeSkippedWorkerMetadata(route.dispatchSkippedWorkers()));
        }
        return new WorkerExecutionResult(
            executionResult.summary(),
            executionResult.outputText(),
            executionResult.producedArtifact(),
            executionResult.artifactTitle(),
            executionResult.artifactContent(),
            executionResult.suggestedNextStep(),
            executionResult.confidence(),
            executionResult.executionStatus(),
            executionResult.evidenceRefs(),
            executionResult.unfinishedItems(),
            executionResult.tokenUsage(),
            executionResult.durationMs(),
            Map.copyOf(metadata)
        );
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

    private List<WorkerRouter.RouteSkippedWorker> routeSkippedWorkers(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<WorkerRouter.RouteSkippedWorker> skippedWorkers = new ArrayList<>();
        for (Object item : iterable) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String workerId = stringValue(map.get("worker_id"));
            String reason = stringValue(map.get("reason"));
            if (workerId == null || workerId.isBlank()) {
                continue;
            }
            skippedWorkers.add(new WorkerRouter.RouteSkippedWorker(
                workerId,
                reason,
                stringValue(map.get("provider_failure_class")),
                stringValue(map.get("provider_failure_reason")),
                booleanValue(map.get("provider_retryable"))
            ));
        }
        return skippedWorkers.isEmpty() ? List.of() : List.copyOf(skippedWorkers);
    }

    private Map<String, Object> mergeLatestWorkerMetadata(Map<String, Object> primarySource,
                                                          Map<String, Object> secondarySource) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Map<String, Object> primary = selectLatestWorkerMetadata(primarySource);
        if (!primary.isEmpty()) {
            merged.putAll(primary);
        }
        Map<String, Object> secondary = selectLatestWorkerMetadata(secondarySource);
        if (!secondary.isEmpty()) {
            merged.putAll(secondary);
            if (!isFailedExecutionStatus(metadataString(secondary, "execution_status"))
                && !hasProviderFailureSignal(secondary)) {
                merged.remove("failure_class");
                merged.remove("failure_summary_readable");
                merged.remove("provider_failure_class");
                merged.remove("provider_failure_reason");
                merged.remove("provider_retryable");
            }
        }
        return merged.isEmpty() ? Map.of() : merged;
    }

    private boolean hasProviderFailureSignal(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        String providerError = metadataString(metadata, "provider_error");
        if (providerError != null && !providerError.isBlank()) {
            return true;
        }
        String turnStatus = metadataString(metadata, "provider_turn_status");
        return turnStatus != null
            && ("failed".equalsIgnoreCase(turnStatus)
            || "error".equalsIgnoreCase(turnStatus)
            || "timeout".equalsIgnoreCase(turnStatus)
            || "timed_out".equalsIgnoreCase(turnStatus));
    }

    private Map<String, Object> selectLatestWorkerMetadata(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        copyMetadataKey(source, selected, "tool_aware_executor");
        copyMetadataKey(source, selected, "tool_execution_mode");
        copyMetadataKey(source, selected, "prompt_rendering_mode");
        copyMetadataKey(source, selected, "mounted_context_mode");
        copyMetadataKey(source, selected, "prompt_mode");
        copyMetadataKey(source, selected, "mounted_context_rendered");
        copyMetadataKey(source, selected, "mounted_render_used");
        copyMetadataKey(source, selected, "mounted_context_injected");
        copyMetadataKey(source, selected, "mounted_context_panel_count");
        copyMetadataKey(source, selected, "mounted_panel_count");
        copyMetadataKey(source, selected, "mounted_context_non_empty_panel_count");
        copyMetadataKey(source, selected, "mounted_non_empty_panel_count");
        copyMetadataKey(source, selected, "mounted_context_selection_trace_count");
        copyMetadataKey(source, selected, MountedContextPromptBudgetSupport.RENDERED_PANEL_COUNT);
        copyMetadataKey(source, selected, MountedContextPromptBudgetSupport.HIDDEN_PANEL_COUNT);
        copyMetadataKey(source, selected, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT);
        copyMetadataKey(source, selected, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT);
        copyMetadataKey(source, selected, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT);
        copyMetadataKey(source, selected, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT);
        copyMetadataKey(source, selected, MountedContextPromptBudgetSupport.BUDGET_TRUNCATED);
        copyMetadataKey(source, selected, "mounted_pinned_count");
        copyMetadataKey(source, selected, "mounted_active_count");
        copyMetadataKey(source, selected, "mounted_ancestor_count");
        copyMetadataKey(source, selected, "mounted_sibling_count");
        copyMetadataKey(source, selected, "mounted_evidence_count");
        copyMetadataKey(source, selected, "mounted_index_count");
        copyMetadataKey(source, selected, "mounted_archive_count");
        copyMetadataKey(source, selected, "image_input_count");
        copyMetadataKey(source, selected, "image_input_used");
        copyMetadataKey(source, selected, "selected_worker");
        copyMetadataKey(source, selected, "selected_worker_type");
        copyMetadataKey(source, selected, "selected_model_tier");
        copyMetadataKey(source, selected, "execution_role");
        copyMetadataKey(source, selected, "why_selected");
        copyMetadataKey(source, selected, "candidate_workers");
        copyMetadataKey(source, selected, "fallback_workers");
        copyMetadataKey(source, selected, "preferred_worker_hint");
        copyMetadataKey(source, selected, "learning_hint_applied");
        copyMetadataKey(source, selected, "fallback_reason");
        copyMetadataKey(source, selected, "route_source");
        copyMetadataKey(source, selected, "provider_id");
        copyMetadataKey(source, selected, "execution_backend");
        copyMetadataKey(source, selected, "execution_id");
        copyMetadataKey(source, selected, "execution_started_at");
        copyMetadataKey(source, selected, "execution_finished_at");
        copyMetadataKey(source, selected, "execution_duration_ms");
        copyMetadataKey(source, selected, "duration_ms");
        copyMetadataKey(source, selected, "execution_status");
        copyMetadataKey(source, selected, "failure_summary_readable");
        copyMetadataKey(source, selected, "tool_invocation_id");
        copyMetadataKey(source, selected, "tool_invocation_ids");
        copyMetadataKey(source, selected, "provider_session_id");
        copyMetadataKey(source, selected, "provider_thread_id");
        copyMetadataKey(source, selected, "resume_provider_session_id");
        copyMetadataKey(source, selected, "provider_error");
        copyMetadataKey(source, selected, "provider_turn_status");
        copyMetadataKey(source, selected, "provider_timeout_kind");
        copyMetadataKey(source, selected, "provider_abort_reason");
        copyMetadataKey(source, selected, "provider_activity_timeout_ms");
        copyMetadataKey(source, selected, "provider_turn_activity_timeout_ms");
        copyMetadataKey(source, selected, "provider_turn_max_duration_ms");
        copyMetadataKey(source, selected, "provider_failure_class");
        copyMetadataKey(source, selected, "provider_failure_reason");
        copyMetadataKey(source, selected, "provider_retryable");
        copyMetadataKey(source, selected, "provider_output_parser");
        copyMetadataKey(source, selected, "provider_protocol_trace");
        copyMetadataKey(source, selected, "provider_output_truncated");
        copyMetadataKey(source, selected, "provider_output_total_bytes");
        copyMetadataKey(source, selected, "provider_output_capture_limit_bytes");
        copyMetadataKey(source, selected, "provider_output_sqlite_limit_chars");
        copyMetadataKey(source, selected, "provider_run_dir");
        copyMetadataKey(source, selected, "provider_prompt_path");
        copyMetadataKey(source, selected, "provider_stdout_path");
        copyMetadataKey(source, selected, "provider_event_log_path");
        copyMetadataKey(source, selected, "provider_last_message_path");
        copyMetadataKey(source, selected, "provider_run_metadata_path");
        copyMetadataKey(source, selected, "model_mode");
        copyMetadataKey(source, selected, "orchestration_stage");
        copyMetadataKey(source, selected, "planner_worker");
        copyMetadataKey(source, selected, "planner_model_tier");
        copyMetadataKey(source, selected, "executor_worker");
        copyMetadataKey(source, selected, "executor_model_tier");
        copyMetadataKey(source, selected, "target_worker");
        copyMetadataKey(source, selected, "tool_name");
        copyMetadataKey(source, selected, "tool_success");
        copyMetadataKey(source, selected, "tool_summary");
        copyMetadataKey(source, selected, "tool_plan_reason");
        copyMetadataKey(source, selected, "auto_write_generation_mode");
        copyMetadataKey(source, selected, "auto_write_generation_error");
        copyMetadataKey(source, selected, "output_file_required");
        copyMetadataKey(source, selected, "output_file_path");
        copyMetadataKey(source, selected, "output_file_exists");
        copyMetadataKey(source, selected, "output_file_size");
        copyMetadataKey(source, selected, "output_dir_required");
        copyMetadataKey(source, selected, "output_dir_path");
        copyMetadataKey(source, selected, "output_dir_exists");
        copyMetadataKey(source, selected, "output_dir_entry_count");
        copyMetadataKey(source, selected, "file_backed_artifact");
        copyMetadataKey(source, selected, "directory_backed_artifact");
        copyMetadataKey(source, selected, "evidence_refs");
        copyMetadataKey(source, selected, "unfinished_items");
        copyMetadataKey(source, selected, "grounded_output_present");
        copyMetadataKey(source, selected, "grounding_mode");
        copyMetadataKey(source, selected, "more_declared_rounds_remain");
        copyMetadataKey(source, selected, "current_round_requires_write");
        copyMetadataKey(source, selected, "missing_required_current_round_write");
        copyMetadataKey(source, selected, "current_round_instruction");
        copyMetadataKey(source, selected, "next_round_instruction");
        copyMetadataKey(source, selected, "tool_round_index");
        copyMetadataKey(source, selected, "declared_round_count");
        copyMetadataKey(source, selected, "max_tool_rounds");
        copyMetadataKey(source, selected, "tool_chain_step_count");
        copyMetadataKey(source, selected, "tool_chain_termination_reason");
        copyMetadataKey(source, selected, "tool_chain_trace");
        copyMetadataKey(source, selected, "failure_class");
        copyMetadataKey(source, selected, "failure_summary_readable");
        copyMetadataKey(source, selected, "recovery_policy");
        copyMetadataKey(source, selected, "recovery_stage");
        copyMetadataKey(source, selected, "auto_same_worker_retry_count");
        copyMetadataKey(source, selected, "auto_handoff_count");
        copyMetadataKey(source, selected, "auto_handoff_target");
        copyMetadataKey(source, selected, "previous_worker");
        return selected;
    }

    private Task mergeProviderContinuationMetadata(Task task, WorkerExecutionResult executionResult) {
        if (task == null || executionResult == null || executionResult.metadata() == null || executionResult.metadata().isEmpty()) {
            return task;
        }
        Map<String, Object> metadata = executionResult.metadata();
        String providerId = metadataString(metadata, "provider_id");
        String executionBackend = metadataString(metadata, "execution_backend");
        String providerSessionId = metadataString(metadata, "provider_session_id");
        String providerThreadId = metadataString(metadata, "provider_thread_id");
        String resumeProviderSessionId = metadataString(metadata, "resume_provider_session_id");
        return withMetadataEntries(task,
            "provider_id", providerId,
            "execution_backend", executionBackend,
            "provider_session_id", providerSessionId,
            "provider_thread_id", providerThreadId,
            "resume_provider_session_id", resumeProviderSessionId,
            "codex_thread_id", "codex".equalsIgnoreCase(providerId)
                ? firstNonBlank(providerThreadId, providerSessionId)
                : null
        );
    }

    private WorkerExecutionResult synthesizeFailedExecutionResult(Task task,
                                                                  WorkerRouter.RouteResult route,
                                                                  Worker selectedWorker,
                                                                  String selectedWorkerType,
                                                                  String selectedModelTier,
                                                                  String executionRole,
                                                                  String whySelected,
                                                                  String fallbackReason,
                                                                  RuntimeException error) {
        String readable = buildReadableFailureSummary(task, error);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("parser", "runtime_exception");
        metadata.put("failure_summary_readable", readable);
        metadata.put("output_text", readable);
        metadata.put("artifact_content", readable);
        WorkerExecutionResult failed = new WorkerExecutionResult(
            readable,
            readable,
            false,
            "",
            readable,
            "Inspect failure, retry, or handoff.",
            "low",
            "failed",
            List.of(),
            List.of("worker round failed"),
            0,
            0L,
            metadata
        );
        return enrichCurrentRoundWorkerMetadata(
            task,
            route,
            selectedWorker,
            selectedWorkerType,
            selectedModelTier,
            executionRole,
            whySelected,
            fallbackReason,
            failed
        );
    }

    private String buildReadableFailureSummary(Task task, RuntimeException error) {
        String worker = firstNonBlank(task != null ? task.assignedWorker() : null, "worker");
        String message = firstNonBlank(
            error != null ? error.getMessage() : null,
            error != null ? error.getClass().getSimpleName() : null,
            "unknown error"
        );
        return sanitizeReadableFailureSummary(worker, "worker " + worker + " failed: " + message);
    }

    private RecoveryDirective maybePlanFailureRecovery(Task task,
                                                       Map<String, Object> latestWorkerMetadata,
                                                       String latestOutput) {
        if (successfulCurrentRound(task, latestWorkerMetadata, latestOutput)) {
            return null;
        }
        String failureSummary = resolveRecoveryFailureText(latestWorkerMetadata, latestOutput);
        if (!isFailedExecutionStatus(metadataString(latestWorkerMetadata, "execution_status"))
            && plannerOutputRecoveryReason(task, latestWorkerMetadata, latestOutput) == null
            && !looksLikeLocalWorkspaceAccessRefusal(task, latestWorkerMetadata, failureSummary)) {
            return null;
        }
        String failureClass = classifyFailureClass(task, latestWorkerMetadata, failureSummary);
        String recoveryPolicy = "same_worker_retry_then_auto_handoff";
        String previousWorker = firstNonBlank(
            task != null ? task.assignedWorker() : null,
            metadataString(latestWorkerMetadata, "selected_worker")
        );
        int sameWorkerRetryCount = java.util.Optional.ofNullable(
            metadataInt(task != null ? task.metadata() : null, "auto_same_worker_retry_count")
        ).orElse(0);
        int autoHandoffCount = java.util.Optional.ofNullable(
            metadataInt(task != null ? task.metadata() : null, "auto_handoff_count")
        ).orElse(0);
        log.info(
            "[Recovery] task={} previousWorker={} failureClass={} sameWorkerRetryCount={} autoHandoffCount={} failureSummary={}",
            task != null ? task.id() : null,
            previousWorker,
            failureClass,
            sameWorkerRetryCount,
            autoHandoffCount,
            failureSummary
        );

        if ("worker_budget_exhausted".equals(failureClass)) {
            if (sameWorkerRetryCount < 1) {
                // 一次性兜底：超时可能是 CCX 网关一次性停顿；给同 worker 再一轮预算。
                log.info(
                    "[Recovery] task={} action=same_worker_retry(budget) worker={} reason={}",
                    task != null ? task.id() : null,
                    previousWorker,
                    failureSummary
                );
                com.agentcloud.judgment.model.ExecutionDecision budgetRetry = new com.agentcloud.judgment.model.ExecutionDecision(
                    "retry", "same_worker_retry", "", false, false, ""
                );
                com.agentcloud.judgment.model.CompletionDecision budgetComp = new com.agentcloud.judgment.model.CompletionDecision(
                    "incomplete", "low", "Round budget exhausted; one same-worker retry granted before human gate.", ""
                );
                return RecoveryDirective.sameWorkerRetry(
                    failureClass,
                    failureSummary,
                    recoveryPolicy,
                    previousWorker,
                    sameWorkerRetryCount + 1,
                    autoHandoffCount,
                    budgetRetry,
                    budgetComp
                );
            }
            // 已重试过：不再跨 sibling lane auto_handoff（同 provider/网关会重复同一过度探索），
            // 直接 human_gate 并给可操作原因，让操作员决定加预算 / 拆分任务 / 重新界定目标。
            String budgetGateReason = "Round budget exhausted (worker round timeout). If the worker was making "
                + "progress, raise HARNESS_WORKER_TIMEOUT_SECONDS or decompose the task; otherwise re-scope the goal. "
                + "Sibling-lane handoff skipped (same provider/gateway would repeat).";
            log.warn(
                "[Recovery] task={} action=human_gate(budget) previousWorker={} failureClass={} reason={}",
                task != null ? task.id() : null,
                previousWorker,
                failureClass,
                budgetGateReason
            );
            com.agentcloud.judgment.model.ExecutionDecision budgetGateExec = new com.agentcloud.judgment.model.ExecutionDecision(
                "human_gate", "escalate", "", false, true, ""
            );
            com.agentcloud.judgment.model.CompletionDecision budgetGateComp = new com.agentcloud.judgment.model.CompletionDecision(
                "incomplete", "low", budgetGateReason, ""
            );
            return RecoveryDirective.humanGate(
                "worker_budget_exhausted",
                budgetGateReason,
                recoveryPolicy,
                previousWorker,
                sameWorkerRetryCount,
                autoHandoffCount,
                null,
                budgetGateExec,
                budgetGateComp
            );
        }

        if ("worker_runtime_transient".equals(failureClass) && sameWorkerRetryCount < 1) {
            log.info(
                "[Recovery] task={} action=same_worker_retry worker={} reason={}",
                task != null ? task.id() : null,
                previousWorker,
                failureSummary
            );
            com.agentcloud.judgment.model.ExecutionDecision execDecision = new com.agentcloud.judgment.model.ExecutionDecision(
                "retry", "same_worker_retry", "", false, false, ""
            );
            com.agentcloud.judgment.model.CompletionDecision compDecision = new com.agentcloud.judgment.model.CompletionDecision(
                "incomplete", "low", "Transient failure, task incomplete", ""
            );
            return RecoveryDirective.sameWorkerRetry(
                failureClass,
                failureSummary,
                recoveryPolicy,
                previousWorker,
                sameWorkerRetryCount + 1,
                autoHandoffCount,
                execDecision,
                compDecision
            );
        }

        String handoffTarget = selectRecoveryHandoffTarget(task, latestWorkerMetadata, previousWorker);
        if ("worker_backend_deterministic".equals(failureClass) && autoHandoffCount < 1 && handoffTarget != null) {
            markWorkerTemporarilyUnavailable(task, previousWorker, failureSummary);
            com.agentcloud.judgment.model.ExecutionDecision execDecision = new com.agentcloud.judgment.model.ExecutionDecision(
                "handoff", "auto_handoff", "", false, false, handoffTarget
            );
            com.agentcloud.judgment.model.CompletionDecision compDecision = new com.agentcloud.judgment.model.CompletionDecision(
                "incomplete", "low", "Backend or capability limitation detected; fallback worker scheduled.", ""
            );
            log.info(
                "[Recovery] task={} action=auto_handoff previousWorker={} targetWorker={} reason={}",
                task != null ? task.id() : null,
                previousWorker,
                handoffTarget,
                failureSummary
            );
            return RecoveryDirective.autoHandoff(
                failureClass,
                failureSummary,
                recoveryPolicy,
                previousWorker,
                sameWorkerRetryCount,
                autoHandoffCount + 1,
                handoffTarget,
                execDecision,
                compDecision
            );
        }
        if ("worker_runtime_transient".equals(failureClass) && autoHandoffCount < 1 && handoffTarget != null) {
            markWorkerTemporarilyUnavailable(task, previousWorker, failureSummary);
            com.agentcloud.judgment.model.ExecutionDecision execDecision = new com.agentcloud.judgment.model.ExecutionDecision(
                "handoff", "auto_handoff", "", false, false, handoffTarget
            );
            com.agentcloud.judgment.model.CompletionDecision compDecision = new com.agentcloud.judgment.model.CompletionDecision(
                "incomplete", "low", "Transient failure, task incomplete", ""
            );
            log.info(
                "[Recovery] task={} action=auto_handoff previousWorker={} targetWorker={} reason={}",
                task != null ? task.id() : null,
                previousWorker,
                handoffTarget,
                failureSummary
            );
            return RecoveryDirective.autoHandoff(
                failureClass,
                failureSummary,
                recoveryPolicy,
                previousWorker,
                sameWorkerRetryCount,
                autoHandoffCount + 1,
                handoffTarget,
                execDecision,
                compDecision
            );
        }

        log.warn(
            "[Recovery] task={} action=human_gate previousWorker={} failureClass={} handoffTarget={} reason={}",
            task != null ? task.id() : null,
            previousWorker,
            failureClass,
            handoffTarget,
            failureSummary
        );

        com.agentcloud.judgment.model.ExecutionDecision execDecision = new com.agentcloud.judgment.model.ExecutionDecision(
            "human_gate", "escalate", "", false, true, ""
        );
        com.agentcloud.judgment.model.CompletionDecision compDecision = new com.agentcloud.judgment.model.CompletionDecision(
            "incomplete", "low", "Failure requires human intervention", ""
        );
        return RecoveryDirective.humanGate(
            firstNonBlank(failureClass, "worker_execution_failed"),
            failureSummary,
            recoveryPolicy,
            previousWorker,
            sameWorkerRetryCount,
            autoHandoffCount,
            handoffTarget,
            execDecision,
            compDecision
        );
    }

    private Task applyRecoveryDirective(Task task, RecoveryDirective directive) {
        if (task == null || directive == null) {
            return task;
        }
        Task updated = clearProviderContinuationMetadata(task);
        String currentAssignedWorker = task.assignedWorker();
        String nextAssignedWorker = directive.autoHandoff() && directive.handoffTarget() != null
            ? directive.handoffTarget()
            : currentAssignedWorker;
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("failure_class", directive.failureClass());
        metadata.put("failure_summary_readable", directive.failureSummaryReadable());
        metadata.put("recovery_policy", directive.recoveryPolicy());
        metadata.put("recovery_stage", directive.recoveryStage());
        metadata.put("recovery_execution_mode", directive.recoveryExecutionMode());
        if (directive.sameWorkerRetry() || directive.sameWorkerRetryCount() > 0) {
            metadata.put("auto_same_worker_retry_count", directive.sameWorkerRetryCount());
        }
        if (directive.autoHandoff()) {
            metadata.put("auto_handoff_count", directive.autoHandoffCount());
            metadata.put("auto_handoff_target", directive.handoffTarget());
        } else if (directive.handoffTarget() != null && !directive.handoffTarget().isBlank()) {
            metadata.put("manual_handoff_candidate", directive.handoffTarget());
        }
        metadata.put("previous_worker", directive.previousWorker());
        metadata.put("assigned_worker", nextAssignedWorker);
        metadata.put("target_worker", directive.autoHandoff() ? directive.handoffTarget() : metadataString(task.metadata(), "target_worker"));
        LinkedHashMap<String, Object> updatedMetadata = updated.metadata() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(updated.metadata());
        updatedMetadata.putAll(metadata);
        if (!directive.autoHandoff()) {
            updatedMetadata.remove("auto_handoff_target");
        }
        updated = updated.withMetadata(updatedMetadata);
        if (directive.autoHandoff() && directive.handoffTarget() != null) {
            updated = updated.withAssignedWorker(directive.handoffTarget());
        }
        return syncAssignedWorkerMetadata(updated);
    }

    private String resolveRecoveryFailureText(Map<String, Object> latestWorkerMetadata, String latestOutput) {
        String worker = firstNonBlank(
            metadataString(latestWorkerMetadata, "selected_worker"),
            metadataString(latestWorkerMetadata, "assigned_worker"),
            metadataString(latestWorkerMetadata, "provider_id"),
            "worker"
        );
        String providerRaw = firstNonBlank(
            metadataString(latestWorkerMetadata, "provider_error"),
            metadataString(latestWorkerMetadata, "provider_failure_reason"),
            metadataString(latestWorkerMetadata, "provider_turn_status")
        );
        if (providerRaw != null) {
            return sanitizeStructuredProviderFailureSummary(worker, providerRaw);
        }
        String raw = firstNonBlank(
            metadataString(latestWorkerMetadata, "failure_summary_readable"),
            metadataString(latestWorkerMetadata, "output_text"),
            metadataString(latestWorkerMetadata, "artifact_content"),
            latestOutput,
            metadataString(latestWorkerMetadata, "tool_summary"),
            metadataString(latestWorkerMetadata, "tool_plan_reason"),
            "worker execution failed"
        );
        return sanitizeReadableFailureSummary(worker, raw);
    }

    private String classifyFailureClass(Task task, Map<String, Object> latestWorkerMetadata, String failureSummary) {
        String providerFailureClass = metadataString(latestWorkerMetadata, "provider_failure_class");
        String executionStatus = metadataString(latestWorkerMetadata, "execution_status");
        if ("partial_timeout".equalsIgnoreCase(executionStatus)
            || "partial_timeout".equalsIgnoreCase(metadataString(latestWorkerMetadata, "provider_turn_status"))) {
            return "partial_result_or_quality_risk";
        }
        if (looksLikeLocalWorkspaceAccessRefusal(task, latestWorkerMetadata, failureSummary)) {
            return "worker_backend_deterministic";
        }
        if ("provider_runtime_transient".equals(providerFailureClass)
            || "provider_protocol_error".equals(providerFailureClass)) {
            return "worker_runtime_transient";
        }
        if ("provider_auth_required".equals(providerFailureClass)
            || "provider_not_installed".equals(providerFailureClass)) {
            return "task_environment_blocked";
        }
        if (looksLikeRoundBudgetTimeout(failureSummary)) {
            // 单轮预算超时不等于瞬时故障：同 worker/sibling lane 重试通常重复同一过度探索，
            // 必须在 empty output 检查之前判断，避免 readable 被 selectLatestWorkerMetadata 过滤后
            // 空输出分支误吞（output_text/failure_summary_readable 可能在白名单外）。
            return "worker_budget_exhausted";
        }
        if (looksLikeEmptyOutputFailure(latestWorkerMetadata)) {
            return "worker_runtime_transient";
        }
        if (looksLikeTransientWorkerRuntimeFailure(failureSummary)) {
            return "worker_runtime_transient";
        }
        if (looksLikePartialResultOrQualityRisk(latestWorkerMetadata)) {
            return "partial_result_or_quality_risk";
        }
        if (looksLikeTaskEnvironmentBlocked(latestWorkerMetadata, failureSummary)) {
            return "task_environment_blocked";
        }
        if (looksLikeWorkerBackendDeterministic(latestWorkerMetadata, failureSummary)) {
            return "worker_backend_deterministic";
        }
        return "worker_execution_failed";
    }

    private boolean looksLikeEmptyOutputFailure(Map<String, Object> latestWorkerMetadata) {
        String outputText = metadataString(latestWorkerMetadata, "output_text");
        String artifactContent = metadataString(latestWorkerMetadata, "artifact_content");
        String toolSummary = metadataString(latestWorkerMetadata, "tool_summary");
        
        return blankToNull(outputText) == null && blankToNull(artifactContent) == null && blankToNull(toolSummary) == null;
    }

    /**
     * 识别 executeOneRoundWithTimeout 抛出的单轮预算超时（消息前缀固定为
     * "Worker execution timed out after <N>s: worker="）。这类超时与 provider 瞬时故障不同，
     * 不应走 transient 的 sibling-lane auto_handoff。
     */
    private boolean looksLikeRoundBudgetTimeout(String failureSummary) {
        String normalized = blankToNull(failureSummary);
        if (normalized == null) {
            return false;
        }
        // summarizeKnownFailure compresses "Worker execution timed out after Ns: worker=X" into
        // "worker X failed: timeout".  Match the canonical form via the TIMEOUT pattern.
        return TIMEOUT.matcher(normalized).find();
    }

    private boolean looksLikeTransientWorkerRuntimeFailure(String failureSummary) {
        String normalized = blankToNull(failureSummary);
        if (normalized == null) {
            return false;
        }
        String text = normalized.toLowerCase();
        return text.contains("thread not found")
            || text.contains("session expired")
            || text.contains("provider unavailable")
            || text.contains("failed to start")
            || text.contains("connection reset")
            || text.contains("failed to start codex app-server")
            || text.contains("没找到线程")
            || text.contains("未找到线程");
    }

    private boolean looksLikeTaskEnvironmentBlocked(Map<String, Object> latestWorkerMetadata, String failureSummary) {
        String normalized = blankToNull(failureSummary);
        if (normalized == null) {
            normalized = firstNonBlank(
                metadataString(latestWorkerMetadata, "output_text"),
                metadataString(latestWorkerMetadata, "artifact_content"),
                metadataString(latestWorkerMetadata, "tool_summary"),
                metadataString(latestWorkerMetadata, "tool_plan_reason")
            );
        }
        if (normalized == null) {
            return false;
        }
        if (FILE_NOT_FOUND.matcher(normalized).find()) {
            return true;
        }
        String lower = normalized.toLowerCase();
        return lower.contains("no such file")
            || lower.contains("file not found")
            || lower.contains("directory not found")
            || lower.contains("path not found")
            || lower.contains("permission denied")
            || lower.contains("access is denied")
            || lower.contains("command not found")
            || lower.contains("not recognized as an internal or external command")
            || normalized.contains("文件不存在")
            || normalized.contains("目录不存在")
            || normalized.contains("命令不存在")
            || normalized.contains("权限不足")
            || normalized.contains("拒绝访问");
    }

    private boolean looksLikeWorkerBackendDeterministic(Map<String, Object> latestWorkerMetadata, String failureSummary) {
        String normalized = blankToNull(failureSummary);
        if (normalized == null) {
            normalized = firstNonBlank(
                metadataString(latestWorkerMetadata, "output_text"),
                metadataString(latestWorkerMetadata, "artifact_content"),
                metadataString(latestWorkerMetadata, "tool_summary"),
                metadataString(latestWorkerMetadata, "tool_plan_reason")
            );
        }
        if (normalized == null) {
            return false;
        }
        if (BACKEND_UNSUPPORTED.matcher(normalized).find()) {
            return true;
        }
        String lower = normalized.toLowerCase();
        return lower.contains("unsupported")
            || lower.contains("not supported")
            || lower.contains("missing capability")
            || lower.contains("capability missing")
            || lower.contains("tool unsupported")
            || lower.contains("does not support")
            || lower.contains("401")
            || lower.contains("invalid token")
            || lower.contains("authentication failed")
            || lower.contains("failed to authenticate")
            || lower.contains("unauthorized")
            || normalized.contains("工具不支持")
            || normalized.contains("能力不足")
            || normalized.contains("认证失败")
            || normalized.contains("不支持当前模式")
            || normalized.contains("不支持该工具");
    }

    private boolean looksLikePartialResultOrQualityRisk(Map<String, Object> latestWorkerMetadata) {
        if (latestWorkerMetadata == null || latestWorkerMetadata.isEmpty()) {
            return false;
        }
        if (metadataBoolean(latestWorkerMetadata, "grounded_output_present")
            || metadataBoolean(latestWorkerMetadata, "file_backed_artifact")
            || metadataBoolean(latestWorkerMetadata, "directory_backed_artifact")
            || metadataBoolean(latestWorkerMetadata, "produced_artifact")) {
            return true;
        }
        if (!metadataStringList(latestWorkerMetadata, "unfinished_items").isEmpty()) {
            return true;
        }
        return !metadataStringList(latestWorkerMetadata, "tool_invocation_ids").isEmpty();
    }

    private boolean looksLikeLocalWorkspaceAccessRefusal(Task task,
                                                         Map<String, Object> latestWorkerMetadata,
                                                         String text) {
        if (!prefersCodingRecovery(task, latestWorkerMetadata)) {
            return false;
        }
        String normalized = blankToNull(text);
        if (normalized == null) {
            normalized = firstNonBlank(
                metadataString(latestWorkerMetadata, "output_text"),
                metadataString(latestWorkerMetadata, "artifact_content"),
                metadataString(latestWorkerMetadata, "summary")
            );
        }
        if (normalized == null) {
            return false;
        }
        String lower = normalized.toLowerCase();
        return lower.contains("cannot access local file")
            || lower.contains("cannot access local path")
            || lower.contains("cannot access your local")
            || lower.contains("cannot access the local")
            || lower.contains("cannot directly access")
            || lower.contains("unable to access local")
            || lower.contains("unable to access your local")
            || lower.contains("no access to your local")
            || lower.contains("paste the document")
            || lower.contains("paste the content")
            || lower.contains("provide the document")
            || lower.contains("provide the file contents")
            || normalized.contains("无法直接访问")
            || normalized.contains("无法访问本地")
            || normalized.contains("无法访问您本地")
            || normalized.contains("无法访问你的本地")
            || normalized.contains("请补充以下信息")
            || normalized.contains("请粘贴")
            || normalized.contains("粘贴在这里");
    }

    private boolean isFailedExecutionStatus(String status) {
        String normalized = blankToNull(status);
        if (normalized == null) {
            return false;
        }
        return List.of("failed", "error", "timeout", "partial_timeout", "cancelled", "blocked", "empty")
            .contains(normalized.toLowerCase());
    }

    private void appendWorkerRoundMessage(Task task,
                                          Artifact artifact,
                                          AgentRunRecord agentRun,
                                          WorkerExecutionResult executionResult) {
        if (task == null || artifact == null || executionResult == null || sessionMessageDao == null) {
            return;
        }
        try {
            if (sessionMessageDao.findWorkerRoundByArtifactId(task.sessionId(), task.id(), artifact.id()) != null) {
                return;
            }
            Map<String, Object> artifactMetadata = artifact.metadata();
            Map<String, Object> latestWorkerMetadata = nestedMetadata(artifactMetadata, "latest_worker_metadata");
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source_surface", "control_node_graph");
            metadata.put("created_via", "worker_round_projection");
            metadata.put("artifact_id", artifact.id());
            metadata.put("artifact_type", artifact.artifactType());
            metadata.put("artifact_title", artifact.title());
            metadata.put("worker_id", firstNonBlank(task.assignedWorker(), metadataString(artifactMetadata, "worker_id")));
            metadata.put("execution_status", metadataString(artifactMetadata, "execution_status"));
            metadata.put("duration_ms", artifactMetadata.get("duration_ms"));
            metadata.put("output_chars", safeTextLength(metadataString(artifactMetadata, "output_text")));
            metadata.put("output_preview", previewText(
                firstNonBlank(
                    metadataString(artifactMetadata, "summary"),
                    metadataString(artifactMetadata, "output_text"),
                    artifact.summary()
                ),
                1_000
            ));
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_id");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_thread_id");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_session_id");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "resume_provider_session_id");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_error");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_turn_status");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_timeout_kind");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_abort_reason");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_activity_timeout_ms");
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
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_stdout_path");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_run_metadata_path");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "partial_output");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "partial_output_chars");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "partial_timeout_min_output_chars");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "truncated");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "provider_output_truncated");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "unfinished_items");
            copyFromArtifactOrLatest(metadata, artifactMetadata, latestWorkerMetadata, "suggested_next_step");
            if (agentRun != null) {
                metadata.put("agent_run_id", agentRun.runId());
                metadata.put("agent_run_status", agentRun.status());
            }
            String content = buildWorkerRoundMessageContent(task, artifact, metadata);
            sessionMessageDao.insert(new SessionMessage(
                IdGenerator.newId("msg"),
                task.sessionId(),
                task.id(),
                "assistant",
                "worker_round",
                content,
                Instant.now(),
                metadata
            ));
            sessionDao.touch(task.sessionId(), Instant.now());
        } catch (Exception e) {
            log.warn("Failed to append worker_round session message for task {}", task.id(), e);
        }
    }

    private String buildWorkerRoundMessageContent(Task task, Artifact artifact, Map<String, Object> metadata) {
        String worker = firstNonBlank(
            stringValue(metadata.get("worker_id")),
            task != null ? task.assignedWorker() : null,
            "worker"
        );
        String executionStatus = firstNonBlank(
            stringValue(metadata.get("execution_status")),
            "completed"
        );
        String outcome = switch (executionStatus.toLowerCase()) {
            case "partial_timeout" -> "达到时间上限，已保留部分输出";
            case "timeout" -> "执行超时";
            case "failed", "error" -> "执行失败";
            case "cancelled" -> "执行中断";
            default -> "完成一轮执行";
        };
        String preview = firstNonBlank(
            stringValue(metadata.get("output_preview")),
            artifact != null ? artifact.summary() : null
        );
        String nextStep = stringValue(metadata.get("suggested_next_step"));
        String readableFailure = sanitizeReadableFailureSummary(worker, firstNonBlank(
            metadataString(metadata, "provider_failure_reason"),
            metadataString(artifact != null ? artifact.metadata() : null, "summary"),
            preview
        ));
        if ("partial_timeout".equalsIgnoreCase(executionStatus)) {
            return firstNonBlank(
                previewText("Codex 执行回合已截断，保留部分输出。摘要：" + firstNonBlank(preview, readableFailure), 320),
                "Codex 执行回合已截断，保留部分输出。"
            );
        }
        String base = "worker " + worker + " " + outcome;
        if ("failed".equalsIgnoreCase(executionStatus)
            || "error".equalsIgnoreCase(executionStatus)
            || "timeout".equalsIgnoreCase(executionStatus)
            || "cancelled".equalsIgnoreCase(executionStatus)) {
            return previewText(base + "。原因：" + readableFailure, 320);
        }
        if (nextStep != null) {
            return previewText(base + "。下一步：" + nextStep, 320);
        }
        return previewText(base + "。摘要：" + firstNonBlank(preview, artifact != null ? artifact.summary() : null), 320);
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
            .filter(Objects::nonNull)
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

    private int safeTextLength(String value) {
        return value == null ? 0 : value.length();
    }

    private String previewText(String value, int limit) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        return normalized.length() > limit ? normalized.substring(0, limit).trim() + "..." : normalized;
    }

    private boolean successfulCurrentRound(Task task, Map<String, Object> latestWorkerMetadata, String latestOutput) {
        String executionStatus = metadataString(latestWorkerMetadata, "execution_status");
        if (isFailedExecutionStatus(executionStatus)) {
            return false;
        }
        boolean hasSuccessSignal = metadataBoolean(latestWorkerMetadata, "grounded_output_present")
            || List.of("completed", "complete", "succeeded", "success", "done")
                .contains(java.util.Optional.ofNullable(executionStatus).orElse("").toLowerCase());
        if (!hasSuccessSignal) {
            return false;
        }
        String currentOutput = firstNonBlank(
            latestOutput,
            metadataString(latestWorkerMetadata, "output_text"),
            metadataString(latestWorkerMetadata, "artifact_content"),
            metadataString(latestWorkerMetadata, "summary")
        );
        return !looksLikeTransientWorkerRuntimeFailure(currentOutput)
            && !looksLikeLocalWorkspaceAccessRefusal(task, latestWorkerMetadata, currentOutput);
    }

    private String sanitizeReadableFailureSummary(String worker, String raw) {
        String normalized = blankToNull(raw);
        if (normalized == null) {
            return "worker " + firstNonBlank(worker, "worker") + " failed";
        }
        String compact = normalized
            .replace('\r', '\n')
            .replace('\u0000', ' ')
            .trim();
        String core = firstFailureLine(compact);
        core = summarizeKnownFailure(firstNonBlank(worker, "worker"), core);
        core = stripNoise(core);
        core = compressWhitespace(core);
        if (core.length() > FAILURE_SUMMARY_LIMIT) {
            core = core.substring(0, FAILURE_SUMMARY_LIMIT).trim() + "...";
        }
        return core.isBlank() ? "worker " + firstNonBlank(worker, "worker") + " failed" : core;
    }

    private String sanitizeStructuredProviderFailureSummary(String worker, String raw) {
        String normalized = blankToNull(raw);
        String normalizedWorker = firstNonBlank(worker, "worker");
        if (normalized == null) {
            return "worker " + normalizedWorker + " failed";
        }
        String compact = normalized
            .replace('\r', '\n')
            .replace('\u0000', ' ')
            .trim();
        String core = firstFailureLine(compact);
        core = stripNoise(core);
        core = compressWhitespace(core);
        if (core.length() > FAILURE_SUMMARY_LIMIT) {
            core = core.substring(0, FAILURE_SUMMARY_LIMIT).trim() + "...";
        }
        return core.isBlank()
            ? "worker " + normalizedWorker + " failed"
            : "worker " + normalizedWorker + " failed: " + core;
    }

    private String firstFailureLine(String raw) {
        for (String line : raw.split("\\R")) {
            String trimmed = blankToNull(line);
            if (trimmed == null) {
                continue;
            }
            if (looksLikeNoiseLine(trimmed)) {
                continue;
            }
            return trimmed;
        }
        return raw;
    }

    private boolean looksLikeNoiseLine(String line) {
        String trimmed = blankToNull(line);
        if (trimmed == null) {
            return true;
        }
        if (trimmed.startsWith("---")
            || trimmed.startsWith("name:")
            || trimmed.startsWith("description:")
            || trimmed.startsWith("#")
            || trimmed.startsWith(".")
            || trimmed.matches("^[\\w.-]+\\\\[\\w .-]+$")) {
            return true;
        }
        return trimmed.endsWith(".md")
            || trimmed.endsWith(".png")
            || trimmed.endsWith(".log")
            || trimmed.endsWith(".json")
            || trimmed.endsWith(".java")
            || trimmed.endsWith(".js");
    }

    private String summarizeKnownFailure(String worker, String text) {
        String normalizedWorker = firstNonBlank(worker, "worker");
        Matcher enThreadWithParens = THREAD_NOT_FOUND_EN_WITH_PARENS.matcher(text);
        if (enThreadWithParens.find()) {
            String threadId = blankToNull(enThreadWithParens.group(1));
            return threadId == null
                ? "worker " + normalizedWorker + " failed: thread not found"
                : "worker " + normalizedWorker + " failed: thread not found (" + threadId + ")";
        }
        Matcher zhThread = THREAD_NOT_FOUND_ZH.matcher(text);
        if (zhThread.find()) {
            String threadId = blankToNull(zhThread.group(2));
            return threadId == null
                ? "worker " + normalizedWorker + " failed: thread not found"
                : "worker " + normalizedWorker + " failed: thread not found (" + threadId + ")";
        }
        if (looksLikeGarbledThreadNotFound(text)) {
            String threadId = extractQuotedThreadId(text);
            return threadId == null
                ? "worker " + normalizedWorker + " failed: thread not found"
                : "worker " + normalizedWorker + " failed: thread not found (" + threadId + ")";
        }
        Matcher enThread = THREAD_NOT_FOUND_EN.matcher(text);
        if (enThread.find()) {
            String threadId = blankToNull(enThread.group(1));
            return threadId == null
                ? "worker " + normalizedWorker + " failed: thread not found"
                : "worker " + normalizedWorker + " failed: thread not found (" + threadId + ")";
        }
        if (PROVIDER_UNAVAILABLE.matcher(text).find()) {
            return "worker " + normalizedWorker + " failed: provider unavailable";
        }
        if (SESSION_EXPIRED.matcher(text).find()) {
            return "worker " + normalizedWorker + " failed: session expired";
        }
        if (CONNECTION_RESET.matcher(text).find()) {
            return "worker " + normalizedWorker + " failed: connection reset";
        }
        if (FAILED_TO_START.matcher(text).find()) {
            return "worker " + normalizedWorker + " failed: backend failed to start";
        }
        if (TIMEOUT.matcher(text).find()) {
            return "worker " + normalizedWorker + " failed: timeout";
        }
        return text;
    }

    private boolean looksLikeGarbledThreadNotFound(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase();
        int replacementCount = (int) text.chars().filter(ch -> ch == '\uFFFD').count();
        boolean hasGarbledChineseMarker = normalized.contains("û") || normalized.contains("ҵ") || replacementCount >= 4;
        return hasGarbledChineseMarker && extractQuotedThreadId(text) != null;
    }

    private String extractQuotedThreadId(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = QUOTED_THREAD_ID.matcher(text);
        return matcher.find() ? blankToNull(matcher.group(1)) : null;
    }

    private String stripNoise(String text) {
        String sanitized = text;
        int fenceIndex = sanitized.indexOf("---");
        if (fenceIndex > 0) {
            sanitized = sanitized.substring(0, fenceIndex);
        }
        int docsIndex = sanitized.indexOf("docs\\");
        if (docsIndex > 0) {
            sanitized = sanitized.substring(0, docsIndex);
        }
        int rootListingIndex = sanitized.indexOf(".github");
        if (rootListingIndex > 0) {
            sanitized = sanitized.substring(0, rootListingIndex);
        }
        return sanitized.trim();
    }

    private String compressWhitespace(String text) {
        return text
            .replaceAll("[\\t\\x0B\\f]+", " ")
            .replaceAll(" {2,}", " ")
            .replaceAll("\\s*\\n\\s*", " ")
            .trim();
    }

    private String selectRecoveryHandoffTarget(Task task,
                                               Map<String, Object> latestWorkerMetadata,
                                               String currentWorker) {
        String currentProvider = firstNonBlank(
            metadataString(latestWorkerMetadata, "provider_id"),
            metadataString(task != null ? task.metadata() : null, "provider_id"),
            resolveProviderId(currentWorker)
        );
        boolean avoidCurrentProvider = shouldAvoidCurrentProvider(task, latestWorkerMetadata, currentProvider);
        String preferred = findRecoveryHandoffTarget(task, latestWorkerMetadata, currentWorker, currentProvider, avoidCurrentProvider);
        if (preferred != null) {
            return preferred;
        }
        if (avoidCurrentProvider) {
            log.info(
                "[Recovery] task={} no alternate provider candidate available currentProvider={}, falling back to same-provider candidates",
                task != null ? task.id() : null,
                currentProvider
            );
            return findRecoveryHandoffTarget(task, latestWorkerMetadata, currentWorker, currentProvider, false);
        }
        return null;
    }

    private String findRecoveryHandoffTarget(Task task,
                                             Map<String, Object> latestWorkerMetadata,
                                             String currentWorker,
                                             String currentProvider,
                                             boolean avoidCurrentProvider) {
        List<String> preferredCandidates = preferredRecoveryCandidates(task, latestWorkerMetadata, currentWorker);
        for (String candidate : preferredCandidates) {
            if (!candidate.equals(currentWorker)
                && isWorkerAvailable(candidate, "preferred_recovery_candidates")
                && acceptsRecoveryCandidate(candidate, currentProvider, avoidCurrentProvider, "preferred_recovery_candidates")) {
                return candidate;
            }
        }
        for (String candidate : metadataStringList(latestWorkerMetadata, "fallback_workers")) {
            if (!candidate.equals(currentWorker)
                && isWorkerAvailable(candidate, "latest_fallback_workers")
                && acceptsRecoveryCandidate(candidate, currentProvider, avoidCurrentProvider, "latest_fallback_workers")) {
                return candidate;
            }
        }
        for (String candidate : metadataStringList(latestWorkerMetadata, "candidate_workers")) {
            if (!candidate.equals(currentWorker)
                && isWorkerAvailable(candidate, "latest_candidate_workers")
                && acceptsRecoveryCandidate(candidate, currentProvider, avoidCurrentProvider, "latest_candidate_workers")) {
                return candidate;
            }
        }
        if (router == null || task == null) {
            return null;
        }
        Task unpinnedTask = withMetadataEntries(task.withAssignedWorker(null), "assigned_worker", null);
        WorkerRouter.RouteResult route = router.selectWorker(unpinnedTask);
        if (route == null) {
            return null;
        }
        for (String candidate : route.fallbackWorkers()) {
            if (!candidate.equals(currentWorker)
                && isWorkerAvailable(candidate, "router_fallback_workers")
                && acceptsRecoveryCandidate(candidate, currentProvider, avoidCurrentProvider, "router_fallback_workers")) {
                return candidate;
            }
        }
        for (String candidate : route.candidateWorkers()) {
            if (!candidate.equals(currentWorker)
                && isWorkerAvailable(candidate, "router_candidate_workers")
                && acceptsRecoveryCandidate(candidate, currentProvider, avoidCurrentProvider, "router_candidate_workers")) {
                return candidate;
            }
        }
        String selected = route.selectedWorker();
        if (selected != null
            && !selected.equals(currentWorker)
            && isWorkerAvailable(selected, "router_selected_worker")
            && acceptsRecoveryCandidate(selected, currentProvider, avoidCurrentProvider, "router_selected_worker")) {
            return selected;
        }
        return null;
    }

    private boolean shouldAvoidCurrentProvider(Task task,
                                               Map<String, Object> latestWorkerMetadata,
                                               String currentProvider) {
        if (blankToNull(currentProvider) == null || agentRunService == null) {
            return false;
        }
        String failureSummary = resolveRecoveryFailureText(latestWorkerMetadata, null);
        String failureClass = classifyFailureClass(task, latestWorkerMetadata, failureSummary);
        if (!"worker_runtime_transient".equals(failureClass)) {
            return false;
        }
        boolean avoid = agentRunService.shouldDeprioritizeProvider(currentProvider);
        if (avoid) {
            log.info(
                "[Recovery] task={} deprioritizing provider={} for transient failure recovery",
                task != null ? task.id() : null,
                currentProvider
            );
        }
        return avoid;
    }

    private boolean acceptsRecoveryCandidate(String candidateWorker,
                                             String currentProvider,
                                             boolean avoidCurrentProvider,
                                             String candidateSource) {
        if (!avoidCurrentProvider) {
            return true;
        }
        String candidateProvider = resolveProviderId(candidateWorker);
        if (candidateProvider == null || !candidateProvider.equalsIgnoreCase(currentProvider)) {
            return true;
        }
        log.info(
            "[Recovery] skip candidate worker={} source={} provider={} because current provider is temporarily deprioritized",
            candidateWorker,
            candidateSource,
            candidateProvider
        );
        return false;
    }

    private String resolveProviderId(String workerId) {
        Worker worker = blankToNull(workerId) == null || router == null ? null : router.getWorker(workerId);
        return com.agentcloud.agent.AgentProviderResolver.providerIdForWorker(
            workerId,
            worker != null ? worker.workerType() : workerId
        );
    }

    private List<String> preferredRecoveryCandidates(Task task,
                                                     Map<String, Object> latestWorkerMetadata,
                                                     String currentWorker) {
        if (!prefersCodingRecovery(task, latestWorkerMetadata)) {
            return List.of();
        }
        List<String> ordered = new java.util.ArrayList<>();
        appendCodingRecoveryCandidates(ordered, task, latestWorkerMetadata, currentWorker);
        return ordered;
    }

    private boolean prefersCodingRecovery(Task task, Map<String, Object> latestWorkerMetadata) {
        String taskType = firstNonBlank(
            metadataString(task != null ? task.metadata() : null, "task_type"),
            metadataString(latestWorkerMetadata, "task_type")
        );
        if ("coding".equalsIgnoreCase(blankToNull(taskType))) {
            return true;
        }
        String failureSummary = firstNonBlank(
            metadataString(latestWorkerMetadata, "failure_summary_readable"),
            metadataString(latestWorkerMetadata, "output_text"),
            task != null ? task.goal() : null,
            task != null ? task.title() : null
        );
        return looksLikeCodingRecoveryContext(task, latestWorkerMetadata, failureSummary);
    }

    private boolean looksLikeCodingRecoveryContext(Task task,
                                                   Map<String, Object> latestWorkerMetadata,
                                                   String text) {
        String normalized = blankToNull(text);
        if (normalized == null) {
            normalized = firstNonBlank(
                task != null ? task.goal() : null,
                task != null ? task.title() : null,
                metadataString(task != null ? task.metadata() : null, "goal"),
                metadataString(task != null ? task.metadata() : null, "workspace"),
                metadataString(task != null ? task.metadata() : null, "repo_path")
            );
        }
        if (normalized == null) {
            return false;
        }
        String lower = normalized.toLowerCase();
        return lower.contains("\\gitall\\")
            || lower.contains("\\src\\main\\")
            || lower.contains("/src/main/")
            || lower.contains(".java")
            || lower.contains(".js")
            || lower.contains(".ts")
            || lower.contains("articleeditor")
            || lower.contains("pom.xml")
            || lower.contains("package.json")
            || normalized.contains("修改")
            || normalized.contains("改代码")
            || normalized.contains("修复")
            || normalized.contains("实现")
            || normalized.contains("仓库")
            || normalized.contains("代码");
    }

    private void appendCodingRecoveryCandidates(List<String> ordered,
                                                Task task,
                                                Map<String, Object> latestWorkerMetadata,
                                                String currentWorker) {
        addRecoveryCandidate(ordered, task, metadataString(task != null ? task.metadata() : null, "preferred_worker"), currentWorker);
        addRecoveryCandidate(ordered, task, "codex", currentWorker);
        addRecoveryCandidate(ordered, task, "cursor", currentWorker);
        addRecoveryCandidate(ordered, task, "copilot", currentWorker);
        addRecoveryCandidate(ordered, task, "opencode", currentWorker);
        addRecoveryCandidate(ordered, task, "codebuddy", currentWorker);
        addRecoveryCandidate(ordered, task, "deepseek", currentWorker);
        addRecoveryCandidate(ordered, task, "claude", currentWorker);
        for (String candidate : metadataStringList(latestWorkerMetadata, "candidate_workers")) {
            if (isCodingCapableWorker(candidate) && !"openclaw-native".equals(candidate)) {
                addRecoveryCandidate(ordered, task, candidate, currentWorker);
            }
        }
        for (String candidate : metadataStringList(latestWorkerMetadata, "fallback_workers")) {
            if (isCodingCapableWorker(candidate) && !"openclaw-native".equals(candidate)) {
                addRecoveryCandidate(ordered, task, candidate, currentWorker);
            }
        }
    }

    private void addRecoveryCandidate(List<String> ordered, Task task, String candidate, String currentWorker) {
        String normalized = blankToNull(candidate);
        if (normalized == null || normalized.equals(currentWorker) || ordered.contains(normalized)) {
            return;
        }
        if (workerIsManualOnly(normalized)) {
            log.info(
                "[Recovery] skip candidate worker={} because auto_route_policy=manual_only",
                normalized
            );
            return;
        }
        if (requiresLocalWorkspaceAccess(task) && !workerHasLocalWorkspaceAccess(normalized)) {
            log.info(
                "[Recovery] skip candidate worker={} because local workspace access is required",
                normalized
            );
            return;
        }
        ordered.add(normalized);
    }

    private boolean requiresLocalWorkspaceAccess(Task task) {
        if (!TaskTypeHeuristics.looksLikeCodingTask(task)) {
            return false;
        }
        String text = firstNonBlank(
            task != null ? task.goal() : null,
            task != null ? task.title() : null,
            metadataString(task != null ? task.metadata() : null, "workspace_root"),
            metadataString(task != null ? task.metadata() : null, "workspace"),
            metadataString(task != null ? task.metadata() : null, "working_directory"),
            metadataString(task != null ? task.metadata() : null, "cwd"),
            metadataString(task != null ? task.metadata() : null, "repo_path")
        );
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("\\gitall\\")
            || lower.contains("\\src\\main\\")
            || lower.contains("/src/main/")
            || lower.contains("pom.xml")
            || lower.contains("package.json")
            || metadataString(task != null ? task.metadata() : null, "workspace_root") != null
            || metadataString(task != null ? task.metadata() : null, "workspace") != null
            || metadataString(task != null ? task.metadata() : null, "working_directory") != null
            || metadataString(task != null ? task.metadata() : null, "cwd") != null;
    }

    private boolean workerHasLocalWorkspaceAccess(String workerId) {
        if (blankToNull(workerId) == null || router == null) {
            return false;
        }
        Worker worker = router.getWorker(workerId);
        if (worker == null) {
            return false;
        }
        String explicit = workerMetadata(worker, "local_workspace_access");
        if (explicit != null) {
            return Boolean.parseBoolean(explicit);
        }
        return worker.toolScope() != null && !worker.toolScope().isEmpty()
            && worker.toolCapabilities() != null && !worker.toolCapabilities().isEmpty();
    }

    private boolean workerIsManualOnly(String workerId) {
        if (blankToNull(workerId) == null || router == null) {
            return false;
        }
        Worker worker = router.getWorker(workerId);
        String policy = workerMetadata(worker, "auto_route_policy");
        return "manual_only".equalsIgnoreCase(blankToNull(policy));
    }

    private Task applyManualWindowGate(Task task, WorkerRouter.RouteResult route) {
        String recommended = firstNonBlank(route != null ? route.recommendedManualProvider() : null, "trae");
        String instruction = firstNonBlank(
            route != null ? route.manualFollowupInstruction() : null,
            WorkerRouter.manualFollowupInstruction(recommended)
        );
        Task gated = withMetadataEntries(
            task,
            "manual_window_required", true,
            "recommended_manual_provider", recommended,
            "manual_window_candidates", route != null ? route.manualWindowCandidates() : List.of(),
            "manual_followup_instruction", instruction,
            "cost_route_stage", route != null ? route.costRouteStage() : "manual_window_recommendation",
            "provider_routing_wait_reason", firstNonBlank(route != null ? route.fallbackReason() : null, "manual window required")
        );
        return gated.withStatus("waiting_human")
            .withControlNode("human_gate")
            .withWaitingReason("manual window provider required: " + recommended);
    }

    private boolean isCodingCapableWorker(String workerId) {
        if (blankToNull(workerId) == null || router == null) {
            return false;
        }
        Worker worker = router.getWorker(workerId);
        return worker != null
            && worker.capabilities() != null
            && worker.capabilities().contains("coding");
    }

    private void copyMetadataKey(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private String workerMetadata(Worker worker, String key) {
        if (worker == null || worker.metadata() == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = worker.metadata().get(key);
        return value == null ? null : value.toString();
    }

    private String workerMetadata(String workerId, String key) {
        if (router == null || workerId == null || workerId.isBlank()) {
            return null;
        }
        return workerMetadata(router.getWorker(workerId), key);
    }

    /**
     * P2 Advisory Handoff: find available strong-tier advisory worker.
     * Returns workerId when currentModelTier is small, model_mode is not small_only/strong_only, and a ready strong-tier worker exists; null otherwise.
     */
    private String resolveAdvisoryHandoff(Task task, String currentModelTier) {
        String modelMode = metadataString(task != null ? task.metadata() : null, "model_mode");
        if ("small_only".equalsIgnoreCase(modelMode) || "strong_only".equalsIgnoreCase(modelMode)) {
            return null;
        }
        if (!"small".equalsIgnoreCase(currentModelTier)) {
            return null;
        }
        if (router == null) {
            return null;
        }
        for (Worker worker : router.listReadyWorkers()) {
            String tier = workerMetadata(worker, "model_tier");
            if ("strong".equalsIgnoreCase(tier) && !worker.suggestOnly()) {
                return worker.workerId();
            }
        }
        return null;
    }

    private AgentRunRecord recordCompletedAgentRun(Task task, WorkerRouter.RouteResult route, Worker selectedWorker,
                                                   WorkerExecutionResult result, Instant startedAt, Instant endedAt) {
        if (agentRunService == null) {
            return null;
        }
        try {
            return agentRunService.recordCompletedWorkerRun(task, route, selectedWorker, result, startedAt, endedAt);
        } catch (Exception e) {
            log.warn("Failed to record agent run for task {}", task != null ? task.id() : null, e);
            return null;
        }
    }

    private AgentRunRecord recordFailedAgentRun(Task task, WorkerRouter.RouteResult route, Worker selectedWorker,
                                                Instant startedAt, Instant endedAt, RuntimeException error) {
        if (agentRunService == null) {
            return null;
        }
        try {
            return agentRunService.recordFailedWorkerRun(task, route, selectedWorker, startedAt, endedAt, error);
        } catch (Exception e) {
            log.warn("Failed to record failed agent run for task {}", task != null ? task.id() : null, e);
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = stringValue(value);
        return text == null || text.isBlank() ? null : Boolean.parseBoolean(text);
    }

    private void emitEvent(Task task, String eventType, String summary) {
        emitEvent(task, eventType, summary, Map.of("control_node", eventType));
    }

    private void markWorkerTemporarilyUnavailable(Task task, String workerId, String failureSummary) {
        if (router == null || blankToNull(workerId) == null) {
            return;
        }
        router.markWorkerTemporarilyUnavailable(workerId, failureSummary);
        log.warn(
            "[Recovery] task={} worker={} marked temporarily unavailable reason={}",
            task != null ? task.id() : null,
            workerId,
            failureSummary
        );
    }

    private boolean isWorkerAvailable(String workerId, String candidateSource) {
        if (blankToNull(workerId) == null) {
            return false;
        }
        if (router == null) {
            log.info(
                "[Recovery] candidate worker={} source={} assumed available because router is not configured",
                workerId,
                candidateSource
            );
            return true;
        }
        boolean ready = router.isWorkerReady(workerId);
        if (!ready) {
            log.info(
                "[Recovery] skip candidate worker={} source={} readinessReason={}",
                workerId,
                candidateSource,
                router.workerReadinessReason(workerId)
            );
        }
        return ready;
    }

    private void emitEvent(Task task, String eventType, String summary, Map<String, Object> payload) {
        eventDao.insert(new Event(IdGenerator.newId("evt"), task.sessionId(), task.id(), Instant.now(),
            eventType, "control_node", null, summary, payload != null ? payload : Map.of("control_node", eventType)));
    }

    private Map<String, Object> metadataOf(Object... entries) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            Object key = entries[i];
            Object value = entries[i + 1];
            if (key != null && value != null) {
                metadata.put(key.toString(), value);
            }
        }
        return metadata;
    }

    private void putIfNonBlank(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    private Task syncAssignedWorkerMetadata(Task task) {
        if (task == null) {
            return null;
        }
        String assignedWorker = blankToNull(task.assignedWorker());
        String metadataAssignedWorker = blankToNull(metadataString(task.metadata(), "assigned_worker"));
        if (assignedWorker == null && metadataAssignedWorker != null) {
            return task.withAssignedWorker(metadataAssignedWorker);
        }
        if (Objects.equals(assignedWorker, metadataAssignedWorker)) {
            return task;
        }
        return withMetadataEntries(task, "assigned_worker", assignedWorker);
    }

    private Task normalizeWorkerAssignmentMetadata(Task task) {
        Task normalized = syncAssignedWorkerMetadata(task);
        if (normalized == null) {
            return null;
        }
        String assignedWorker = blankToNull(normalized.assignedWorker());
        String targetWorker = blankToNull(metadataString(normalized.metadata(), "target_worker"));
        String preassignedReason = blankToNull(metadataString(normalized.metadata(), "preassigned_selection_reason"));
        String previousWorker = blankToNull(metadataString(normalized.metadata(), "previous_worker"));
        boolean staleTargetWorker = assignedWorker != null
            && targetWorker != null
            && !assignedWorker.equals(targetWorker)
            && !assignedWorker.equals(previousWorker);
        if (!staleTargetWorker) {
            return normalized;
        }
        return withMetadataEntries(
            normalized,
            "target_worker", assignedWorker,
            "preassigned_selection_reason", preassignedReason != null && preassignedReason.contains(previousWorker != null ? previousWorker : "")
                ? "task already assigned to worker=" + assignedWorker
                : preassignedReason
        );
    }

    private record RecoveryDirective(
        String failureClass,
        String failureSummaryReadable,
        String recoveryPolicy,
        String recoveryStage,
        String recoveryExecutionMode,
        int sameWorkerRetryCount,
        int autoHandoffCount,
        String handoffTarget,
        String previousWorker,
        boolean sameWorkerRetry,
        boolean autoHandoff,
        com.agentcloud.judgment.model.ExecutionDecision executionDecision,
        com.agentcloud.judgment.model.CompletionDecision completionDecision
    ) {
        private static RecoveryDirective sameWorkerRetry(String failureClass,
                                                         String failureSummaryReadable,
                                                         String recoveryPolicy,
                                                         String previousWorker,
                                                         int sameWorkerRetryCount,
                                                         int autoHandoffCount,
                                                         com.agentcloud.judgment.model.ExecutionDecision executionDecision,
                                                         com.agentcloud.judgment.model.CompletionDecision completionDecision) {
            return new RecoveryDirective(
                failureClass,
                failureSummaryReadable,
                recoveryPolicy,
                "same_worker_retry_scheduled",
                "fresh_session",
                sameWorkerRetryCount,
                autoHandoffCount,
                null,
                previousWorker,
                true,
                false,
                executionDecision,
                completionDecision
            );
        }

        private static RecoveryDirective autoHandoff(String failureClass,
                                                     String failureSummaryReadable,
                                                     String recoveryPolicy,
                                                     String previousWorker,
                                                     int sameWorkerRetryCount,
                                                     int autoHandoffCount,
                                                     String handoffTarget,
                                                     com.agentcloud.judgment.model.ExecutionDecision executionDecision,
                                                     com.agentcloud.judgment.model.CompletionDecision completionDecision) {
            return new RecoveryDirective(
                failureClass,
                failureSummaryReadable,
                recoveryPolicy,
                "auto_handoff_scheduled",
                "fresh_session",
                sameWorkerRetryCount,
                autoHandoffCount,
                handoffTarget,
                previousWorker,
                false,
                true,
                executionDecision,
                completionDecision
            );
        }

        private static RecoveryDirective humanGate(String failureClass,
                                                   String failureSummaryReadable,
                                                   String recoveryPolicy,
                                                   String previousWorker,
                                                   int sameWorkerRetryCount,
                                                   int autoHandoffCount,
                                                   String handoffTarget,
                                                   com.agentcloud.judgment.model.ExecutionDecision executionDecision,
                                                   com.agentcloud.judgment.model.CompletionDecision completionDecision) {
            return new RecoveryDirective(
                failureClass,
                failureSummaryReadable,
                recoveryPolicy,
                "human_gate_required",
                null,
                sameWorkerRetryCount,
                autoHandoffCount,
                handoffTarget,
                previousWorker,
                false,
                false,
                executionDecision,
                completionDecision
            );
        }
    }

    private record OrchestrationJudgment(
        Task task,
        com.agentcloud.judgment.model.ExecutionDecision executionDecision,
        com.agentcloud.judgment.model.CompletionDecision completionDecision
    ) {}

    private record ActionReconciliationOutcome(
        WorkerExecutionResult executionResult,
        AgentActionReconciliationResult reconciliation
    ) {}
}