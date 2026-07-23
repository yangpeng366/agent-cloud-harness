package com.agentcloud.agent.providers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Codex provider profile 配置，承载同一条 codex CLI 对接不同 API / 账户通道的参数。
 *
 * 解析优先级：task metadata > worker metadata > provider 默认配置（system property / env var）。
 */
public record ProviderProfileConfig(
    String providerProfileId,
    String modelProvider,
    String model,
    String cliProfile,
    Map<String, String> configOverrides
) {
    public ProviderProfileConfig {
        if (providerProfileId == null) providerProfileId = "";
        if (modelProvider == null) modelProvider = "";
        if (model == null) model = "";
        if (cliProfile == null) cliProfile = "";
        if (configOverrides == null) configOverrides = Map.of();
    }

    /**
     * 判断该 profile 是否有实质配置（非全空默认值）。
     */
    public boolean hasSubstantiveConfig() {
        return !modelProvider.isBlank()
            || !model.isBlank()
            || !cliProfile.isBlank()
            || !configOverrides.isEmpty();
    }

    /**
     * 将 profile 关键字段导出为 metadata map，供观测与 trace 使用。
     */
    public Map<String, Object> toMetadata() {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (!providerProfileId.isBlank()) {
            metadata.put("provider_profile_id", providerProfileId);
        }
        if (!modelProvider.isBlank()) {
            metadata.put("configured_model_provider", modelProvider);
        }
        if (!model.isBlank()) {
            metadata.put("configured_model", model);
        }
        if (!cliProfile.isBlank()) {
            metadata.put("configured_cli_profile", cliProfile);
        }
        if (!configOverrides.isEmpty()) {
            metadata.put("configured_config_overrides", Map.copyOf(configOverrides));
        }
        return Map.copyOf(metadata);
    }

    /**
     * 合并两个 profile：other 中的非空字段覆盖 this 的对应字段。
     * 用于实现 task > worker > provider 的优先级合并。
     */
    public ProviderProfileConfig merge(ProviderProfileConfig other) {
        if (other == null) {
            return this;
        }
        return new ProviderProfileConfig(
            blankToThis(providerProfileId, other.providerProfileId),
            blankToThis(modelProvider, other.modelProvider),
            blankToThis(model, other.model),
            blankToThis(cliProfile, other.cliProfile),
            mergeConfigOverrides(configOverrides, other.configOverrides)
        );
    }

    /**
     * 从 worker metadata 构建 profile 配置。
     */
    public static ProviderProfileConfig fromWorkerMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return new ProviderProfileConfig("", "", "", "", Map.of());
        }
        return new ProviderProfileConfig(
            stringFromMap(metadata, "provider_profile_id"),
            stringFromMap(metadata, "provider_model_provider"),
            stringFromMap(metadata, "provider_model"),
            stringFromMap(metadata, "provider_cli_profile"),
            Map.of()
        );
    }

    /**
     * 从 task metadata 构建 profile 配置。
     */
    public static ProviderProfileConfig fromTaskMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return new ProviderProfileConfig("", "", "", "", Map.of());
        }
        return new ProviderProfileConfig(
            stringFromMap(metadata, "preferred_provider_profile"),
            stringFromMap(metadata, "provider_model_provider"),
            stringFromMap(metadata, "provider_model"),
            stringFromMap(metadata, "provider_cli_profile"),
            Map.of()
        );
    }

    private static String blankToThis(String base, String override) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        return base;
    }

    private static Map<String, String> mergeConfigOverrides(
        Map<String, String> base, Map<String, String> override) {
        if (override == null || override.isEmpty()) {
            return base;
        }
        if (base == null || base.isEmpty()) {
            return override;
        }
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(base);
        merged.putAll(override);
        return Map.copyOf(merged);
    }

    private static String stringFromMap(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }
}
