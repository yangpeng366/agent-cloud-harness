package com.agentcloud.agent.providers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地 CLI provider 的共享配置解析，避免探测和执行链路使用不同配置来源。
 */
public final class LocalCliProviderConfig {
    private final String providerId;
    private final String defaultBinary;
    private final String pathEnvVar;
    private final String modelEnvVar;
    private final String pathProperty;
    private final String modelProperty;

    public LocalCliProviderConfig(String providerId,
                                  String defaultBinary,
                                  String pathEnvVar,
                                  String modelEnvVar) {
        this.providerId = providerId == null ? "" : providerId;
        this.defaultBinary = defaultBinary == null || defaultBinary.isBlank() ? this.providerId : defaultBinary;
        this.pathEnvVar = blankToNull(pathEnvVar);
        this.modelEnvVar = blankToNull(modelEnvVar);
        this.pathProperty = this.providerId.isBlank() ? null : "agentcloud.providers." + this.providerId + ".path";
        this.modelProperty = this.providerId.isBlank() ? null : "agentcloud.providers." + this.providerId + ".model";
    }

    public ResolvedConfig resolve() {
        ConfigValue binary = resolveConfig(pathProperty, pathEnvVar, defaultBinary);
        ConfigValue model = resolveConfig(modelProperty, modelEnvVar, null);
        return new ResolvedConfig(
            providerId,
            defaultBinary,
            pathEnvVar,
            modelEnvVar,
            pathProperty,
            modelProperty,
            binary,
            model
        );
    }

    private ConfigValue resolveConfig(String propertyKey, String envKey, String fallbackValue) {
        String propertyValue = blankToNull(propertyKey == null ? null : System.getProperty(propertyKey));
        if (propertyValue != null) {
            return new ConfigValue(propertyValue, "system_property");
        }
        String envValue = blankToNull(envKey == null ? null : System.getenv(envKey));
        if (envValue != null) {
            return new ConfigValue(envValue, "environment");
        }
        return new ConfigValue(fallbackValue, "default");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record ResolvedConfig(String providerId,
                                 String defaultBinary,
                                 String pathEnvVar,
                                 String modelEnvVar,
                                 String pathProperty,
                                 String modelProperty,
                                 ConfigValue binary,
                                 ConfigValue model) {
        public ResolvedConfig {
            if (providerId == null) providerId = "";
            if (defaultBinary == null || defaultBinary.isBlank()) defaultBinary = providerId;
            if (binary == null) binary = new ConfigValue(defaultBinary, "default");
            if (model == null) model = new ConfigValue("", "default");
        }

        public Map<String, Object> metadata() {
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("binary", defaultBinary);
            metadata.put("configured_binary", binary.value());
            metadata.put("binary_source", binary.source());
            putIfNotBlank(metadata, "path_env_var", pathEnvVar);
            putIfNotBlank(metadata, "path_property", pathProperty);
            putIfNotBlank(metadata, "configured_model", model.value());
            putIfNotBlank(metadata, "model_source", model.source());
            putIfNotBlank(metadata, "model_env_var", modelEnvVar);
            putIfNotBlank(metadata, "model_property", modelProperty);
            return Map.copyOf(metadata);
        }

        private void putIfNotBlank(Map<String, Object> target, String key, String value) {
            if (target != null && key != null && value != null && !value.isBlank()) {
                target.put(key, value);
            }
        }
    }

    public record ConfigValue(String value, String source) {
        public ConfigValue {
            if (value == null || value.isBlank()) value = "";
            if (source == null || source.isBlank()) source = "default";
        }
    }
}
