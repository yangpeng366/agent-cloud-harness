package com.agentcloud.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Provider 运行产生的工件引用。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentArtifactRef(
    String providerId,
    String runId,
    String artifactType,
    String title,
    String path,
    String summary,
    Instant createdAt,
    Map<String, Object> metadata
) {
    public AgentArtifactRef {
        if (providerId == null) providerId = "";
        if (runId == null) runId = "";
        if (artifactType == null) artifactType = "artifact";
        if (title == null) title = "";
        if (path == null) path = "";
        if (summary == null) summary = "";
        if (createdAt == null) createdAt = Instant.now();
        if (metadata == null) metadata = Map.of();
    }
}
