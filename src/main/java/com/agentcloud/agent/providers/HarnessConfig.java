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
    Map<String, String> workspaceAliases
) {
    public HarnessConfig {
        if (defaults == null) defaults = new HarnessDefaults(null, null, null, null);
        if (ccx == null) ccx = new HarnessCcxConfig(null, null, false, false);
        if (workers == null) workers = List.of();
        if (workspaceAliases == null) workspaceAliases = Map.of();
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
}