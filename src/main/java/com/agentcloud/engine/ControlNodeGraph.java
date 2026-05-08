package com.agentcloud.engine;

import com.agentcloud.engine.memory.PacketBuilder;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.judgment.JudgmentContext;
import com.agentcloud.judgment.JudgmentService;
import com.agentcloud.model.*;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.context.MountedContextPromptRenderResult;
import com.agentcloud.runtime.context.MountedContextPromptBudgetSupport;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime Control Node Graph
 * 6 个最小控制节点：Intake → Scheduler → Continue → [Packet / Human Gate / Handoff]
 */
public class ControlNodeGraph {
    private static final Logger log = LoggerFactory.getLogger(ControlNodeGraph.class);
    private static final MountedContextPromptRenderer JUDGMENT_PROMPT_RENDERER = new MountedContextPromptRenderer();
    private final TaskDao taskDao;
    private final EventDao eventDao;
    private final SessionDao sessionDao;
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

    public ControlNodeGraph(TaskDao taskDao, EventDao eventDao, SessionDao sessionDao,
                            ResumePacketDao packetDao, WorkerRouter router, PacketBuilder packetBuilder,
                            ConsolidationService consolidation,
                            WorkerExecutor workerExecutor, TaskRuntimeContextBuilder runtimeContextBuilder,
                            JudgmentService judgmentService,
                            ArtifactDao artifactDao, DecisionDao decisionDao,
                            LearningMemoryService learningMemoryService) {
        this(taskDao, eventDao, sessionDao, packetDao, router, packetBuilder, consolidation,
            workerExecutor, runtimeContextBuilder, judgmentService, artifactDao, decisionDao,
            learningMemoryService, null);
    }

