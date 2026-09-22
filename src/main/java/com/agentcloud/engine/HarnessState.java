package com.agentcloud.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * harness-state.json 的数据结构：harness 自动发现的本机环境状态。
 * 与 harness-config.yml（用户配置）合并时，用户配置覆盖自动发现结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HarnessState(
    Instant lastUpdated,
    boolean ccxReachable,
    List<String> ccxModels,
    Map<String, CcxChannelStatus> ccxChannels,
    Map<String, WorkerAvailability> workers,
    Map<String, ProviderAvailability> providers,
    int workerReadyCount,
    EyesMcpStatus eyesMcp
) {
    public HarnessState {
        if (ccxModels == null) ccxModels = List.of();
        if (ccxChannels == null) ccxChannels = Map.of();
        if (workers == null) workers = Map.of();
        if (providers == null) providers = Map.of();
        if (eyesMcp == null) eyesMcp = EyesMcpStatus.disabled();
        if (lastUpdated == null) lastUpdated = Instant.now();
    }

    public HarnessState(
        Instant lastUpdated,
        boolean ccxReachable,
        List<String> ccxModels,
        Map<String, CcxChannelStatus> ccxChannels,
        Map<String, WorkerAvailability> workers,
        Map<String, ProviderAvailability> providers,
        int workerReadyCount
    ) {
        this(lastUpdated, ccxReachable, ccxModels, ccxChannels, workers, providers,
            workerReadyCount, EyesMcpStatus.disabled());
    }

    public record CcxChannelStatus(String name, String status, int priority) {}

    public record WorkerAvailability(String workerId, boolean cliAvailable, String lastCheck) {}

    public record ProviderAvailability(String providerId, boolean available, boolean userEnabled) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EyesMcpStatus(
        boolean available,
        String status,
        String serverName,
        String serverVersion,
        int toolCount,
        long durationMs,
        String error,
        Instant checkedAt
    ) {
        public static EyesMcpStatus disabled() {
            return new EyesMcpStatus(false, "disabled", null, null, 0, 0, null, Instant.now());
        }
    }
}
