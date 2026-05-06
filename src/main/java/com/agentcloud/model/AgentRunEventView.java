package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRunEventView(
    String eventId,
    String runId,
    String eventType,
    Instant createdAt,
    String summary,
    Map<String, Object> payload
) {
    public AgentRunEventView {
        if (eventId == null) eventId = "";
        if (runId == null) runId = "";
        if (eventType == null) eventType = "";
        if (createdAt == null) createdAt = Instant.now();
        if (payload == null) payload = Map.of();
    }
}
