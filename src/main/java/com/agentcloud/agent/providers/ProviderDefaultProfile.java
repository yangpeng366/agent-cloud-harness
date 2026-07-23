package com.agentcloud.agent.providers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider 级默认 profile 配置，从 system property / env var 解析。
 * 这层只承载全局默认值，命名 profile 本身挂在 worker metadata。
 */
public record ProviderDefaultProfile(
    String modelProvider,
    String model,
    String cliProfile,
    Map<String, String> configOverrides
) {
    public ProviderDefaultProfile {
        if (modelProvider == null) modelProvider = "";
        if (model == null) model = "";
        if (cliProfile == null) cliProfile = "";
        if (configOverrides == null) configOverrides = Map.of();
    }

    public boolean hasSubstantiveConfig() {
        return !modelProvider.isBlank()
            || !model.isBlank()
            || !cliProfile.isBlank()
            || !configOverrides.isEmpty();
    }

    public ProviderProfileConfig toProfileConfig() {
        return new ProviderProfileConfig(
            "provider_default",
            modelProvider,
            model,
            cliProfile,
            configOverrides
        );
    }

    public Map<String, Object> toMetadata() {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (!modelProvider.isBlank()) {
            metadata.put("default_model_provider", modelProvider);
            metadata.put("default_model_provider_source", "provider_config");
        }
        if (!model.isBlank()) {
            metadata.put("default_model", model);
            metadata.put("default_model_source", "provider_config");
        }
        if (!cliProfile.isBlank()) {
            metadata.put("default_cli_profile", cliProfile);
            metadata.put("default_cli_profile_source", "provider_config");
        }
        if (!configOverrides.isEmpty()) {
            metadata.put("default_config_overrides", Map.copyOf(configOverrides));
        }
        return Map.copyOf(metadata);
    }
}
