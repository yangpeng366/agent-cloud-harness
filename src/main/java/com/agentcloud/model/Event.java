package com.agentcloud.model;

import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Event(
    String id,
    String sessionId,
    String taskId,
    Instant createdAt,
    String eventType,
    String actorType,
    String actorId,
    String summary,
    Map<String, Object> payload
) {
    public Event {
        if (createdAt == null) createdAt = Instant.now();
    }
}
