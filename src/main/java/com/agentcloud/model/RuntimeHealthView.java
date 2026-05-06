package com.agentcloud.model;

import com.agentcloud.agent.AgentProviderStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Managed agent runtime 的简版健康聚合视图。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuntimeHealthView(
    Instant checkedAt,
    int activeRunCount,
    @JsonProperty("failed_run_count_24h")
    int failedRunCount24h,
    @JsonProperty("crashed_run_count_24h")
    int crashedRunCount24h,
    @JsonProperty("cancelled_run_count_24h")
    int cancelledRunCount24h,
    int unavailableProviderCount,
    int authNeededProviderCount,
    Long averageRunDurationMs,
    Map<String, Double> providerFailureRate,
    List<ProviderRuntimeStats> providerStats,
    List<AgentRunRecord> activeRuns,
    List<AgentRunRecord> recentFailures,
    List<AgentProviderStatus> unavailableProviders,
    List<AgentProviderStatus> authProblemProviders,
    List<AgentRunRecord> recentRuns,
    Map<String, Object> metadata
) {
    public RuntimeHealthView {
        if (checkedAt == null) checkedAt = Instant.now();
        if (providerFailureRate == null) providerFailureRate = Map.of();
        if (providerStats == null) providerStats = List.of();
        if (activeRuns == null) activeRuns = List.of();
        if (recentFailures == null) recentFailures = List.of();
        if (unavailableProviders == null) unavailableProviders = List.of();
        if (authProblemProviders == null) authProblemProviders = List.of();
        if (recentRuns == null) recentRuns = List.of();
        if (metadata == null) metadata = Map.of();
    }
}
