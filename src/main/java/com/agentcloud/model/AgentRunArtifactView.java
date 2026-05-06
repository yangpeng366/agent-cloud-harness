package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRunArtifactView(
    String artifactId,
    String runId,
    String providerId,
    String artifactType,
    String title,
    String path,
    String summary,
    Instant createdAt,
    Map<String, Object> metadata
) {
    public AgentRunArtifactView {
        if (artifactId == null) artifactId = "";
        if (runId == null) runId = "";
        if (providerId == null || providerId.isBlank()) providerId = "unknown";
        if (artifactType == null) artifactType = "";
        if (createdAt == null) createdAt = Instant.now();
        if (metadata == null) metadata = Map.of();
    }
}
