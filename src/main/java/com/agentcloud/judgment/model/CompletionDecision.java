package com.agentcloud.judgment.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 完成度与对齐判断结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompletionDecision(
    String status,
    String alignmentLevel,
    String reason,
    String suggestedNextAction,
    Map<String, Object> runtimeFacts
) {
    public CompletionDecision {
        if (status == null) status = "incomplete";
        if (alignmentLevel == null) alignmentLevel = "medium";
        if (reason == null) reason = "";
        if (runtimeFacts == null) runtimeFacts = Map.of();
    }

    public CompletionDecision(
        String status,
        String alignmentLevel,
        String reason,
        String suggestedNextAction
    ) {
        this(status, alignmentLevel, reason, suggestedNextAction, Map.of());
    }
}
