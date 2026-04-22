package com.agentcloud.model;

import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Session(
    String id,
    String title,
    String status,        // active | paused | closed
    Instant createdAt,
    Instant updatedAt,
    Instant closedAt,
    String rootTaskId,
    String currentTaskId,
    String summary,
    Map<String, Object> metadata
) {
    public Session {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    public static Session create(String id, String title, String status) {
        return new Session(id, title, status, Instant.now(), Instant.now(), null, null, null, null, null);
    }
}
