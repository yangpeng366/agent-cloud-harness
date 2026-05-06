package com.agentcloud.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Provider 侧一次执行引用。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRunRef(
    String providerId,
    String runId,
    String sessionId,
    String taskId,
    String status,
    Instant startedAt,
    Map<String, Object> metadata
) {
    public AgentRunRef {
        if (providerId == null) providerId = "";
        if (runId == null) runId = "";
        if (sessionId == null) sessionId = "";
        if (taskId == null) taskId = "";
        if (status == null) status = "queued";
        if (startedAt == null) startedAt = Instant.now();
        if (metadata == null) metadata = Map.of();
    }
}
