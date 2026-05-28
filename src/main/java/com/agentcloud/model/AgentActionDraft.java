package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Worker/agent 在单轮执行后提出的 runtime action proposal。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentActionDraft(
    String actionType,
    String summary,
    Map<String, Object> payload,
    String riskLevel,
    Boolean requiresApproval,
    String reason,
    String confidence
) {
    public AgentActionDraft {
        if (actionType == null) actionType = "";
        if (summary == null) summary = "";
        if (payload == null) payload = Map.of();
        if (riskLevel == null || riskLevel.isBlank()) riskLevel = "low";
        if (requiresApproval == null) requiresApproval = false;
        if (reason == null) reason = "";
        if (confidence == null) confidence = "";
    }
}
