package com.agentcloud.agent.providers;

import java.util.List;
import java.util.Map;

/**
 * harness-config.yml 顶层配置 record。
 * 由 HarnessConfigLoader 解析 YAML 后构建。
 */
public record HarnessConfig(
    HarnessDefaults defaults,
    HarnessCcxConfig ccx,
    List<WorkerLaneConfig> workers,
    List<ProviderProtocolConfig> providers,
    Map<String, String> workspaceAliases,
    FeatureFlags featureFlags,
    HarnessEyesMcpConfig eyesMcp
) {
    public HarnessConfig {
        if (defaults == null) defaults = new HarnessDefaults(null, null, null, null);
        if (ccx == null) ccx = new HarnessCcxConfig(null, null, false, false);
        if (workers == null) workers = List.of();
        if (providers == null) providers = List.of();
        if (workspaceAliases == null) workspaceAliases = Map.of();
        if (featureFlags == null) featureFlags = FeatureFlags.defaults();
        if (eyesMcp == null) eyesMcp = new HarnessEyesMcpConfig(null, false, null, false, null);
    }

    public HarnessConfig(
        HarnessDefaults defaults,
        HarnessCcxConfig ccx,
        List<WorkerLaneConfig> workers,
        List<ProviderProtocolConfig> providers,
        Map<String, String> workspaceAliases
    ) {
        this(defaults, ccx, workers, providers, workspaceAliases, null);
    }

    public HarnessConfig(
        HarnessDefaults defaults,
        HarnessCcxConfig ccx,
        List<WorkerLaneConfig> workers,
        List<ProviderProtocolConfig> providers,
        Map<String, String> workspaceAliases,
        FeatureFlags featureFlags
    ) {
        this(defaults, ccx, workers, providers, workspaceAliases, featureFlags, null);
    }

    /**
     * 全局默认配置。
     */
    public record HarnessDefaults(
        String providerModelProvider,
        String providerBaseUrl,
        String providerWireApi,
        String providerBearerToken
    ) {
        public HarnessDefaults {
            if (providerModelProvider == null) providerModelProvider = "ccx";
            if (providerBaseUrl == null) providerBaseUrl = "";
            if (providerWireApi == null) providerWireApi = "";
            if (providerBearerToken == null) providerBearerToken = "";
        }
    }

    /**
     * CCX 渠道健康检查配置。
     */
    public record HarnessCcxConfig(
        String baseUrl,
        String adminKey,
        boolean healthCheckOnStartup,
        boolean channelSyncOnStartup
    ) {
        public HarnessCcxConfig {
            if (baseUrl == null) baseUrl = "http://127.0.0.1:3688";
            if (adminKey == null) adminKey = "";
        }
    }

    /**
     * OpenEyes MCP stdio reachability probe configuration.
     */
    public record HarnessEyesMcpConfig(
        String command,
        boolean healthCheckOnStartup,
        Integer startupTimeoutSeconds,
        boolean enabled,
        Boolean registerAsTool
    ) {
        public HarnessEyesMcpConfig {
            if (command == null || command.isBlank()) command = "eyes-mcp";
            if (startupTimeoutSeconds == null || startupTimeoutSeconds <= 0) startupTimeoutSeconds = 5;
            if (registerAsTool == null) registerAsTool = false;
        }
    }

    /**
     * Runtime feature flags declared at YAML root as feature_flags.*.
     */
    public record FeatureFlags(
        boolean jevContextScoring,
        boolean jevPatrolDispatcher,
        boolean jevPatrolPostprocess,
        boolean jevToolRecallFilter
    ) {
        public static FeatureFlags defaults() {
            return new FeatureFlags(false, false, false, false);
        }
    }
}
