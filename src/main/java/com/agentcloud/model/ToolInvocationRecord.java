package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * 工具调用轨迹记录。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolInvocationRecord(
    String id,
    String sessionId,
    String taskId,
    String workerId,
    String toolName,
    Map<String, Object> arguments,
    String resultSummary,
    boolean success,
    Integer elapsedMs,
    Instant createdAt,
    Map<String, Object> metadata
) {
    public ToolInvocationRecord {
        if (id == null) id = "";
        if (sessionId == null) sessionId = "";
        if (taskId == null) taskId = "";
        if (workerId == null) workerId = "";
        if (toolName == null) toolName = "";
        if (arguments == null) arguments = Map.of();
        if (resultSummary == null) resultSummary = "";
        if (elapsedMs != null && elapsedMs < 0) elapsedMs = 0;
        if (createdAt == null) createdAt = Instant.now();
        if (metadata == null) metadata = Map.of();
    }
}
