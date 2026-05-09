package com.agentcloud.worker;

import java.util.Locale;
import java.util.Set;

/**
 * 统一维护当前 harness 已接入的 provider execution backend 支持矩阵，
 * 避免 readiness / routing / executor 各自维护不同名单而产生漂移。
 */
public final class ProviderExecutionSupport {
    private static final Set<String> PROVIDER_NATIVE_CLI = Set.of(
        "cursor", "openclaw", "claude", "gemini", "deepseek", "kimi", "copilot", "opencode"
    );
    private static final Set<String> PROVIDER_APP_SERVER = Set.of("codex");

    private ProviderExecutionSupport() {
    }

    public static boolean supportsBackend(String providerId, String executionBackend) {
        String backend = normalize(executionBackend);
        return switch (backend) {
            case "provider_native_cli" -> supportsProviderNativeCli(providerId);
            case "provider_app_server" -> supportsProviderAppServer(providerId);
            default -> false;
        };
    }

    public static boolean supportsProviderNativeCli(String providerId) {
        return PROVIDER_NATIVE_CLI.contains(normalize(providerId));
    }

    public static boolean supportsProviderAppServer(String providerId) {
        return PROVIDER_APP_SERVER.contains(normalize(providerId));
    }

    public static String unsupportedReason(String providerId, String executionBackend) {
        String backend = executionBackend == null || executionBackend.isBlank()
            ? "unknown"
            : executionBackend;
        String provider = providerId == null || providerId.isBlank()
            ? "unknown"
            : providerId;
        return "executor backend not supported by current harness: provider=" + provider + " backend=" + backend;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
