package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskRecoveryPlan(
    String taskId,
    String status,
    String controlNode,
    String assignedWorker,
    boolean recoverable,
    String recommendedAction,
    String targetWorker,
    String reason,
    String failureClass,
    String providerFailureClass,
    String failureEvidenceSource,
    String failureEvidence,
    String recoveryStage,
    String recoveryExecutionMode
) {}
