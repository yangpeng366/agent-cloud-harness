package com.agentcloud.model;

import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GoalEvent(
    String id,
    String goalId,
    String eventType,
    String actorType,
    String actorId,
    String summary,
    Map<String, Object> payload,
    Instant createdAt
) {
    public GoalEvent {
        if (createdAt == null) createdAt = Instant.now();
    }

    public static GoalEvent create(String id, String goalId, String eventType, String summary, Map<String, Object> payload) {
        return new GoalEvent(id, goalId, eventType, "system", null, summary, payload, Instant.now());
    }
}