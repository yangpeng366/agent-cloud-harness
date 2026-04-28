package com.agentcloud.engine;

import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ExperimentRunRecord;
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
        return listRuns(experimentName, taskCaseKey, taskLengthBucket, modelMode, null, null, null, null, limit);
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
        String normalizedModelMode = blankToNull(modelMode);
        if (normalizedModelMode != null) {
            normalizedModelMode = normalizeModelMode(normalizedModelMode);
        }
        String normalizedToolExecutionMode = blankToNull(toolExecutionMode);
        String normalizedToolChainTerminationReason = blankToNull(toolChainTerminationReason);
        Integer normalizedMinToolChainSteps = positiveIntOrNull(minToolChainSteps);
        Integer normalizedMaxToolChainSteps = positiveIntOrNull(maxToolChainSteps);
        int sanitizedLimit = Math.max(1, Math.min(limit, 200));
        int queryLimit = hasToolChainFilters(
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
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
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
