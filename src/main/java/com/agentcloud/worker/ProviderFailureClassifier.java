package com.agentcloud.worker;

import java.util.Locale;

/**
 * Provider 层失败分类，供执行器、run 统计和恢复链复用。
 */
public final class ProviderFailureClassifier {
    private ProviderFailureClassifier() {
    }

    public static Classification classify(String status, String message) {
        String normalizedStatus = normalize(status);
        String text = normalize(message);
        if (containsAny(text,
            "auth required",
            "authentication required",
            "login required",
            "not authenticated",
            "unauthorized",
            "please login",
            "请登录"
        )) {
            return new Classification("provider_auth_required", readable(message, normalizedStatus), false);
        }
        if (containsAny(text,
            "binary not found",
            "command not found",
            "not recognized as an internal or external command",
            "cannot run program",
            "no such file",
            "系统找不到指定的文件"
        )) {
            return new Classification("provider_not_installed", readable(message, normalizedStatus), false);
        }
        if (containsAny(text,
            "thread not found",
            "session expired",
            "provider unavailable",
            "connection reset",
            "timed out",
            "timeout",
            "failed to start",
            "process exited",
            "interrupted",
            "output too large",
            "output exceeds",
            "output exceeded",
            "output limit",
            "max output",
            "maximum output",
            "response too large",
            "context length exceeded",
            "context window exceeded",
            "没找到线程",
            "未找到线程",
            "输出过大",
            "输出超过",
            "上下文超限"
        )) {
            return new Classification("provider_runtime_transient", readable(message, normalizedStatus), true);
        }
        if (containsAny(text,
            "unknown option",
            "unknown argument",
            "unknown command",
            "unrecognized option",
            "unrecognized argument",
            "invalid option",
            "invalid argument",
            "unexpected argument",
            "unsupported option",
            "unsupported argument",
            "no such command",
            "unknown subcommand",
            "unrecognized subcommand",
            "invalid subcommand",
            "json-rpc",
            "json rpc",
            "protocol",
            "parse",
            "returned no thread id",
            "no thread id",
            "app-server"
        )) {
            return new Classification("provider_protocol_error", readable(message, normalizedStatus), true);
        }
        if (isFailureStatus(normalizedStatus)) {
            return new Classification("provider_execution_failed", readable(message, normalizedStatus), false);
        }
        return null;
    }

    /**
     * Codex profile 级失败分类，在 provider 级分类之上补充 profile 特有语义。
     */
    public static ProfileFailureClassification classifyProfileFailure(String status, String message) {
        String text = normalize(message);
        if (containsAny(text,
            "quota exceeded",
            "rate limit",
            "rate_limit",
            "too many requests",
            "usage limit",
            "usage_limit",
            "billing limit",
            "billing_limit",
            "insufficient quota",
            "额度耗尽",
            "额度不足",
            "请求过多"
        )) {
            return new ProfileFailureClassification("quota_exhausted", readable(message, normalize(status)), true);
        }
        if (containsAny(text,
            "auth blocked",
            "auth_blocked",
            "api key invalid",
            "api_key_invalid",
            "invalid api key",
            "invalid_api_key",
            "api key revoked",
            "api_key_revoked",
            "forbidden",
            "403",
            "认证被阻止",
            "API 密钥无效"
        )) {
            return new ProfileFailureClassification("auth_blocked", readable(message, normalize(status)), false);
        }
        if (containsAny(text,
            "backend unavailable",
            "backend_unavailable",
            "service unavailable",
            "503",
            "502",
            "bad gateway",
            "gateway timeout",
            "504",
            "upstream connect error",
            "connection refused",
            "后端不可用"
        )) {
            return new ProfileFailureClassification("backend_unavailable", readable(message, normalize(status)), true);
        }
        if (containsAny(text,
            "profile misconfigured",
            "profile_misconfigured",
            "unknown model_provider",
            "unknown model provider",
            "unsupported model_provider",
            "unsupported model provider",
            "invalid profile",
            "invalid_profile",
            "profile not found",
            "配置错误",
            "profile 配错"
        )) {
            return new ProfileFailureClassification("profile_misconfigured", readable(message, normalize(status)), false);
        }
        if (containsAny(text,
            "dispatch preflight failed",
            "dispatch_preflight_failed",
            "preflight rejected",
            "dispatch probe failed"
        )) {
            return new ProfileFailureClassification("dispatch_preflight_failed", readable(message, normalize(status)), true);
        }
        return null;
    }

    private static boolean isFailureStatus(String status) {
        return "failed".equals(status)
            || "timeout".equals(status)
            || "cancelled".equals(status)
            || "empty".equals(status)
            || "blocked".equals(status);
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String readable(String message, String fallback) {
        String value = message == null || message.isBlank() ? fallback : message.replaceAll("\\s+", " ").trim();
        if (value == null || value.isBlank()) {
            return "provider execution failed";
        }
        return value.length() > 240 ? value.substring(0, 240) + "..." : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record Classification(String failureClass, String reason, boolean retryable) {
    }

    public record ProfileFailureClassification(String failureClass, String reason, boolean retryable) {
    }
}
