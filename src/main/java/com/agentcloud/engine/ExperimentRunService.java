package com.agentcloud.engine;

import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ExperimentRunRecord;
import com.agentcloud.model.ExperimentRunSummary;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ExperimentRunDao;
import com.agentcloud.store.ToolInvocationDao;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责把任务执行轨迹归并成可比较的 experiment run 记录。
 */
public class ExperimentRunService {
    private static final int QUERY_LIMIT = 1000;
    private static final double STRONG_ROUND_COST = 1.0;
    private static final double SMALL_ROUND_COST = 0.35;
    private static final double TOOL_ROUND_COST = 0.10;
    private static final double UNKNOWN_ROUND_COST = 0.60;

    private final ExperimentRunDao experimentRunDao;
    private final DecisionDao decisionDao;
    private final ArtifactDao artifactDao;
    private final EventDao eventDao;
    private final ToolInvocationDao toolInvocationDao;

    public ExperimentRunService(ExperimentRunDao experimentRunDao,
                                DecisionDao decisionDao,
                                ArtifactDao artifactDao,
                                EventDao eventDao,
                                ToolInvocationDao toolInvocationDao) {
        this.experimentRunDao = experimentRunDao;
        this.decisionDao = decisionDao;
        this.artifactDao = artifactDao;
        this.eventDao = eventDao;
        this.toolInvocationDao = toolInvocationDao;
    }

    public ExperimentRunRecord refresh(Task task) {
        if (task == null) {
            return null;
        }

        ExperimentRunRecord existing = experimentRunDao.findByTaskId(task.id()).orElse(null);
        List<Decision> decisions = decisionDao.listBySessionAndTask(task.sessionId(), task.id(), QUERY_LIMIT);
        List<Artifact> artifacts = artifactDao.listBySessionAndTask(task.sessionId(), task.id(), QUERY_LIMIT);
        List<Event> events = eventDao.listBySessionAndTask(task.sessionId(), task.id(), QUERY_LIMIT);
        List<ToolInvocationRecord> toolInvocations = toolInvocationDao != null
            ? toolInvocationDao.listBySessionAndTask(task.sessionId(), task.id(), QUERY_LIMIT)
            : List.of();

        Decision completionJudgment = latestDecision(decisions, "completion_judgment");
        Decision executionJudgment = latestDecision(decisions, "execution_judgment");
        Map<String, Object> latestWorkerMetadata = resolveLatestWorkerMetadata(artifacts);
        ToolChainSummary toolChainSummary = resolveToolChainSummary(latestWorkerMetadata, toolInvocations);
        Artifact latestWorkerArtifact = latestWorkerArtifact(artifacts);

        int workerRoundCount = 0;
        int strongRoundCount = 0;
        int smallRoundCount = 0;
        int toolWorkerRoundCount = 0;
        int unknownRoundCount = 0;
        double strongCost = 0.0;
        double smallCost = 0.0;
        double toolWorkerCost = 0.0;
        double unknownCost = 0.0;

        for (Artifact artifact : artifacts) {
            if (!isWorkerRoundArtifact(artifact)) {
                continue;
            }
            workerRoundCount++;
            String tier = metadataString(artifact.metadata(), "selected_model_tier");
            if ("strong".equalsIgnoreCase(tier)) {
                strongRoundCount++;
                strongCost += STRONG_ROUND_COST;
            } else if ("small".equalsIgnoreCase(tier)) {
                smallRoundCount++;
                smallCost += SMALL_ROUND_COST;
            } else if ("tool".equalsIgnoreCase(tier)) {
                toolWorkerRoundCount++;
                toolWorkerCost += TOOL_ROUND_COST;
            } else {
                unknownRoundCount++;
                unknownCost += UNKNOWN_ROUND_COST;
            }
        }

        int toolInvocationCount = toolInvocations.size();
        double toolInvocationCost = toolInvocationCount * TOOL_ROUND_COST;
        double totalCost = roundToThree(strongCost + smallCost + toolWorkerCost + unknownCost + toolInvocationCost);
        int handoffCount = countEvents(events, "node_handoff");
        int resumeCount = countResumeActions(events);
        int humanGateCount = countEvents(events, "node_human_gate");
        String acceptanceResult = deriveAcceptanceResult(task, completionJudgment);
        String qualityNote = deriveQualityNote(task, completionJudgment);
        String failureReason = deriveFailureReason(task, acceptanceResult, completionJudgment, executionJudgment, events);
        Boolean recoverySuccess = resumeCount > 0 ? "done".equalsIgnoreCase(task.status()) : null;
        double strongModelCostRatio = totalCost <= 0.0 ? 0.0 : roundToThree(strongCost / totalCost);
        String evaluatorRole = metadataString(completionJudgment != null ? completionJudgment.metadata() : null, "evaluator_role");
        String evaluatorModelTier = metadataString(completionJudgment != null ? completionJudgment.metadata() : null, "evaluator_model_tier");
        String evaluatorReason = metadataString(completionJudgment != null ? completionJudgment.metadata() : null, "evaluator_reason");
        String evaluationReason = metadataString(completionJudgment != null ? completionJudgment.metadata() : null, "evaluation_reason");
        String executionAction = metadataString(executionJudgment != null ? executionJudgment.metadata() : null, "action");
        String executionNextStep = metadataString(executionJudgment != null ? executionJudgment.metadata() : null, "next_step");
        Boolean executionNeedsCheckpoint = metadataBoolean(
            executionJudgment != null ? executionJudgment.metadata() : null,
            "needs_checkpoint"
        );
        Boolean executionNeedsHuman = metadataBoolean(
            executionJudgment != null ? executionJudgment.metadata() : null,
            "needs_human"
        );
        String completionJudgmentStatus = metadataString(
            completionJudgment != null ? completionJudgment.metadata() : null,
            "status"
        );
        String completionAlignmentLevel = metadataString(
            completionJudgment != null ? completionJudgment.metadata() : null,
            "alignment_level"
        );
        String completionSuggestedNextAction = metadataString(
            completionJudgment != null ? completionJudgment.metadata() : null,
            "suggested_next_action"
        );
        boolean hasRouteEvidence = hasRouteEvidence(latestWorkerMetadata);
        boolean hasExecutionJudgment = executionJudgment != null;
        boolean hasCompletionJudgment = completionJudgment != null;
        Boolean orchestrationClosedLoopObserved = deriveOrchestrationClosedLoopObserved(
            task, completionJudgment, latestWorkerMetadata
        );
        String orchestrationProofSummary = deriveOrchestrationProofSummary(
            task, latestWorkerMetadata, evaluatorRole, evaluatorModelTier, orchestrationClosedLoopObserved
        );
        boolean hasClosedLoopEvidenceChain = hasRouteEvidence && hasExecutionJudgment && hasCompletionJudgment;
        String judgmentEvidenceChain = buildJudgmentEvidenceChain(
            latestWorkerMetadata,
            executionJudgment,
            completionJudgment
        );
        String closedLoopProofSummary = buildClosedLoopProofSummary(
            task,
            latestWorkerMetadata,
            executionJudgment,
            completionJudgment,
            toolChainSummary,
            hasRouteEvidence,
            hasExecutionJudgment,
            hasCompletionJudgment,
            hasClosedLoopEvidenceChain,
            orchestrationProofSummary
        );

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("cost_basis", "heuristic_worker_round_v1");
        metadata.put("worker_round_count", workerRoundCount);
        metadata.put("tool_invocation_count", toolInvocationCount);
        metadata.put("strong_round_count", strongRoundCount);
        metadata.put("small_round_count", smallRoundCount);
        metadata.put("tool_worker_round_count", toolWorkerRoundCount);
        metadata.put("unknown_round_count", unknownRoundCount);
        metadata.put("strong_cost_units", roundToThree(strongCost));
        metadata.put("small_cost_units", roundToThree(smallCost));
        metadata.put("tool_worker_cost_units", roundToThree(toolWorkerCost));
        metadata.put("tool_invocation_cost_units", roundToThree(toolInvocationCost));
        metadata.put("unknown_cost_units", roundToThree(unknownCost));
        metadata.put("latest_evaluation_result", metadataString(
            completionJudgment != null ? completionJudgment.metadata() : null,
            "evaluation_result"
        ));
        if (evaluationReason != null) {
            metadata.put("evaluation_reason", evaluationReason);
        }
        if (executionAction != null) {
            metadata.put("execution_judgment_action", executionAction);
        }
        if (executionNextStep != null) {
            metadata.put("execution_judgment_next_step", executionNextStep);
        }
        if (executionNeedsCheckpoint != null) {
            metadata.put("execution_judgment_needs_checkpoint", executionNeedsCheckpoint);
        }
        if (executionNeedsHuman != null) {
            metadata.put("execution_judgment_needs_human", executionNeedsHuman);
        }
        if (completionJudgmentStatus != null) {
            metadata.put("completion_judgment_status", completionJudgmentStatus);
        }
        if (completionAlignmentLevel != null) {
            metadata.put("completion_alignment_level", completionAlignmentLevel);
        }
        if (completionSuggestedNextAction != null) {
            metadata.put("completion_suggested_next_action", completionSuggestedNextAction);
        }
        metadata.put("has_route_evidence", hasRouteEvidence);
        metadata.put("has_execution_judgment", hasExecutionJudgment);
        metadata.put("has_completion_judgment", hasCompletionJudgment);
        metadata.put("has_closed_loop_evidence_chain", hasClosedLoopEvidenceChain);
        if (judgmentEvidenceChain != null) {
            metadata.put("judgment_evidence_chain", judgmentEvidenceChain);
        }
        if (closedLoopProofSummary != null) {
            metadata.put("closed_loop_proof_summary", closedLoopProofSummary);
        }
        Map<String, Object> closedLoopEvidence = buildClosedLoopEvidence(
            task,
            latestWorkerMetadata,
            latestWorkerArtifact,
            executionJudgment,
            completionJudgment,
            toolChainSummary,
            toolInvocations,
            hasRouteEvidence,
            hasExecutionJudgment,
            hasCompletionJudgment,
            hasClosedLoopEvidenceChain,
            orchestrationClosedLoopObserved,
            judgmentEvidenceChain,
            closedLoopProofSummary,
            orchestrationProofSummary
        );
        if (!closedLoopEvidence.isEmpty()) {
            metadata.put("closed_loop_evidence", closedLoopEvidence);
        }
        if (evaluatorRole != null) {
            metadata.put("evaluator_role", evaluatorRole);
        }
        if (evaluatorModelTier != null) {
            metadata.put("evaluator_model_tier", evaluatorModelTier);
        }
        if (evaluatorReason != null) {
            metadata.put("evaluator_reason", evaluatorReason);
        }
        if (orchestrationClosedLoopObserved != null) {
            metadata.put("orchestration_closed_loop_observed", orchestrationClosedLoopObserved);
        }
        if (orchestrationProofSummary != null) {
            metadata.put("orchestration_proof_summary", orchestrationProofSummary);
        }
        metadata.put("current_control_node", task.controlNode());
        metadata.put("assigned_worker", task.assignedWorker());
        copyLatestWorkerMetadata(latestWorkerMetadata, metadata);
        if (toolChainSummary.executionMode() != null) {
            metadata.put("tool_execution_mode", toolChainSummary.executionMode());
        }
        if (toolChainSummary.stepCount() != null) {
            metadata.put("tool_chain_step_count", toolChainSummary.stepCount());
        }
        if (toolChainSummary.terminationReason() != null) {
            metadata.put("tool_chain_termination_reason", toolChainSummary.terminationReason());
        }
        if (toolChainSummary.traceSummary() != null) {
            metadata.put("tool_chain_trace_summary", toolChainSummary.traceSummary());
        }
        if (!toolChainSummary.toolNames().isEmpty()) {
            metadata.put("tool_chain_tools", toolChainSummary.toolNames());
        }
        if (task.metadata() != null) {
            copyMetadata(task.metadata(), metadata, "experiment_name");
            copyMetadata(task.metadata(), metadata, "task_case_key");
            copyMetadata(task.metadata(), metadata, "task_length_bucket");
            copyMetadata(task.metadata(), metadata, "model_mode");
            copyMetadata(task.metadata(), metadata, "orchestration_stage");
            copyMetadata(task.metadata(), metadata, "planner_worker");
            copyMetadata(task.metadata(), metadata, "executor_worker");
        }

        Instant createdAt = existing != null ? existing.createdAt() : Instant.now();
        ExperimentRunRecord record = new ExperimentRunRecord(
            existing != null ? existing.id() : IdGenerator.newId("xrun"),
            task.sessionId(),
            task.id(),
            firstNonBlank(
                metadataString(task.metadata(), "experiment_name"),
                metadataString(task.metadata(), "eval_name")
            ),
            resolveTaskCaseKey(task),
            firstNonBlank(task.title(), task.id()),
            metadataString(task.metadata(), "task_type"),
            firstNonBlank(
                metadataString(task.metadata(), "task_length_bucket"),
                metadataString(task.metadata(), "length_bucket"),
                "unspecified"
            ),
            normalizeModelMode(metadataString(task.metadata(), "model_mode")),
            workerRoundCount + toolInvocationCount,
            firstNonBlank(task.status(), "active"),
            acceptanceResult,
            totalCost,
            strongModelCostRatio,
            handoffCount,
            resumeCount,
            humanGateCount,
            failureReason,
            recoverySuccess,
            qualityNote,
            createdAt,
            Instant.now(),
            metadata
        );
        experimentRunDao.upsert(record);
        return experimentRunDao.findByTaskId(task.id()).orElse(record);
    }

