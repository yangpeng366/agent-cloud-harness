package com.agentcloud.worker;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
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
    Integer tokenUsage,
    Long durationMs,
    Map<String, Object> metadata
) {
    public WorkerExecutionResult(String summary, String outputText, boolean producedArtifact,
                                 String artifactTitle, String artifactContent, String suggestedNextStep,
                                 String confidence, Integer tokenUsage, Long durationMs,
                                 Map<String, Object> metadata) {
        this(summary, outputText, producedArtifact, artifactTitle, artifactContent, suggestedNextStep,
            confidence, "unknown", List.of(), List.of(), tokenUsage, durationMs, metadata);
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
        if (tokenUsage == null) tokenUsage = 0;
        if (durationMs == null) durationMs = 0L;
        if (metadata == null) metadata = Map.of();
    }
}
