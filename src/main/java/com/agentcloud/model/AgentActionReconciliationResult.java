package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单轮 worker action proposals 的协调结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentActionReconciliationResult(
    List<AgentActionDecision> decisions,
    List<AgentAction> acceptedActions,
    List<AgentAction> rejectedActions,
    List<AgentAction> approvalNeededActions
) {
    public AgentActionReconciliationResult {
        if (decisions == null) decisions = List.of();
        if (acceptedActions == null) acceptedActions = List.of();
        if (rejectedActions == null) rejectedActions = List.of();
        if (approvalNeededActions == null) approvalNeededActions = List.of();
    }

    public static AgentActionReconciliationResult empty() {
        return new AgentActionReconciliationResult(List.of(), List.of(), List.of(), List.of());
    }
}
