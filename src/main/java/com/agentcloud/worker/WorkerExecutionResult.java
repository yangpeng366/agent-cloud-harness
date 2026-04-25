package com.agentcloud.worker;

import com.fasterxml.jackson.annotation.JsonInclude;

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
    Integer tokenUsage,
    Long durationMs,
    Map<String, Object> metadata
) {
    public WorkerExecutionResult {
        if (summary == null) summary = "";
        if (outputText == null) outputText = "";
        if (artifactTitle == null) artifactTitle = "";
        if (artifactContent == null) artifactContent = "";
        if (suggestedNextStep == null) suggestedNextStep = "";
        if (confidence == null) confidence = "medium";
        if (tokenUsage == null) tokenUsage = 0;
        if (durationMs == null) durationMs = 0L;
        if (metadata == null) metadata = Map.of();
    }
}
