package com.agentcloud.model;

import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Decision(
    String id,
    String sessionId,
    String taskId,
    Instant createdAt,
    String decisionType,
    String summary,
    String rationale,
    String impactLevel,
    String supersedesDecisionId,
    Map<String, Object> metadata
) {
    public Decision {
        if (createdAt == null) createdAt = Instant.now();
    }
}
