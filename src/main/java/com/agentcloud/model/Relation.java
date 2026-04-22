package com.agentcloud.model;

import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Relation(
    String id,
    String sourceType,
    String sourceId,
    String relationType,   // belongs_to | depends_on | produces | uses | continues | mentions | supersedes | informs
    String targetType,
    String targetId,
    Instant createdAt,
    Map<String, Object> metadata
) {
    public Relation {
        if (createdAt == null) createdAt = Instant.now();
    }
}