    public ExperimentRunRecord getByTaskId(String taskId) {
        return experimentRunDao.findByTaskId(taskId).orElse(null);
    }

    public List<ExperimentRunRecord> listRuns(String experimentName,
                                              String taskCaseKey,
                                              String taskLengthBucket,
                                              String modelMode,
                                              int limit) {
        return listRuns(
            experimentName,
            taskCaseKey,
            taskLengthBucket,
            modelMode,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            limit
        );
    }

    public List<ExperimentRunRecord> listRuns(String experimentName,
                                              String taskCaseKey,
                                              String taskLengthBucket,
                                              String modelMode,
                                              String completionStatus,
                                              String acceptanceResult,
                                              Boolean failureReasonPresent,
                                              Boolean recoverySuccess,
                                              int limit) {
        return listRuns(
            experimentName,
            taskCaseKey,
            taskLengthBucket,
            modelMode,
            completionStatus,
            acceptanceResult,
            failureReasonPresent,
            recoverySuccess,
            null,
            null,
            null,
            null,
            limit
        );
    }

    public List<ExperimentRunRecord> listRuns(String experimentName,
                                              String taskCaseKey,
                                              String taskLengthBucket,
                                              String modelMode,
                                              String completionStatus,
                                              String acceptanceResult,
                                              Boolean failureReasonPresent,
                                              Boolean recoverySuccess,
                                              String routeSource,
                                              Boolean orchestrationClosedLoopObserved,
                                              int limit) {
        return listRuns(
            experimentName,
            taskCaseKey,
            taskLengthBucket,
            modelMode,
            completionStatus,
            acceptanceResult,
            failureReasonPresent,
            recoverySuccess,
            routeSource,
            orchestrationClosedLoopObserved,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            limit
        );
    }

    public List<ExperimentRunRecord> listRuns(String experimentName,
                                              String taskCaseKey,
                                              String taskLengthBucket,
                                              String modelMode,
                                              String toolExecutionMode,
                                              String toolChainTerminationReason,
                                              Integer minToolChainSteps,
                                              Integer maxToolChainSteps,
                                              int limit) {
        return listRuns(
            experimentName,
            taskCaseKey,
            taskLengthBucket,
            modelMode,
            null,
            null,
            null,
            null,
            toolExecutionMode,
            toolChainTerminationReason,
            minToolChainSteps,
            maxToolChainSteps,
            limit
        );
    }

    public List<ExperimentRunRecord> listRuns(String experimentName,
                                              String taskCaseKey,
                                              String taskLengthBucket,
                                              String modelMode,
                                              String completionStatus,
                                              String acceptanceResult,
                                              Boolean failureReasonPresent,
                                              Boolean recoverySuccess,
                                              String routeSource,
                                              Boolean orchestrationClosedLoopObserved,
                                              Boolean hasRouteEvidence,
                                              Boolean hasExecutionJudgment,
                                              Boolean hasCompletionJudgment,
                                              Boolean hasClosedLoopEvidenceChain,
                                              int limit) {
        return listRuns(
            experimentName,
            taskCaseKey,
            taskLengthBucket,
            modelMode,
            completionStatus,
            acceptanceResult,
            failureReasonPresent,
            recoverySuccess,
            routeSource,
            orchestrationClosedLoopObserved,
            hasRouteEvidence,
            hasExecutionJudgment,
            hasCompletionJudgment,
            hasClosedLoopEvidenceChain,
            null,
            null,
            null,
            null,
            limit
        );
    }

    public List<ExperimentRunRecord> listRuns(String experimentName,
                                              String taskCaseKey,
                                              String taskLengthBucket,
                                              String modelMode,
                                              String completionStatus,
                                              String acceptanceResult,
                                              Boolean failureReasonPresent,
                                              Boolean recoverySuccess,
                                              String routeSource,
                                              Boolean orchestrationClosedLoopObserved,
                                              String toolExecutionMode,
                                              String toolChainTerminationReason,
                                              Integer minToolChainSteps,
                                              Integer maxToolChainSteps,
                                              int limit) {
        return listRuns(
            experimentName,
            taskCaseKey,
            taskLengthBucket,
            modelMode,
            completionStatus,
            acceptanceResult,
            failureReasonPresent,
            recoverySuccess,
            routeSource,
            orchestrationClosedLoopObserved,
            null,
            null,
            null,
            null,
            toolExecutionMode,
            toolChainTerminationReason,
            minToolChainSteps,
            maxToolChainSteps,
            limit
        );
    }

    public List<ExperimentRunRecord> listRuns(String experimentName,
                                              String taskCaseKey,
                                              String taskLengthBucket,
                                              String modelMode,
                                              String completionStatus,
                                              String acceptanceResult,
                                              Boolean failureReasonPresent,
                                              Boolean recoverySuccess,
                                              String toolExecutionMode,
                                              String toolChainTerminationReason,
                                              Integer minToolChainSteps,
                                              Integer maxToolChainSteps,
                                              int limit) {
        return listRuns(
            experimentName,
            taskCaseKey,
            taskLengthBucket,
            modelMode,
            completionStatus,
            acceptanceResult,
            failureReasonPresent,
            recoverySuccess,
            null,
            null,
            null,
            null,
            null,
            null,
            toolExecutionMode,
            toolChainTerminationReason,
            minToolChainSteps,
            maxToolChainSteps,
            limit
        );
    }