    public ControlNodeGraph(TaskDao taskDao, EventDao eventDao, SessionDao sessionDao,
                            ResumePacketDao packetDao, WorkerRouter router, PacketBuilder packetBuilder,
                            ConsolidationService consolidation,
                            WorkerExecutor workerExecutor, TaskRuntimeContextBuilder runtimeContextBuilder,
                            JudgmentService judgmentService,
                            ArtifactDao artifactDao, DecisionDao decisionDao,
                            LearningMemoryService learningMemoryService,
                            AgentRunService agentRunService) {
        this.taskDao = taskDao;
        this.eventDao = eventDao;
        this.sessionDao = sessionDao;
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
        log.info("[Scheduler] task={}", task.id());
        emitEvent(task, "node_scheduler", "Scheduling task");
        WorkerExecutionResult executionResult = null;
        WorkerRouter.RouteResult route = null;

        // 自动路由 worker（如果还没分配）
        if (task.assignedWorker() == null || task.assignedWorker().isBlank()) {
            route = router.selectWorker(task);
            if (route.selectedWorker() != null) {
                task = task.withAssignedWorker(route.selectedWorker());
                log.info("[Scheduler] task={} routed to worker={}", task.id(), route.selectedWorker());
            }
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
            try {
                executionResult = workerExecutor.executeOneRound(ctx, task.assignedWorker());
                Task updatedTask = mergeProviderContinuationMetadata(task, executionResult);
                if (!sameState(task, updatedTask)) {
                    taskDao.updateState(updatedTask);
                    task = updatedTask;
                }
                agentRun = recordCompletedAgentRun(task, route, selectedWorker, executionResult, runStartedAt, Instant.now());
            } catch (RuntimeException e) {
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
                throw e;
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

            // 将输出写入 artifact
            if (hasMeaningfulOutput(executionResult)) {
                String summary = firstNonBlank(
                    executionResult.summary(),
                    executionResult.outputText(),
                    executionResult.artifactContent()
                );
                if (summary != null && summary.length() > 500) {
                    summary = summary.substring(0, 500) + "...";
                }
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
                        "output_text", executionResult.outputText(),
                        "artifact_content", executionResult.artifactContent(),
                        "parser", executionResult.metadata().getOrDefault("parser", "unknown")
                    )
                );
                artifactDao.insert(artifact);
            }
        } else {
            log.warn("[Scheduler] task={} has no assigned worker, skipping execution", task.id());
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

        // Execution Judgment
        var execDecision = judgmentService.judgeExecution(jctx);
        var completionDecision = judgmentService.judgeCompletion(jctx);
        OrchestrationJudgment orchestrationJudgment = applyOrchestrationJudgment(
            task, latestWorkerMetadata, executionResult, execDecision, completionDecision
        );
        task = orchestrationJudgment.task();
        execDecision = orchestrationJudgment.executionDecision();
        completionDecision = orchestrationJudgment.completionDecision();
        String evaluatorRole = resolveEvaluatorRole(task);
        String evaluatorModelTier = resolveEvaluatorModelTier(task);
        String evaluatorReason = resolveEvaluatorReason(task, completionDecision.reason());
        boolean orchestrationClosedLoopObserved = isOrchestrationClosedLoopObserved(task, selectedModelTier);
        log.info("[Continue] task={} executionDecision action={} reason={}",
            task.id(), execDecision.action(), execDecision.reason());
        log.info("[Continue] task={} completionDecision status={} alignment={} reason={}",
            task.id(), completionDecision.status(), completionDecision.alignmentLevel(), completionDecision.reason());

        // 记录 judgment 决策
        Decision judgmentRecord = new Decision(
            IdGenerator.newId("dec"), task.sessionId(), task.id(), Instant.now(),
            "execution_judgment",
            "Execution judgment: " + execDecision.action(),
            execDecision.reason(),
            "medium", null,
            withJudgmentPromptMetadata(metadataOf(
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
                "needs_human", execDecision.needsHuman(),
                "target_worker", firstNonBlank(execDecision.targetWorker(), task.assignedWorker()),
                "retry_decision", execDecision.retryDecision(),
                "escalation_decision", execDecision.escalationDecision()
            ), judgmentPromptMetrics)
        );
        decisionDao.insert(judgmentRecord);

        Decision completionRecord = new Decision(
            IdGenerator.newId("dec"), task.sessionId(), task.id(), Instant.now(),
            "completion_judgment",
            "Completion judgment: " + completionDecision.status(),
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
        if (!sameState(task, enrichedTask)) {
            taskDao.updateState(enrichedTask);
            task = enrichedTask;
        }

        // 根据 decision 选择下一状态迁移
        String resolvedAction = resolveAction(
            execDecision.action(), completionDecision.status(), completionDecision.alignmentLevel()
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
                if (shouldAutoContinueHandoff(moved)) {
                    yield handoffNode(moved, true);
                }
                yield moved;
            }
            case "escalate", "wait", "human_gate" -> {
                Task moved = task.withStatus("waiting_human").withControlNode("human_gate");
                taskDao.updateState(moved);
                yield moved;
            }
            case "continue" -> {
                Task moved = task.withControlNode("scheduler");
                taskDao.updateState(moved);
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

    private String resolveAction(String executionAction, String completionStatus, String alignmentLevel) {
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

    private Task enrichTaskFromJudgment(Task task, WorkerExecutionResult executionResult, String latestOutput,
                                        String executionNextStep, String completionNextAction) {
        Task updated = task;
        String summarySource = firstNonBlank(
            executionResult != null ? executionResult.summary() : null,
            latestOutput
        );
        if (summarySource != null && !summarySource.isBlank()) {
            String summary = summarySource.length() > 280 ? summarySource.substring(0, 280) + "..." : summarySource;
            updated = updated.withSummary(summary);
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
            metadataString(latestWorkerMetadata, "fallback_reason")
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

    // === Packet Node ===
    private Task packetNode(Task task) {
        return packetNode(task, "pause_before");
    }

    private Task packetNode(Task task, String checkpointType) {
        log.info("[Packet] task={}", task.id());
        emitEvent(task, "node_packet", "Generating packet before transition");

        Session session = sessionDao.findById(task.sessionId()).orElse(null);
        if (session != null) {
            ResumePacket packet = packetBuilder.buildResumePacket(task, session);
            packetDao.insert(packet);
        }

        // 在关键转移前触发 consolidation
        consolidation.consolidate(task, checkpointType);

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
        Task t = task.withStatus("paused").withControlNode("packet").withWaitingReason(reason);
        taskDao.updateState(t);
        return packetNode(t);
    }

    public Task triggerEscalate(Task task, String reason) {
        log.info("[Trigger] escalate task={} reason={}", task.id(), reason);
        persistTransitionPacket(task, "escalate_before");
        Task t = task.withStatus("waiting_human").withControlNode("human_gate").withWaitingReason(reason);
        taskDao.updateState(t);
        return humanGateNode(t);
    }

    public Task triggerHandoff(Task task, String targetWorker) {
        log.info("[Trigger] handoff task={} to worker={}", task.id(), targetWorker);
        persistTransitionPacket(task.withAssignedWorker(targetWorker), "handoff_before");
        Task t = task.withAssignedWorker(targetWorker).withControlNode("handoff");
        taskDao.updateState(t);
        return handoffNode(t);
    }

    public Task triggerResume(Task task) {
        log.info("[Trigger] resume task={}", task.id());
        Task t = task.withStatus("active").withControlNode("scheduler").withWaitingReason(null);
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

    private Task finalizeCompletedTask(Task task) {
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
        return router.selectWorker(withMetadataEntries(executionSelectionTask, "orchestration_stage", "execution_pending"));
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
        if (!Boolean.parseBoolean(stringValue(latestWorkerMetadata.get("tool_aware_executor")))) {
            return false;
        }
        if (!"multi_tool_round".equalsIgnoreCase(stringValue(latestWorkerMetadata.get("tool_execution_mode")))) {
            return false;
        }
        String terminationReason = stringValue(latestWorkerMetadata.get("tool_chain_termination_reason"));
        if ("repeated_tool_guard".equalsIgnoreCase(terminationReason)
            || "no_progress_guard".equalsIgnoreCase(terminationReason)) {
            return false;
        }
        if (Boolean.parseBoolean(stringValue(latestWorkerMetadata.get("more_declared_rounds_remain")))) {
            return false;
        }
        if (Boolean.parseBoolean(stringValue(latestWorkerMetadata.get("missing_required_current_round_write")))) {
            return false;
        }
        if (Boolean.parseBoolean(stringValue(latestWorkerMetadata.get("grounded_output_present")))) {
            return false;
        }
        if (!isGroundedOutputRequired(latestWorkerMetadata)) {
            return false;
        }
        return autoContinueBurstCount(task) < autoContinueBurstLimit(task, executionResult, latestWorkerMetadata);
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
        if (Boolean.parseBoolean(stringValue(latestWorkerMetadata.get("output_dir_required")))) {
            return Boolean.parseBoolean(stringValue(latestWorkerMetadata.get("image_input_used"))) ? 3 : 2;
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

    private Map<String, Object> buildWorkerArtifactMetadata(WorkerExecutionResult executionResult, Object... entries) {
        Map<String, Object> metadata = metadataOf(entries);
        Map<String, Object> latestWorkerMetadata = mergeLatestWorkerMetadata(
            metadata,
            executionResult != null ? executionResult.metadata() : null
        );
        if (!latestWorkerMetadata.isEmpty()) {
            metadata.put("latest_worker_metadata", latestWorkerMetadata);
        }
        return metadata;
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
        }
        return merged.isEmpty() ? Map.of() : merged;
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
        copyMetadataKey(source, selected, "tool_invocation_id");
        copyMetadataKey(source, selected, "tool_invocation_ids");
        copyMetadataKey(source, selected, "provider_session_id");
        copyMetadataKey(source, selected, "provider_thread_id");
        copyMetadataKey(source, selected, "resume_provider_session_id");
        copyMetadataKey(source, selected, "model_mode");
        copyMetadataKey(source, selected, "orchestration_stage");
        copyMetadataKey(source, selected, "planner_worker");
        copyMetadataKey(source, selected, "executor_worker");
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

    private void emitEvent(Task task, String eventType, String summary) {
        emitEvent(task, eventType, summary, Map.of("control_node", eventType));
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

    private record OrchestrationJudgment(
        Task task,
        com.agentcloud.judgment.model.ExecutionDecision executionDecision,
        com.agentcloud.judgment.model.CompletionDecision completionDecision
    ) {}
}
