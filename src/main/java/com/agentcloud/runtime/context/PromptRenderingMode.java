package com.agentcloud.runtime.context;

import com.agentcloud.model.Task;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;

/**
 * 控制 mounted context 在 prompt 中的 rollout 模式。
 */
public enum PromptRenderingMode {
    ACTIVE_CONTEXT_ONLY("active_context_only"),
    MOUNTED_CONTEXT_SHADOW("mounted_context_shadow"),
    MOUNTED_CONTEXT_PRIMARY("mounted_context_primary");

    private final String wireName;

    PromptRenderingMode(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    public boolean shouldRenderMountedPrompt() {
        return this != ACTIVE_CONTEXT_ONLY;
    }

    public boolean shouldInjectMountedPrompt() {
        return this == MOUNTED_CONTEXT_PRIMARY;
    }

    public static PromptRenderingMode resolve(Task task) {
        return resolve(task != null ? task.metadata() : null);
    }

    public static PromptRenderingMode resolve(Map<String, Object> metadata) {
        return fromWireName(firstNonBlank(
            metadataValue(metadata, "prompt_rendering_mode"),
            metadataValue(metadata, "mounted_context_mode")
        ));
    }

    public static PromptRenderingMode fromWireName(String raw) {
        String normalized = normalize(raw);
        return switch (normalized) {
            case "mounted_context_shadow", "shadow" -> MOUNTED_CONTEXT_SHADOW;
            case "mounted_context_primary", "primary", "mounted" -> MOUNTED_CONTEXT_PRIMARY;
            case "active_context_only", "active", "legacy" -> ACTIVE_CONTEXT_ONLY;
            default -> ACTIVE_CONTEXT_ONLY;
        };
    }

    private static String metadataValue(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase().replace('-', '_').replace(' ', '_');
    }
}
