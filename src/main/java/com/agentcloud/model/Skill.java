package com.agentcloud.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Skill(
    String id,
    String name,
    String description,
    List<String> capabilityTags,
    Map<String, Object> inputSchema,
    Map<String, Object> outputSchema,
    Map<String, Boolean> dependencies,
    String riskLevel,       // low | medium | high | critical
    boolean installed,
    boolean ready,
    Instant lastCheckedAt,
    String version,
    Map<String, Object> metadata,
    Instant createdAt,
    Instant updatedAt
) {
    public Skill {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }
}
