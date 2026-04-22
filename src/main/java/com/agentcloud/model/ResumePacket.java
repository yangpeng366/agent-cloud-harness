package com.agentcloud.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResumePacket(
    String id,
    String sessionId,
    String taskId,
    Instant createdAt,
    String packetVersion,
    String activeTaskSummary,
    String decisionSummary,
    String artifactSummary,
    List<String> openQuestions,
    String nextStep,
    Map<String, Object> payload
) {
    public ResumePacket {
        if (createdAt == null) createdAt = Instant.now();
        if (packetVersion == null) packetVersion = "1.0";
    }
}
