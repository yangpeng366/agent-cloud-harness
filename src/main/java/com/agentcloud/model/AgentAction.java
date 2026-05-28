package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Runtime 对 action proposal 协调后的正式动作记录。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentAction(
    String id,
    String sessionId,
    String taskId,
    String sourceExecutionId,
    String actionType,
    String status,
    String summary,
    Map<String, Object> payload,
    String riskLevel,
    Boolean requiresApproval,
    String acceptedBy,
    String rejectionReason,
    Instant createdAt,
    Instant updatedAt,
    Map<String, Object> metadata
) {
    public AgentAction {
        if (id == null) id = "";
        if (sessionId == null) sessionId = "";
        if (taskId == null) taskId = "";
        if (sourceExecutionId == null) sourceExecutionId = "";
        if (actionType == null) actionType = "";
        if (status == null || status.isBlank()) status = "proposed";
        if (summary == null) summary = "";
        if (payload == null) payload = Map.of();
        if (riskLevel == null || riskLevel.isBlank()) riskLevel = "low";
        if (requiresApproval == null) requiresApproval = false;
        if (acceptedBy == null) acceptedBy = "";
        if (rejectionReason == null) rejectionReason = "";
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
        if (metadata == null) metadata = Map.of();
    }
}
