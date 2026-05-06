package com.agentcloud.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Provider 单次运行结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRunResult(
    String runId,
    String taskId,
    String sessionId,
    String providerId,
    String status,
    Integer exitCode,
    String summary,
    String outputPreview,
    Instant startedAt,
    Instant endedAt,
    Map<String, Object> metadata
) {
    public AgentRunResult {
        if (runId == null) runId = "";
        if (taskId == null) taskId = "";
        if (sessionId == null) sessionId = "";
        if (providerId == null) providerId = "";
        if (status == null) status = "unknown";
        if (summary == null) summary = "";
        if (outputPreview == null) outputPreview = "";
        if (startedAt == null) startedAt = Instant.now();
        if (metadata == null) metadata = Map.of();
    }
}
