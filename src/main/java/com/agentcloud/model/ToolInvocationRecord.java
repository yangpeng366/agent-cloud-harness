package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
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
    String executionId,
    String toolName,
    Map<String, Object> arguments,
    String resultSummary,
    String status,
    boolean success,
    Integer elapsedMs,
    List<String> touchedPaths,
    Instant createdAt,
    Map<String, Object> metadata
) {
    public ToolInvocationRecord {
        if (id == null) id = "";
        if (sessionId == null) sessionId = "";
        if (taskId == null) taskId = "";
        if (workerId == null) workerId = "";
        if (executionId == null) executionId = "";
        if (toolName == null) toolName = "";
        if (arguments == null) arguments = Map.of();
        if (resultSummary == null) resultSummary = "";
        if (status == null || status.isBlank()) status = success ? "succeeded" : "failed";
        if (elapsedMs != null && elapsedMs < 0) elapsedMs = 0;
        if (touchedPaths == null) touchedPaths = List.of();
        if (createdAt == null) createdAt = Instant.now();
        if (metadata == null) metadata = Map.of();
    }
}
