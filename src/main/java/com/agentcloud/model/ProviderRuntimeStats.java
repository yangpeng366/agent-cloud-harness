package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Provider 维度的运行统计，用于 Runtime Health 对比视图。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderRuntimeStats(
    String providerId,
    int totalRuns,
    int activeRuns,
    int completedRuns,
    int failedRuns,
    int cancelledRuns,
    int crashedRuns,
    Long averageDurationMs,
    Double failureRate,
    Instant lastRunAt,
    Instant lastFailureAt,
    String lastFailureSummary,
    Map<String, Object> metadata
) {
    public ProviderRuntimeStats {
        if (providerId == null || providerId.isBlank()) providerId = "unknown";
        if (metadata == null) metadata = Map.of();
    }
}
