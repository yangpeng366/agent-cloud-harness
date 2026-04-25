package com.agentcloud.judgment.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 完成度与对齐判断结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompletionDecision(
    String status,
    String alignmentLevel,
    String reason,
    String suggestedNextAction
) {
    public CompletionDecision {
        if (status == null) status = "incomplete";
        if (alignmentLevel == null) alignmentLevel = "medium";
        if (reason == null) reason = "";
    }
}
