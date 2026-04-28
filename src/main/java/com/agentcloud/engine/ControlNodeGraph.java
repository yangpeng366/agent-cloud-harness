package com.agentcloud.engine;

import com.agentcloud.engine.memory.PacketBuilder;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.judgment.JudgmentContext;
import com.agentcloud.judgment.JudgmentService;
import com.agentcloud.model.*;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
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

    public ControlNodeGraph(TaskDao taskDao, EventDao eventDao, SessionDao sessionDao,
                            ResumePacketDao packetDao, WorkerRouter router, PacketBuilder packetBuilder,
                            ConsolidationService consolidation,
                            WorkerExecutor workerExecutor, TaskRuntimeContextBuilder runtimeContextBuilder,
                            JudgmentService judgmentService,
                            ArtifactDao artifactDao, DecisionDao decisionDao,
                            LearningMemoryService learningMemoryService) {
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
            executionResult = workerExecutor.executeOneRound(ctx, task.assignedWorker());

            emitEvent(task, "worker_round",
                "Worker round completed. worker=" + task.assignedWorker()
                    + " outputLength=" + executionResult.outputText().length()
                    + " durationMs=" + executionResult.durationMs());

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
        JudgmentContext jctx = new JudgmentContext(task, ctx, latestOutput, null, latestWorkerMetadata);

        // Execution Judgment
        var execDecision = judgmentService.judgeExecution(jctx);
        var completionDecision = judgmentService.judgeCompletion(jctx);
        OrchestrationJudgment orchestrationJudgment = applyOrchestrationJudgment(
            task, latestWorkerMetadata, executionResult, execDecision, completionDecision
        );
        task = orchestrationJudgment.task();
        execDecision = orchestrationJudgment.executionDecision();
        completionDecision = orchestrationJudgment.completionDecision();
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
            metadataOf(
                "action", execDecision.action(),
                "judgment_actor", "judgment_service",
                "judgment_stage", "execution",
                "selected_worker", selectedWorkerId,
                "selected_model_tier", selectedModelTier,
                "execution_role", executionRole,
                "why_selected", whySelected,
                "fallback_reason", fallbackReason,
                "next_step", firstNonBlank(execDecision.nextStep(), executionResult != null ? executionResult.suggestedNextStep() : null),
                "needs_checkpoint", execDecision.needsCheckpoint(),
                "needs_human", execDecision.needsHuman(),
                "target_worker", firstNonBlank(execDecision.targetWorker(), task.assignedWorker())
            )
        );
        decisionDao.insert(judgmentRecord);

        Decision completionRecord = new Decision(
            IdGenerator.newId("dec"), task.sessionId(), task.id(), Instant.now(),
            "completion_judgment",
            "Completion judgment: " + completionDecision.status(),
            completionDecision.reason(),
            "medium", null,
            metadataOf(
                "judgment_actor", "judgment_service",
                "judgment_stage", "completion",
                "selected_worker", selectedWorkerId,
                "selected_model_tier", selectedModelTier,
                "execution_role", executionRole,
                "why_selected", whySelected,
                "fallback_reason", fallbackReason,
                "status", completionDecision.status(),
                "alignment_level", completionDecision.alignmentLevel(),
                "suggested_next_action", completionDecision.suggestedNextAction(),
                "evaluation_result", completionDecision.status() + ":" + completionDecision.alignmentLevel()
            )
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
                yield moved;
            }
            default -> {
                log.warn("[Continue] unknown action {}, fallback to scheduler", execDecision.action());
                Task moved = task.withControlNode("scheduler");
                taskDao.updateState(moved);
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
        if (!currentRoundMetadata.isEmpty()) {
            return currentRoundMetadata;
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
        return router.selectWorker(withMetadataEntries(task, "orchestration_stage", "execution_pending"));
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
        copyMetadataKey(source, selected, "selected_worker");
        copyMetadataKey(source, selected, "selected_worker_type");
        copyMetadataKey(source, selected, "selected_model_tier");
        copyMetadataKey(source, selected, "execution_role");
        copyMetadataKey(source, selected, "why_selected");
        copyMetadataKey(source, selected, "preferred_worker_hint");
        copyMetadataKey(source, selected, "learning_hint_applied");
        copyMetadataKey(source, selected, "fallback_reason");
        copyMetadataKey(source, selected, "route_source");
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
        copyMetadataKey(source, selected, "file_backed_artifact");
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

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private void emitEvent(Task task, String eventType, String summary) {
        eventDao.insert(new Event(IdGenerator.newId("evt"), task.sessionId(), task.id(), Instant.now(),
            eventType, "control_node", null, summary, Map.of("control_node", eventType)));
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

    private record OrchestrationJudgment(
        Task task,
        com.agentcloud.judgment.model.ExecutionDecision executionDecision,
        com.agentcloud.judgment.model.CompletionDecision completionDecision
    ) {}
}
