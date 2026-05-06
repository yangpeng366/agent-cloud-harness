package com.agentcloud.agent;

import java.util.Locale;

/**
 * 将历史 worker 标识投影到 Agent Provider 标识。
 */
public final class AgentProviderResolver {
    private AgentProviderResolver() {}

    public static String providerIdForWorker(String workerId, String workerType) {
        String normalizedWorker = normalize(workerId);
        String normalizedType = normalize(workerType);
        if (normalizedWorker == null && normalizedType == null) {
            return null;
        }
        if (contains(normalizedWorker, "codex") || contains(normalizedType, "codex")) {
            return "codex";
        }
        if (contains(normalizedWorker, "openclaw") || contains(normalizedType, "openclaw")
            || contains(normalizedType, "native")) {
            return "openclaw";
        }
        if (contains(normalizedWorker, "kimi") || contains(normalizedType, "kimi")) {
            return "kimi";
        }
        return firstNonBlank(workerType, workerId);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.contains(needle);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
