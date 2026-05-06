package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Agent Provider 执行轮次的持久化视图。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRunRecord(
    String runId,
    String taskId,
    String sessionId,
    String providerId,
    String providerDisplayName,
    String workerRole,
    String selectedWorkerId,
    String selectedModelTier,
    String status,
    Instant startedAt,
    Instant endedAt,
    Long durationMs,
    String summary,
    String lastEventType,
    Integer artifactCount,
    Map<String, Object> metadata
) {
    public AgentRunRecord {
        if (runId == null) runId = "";
        if (taskId == null) taskId = "";
        if (sessionId == null) sessionId = "";
        if (providerId == null || providerId.isBlank()) providerId = "unknown";
        if (providerDisplayName == null || providerDisplayName.isBlank()) providerDisplayName = providerId;
        if (workerRole == null || workerRole.isBlank()) workerRole = "executor";
        if (status == null || status.isBlank()) status = "completed";
        if (startedAt == null) startedAt = Instant.now();
        if (durationMs == null || durationMs < 0) durationMs = 0L;
        if (artifactCount == null || artifactCount < 0) artifactCount = 0;
        if (metadata == null) metadata = Map.of();
    }
}
