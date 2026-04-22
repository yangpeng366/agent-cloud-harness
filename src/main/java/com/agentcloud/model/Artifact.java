package com.agentcloud.model;

import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Artifact(
    String id,
    String sessionId,
    String taskId,
    Instant createdAt,
    String artifactType,
    String title,
    String uri,
    String contentHash,
    String summary,
    Map<String, Object> metadata
) {
    public Artifact {
        if (createdAt == null) createdAt = Instant.now();
    }
}
