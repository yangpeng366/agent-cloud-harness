package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionMessage(
    String id,
    String sessionId,
    String taskId,
    String role,
    String messageType,
    String content,
    Instant createdAt,
    Map<String, Object> metadata
) {
    public SessionMessage {
        if (createdAt == null) createdAt = Instant.now();
    }
}