    public List<ExperimentRunRecord> listRuns(String experimentName,
                                              String taskCaseKey,
                                              String taskLengthBucket,
                                              String modelMode,
                                              String completionStatus,
                                              String acceptanceResult,
                                              Boolean failureReasonPresent,
                                              Boolean recoverySuccess,
                                              String routeSource,
                                              Boolean orchestrationClosedLoopObserved,
                                              Boolean hasRouteEvidence,
                                              Boolean hasExecutionJudgment,
                                              Boolean hasCompletionJudgment,
                                              Boolean hasClosedLoopEvidenceChain,
                                              String toolExecutionMode,
                                              String toolChainTerminationReason,
                                              Integer minToolChainSteps,
                                              Integer maxToolChainSteps,
                                              int limit) {
        String rawModelMode = blankToNull(modelMode);
        final String normalizedModelMode = rawModelMode != null ? normalizeModelMode(rawModelMode) : null;
        String rawCompletionStatus = blankToNull(completionStatus);
        final String normalizedCompletionStatus = rawCompletionStatus != null
            ? rawCompletionStatus.trim().toLowerCase()
            : null;
        String rawAcceptanceResult = blankToNull(acceptanceResult);
        final String normalizedAcceptanceResult = rawAcceptanceResult != null
            ? rawAcceptanceResult.trim().toLowerCase()
            : null;
        final String normalizedRouteSource = blankToNull(routeSource);
        final String normalizedToolExecutionMode = blankToNull(toolExecutionMode);
        final String normalizedToolChainTerminationReason = blankToNull(toolChainTerminationReason);
        final Integer normalizedMinToolChainSteps = positiveIntOrNull(minToolChainSteps);
        final Integer normalizedMaxToolChainSteps = positiveIntOrNull(maxToolChainSteps);
        int sanitizedLimit = Math.max(1, Math.min(limit, 200));
        int queryLimit = hasPostQueryFilters(
            normalizedCompletionStatus,
            normalizedAcceptanceResult,
            failureReasonPresent,
            recoverySuccess,
            normalizedRouteSource,
            orchestrationClosedLoopObserved,
            hasRouteEvidence,
            hasExecutionJudgment,
            hasCompletionJudgment,
            hasClosedLoopEvidenceChain,
            normalizedToolExecutionMode,
            normalizedToolChainTerminationReason,
            normalizedMinToolChainSteps,
            normalizedMaxToolChainSteps
        ) ? QUERY_LIMIT : sanitizedLimit;
        return experimentRunDao.listFiltered(
            blankToNull(experimentName),
            blankToNull(taskCaseKey),
            blankToNull(taskLengthBucket),
            normalizedModelMode,
            queryLimit
        ).stream()
            .filter(run -> matchesOutcomeFilters(
                run,
                normalizedCompletionStatus,
                normalizedAcceptanceResult,
                failureReasonPresent,
                recoverySuccess,
                normalizedRouteSource,
                orchestrationClosedLoopObserved,
                hasRouteEvidence,
                hasExecutionJudgment,
                hasCompletionJudgment,
                hasClosedLoopEvidenceChain
            ))
            .filter(run -> matchesToolChainFilters(
                run,
                normalizedToolExecutionMode,
                normalizedToolChainTerminationReason,
                normalizedMinToolChainSteps,
                normalizedMaxToolChainSteps
            ))
            .limit(sanitizedLimit)
            .toList();
    }

    public ExperimentRunSummary summarizeRuns(String experimentName,
                                              String taskCaseKey,
                                              String taskLengthBucket,
                                              String modelMode,
                                              String completionStatus,
                                              String acceptanceResult,
                                              Boolean failureReasonPresent,
                                              Boolean recoverySuccess,
                                              String routeSource,
                                              Boolean orchestrationClosedLoopObserved,
                                              Boolean hasRouteEvidence,
                                              Boolean hasExecutionJudgment,
                                              Boolean hasCompletionJudgment,
                                              Boolean hasClosedLoopEvidenceChain,
                                              String toolExecutionMode,
                                              String toolChainTerminationReason,
                                              Integer minToolChainSteps,
                                              Integer maxToolChainSteps) {
        List<ExperimentRunRecord> runs = listRuns(
            experimentName,
            taskCaseKey,
            taskLengthBucket,
            modelMode,
            completionStatus,
            acceptanceResult,
            failureReasonPresent,
            recoverySuccess,
            routeSource,
            orchestrationClosedLoopObserved,
            hasRouteEvidence,
            hasExecutionJudgment,
            hasCompletionJudgment,
            hasClosedLoopEvidenceChain,
            toolExecutionMode,
            toolChainTerminationReason,
            minToolChainSteps,
            maxToolChainSteps,
            200
        );
        LinkedHashMap<String, Integer> completionStatusCounts = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> acceptanceResultCounts = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> modelModeCounts = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> routeSourceCounts = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> executionActionCounts = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> completionJudgmentStatusCounts = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> completionAlignmentLevelCounts = new LinkedHashMap<>();
        int failureReasonCount = 0;
        int recoverySuccessCount = 0;
        int orchestrationClosedLoopObservedCount = 0;
        int orchestratedRunCount = 0;
        int runsWithRouteEvidenceCount = 0;
        int runsWithExecutionJudgmentCount = 0;
        int runsWithCompletionJudgmentCount = 0;
        int runsWithClosedLoopEvidenceChainCount = 0;
        int runsWithTracePointersCount = 0;
        int runsWithJudgmentTracePointersCount = 0;
        int runsWithTaskSurfaceRefsCount = 0;
        int runsWithJudgmentSurfaceRefsCount = 0;
        int runsWithToolTraceSurfaceRefsCount = 0;
        int handoffCount = 0;
        int resumeCount = 0;
        int humanGateCount = 0;
        double totalCost = 0.0;
        double totalStrongModelCostRatio = 0.0;

        for (ExperimentRunRecord run : runs) {
            incrementCount(completionStatusCounts, firstNonBlank(run.completionStatus(), "unknown"));
            incrementCount(acceptanceResultCounts, firstNonBlank(run.acceptanceResult(), "unknown"));
            incrementCount(modelModeCounts, firstNonBlank(run.modelMode(), "unknown"));
            incrementCount(routeSourceCounts, firstNonBlank(metadataString(run.metadata(), "route_source"), "unknown"));
            incrementCount(executionActionCounts, metadataString(run.metadata(), "execution_judgment_action"));
            incrementCount(completionJudgmentStatusCounts, metadataString(run.metadata(), "completion_judgment_status"));
            incrementCount(completionAlignmentLevelCounts, metadataString(run.metadata(), "completion_alignment_level"));
            if (run.failureReason() != null && !run.failureReason().isBlank()) {
                failureReasonCount++;
            }
            if (Boolean.TRUE.equals(run.recoverySuccess())) {
                recoverySuccessCount++;
            }
            if (Boolean.TRUE.equals(metadataBoolean(run.metadata(), "has_route_evidence"))) {
                runsWithRouteEvidenceCount++;
            }
            if (Boolean.TRUE.equals(metadataBoolean(run.metadata(), "has_execution_judgment"))) {
                runsWithExecutionJudgmentCount++;
            }
            if (Boolean.TRUE.equals(metadataBoolean(run.metadata(), "has_completion_judgment"))) {
                runsWithCompletionJudgmentCount++;
            }
            if (Boolean.TRUE.equals(metadataBoolean(run.metadata(), "has_closed_loop_evidence_chain"))) {
                runsWithClosedLoopEvidenceChainCount++;
            }
            if (hasTracePointers(run.metadata())) {
                runsWithTracePointersCount++;
            }
            if (hasJudgmentTracePointers(run.metadata())) {
                runsWithJudgmentTracePointersCount++;
            }
            if (hasTaskSurfaceRefs(run.metadata())) {
                runsWithTaskSurfaceRefsCount++;
            }
            if (hasJudgmentSurfaceRefs(run.metadata())) {
                runsWithJudgmentSurfaceRefsCount++;
            }
            if (hasToolTraceSurfaceRefs(run.metadata())) {
                runsWithToolTraceSurfaceRefsCount++;
            }
            if ("orchestrated".equalsIgnoreCase(run.modelMode())) {
                orchestratedRunCount++;
                if (Boolean.TRUE.equals(metadataBoolean(run.metadata(), "orchestration_closed_loop_observed"))) {
                    orchestrationClosedLoopObservedCount++;
                }
            }
            handoffCount += safeInt(run.handoffCount());
            resumeCount += safeInt(run.resumeCount());
            humanGateCount += safeInt(run.humanGateCount());
            totalCost += safeDouble(run.totalCost());
            totalStrongModelCostRatio += safeDouble(run.strongModelCostRatio());
        }

        int runCount = runs.size();
        return new ExperimentRunSummary(
            runCount,
            Map.copyOf(completionStatusCounts),
            Map.copyOf(acceptanceResultCounts),
            Map.copyOf(modelModeCounts),
            Map.copyOf(routeSourceCounts),
            Map.copyOf(executionActionCounts),
            Map.copyOf(completionJudgmentStatusCounts),
            Map.copyOf(completionAlignmentLevelCounts),
            failureReasonCount,
            recoverySuccessCount,
            orchestrationClosedLoopObservedCount,
            orchestratedRunCount,
            runsWithRouteEvidenceCount,
            runsWithExecutionJudgmentCount,
            runsWithCompletionJudgmentCount,
            runsWithClosedLoopEvidenceChainCount,
            runsWithTracePointersCount,
            runsWithJudgmentTracePointersCount,
            runsWithTaskSurfaceRefsCount,
            runsWithJudgmentSurfaceRefsCount,
            runsWithToolTraceSurfaceRefsCount,
            handoffCount,
            resumeCount,
            humanGateCount,
            roundToThree(totalCost),
            runCount == 0 ? 0.0 : roundToThree(totalCost / runCount),
            runCount == 0 ? 0.0 : roundToThree(totalStrongModelCostRatio / runCount)
        );
    }

