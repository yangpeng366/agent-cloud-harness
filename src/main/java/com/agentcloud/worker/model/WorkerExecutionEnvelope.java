package com.agentcloud.worker.model;

import com.agentcloud.worker.WorkerExecutionResult;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 单轮 worker 执行的 runtime-facing envelope。
 *
 * <p>Phase-1 先保持最小侵入：不替换现有 WorkerExecutionResult，
 * 而是在 metadata 中稳定暴露 execution trace 所需的关键字段。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkerExecutionEnvelope(
    String executionId,
    String sessionId,
    String taskId,
    String workerId,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    String executionStatus,
    WorkerExecutionResult result,
    List<String> toolInvocationIds,
    Map<String, Object> metadata
) {
    public WorkerExecutionEnvelope {
        if (executionId == null) executionId = "";
        if (sessionId == null) sessionId = "";
        if (taskId == null) taskId = "";
        if (workerId == null) workerId = "";
        if (startedAt == null) startedAt = Instant.now();
        if (finishedAt == null) finishedAt = startedAt;
        if (durationMs == null) durationMs = 0L;
        if (executionStatus == null || executionStatus.isBlank()) {
            executionStatus = result != null ? result.executionStatus() : "unknown";
        }
        if (toolInvocationIds == null) toolInvocationIds = List.of();
        if (metadata == null) metadata = Map.of();
    }
}
