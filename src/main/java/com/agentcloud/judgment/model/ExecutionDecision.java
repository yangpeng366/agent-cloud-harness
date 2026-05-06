package com.agentcloud.judgment.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 执行控制判断结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutionDecision(
    String action,
    String reason,
    String nextStep,
    boolean needsCheckpoint,
    boolean needsHuman,
    String targetWorker,
    String retryDecision,
    String escalationDecision
) {
    public ExecutionDecision {
        if (action == null) action = "continue";
        if (reason == null) reason = "";
        if (retryDecision == null) retryDecision = "";
        if (escalationDecision == null) escalationDecision = "";
    }

    public ExecutionDecision(String action, String reason, String nextStep, boolean needsCheckpoint, boolean needsHuman, String targetWorker) {
        this(action, reason, nextStep, needsCheckpoint, needsHuman, targetWorker, "", "");
    }
}