    public ExperimentRunSummary summarizeRuns(String experimentName,
                                              String taskCaseKey,
                                              String taskLengthBucket,
                                              String modelMode,
                                              String completionStatus,
                                              String acceptanceResult,
                                              Boolean failureReasonPresent,
                                              Boolean recoverySuccess,
                                              String toolExecutionMode,
                                              String toolChainTerminationReason,
                                              Integer minToolChainSteps,
                                              Integer maxToolChainSteps) {
        return summarizeRuns(
            experimentName,
            taskCaseKey,
            taskLengthBucket,
            modelMode,
            completionStatus,
            acceptanceResult,
            failureReasonPresent,
            recoverySuccess,
            null,
            null,
            null,
            null,
            null,
            null,
            toolExecutionMode,
            toolChainTerminationReason,
            minToolChainSteps,
            maxToolChainSteps
        );
    }

    public ExperimentRunSummary summarizeRuns(String experimentName,
                                              String taskCaseKey,
                                              String taskLengthBucket,
                                              String modelMode,
                                              String completionStatus,
                                              String acceptanceResult,
                                              Boolean failureReasonPresent,
                                              Boolean recoverySuccess,
                                              String routeSource,
                                              Boolean orchestrationClosedLoopObserved,
                                              String toolExecutionMode,
                                              String toolChainTerminationReason,
                                              Integer minToolChainSteps,
                                              Integer maxToolChainSteps) {
        return summarizeRuns(
            experimentName,
            taskCaseKey,
            taskLengthBucket,
            modelMode,
            completionStatus,
            acceptanceResult,
            failureReasonPresent,
            recoverySuccess,
            routeSource,
            orchestrationClosedLoopObserved,
            null,
            null,
            null,
            null,
            toolExecutionMode,
            toolChainTerminationReason,
            minToolChainSteps,
            maxToolChainSteps
        );
    }

    private void incrementCount(Map<String, Integer> counts, String key) {
        if (counts == null || key == null || key.isBlank()) {
            return;
        }
        counts.merge(key, 1, Integer::sum);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private double safeDouble(Double value) {
        return value == null ? 0.0 : value;
    }

    private Decision latestDecision(List<Decision> decisions, String decisionType) {
        if (decisions == null || decisionType == null || decisionType.isBlank()) {
            return null;
        }
        return decisions.stream()
            .filter(decision -> decisionType.equals(decision.decisionType()))
            .findFirst()
            .orElse(null);
    }

    private boolean isWorkerRoundArtifact(Artifact artifact) {
        if (artifact == null || artifact.artifactType() == null) {
            return false;
        }
        return "worker_output".equalsIgnoreCase(artifact.artifactType())
            || "worker_artifact".equalsIgnoreCase(artifact.artifactType());
    }

    private Map<String, Object> resolveLatestWorkerMetadata(List<Artifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return Map.of();
        }
        for (Artifact artifact : artifacts) {
            if (!isWorkerRoundArtifact(artifact) || artifact.metadata() == null || artifact.metadata().isEmpty()) {
                continue;
            }
            LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
            copyLatestWorkerMetadata(artifact.metadata(), merged);
            Map<String, Object> nested = nestedMetadataMap(artifact.metadata(), "latest_worker_metadata");
            if (!nested.isEmpty()) {
                copyLatestWorkerMetadata(nested, merged);
            }
            if (!merged.isEmpty()) {
                return merged;
            }
            return artifact.metadata();
        }
        return Map.of();
    }

