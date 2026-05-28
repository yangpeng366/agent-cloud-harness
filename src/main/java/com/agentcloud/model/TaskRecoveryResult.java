package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskRecoveryResult(
    TaskRecoveryPlan plan,
    TaskControlResult controlResult,
    HandoffResult handoffResult,
    Boolean accepted,
    Boolean async,
    String requestId,
    String statusUrl
) {
    public TaskRecoveryResult(TaskRecoveryPlan plan, TaskControlResult controlResult, HandoffResult handoffResult) {
        this(plan, controlResult, handoffResult, null, null, null, null);
    }

    public static TaskRecoveryResult accepted(TaskRecoveryPlan plan, String requestId, String statusUrl) {
        return new TaskRecoveryResult(plan, null, null, true, true, requestId, statusUrl);
    }
}
