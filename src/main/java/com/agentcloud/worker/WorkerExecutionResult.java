package com.agentcloud.worker;

import com.agentcloud.model.AgentActionDraft;
import com.agentcloud.worker.model.WorkerExecutionEnvelope;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Worker 单轮执行结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkerExecutionResult(
    String summary,
    String outputText,
    boolean producedArtifact,
    String artifactTitle,
    String artifactContent,
    String suggestedNextStep,
    String confidence,
    String executionStatus,
    List<String> evidenceRefs,
    List<String> unfinishedItems,
    List<AgentActionDraft> proposedActions,
    List<String> contextRequests,
    String completionClaim,
    String handoffTarget,
    List<String> riskFlags,
    Integer tokenUsage,
    Long durationMs,
    Map<String, Object> metadata,
    ExecutionOutcome outcome
) {
    public WorkerExecutionResult(String summary, String outputText, boolean producedArtifact,
                                 String artifactTitle, String artifactContent, String suggestedNextStep,
                                 String confidence, Integer tokenUsage, Long durationMs,
                                 Map<String, Object> metadata) {
        this(summary, outputText, producedArtifact, artifactTitle, artifactContent, suggestedNextStep,
            confidence, "unknown", List.of(), List.of(), List.of(), List.of(), "", "", List.of(),
            tokenUsage, durationMs, metadata, null);
    }

    public WorkerExecutionResult(String summary, String outputText, boolean producedArtifact,
                                 String artifactTitle, String artifactContent, String suggestedNextStep,
                                 String confidence, String executionStatus,
                                 List<String> evidenceRefs, List<String> unfinishedItems,
                                 Integer tokenUsage, Long durationMs,
                                 Map<String, Object> metadata,
                                 ExecutionOutcome outcome) {
        this(summary, outputText, producedArtifact, artifactTitle, artifactContent, suggestedNextStep,
            confidence, executionStatus, evidenceRefs, unfinishedItems, List.of(), List.of(), "", "", List.of(),
            tokenUsage, durationMs, metadata, outcome);
    }

    public WorkerExecutionResult(String summary, String outputText, boolean producedArtifact,
                                 String artifactTitle, String artifactContent, String suggestedNextStep,
                                 String confidence, String executionStatus,
                                 List<String> evidenceRefs, List<String> unfinishedItems,
                                 Integer tokenUsage, Long durationMs,
                                 Map<String, Object> metadata) {
        this(summary, outputText, producedArtifact, artifactTitle, artifactContent, suggestedNextStep,
            confidence, executionStatus, evidenceRefs, unfinishedItems, List.of(), List.of(), "", "", List.of(),
            tokenUsage, durationMs, metadata, null);
    }

    public WorkerExecutionResult(String summary, String outputText, boolean producedArtifact,
                                 String artifactTitle, String artifactContent, String suggestedNextStep,
                                 String confidence, String executionStatus,
                                 List<String> evidenceRefs, List<String> unfinishedItems,
                                 List<AgentActionDraft> proposedActions,
                                 List<String> contextRequests,
                                 String completionClaim,
                                 String handoffTarget,
                                 List<String> riskFlags,
                                 Integer tokenUsage,
                                 Long durationMs,
                                 Map<String, Object> metadata) {
        this(summary, outputText, producedArtifact, artifactTitle, artifactContent, suggestedNextStep,
            confidence, executionStatus, evidenceRefs, unfinishedItems, proposedActions, contextRequests,
            completionClaim, handoffTarget, riskFlags, tokenUsage, durationMs, metadata, null);
    }

    public WorkerExecutionResult {
        if (summary == null) summary = "";
        if (outputText == null) outputText = "";
        if (artifactTitle == null) artifactTitle = "";
        if (artifactContent == null) artifactContent = "";
        if (suggestedNextStep == null) suggestedNextStep = "";
        if (confidence == null) confidence = "medium";
        if (executionStatus == null) executionStatus = "unknown";
        if (evidenceRefs == null) evidenceRefs = List.of();
        if (unfinishedItems == null) unfinishedItems = List.of();
        if (proposedActions == null) proposedActions = List.of();
        if (contextRequests == null) contextRequests = List.of();
        if (completionClaim == null) completionClaim = "";
        if (handoffTarget == null) handoffTarget = "";
        if (riskFlags == null) riskFlags = List.of();
        if (tokenUsage == null) tokenUsage = 0;
        if (durationMs == null) durationMs = 0L;
        if (metadata == null) metadata = Map.of();
        if (outcome == null) outcome = inferOutcome(executionStatus);
    }

    private static ExecutionOutcome inferOutcome(String executionStatus) {
        if (executionStatus == null) {
            return ExecutionOutcome.COMPLETED;
        }
        return switch (executionStatus.toLowerCase()) {
            case "completed" -> ExecutionOutcome.COMPLETED;
            case "partial_timeout" -> ExecutionOutcome.COMPLETED_PARTIAL;
            default -> ExecutionOutcome.FAILED;
        };
    }

    public WorkerExecutionResult withEnvelope(WorkerExecutionEnvelope envelope) {
        if (envelope == null) {
            return this;
        }
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (metadata != null && !metadata.isEmpty()) {
            merged.putAll(metadata);
        }
        merged.put("execution_id", envelope.executionId());
        merged.put("execution_started_at", envelope.startedAt().toString());
        merged.put("execution_finished_at", envelope.finishedAt().toString());
        merged.put("execution_duration_ms", envelope.durationMs());
        merged.put("execution_status", envelope.executionStatus());
        merged.put("execution_outcome", outcome != null ? outcome.name() : null);
        if (envelope.toolInvocationIds() != null && !envelope.toolInvocationIds().isEmpty()) {
            merged.put("tool_invocation_ids", envelope.toolInvocationIds());
        }
        if (envelope.metadata() != null && !envelope.metadata().isEmpty()) {
            merged.putAll(envelope.metadata());
        }
        return new WorkerExecutionResult(
            summary,
            outputText,
            producedArtifact,
            artifactTitle,
            artifactContent,
            suggestedNextStep,
            confidence,
            envelope.executionStatus(),
            evidenceRefs,
            unfinishedItems,
            proposedActions,
            contextRequests,
            completionClaim,
            handoffTarget,
            riskFlags,
            tokenUsage,
            envelope.durationMs(),
            merged,
            outcome
        );
    }

    public static WorkerExecutionResult withEnvelope(WorkerExecutionResult result,
                                                     String executionId,
                                                     String sessionId,
                                                     String taskId,
                                                     String workerId,
                                                     Instant startedAt,
                                                     Instant finishedAt,
                                                     List<String> toolInvocationIds,
                                                     Map<String, Object> envelopeMetadata) {
        if (result == null) {
            return null;
        }
        WorkerExecutionEnvelope envelope = new WorkerExecutionEnvelope(
            executionId,
            sessionId,
            taskId,
            workerId,
            startedAt,
            finishedAt,
            finishedAt != null && startedAt != null ? Math.max(0L, finishedAt.toEpochMilli() - startedAt.toEpochMilli()) : result.durationMs(),
            result.executionStatus(),
            result,
            toolInvocationIds,
            envelopeMetadata
        );
        return result.withEnvelope(envelope);
    }
}