    private Artifact latestWorkerArtifact(List<Artifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return null;
        }
        return artifacts.stream()
            .filter(artifact -> "worker_artifact".equalsIgnoreCase(artifact.artifactType()))
            .findFirst()
            .orElseGet(() -> artifacts.stream()
                .filter(this::isWorkerRoundArtifact)
                .findFirst()
                .orElse(null));
    }

    private ToolChainSummary resolveToolChainSummary(Map<String, Object> latestWorkerMetadata,
                                                     List<ToolInvocationRecord> toolInvocations) {
        String executionMode = firstNonBlank(
            metadataString(latestWorkerMetadata, "tool_execution_mode"),
            latestToolExecutionMode(toolInvocations)
        );
        Integer stepCount = metadataInt(latestWorkerMetadata, "tool_chain_step_count");
        String terminationReason = metadataString(latestWorkerMetadata, "tool_chain_termination_reason");
        List<String> toolNames = toolNamesFromTrace(latestWorkerMetadata != null
            ? latestWorkerMetadata.get("tool_chain_trace")
            : null);

        if (stepCount == null && "multi_tool_round".equalsIgnoreCase(executionMode)) {
            stepCount = maxMultiToolStepIndex(toolInvocations);
        }
        if (toolNames.isEmpty() && "multi_tool_round".equalsIgnoreCase(executionMode)) {
            toolNames = toolNamesFromInvocations(toolInvocations);
        }

        return new ToolChainSummary(
            executionMode,
            stepCount,
            terminationReason,
            buildToolChainTraceSummary(stepCount, terminationReason, toolNames),
            List.copyOf(toolNames)
        );
    }

    private int countEvents(List<Event> events, String eventType) {
        if (events == null || eventType == null || eventType.isBlank()) {
            return 0;
        }
        return (int) events.stream()
            .filter(event -> eventType.equals(event.eventType()))
            .count();
    }

    private int countResumeActions(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        return (int) events.stream()
            .filter(event -> "task_control_action".equals(event.eventType()))
            .filter(event -> "resume".equalsIgnoreCase(metadataString(event.payload(), "action")))
            .count();
    }

    private String deriveAcceptanceResult(Task task, Decision completionJudgment) {
        String completionStatus = metadataString(completionJudgment != null ? completionJudgment.metadata() : null, "status");
        String alignmentLevel = metadataString(completionJudgment != null ? completionJudgment.metadata() : null, "alignment_level");
        if (isDoneStatus(completionStatus) && !isLowAlignment(alignmentLevel)) {
            return "accepted";
        }
        if (isRejectedStatus(completionStatus) || isLowAlignment(alignmentLevel)) {
            return "rejected";
        }
        if ("failed".equalsIgnoreCase(task.status())) {
            return "rejected";
        }
        if ("done".equalsIgnoreCase(task.status())) {
            return "accepted";
        }
        if ("waiting_human".equalsIgnoreCase(task.status())) {
            return "needs_followup";
        }
        return "not_evaluated";
    }

    private String deriveQualityNote(Task task, Decision completionJudgment) {
        return firstNonBlank(
            metadataString(completionJudgment != null ? completionJudgment.metadata() : null, "evaluation_result"),
            completionJudgment != null ? completionJudgment.rationale() : null,
            task.summary()
        );
    }

    private Boolean deriveOrchestrationClosedLoopObserved(Task task,
                                                          Decision completionJudgment,
                                                          Map<String, Object> latestWorkerMetadata) {
        if (!"orchestrated".equalsIgnoreCase(normalizeModelMode(metadataString(task.metadata(), "model_mode")))) {
            return null;
        }
        Object explicit = completionJudgment != null && completionJudgment.metadata() != null
            ? completionJudgment.metadata().get("orchestration_closed_loop_observed")
            : null;
        if (explicit instanceof Boolean bool) {
            return bool;
        }
        String plannerWorker = firstNonBlank(
            metadataString(task.metadata(), "planner_worker"),
            metadataString(latestWorkerMetadata, "planner_worker")
        );
        String executorWorker = firstNonBlank(
            metadataString(task.metadata(), "executor_worker"),
            metadataString(latestWorkerMetadata, "executor_worker")
        );
        String executorModelTier = firstNonBlank(
            metadataString(task.metadata(), "executor_model_tier"),
            metadataString(latestWorkerMetadata, "selected_model_tier")
        );
        String evaluatorModelTier = metadataString(completionJudgment != null ? completionJudgment.metadata() : null, "evaluator_model_tier");
        return plannerWorker != null
            && executorWorker != null
            && "small".equalsIgnoreCase(executorModelTier)
            && "strong".equalsIgnoreCase(firstNonBlank(evaluatorModelTier, "strong"));
    }

    private boolean hasRouteEvidence(Map<String, Object> latestWorkerMetadata) {
        if (latestWorkerMetadata == null || latestWorkerMetadata.isEmpty()) {
            return false;
        }
        return firstNonBlank(
            metadataString(latestWorkerMetadata, "route_source"),
            metadataString(latestWorkerMetadata, "selected_worker"),
            metadataString(latestWorkerMetadata, "why_selected")
        ) != null;
    }

    private String buildJudgmentEvidenceChain(Map<String, Object> latestWorkerMetadata,
                                              Decision executionJudgment,
                                              Decision completionJudgment) {
        String routeSource = metadataString(latestWorkerMetadata, "route_source");
        String selectedWorker = firstNonBlank(
            metadataString(latestWorkerMetadata, "selected_worker"),
            metadataString(latestWorkerMetadata, "executor_worker")
        );
        String executionAction = metadataString(
            executionJudgment != null ? executionJudgment.metadata() : null,
            "action"
        );
        String completionStatus = metadataString(
            completionJudgment != null ? completionJudgment.metadata() : null,
            "status"
        );
        String completionAlignment = metadataString(
            completionJudgment != null ? completionJudgment.metadata() : null,
            "alignment_level"
        );
        if (routeSource == null && selectedWorker == null && executionAction == null
            && completionStatus == null && completionAlignment == null) {
            return null;
        }
        return "route="
            + firstNonBlank(routeSource, "unknown")
            + ":" + firstNonBlank(selectedWorker, "unassigned")
            + " -> exec="
            + firstNonBlank(executionAction, "missing")
            + " -> completion="
            + firstNonBlank(completionStatus, "missing")
            + ":" + firstNonBlank(completionAlignment, "unknown");
    }

    private String buildClosedLoopProofSummary(Task task,
                                               Map<String, Object> latestWorkerMetadata,
                                               Decision executionJudgment,
                                               Decision completionJudgment,
                                               ToolChainSummary toolChainSummary,
                                               boolean hasRouteEvidence,
                                               boolean hasExecutionJudgment,
                                               boolean hasCompletionJudgment,
                                               boolean hasClosedLoopEvidenceChain,
                                               String orchestrationProofSummary) {
        List<String> parts = new ArrayList<>();
        parts.add("route=" + (hasRouteEvidence ? "present" : "missing"));
        parts.add("execution_judgment=" + (hasExecutionJudgment ? "present" : "missing"));
        parts.add("completion_judgment=" + (hasCompletionJudgment ? "present" : "missing"));
        parts.add("closed_loop_evidence_chain=" + (hasClosedLoopEvidenceChain ? "complete" : "partial"));

        String routeSource = metadataString(latestWorkerMetadata, "route_source");
        String selectedWorker = firstNonBlank(
            metadataString(latestWorkerMetadata, "selected_worker"),
            metadataString(latestWorkerMetadata, "executor_worker"),
            task != null ? task.assignedWorker() : null
        );
        if (routeSource != null || selectedWorker != null) {
            parts.add("route_signal=" + firstNonBlank(routeSource, "unknown")
                + ":" + firstNonBlank(selectedWorker, "unassigned"));
        }

        String executionAction = metadataString(
            executionJudgment != null ? executionJudgment.metadata() : null,
            "action"
        );
        if (executionAction != null) {
            parts.add("exec_action=" + executionAction);
        }

        String completionStatus = metadataString(
            completionJudgment != null ? completionJudgment.metadata() : null,
            "status"
        );
        String completionAlignment = metadataString(
            completionJudgment != null ? completionJudgment.metadata() : null,
            "alignment_level"
        );
        if (completionStatus != null) {
            parts.add("completion=" + completionStatus
                + ":" + firstNonBlank(completionAlignment, "unknown"));
        }

        if (toolChainSummary != null && toolChainSummary.traceSummary() != null) {
            parts.add("tool_chain=" + toolChainSummary.traceSummary());
        }

        if (orchestrationProofSummary != null) {
            parts.add("orchestration=" + orchestrationProofSummary);
        }
        return String.join(" | ", parts);
    }

    private Map<String, Object> buildClosedLoopEvidence(Task task,
                                                        Map<String, Object> latestWorkerMetadata,
                                                        Artifact latestWorkerArtifact,
                                                        Decision executionJudgment,
                                                        Decision completionJudgment,
                                                        ToolChainSummary toolChainSummary,
                                                        List<ToolInvocationRecord> toolInvocations,
                                                        boolean hasRouteEvidence,
                                                        boolean hasExecutionJudgment,
                                                        boolean hasCompletionJudgment,
                                                        boolean hasClosedLoopEvidenceChain,
                                                        Boolean orchestrationClosedLoopObserved,
                                                        String judgmentEvidenceChain,
                                                        String closedLoopProofSummary,
                                                        String orchestrationProofSummary) {
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("chain_status", hasClosedLoopEvidenceChain ? "complete" : "partial");
        evidence.put("has_route_evidence", hasRouteEvidence);
        evidence.put("has_execution_judgment", hasExecutionJudgment);
        evidence.put("has_completion_judgment", hasCompletionJudgment);
        if (judgmentEvidenceChain != null) {
            evidence.put("judgment_evidence_chain", judgmentEvidenceChain);
        }
        if (closedLoopProofSummary != null) {
            evidence.put("proof_summary", closedLoopProofSummary);
        }

        Map<String, Object> routeEvidence = buildRouteEvidence(task, latestWorkerMetadata, hasRouteEvidence);
        if (!routeEvidence.isEmpty()) {
            evidence.put("route", routeEvidence);
        }

        Map<String, Object> workerExecutionEvidence = buildWorkerExecutionEvidence(latestWorkerMetadata);
        if (!workerExecutionEvidence.isEmpty()) {
            evidence.put("worker_execution", workerExecutionEvidence);
        }

        Map<String, Object> executionJudgmentEvidence = buildExecutionJudgmentEvidence(executionJudgment);
        if (!executionJudgmentEvidence.isEmpty()) {
            evidence.put("execution_judgment", executionJudgmentEvidence);
        }

        Map<String, Object> completionJudgmentEvidence = buildCompletionJudgmentEvidence(completionJudgment);
        if (!completionJudgmentEvidence.isEmpty()) {
            evidence.put("completion_judgment", completionJudgmentEvidence);
        }

        Map<String, Object> toolChainEvidence = buildToolChainEvidence(toolChainSummary);
        if (!toolChainEvidence.isEmpty()) {
            evidence.put("tool_chain", toolChainEvidence);
        }

        Map<String, Object> orchestrationEvidence = buildOrchestrationEvidence(
            task,
            latestWorkerMetadata,
            completionJudgment,
            orchestrationClosedLoopObserved,
            orchestrationProofSummary
        );
        if (!orchestrationEvidence.isEmpty()) {
            evidence.put("orchestration", orchestrationEvidence);
        }
        Map<String, Object> tracePointers = buildTracePointers(
            task,
            latestWorkerArtifact,
            executionJudgment,
            completionJudgment,
            toolInvocations
        );
        if (!tracePointers.isEmpty()) {
            evidence.put("trace_pointers", tracePointers);
        }
        Map<String, Object> taskSurfaceRefs = buildTaskSurfaceRefs(task, executionJudgment, completionJudgment, toolInvocations);
        if (!taskSurfaceRefs.isEmpty()) {
            evidence.put("task_surface_refs", taskSurfaceRefs);
        }
        return evidence;
    }

    private Map<String, Object> buildTracePointers(Task task,
                                                   Artifact latestWorkerArtifact,
                                                   Decision executionJudgment,
                                                   Decision completionJudgment,
                                                   List<ToolInvocationRecord> toolInvocations) {
        LinkedHashMap<String, Object> pointers = new LinkedHashMap<>();
        putIfNotBlank(pointers, "task_id", task != null ? task.id() : null);
        putIfNotBlank(pointers, "session_id", task != null ? task.sessionId() : null);
        putIfNotBlank(pointers, "worker_artifact_id", latestWorkerArtifact != null ? latestWorkerArtifact.id() : null);
        putIfNotBlank(pointers, "execution_judgment_id", executionJudgment != null ? executionJudgment.id() : null);
        putIfNotBlank(pointers, "completion_judgment_id", completionJudgment != null ? completionJudgment.id() : null);
        putIfStringList(pointers, "tool_invocation_ids", latestToolInvocationIds(toolInvocations));
        putIfStringList(pointers, "tool_execution_ids", latestToolExecutionIds(toolInvocations));
        return pointers;
    }

    private Map<String, Object> buildTaskSurfaceRefs(Task task,
                                                     Decision executionJudgment,
                                                     Decision completionJudgment,
                                                     List<ToolInvocationRecord> toolInvocations) {
        if (task == null || task.id() == null || task.id().isBlank()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> refs = new LinkedHashMap<>();
        putIfNotBlank(refs, "task_id", task.id());
        refs.put("live_flow_path", "/api/v1/tasks/" + task.id() + "/live_flow");
        refs.put("runtime_context_path", "/api/v1/tasks/" + task.id() + "/runtime_context");
        refs.put("harness_trace_path", "/api/v1/tasks/" + task.id() + "/harness_trace");
        if (executionJudgment != null || completionJudgment != null) {
            refs.put("judgment_trace_path", "/api/v1/tasks/" + task.id() + "/judgment_trace");
        }
        if (toolInvocations != null && !toolInvocations.isEmpty()) {
            refs.put("tool_trace_path", "/api/v1/tasks/" + task.id() + "/tool_trace");
        }
        return refs;
    }

    private Map<String, Object> buildRouteEvidence(Task task,
                                                   Map<String, Object> latestWorkerMetadata,
                                                   boolean hasRouteEvidence) {
        LinkedHashMap<String, Object> route = new LinkedHashMap<>();
        route.put("present", hasRouteEvidence);
        putIfNotBlank(route, "route_source", metadataString(latestWorkerMetadata, "route_source"));
        putIfNotBlank(route, "selected_worker", firstNonBlank(
            metadataString(latestWorkerMetadata, "selected_worker"),
            metadataString(latestWorkerMetadata, "executor_worker"),
            task != null ? task.assignedWorker() : null
        ));
        putIfNotBlank(route, "selected_model_tier", metadataString(latestWorkerMetadata, "selected_model_tier"));
        putIfNotBlank(route, "preferred_worker_hint", metadataString(latestWorkerMetadata, "preferred_worker_hint"));
        putIfPresent(route, "learning_hint_applied", metadataBoolean(latestWorkerMetadata, "learning_hint_applied"));
        putIfNotBlank(route, "why_selected", metadataString(latestWorkerMetadata, "why_selected"));
        putIfNotBlank(route, "fallback_reason", metadataString(latestWorkerMetadata, "fallback_reason"));
        return route;
    }

    private Map<String, Object> buildWorkerExecutionEvidence(Map<String, Object> latestWorkerMetadata) {
        LinkedHashMap<String, Object> execution = new LinkedHashMap<>();
        putIfNotBlank(execution, "execution_status", metadataString(latestWorkerMetadata, "execution_status"));
        putIfStringList(execution, "evidence_refs", metadataStringList(latestWorkerMetadata, "evidence_refs"));
        putIfStringList(execution, "unfinished_items", metadataStringList(latestWorkerMetadata, "unfinished_items"));
        putIfPresent(execution, "tool_aware_executor", metadataBoolean(latestWorkerMetadata, "tool_aware_executor"));
        return execution;
    }

    private Map<String, Object> buildExecutionJudgmentEvidence(Decision executionJudgment) {
        LinkedHashMap<String, Object> judgment = new LinkedHashMap<>();
        Map<String, Object> metadata = executionJudgment != null ? executionJudgment.metadata() : null;
        putIfNotBlank(judgment, "decision_id", executionJudgment != null ? executionJudgment.id() : null);
        putIfNotBlank(judgment, "action", metadataString(metadata, "action"));
        putIfNotBlank(judgment, "next_step", metadataString(metadata, "next_step"));
        putIfPresent(judgment, "needs_checkpoint", metadataBoolean(metadata, "needs_checkpoint"));
        putIfPresent(judgment, "needs_human", metadataBoolean(metadata, "needs_human"));
        putIfNotBlank(judgment, "summary", executionJudgment != null ? executionJudgment.summary() : null);
        putIfNotBlank(judgment, "rationale", executionJudgment != null ? executionJudgment.rationale() : null);
        return judgment;
    }

    private Map<String, Object> buildCompletionJudgmentEvidence(Decision completionJudgment) {
        LinkedHashMap<String, Object> judgment = new LinkedHashMap<>();
        Map<String, Object> metadata = completionJudgment != null ? completionJudgment.metadata() : null;
        putIfNotBlank(judgment, "decision_id", completionJudgment != null ? completionJudgment.id() : null);
        putIfNotBlank(judgment, "status", metadataString(metadata, "status"));
        putIfNotBlank(judgment, "alignment_level", metadataString(metadata, "alignment_level"));
        putIfNotBlank(judgment, "evaluation_result", metadataString(metadata, "evaluation_result"));
        putIfNotBlank(judgment, "evaluation_reason", metadataString(metadata, "evaluation_reason"));
        putIfNotBlank(judgment, "suggested_next_action", metadataString(metadata, "suggested_next_action"));
        putIfNotBlank(judgment, "evaluator_role", metadataString(metadata, "evaluator_role"));
        putIfNotBlank(judgment, "evaluator_model_tier", metadataString(metadata, "evaluator_model_tier"));
        putIfNotBlank(judgment, "evaluator_reason", metadataString(metadata, "evaluator_reason"));
        putIfNotBlank(judgment, "summary", completionJudgment != null ? completionJudgment.summary() : null);
        putIfNotBlank(judgment, "rationale", completionJudgment != null ? completionJudgment.rationale() : null);
        return judgment;
    }

    private Map<String, Object> buildToolChainEvidence(ToolChainSummary toolChainSummary) {
        LinkedHashMap<String, Object> toolChain = new LinkedHashMap<>();
        if (toolChainSummary == null) {
            return toolChain;
        }
        putIfNotBlank(toolChain, "execution_mode", toolChainSummary.executionMode());
        putIfPresent(toolChain, "step_count", toolChainSummary.stepCount());
        putIfNotBlank(toolChain, "termination_reason", toolChainSummary.terminationReason());
        putIfNotBlank(toolChain, "trace_summary", toolChainSummary.traceSummary());
        putIfStringList(toolChain, "tool_names", toolChainSummary.toolNames());
        return toolChain;
    }

    private Map<String, Object> buildOrchestrationEvidence(Task task,
                                                           Map<String, Object> latestWorkerMetadata,
                                                           Decision completionJudgment,
                                                           Boolean orchestrationClosedLoopObserved,
                                                           String orchestrationProofSummary) {
        if (task == null || !"orchestrated".equalsIgnoreCase(normalizeModelMode(metadataString(task.metadata(), "model_mode")))) {
            return Map.of();
        }
        LinkedHashMap<String, Object> orchestration = new LinkedHashMap<>();
        putIfNotBlank(orchestration, "planner_worker", firstNonBlank(
            metadataString(task.metadata(), "planner_worker"),
            metadataString(latestWorkerMetadata, "planner_worker")
        ));
        putIfNotBlank(orchestration, "executor_worker", firstNonBlank(
            metadataString(task.metadata(), "executor_worker"),
            metadataString(latestWorkerMetadata, "executor_worker"),
            task.assignedWorker()
        ));
        Map<String, Object> completionMetadata = completionJudgment != null ? completionJudgment.metadata() : null;
        putIfNotBlank(orchestration, "evaluator_role", firstNonBlank(
            metadataString(completionMetadata, "evaluator_role"),
            "strong_evaluator"
        ));
        putIfNotBlank(orchestration, "evaluator_model_tier", firstNonBlank(
            metadataString(completionMetadata, "evaluator_model_tier"),
            "strong"
        ));
        putIfPresent(orchestration, "closed_loop_observed", orchestrationClosedLoopObserved);
        putIfNotBlank(orchestration, "proof_summary", orchestrationProofSummary);
        return orchestration;
    }

    private String deriveOrchestrationProofSummary(Task task,
                                                   Map<String, Object> latestWorkerMetadata,
                                                   String evaluatorRole,
                                                   String evaluatorModelTier,
                                                   Boolean orchestrationClosedLoopObserved) {
        if (!"orchestrated".equalsIgnoreCase(normalizeModelMode(metadataString(task.metadata(), "model_mode")))) {
            return null;
        }
        String plannerWorker = firstNonBlank(
            metadataString(task.metadata(), "planner_worker"),
            metadataString(latestWorkerMetadata, "planner_worker")
        );
        String executorWorker = firstNonBlank(
            metadataString(task.metadata(), "executor_worker"),
            metadataString(latestWorkerMetadata, "executor_worker"),
            task.assignedWorker()
        );
        String normalizedEvaluatorRole = firstNonBlank(evaluatorRole, "strong_evaluator");
        String normalizedEvaluatorTier = firstNonBlank(evaluatorModelTier, "strong");
        String summary = firstNonBlank(plannerWorker, "unknown_planner")
            + " -> " + firstNonBlank(executorWorker, "unknown_executor")
            + " -> " + normalizedEvaluatorRole + "(" + normalizedEvaluatorTier + ")";
        if (Boolean.TRUE.equals(orchestrationClosedLoopObserved)) {
            return summary + " [closed_loop]";
        }
        return summary + " [partial_loop]";
    }

    private String deriveFailureReason(Task task,
                                       String acceptanceResult,
                                       Decision completionJudgment,
                                       Decision executionJudgment,
                                       List<Event> events) {
        if ("accepted".equalsIgnoreCase(acceptanceResult) && "done".equalsIgnoreCase(task.status())) {
            return null;
        }
        Event latestStateChange = latestEvent(events, "task_state_changed");
        Event latestControlAction = latestEvent(events, "task_control_action");
        return firstNonBlank(
            task.waitingReason(),
            metadataString(latestControlAction != null ? latestControlAction.payload() : null, "reason"),
            completionJudgment != null ? completionJudgment.rationale() : null,
            executionJudgment != null ? executionJudgment.rationale() : null,
            executionJudgment != null ? executionJudgment.summary() : null,
            latestStateChange != null ? latestStateChange.summary() : null
        );
    }

    private Event latestEvent(List<Event> events, String eventType) {
        if (events == null || eventType == null || eventType.isBlank()) {
            return null;
        }
        return events.stream()
            .filter(event -> eventType.equals(event.eventType()))
            .findFirst()
            .orElse(null);
    }

    private String resolveTaskCaseKey(Task task) {
        String explicit = metadataString(task.metadata(), "task_case_key");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return firstNonBlank(
            metadataString(task.metadata(), "experiment_name"),
            "task"
        ) + "::" + firstNonBlank(
            metadataString(task.metadata(), "task_type"),
            "other"
        ) + "::" + firstNonBlank(
            blankToNull(task.title()),
            blankToNull(task.goal()),
            blankToNull(metadataString(task.metadata(), "intent")),
            task.id()
        );
    }

    private boolean isDoneStatus(String status) {
        if (status == null) {
            return false;
        }
        return "done".equalsIgnoreCase(status) || "complete".equalsIgnoreCase(status);
    }

    private boolean isRejectedStatus(String status) {
        if (status == null) {
            return false;
        }
        return "misaligned".equalsIgnoreCase(status)
            || "needs_clarification".equalsIgnoreCase(status)
            || "failed".equalsIgnoreCase(status);
    }

    private boolean isLowAlignment(String alignmentLevel) {
        return alignmentLevel != null && "low".equalsIgnoreCase(alignmentLevel);
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

    private void copyMetadata(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private void copyLatestWorkerMetadata(Map<String, Object> source, Map<String, Object> target) {
        if (source == null || source.isEmpty() || target == null) {
            return;
        }
        copyMetadata(source, target, "selected_worker");
        copyMetadata(source, target, "selected_worker_type");
        copyMetadata(source, target, "selected_model_tier");
        copyMetadata(source, target, "execution_role");
        copyMetadata(source, target, "selection_scope");
        copyMetadata(source, target, "why_selected");
        copyMetadata(source, target, "preferred_worker_hint");
        copyMetadata(source, target, "learning_hint_applied");
        copyMetadata(source, target, "fallback_reason");
        copyMetadata(source, target, "route_source");
        copyMetadata(source, target, "model_mode");
        copyMetadata(source, target, "orchestration_stage");
        copyMetadata(source, target, "planner_worker");
        copyMetadata(source, target, "executor_worker");
        copyMetadata(source, target, "target_worker");
        copyMetadata(source, target, "tool_aware_executor");
        copyMetadata(source, target, "tool_execution_mode");
        copyMetadata(source, target, "prompt_rendering_mode");
        copyMetadata(source, target, "prompt_mode");
        copyMetadata(source, target, "mounted_context_rendered");
        copyMetadata(source, target, "mounted_render_used");
        copyMetadata(source, target, "mounted_context_injected");
        copyMetadata(source, target, "mounted_context_panel_count");
        copyMetadata(source, target, "mounted_panel_count");
        copyMetadata(source, target, "mounted_context_non_empty_panel_count");
        copyMetadata(source, target, "mounted_non_empty_panel_count");
        copyMetadata(source, target, "mounted_context_selection_trace_count");
        copyMetadata(source, target, "mounted_pinned_count");
        copyMetadata(source, target, "mounted_active_count");
        copyMetadata(source, target, "mounted_ancestor_count");
        copyMetadata(source, target, "mounted_sibling_count");
        copyMetadata(source, target, "mounted_evidence_count");
        copyMetadata(source, target, "mounted_index_count");
        copyMetadata(source, target, "mounted_archive_count");
        copyMetadata(source, target, "image_input_count");
        copyMetadata(source, target, "image_input_used");
        copyMetadata(source, target, "tool_name");
        copyMetadata(source, target, "tool_success");
        copyMetadata(source, target, "tool_summary");
        copyMetadata(source, target, "tool_plan_reason");
        copyMetadata(source, target, "auto_write_generation_mode");
        copyMetadata(source, target, "auto_write_generation_error");
        copyMetadata(source, target, "output_file_required");
        copyMetadata(source, target, "output_file_path");
        copyMetadata(source, target, "output_file_exists");
        copyMetadata(source, target, "output_file_size");
        copyMetadata(source, target, "file_backed_artifact");
        copyMetadata(source, target, "grounding_mode");
        copyMetadata(source, target, "more_declared_rounds_remain");
        copyMetadata(source, target, "current_round_requires_write");
        copyMetadata(source, target, "missing_required_current_round_write");
        copyMetadata(source, target, "current_round_instruction");
        copyMetadata(source, target, "next_round_instruction");
        copyMetadata(source, target, "tool_round_index");
        copyMetadata(source, target, "declared_round_count");
        copyMetadata(source, target, "max_tool_rounds");
        copyMetadata(source, target, "tool_chain_step_count");
        copyMetadata(source, target, "tool_chain_termination_reason");
        copyMetadata(source, target, "tool_chain_trace");
        copyMetadata(source, target, "execution_status");
        copyMetadata(source, target, "evidence_refs");
        copyMetadata(source, target, "unfinished_items");
        copyMetadata(source, target, "evaluator_role");
        copyMetadata(source, target, "evaluator_model_tier");
        copyMetadata(source, target, "evaluator_reason");
        copyMetadata(source, target, "evaluation_reason");
        copyMetadata(source, target, "orchestration_closed_loop_observed");
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private List<String> metadataStringList(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return List.of();
        }
        Object value = metadata.get(key);
        if (!(value instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }
        ArrayList<String> values = new ArrayList<>();
        for (Object entry : rawList) {
            if (entry == null) {
                continue;
            }
            String normalized = entry.toString().trim();
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return List.copyOf(values);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMetadataMap(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
            return Map.of();
        }
        Object value = metadata.get(key);
        if (!(value instanceof Map<?, ?> nested)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : nested.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                normalized.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return normalized;
    }

    private Integer metadataInt(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean metadataBoolean(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            String normalized = text.trim();
            if ("true".equalsIgnoreCase(normalized)) {
                return true;
            }
            if ("false".equalsIgnoreCase(normalized)) {
                return false;
            }
        }
        return null;
    }

    private String latestToolExecutionMode(List<ToolInvocationRecord> toolInvocations) {
        if (toolInvocations == null || toolInvocations.isEmpty()) {
            return null;
        }
        return toolInvocations.stream()
            .map(ToolInvocationRecord::metadata)
            .map(metadata -> metadataString(metadata, "tool_execution_mode"))
            .filter(mode -> mode != null && !mode.isBlank())
            .findFirst()
            .orElse(null);
    }

    private int maxMultiToolStepIndex(List<ToolInvocationRecord> toolInvocations) {
        if (toolInvocations == null || toolInvocations.isEmpty()) {
            return 0;
        }
        return toolInvocations.stream()
            .filter(record -> "multi_tool_round".equalsIgnoreCase(metadataString(record.metadata(), "tool_execution_mode")))
            .map(record -> metadataInt(record.metadata(), "tool_chain_step_index"))
            .filter(step -> step != null && step > 0)
            .mapToInt(Integer::intValue)
            .max()
            .orElse(0);
    }

    private List<String> toolNamesFromTrace(Object rawTrace) {
        if (!(rawTrace instanceof List<?> traceList) || traceList.isEmpty()) {
            return List.of();
        }
        ArrayList<String> toolNames = new ArrayList<>();
        for (Object entry : traceList) {
            if (!(entry instanceof Map<?, ?> traceMap)) {
                continue;
            }
            Object value = traceMap.containsKey("tool_name") ? traceMap.get("tool_name") : traceMap.get("toolName");
            if (value == null) {
                continue;
            }
            String normalized = value.toString().trim();
            if (!normalized.isBlank()) {
                toolNames.add(normalized);
            }
        }
        return toolNames;
    }

    private List<String> toolNamesFromInvocations(List<ToolInvocationRecord> toolInvocations) {
        if (toolInvocations == null || toolInvocations.isEmpty()) {
            return List.of();
        }
        return toolInvocations.stream()
            .filter(record -> "multi_tool_round".equalsIgnoreCase(metadataString(record.metadata(), "tool_execution_mode")))
            .filter(record -> record.toolName() != null && !record.toolName().isBlank())
            .sorted((left, right) -> Integer.compare(
                metadataInt(left.metadata(), "tool_chain_step_index") != null
                    ? metadataInt(left.metadata(), "tool_chain_step_index")
                    : Integer.MAX_VALUE,
                metadataInt(right.metadata(), "tool_chain_step_index") != null
                    ? metadataInt(right.metadata(), "tool_chain_step_index")
                    : Integer.MAX_VALUE
            ))
            .map(ToolInvocationRecord::toolName)
            .distinct()
            .toList();
    }

    private String buildToolChainTraceSummary(Integer stepCount, String terminationReason, List<String> toolNames) {
        List<String> parts = new ArrayList<>();
        if (stepCount != null) {
            parts.add(stepCount + (stepCount == 1 ? " step" : " steps"));
        }
        if (terminationReason != null && !terminationReason.isBlank()) {
            parts.add(terminationReason);
        }
        if (toolNames != null && !toolNames.isEmpty()) {
            parts.add(String.join(" -> ", toolNames));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private List<String> latestToolInvocationIds(List<ToolInvocationRecord> toolInvocations) {
        return relevantToolInvocations(toolInvocations).stream()
            .map(ToolInvocationRecord::id)
            .filter(id -> id != null && !id.isBlank())
            .toList();
    }

    private List<String> latestToolExecutionIds(List<ToolInvocationRecord> toolInvocations) {
        return relevantToolInvocations(toolInvocations).stream()
            .map(ToolInvocationRecord::executionId)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
    }

    private List<ToolInvocationRecord> relevantToolInvocations(List<ToolInvocationRecord> toolInvocations) {
        if (toolInvocations == null || toolInvocations.isEmpty()) {
            return List.of();
        }
        List<ToolInvocationRecord> multiToolInvocations = toolInvocations.stream()
            .filter(record -> "multi_tool_round".equalsIgnoreCase(metadataString(record.metadata(), "tool_execution_mode")))
            .sorted((left, right) -> Integer.compare(
                metadataInt(left.metadata(), "tool_chain_step_index") != null
                    ? metadataInt(left.metadata(), "tool_chain_step_index")
                    : Integer.MAX_VALUE,
                metadataInt(right.metadata(), "tool_chain_step_index") != null
                    ? metadataInt(right.metadata(), "tool_chain_step_index")
                    : Integer.MAX_VALUE
            ))
            .toList();
        if (!multiToolInvocations.isEmpty()) {
            return multiToolInvocations;
        }
        return toolInvocations.stream()
            .sorted((left, right) -> Integer.compare(
                metadataInt(left.metadata(), "tool_chain_step_index") != null
                    ? metadataInt(left.metadata(), "tool_chain_step_index")
                    : Integer.MAX_VALUE,
                metadataInt(right.metadata(), "tool_chain_step_index") != null
                    ? metadataInt(right.metadata(), "tool_chain_step_index")
                    : Integer.MAX_VALUE
            ))
            .toList();
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    private void putIfStringList(Map<String, Object> target, String key, List<String> values) {
        if (target == null || key == null || key.isBlank() || values == null || values.isEmpty()) {
            return;
        }
        target.put(key, values);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || key.isBlank() || value == null) {
            return;
        }
        target.put(key, value);
    }

    private boolean hasTracePointers(Map<String, Object> metadata) {
        Map<String, Object> evidence = nestedMetadataMap(metadata, "closed_loop_evidence");
        if (evidence.isEmpty()) {
            return false;
        }
        Map<String, Object> pointers = nestedMetadataMap(evidence, "trace_pointers");
        return !pointers.isEmpty();
    }

    private boolean hasJudgmentTracePointers(Map<String, Object> metadata) {
        Map<String, Object> evidence = nestedMetadataMap(metadata, "closed_loop_evidence");
        if (evidence.isEmpty()) {
            return false;
        }
        Map<String, Object> pointers = nestedMetadataMap(evidence, "trace_pointers");
        return metadataString(pointers, "execution_judgment_id") != null
            || metadataString(pointers, "completion_judgment_id") != null;
    }

    private boolean hasTaskSurfaceRefs(Map<String, Object> metadata) {
        Map<String, Object> refs = taskSurfaceRefs(metadata);
        return metadataString(refs, "live_flow_path") != null
            && metadataString(refs, "harness_trace_path") != null;
    }

    private boolean hasJudgmentSurfaceRefs(Map<String, Object> metadata) {
        Map<String, Object> refs = taskSurfaceRefs(metadata);
        return metadataString(refs, "judgment_trace_path") != null;
    }

    private boolean hasToolTraceSurfaceRefs(Map<String, Object> metadata) {
        Map<String, Object> refs = taskSurfaceRefs(metadata);
        return metadataString(refs, "tool_trace_path") != null;
    }

    private Map<String, Object> taskSurfaceRefs(Map<String, Object> metadata) {
        Map<String, Object> evidence = nestedMetadataMap(metadata, "closed_loop_evidence");
        if (evidence.isEmpty()) {
            return Map.of();
        }
        return nestedMetadataMap(evidence, "task_surface_refs");
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

    private boolean hasToolChainFilters(String toolExecutionMode,
                                        String toolChainTerminationReason,
                                        Integer minToolChainSteps,
                                        Integer maxToolChainSteps) {
        return toolExecutionMode != null
            || toolChainTerminationReason != null
            || minToolChainSteps != null
            || maxToolChainSteps != null;
    }

    private boolean hasPostQueryFilters(String completionStatus,
                                        String acceptanceResult,
                                        Boolean failureReasonPresent,
                                        Boolean recoverySuccess,
                                        String routeSource,
                                        Boolean orchestrationClosedLoopObserved,
                                        Boolean hasRouteEvidence,
                                        Boolean hasExecutionJudgment,
                                        Boolean hasCompletionJudgment,
                                        Boolean hasClosedLoopEvidenceChain,
                                        String toolExecutionMode,
                                        String toolChainTerminationReason,
                                        Integer minToolChainSteps,
                                        Integer maxToolChainSteps) {
        return completionStatus != null
            || acceptanceResult != null
            || failureReasonPresent != null
            || recoverySuccess != null
            || routeSource != null
            || orchestrationClosedLoopObserved != null
            || hasRouteEvidence != null
            || hasExecutionJudgment != null
            || hasCompletionJudgment != null
            || hasClosedLoopEvidenceChain != null
            || hasToolChainFilters(
                toolExecutionMode,
                toolChainTerminationReason,
                minToolChainSteps,
                maxToolChainSteps
            );
    }

    private boolean matchesOutcomeFilters(ExperimentRunRecord run,
                                          String completionStatus,
                                          String acceptanceResult,
                                          Boolean failureReasonPresent,
                                          Boolean recoverySuccess,
                                          String routeSource,
                                          Boolean orchestrationClosedLoopObserved,
                                          Boolean hasRouteEvidence,
                                          Boolean hasExecutionJudgment,
                                          Boolean hasCompletionJudgment,
                                          Boolean hasClosedLoopEvidenceChain) {
        if (run == null) {
            return false;
        }
        Map<String, Object> metadata = run.metadata();
        if (completionStatus != null) {
            String observedCompletionStatus = blankToNull(run.completionStatus());
            if (observedCompletionStatus == null || !completionStatus.equalsIgnoreCase(observedCompletionStatus)) {
                return false;
            }
        }
        if (acceptanceResult != null) {
            String observedAcceptanceResult = blankToNull(run.acceptanceResult());
            if (observedAcceptanceResult == null || !acceptanceResult.equalsIgnoreCase(observedAcceptanceResult)) {
                return false;
            }
        }
        if (failureReasonPresent != null) {
            boolean observedFailureReasonPresent = run.failureReason() != null && !run.failureReason().isBlank();
            if (failureReasonPresent.booleanValue() != observedFailureReasonPresent) {
                return false;
            }
        }
        if (recoverySuccess != null) {
            if (run.recoverySuccess() == null || recoverySuccess.booleanValue() != run.recoverySuccess().booleanValue()) {
                return false;
            }
        }
        if (routeSource != null) {
            String observedRouteSource = blankToNull(metadataString(metadata, "route_source"));
            if (observedRouteSource == null || !routeSource.equalsIgnoreCase(observedRouteSource)) {
                return false;
            }
        }
        if (orchestrationClosedLoopObserved != null) {
            Boolean observedClosedLoop = metadataBoolean(metadata, "orchestration_closed_loop_observed");
            if (observedClosedLoop == null || orchestrationClosedLoopObserved.booleanValue() != observedClosedLoop.booleanValue()) {
                return false;
            }
        }
        if (!matchesBooleanMetadata(metadata, "has_route_evidence", hasRouteEvidence)) {
            return false;
        }
        if (!matchesBooleanMetadata(metadata, "has_execution_judgment", hasExecutionJudgment)) {
            return false;
        }
        if (!matchesBooleanMetadata(metadata, "has_completion_judgment", hasCompletionJudgment)) {
            return false;
        }
        if (!matchesBooleanMetadata(metadata, "has_closed_loop_evidence_chain", hasClosedLoopEvidenceChain)) {
            return false;
        }
        return true;
    }

    private boolean matchesBooleanMetadata(Map<String, Object> metadata, String key, Boolean expected) {
        if (expected == null) {
            return true;
        }
        Boolean observed = metadataBoolean(metadata, key);
        return observed != null && expected.booleanValue() == observed.booleanValue();
    }

    private boolean matchesToolChainFilters(ExperimentRunRecord run,
                                            String toolExecutionMode,
                                            String toolChainTerminationReason,
                                            Integer minToolChainSteps,
                                            Integer maxToolChainSteps) {
        Map<String, Object> metadata = run != null ? run.metadata() : Map.of();
        String observedExecutionMode = metadataString(metadata, "tool_execution_mode");
        String observedTerminationReason = metadataString(metadata, "tool_chain_termination_reason");
        Integer observedStepCount = metadataInt(metadata, "tool_chain_step_count");
        if (toolExecutionMode != null && (observedExecutionMode == null || !toolExecutionMode.equalsIgnoreCase(observedExecutionMode))) {
            return false;
        }
        if (toolChainTerminationReason != null
            && (observedTerminationReason == null || !toolChainTerminationReason.equalsIgnoreCase(observedTerminationReason))) {
            return false;
        }
        if (minToolChainSteps != null && (observedStepCount == null || observedStepCount < minToolChainSteps)) {
            return false;
        }
        if (maxToolChainSteps != null && (observedStepCount == null || observedStepCount > maxToolChainSteps)) {
            return false;
        }
        return true;
    }

    private Integer positiveIntOrNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private double roundToThree(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record ToolChainSummary(
        String executionMode,
        Integer stepCount,
        String terminationReason,
        String traceSummary,
        List<String> toolNames
    ) {}
}
