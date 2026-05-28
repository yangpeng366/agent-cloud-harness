package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Runtime 对单个 action proposal 的协调结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentActionDecision(
    AgentActionDraft draft,
    String decision,
    String reason,
    AgentAction action
) {
    public AgentActionDecision {
        if (decision == null || decision.isBlank()) decision = "reject";
        if (reason == null) reason = "";
    }
}
