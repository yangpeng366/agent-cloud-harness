package com.agentcloud.llm;

/**
 * LLM 配置，从环境变量读取。
 */
public class LlmConfig {
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String reviewModel;
    private final String wireApi;
    private final int requestTimeoutSeconds;
    private final int maxRetries;
    private final Integer maxTokens;

    public LlmConfig() {
        this(
            System.getenv("OPENAI_API_KEY"),
            System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1"),
            System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini"),
            System.getenv("OPENAI_REVIEW_MODEL"),
            System.getenv().getOrDefault("OPENAI_WIRE_API", "chat_completions"),
            parseIntEnv("OPENAI_TIMEOUT_SECONDS", 60),
            parseIntEnv("OPENAI_MAX_RETRIES", 2),
            parseOptionalIntEnv("OPENAI_MAX_TOKENS")
        );
    }

    public LlmConfig(String apiKey,
                     String baseUrl,
                     String model,
                     String reviewModel,
                     String wireApi,
                     int requestTimeoutSeconds,
                     int maxRetries,
                     Integer maxTokens) {
        this.apiKey = blankToNull(apiKey);
        this.baseUrl = defaultIfBlank(baseUrl, "https://api.openai.com/v1");
        this.model = defaultIfBlank(model, "gpt-4o-mini");
        this.reviewModel = defaultIfBlank(reviewModel, this.model);
        this.wireApi = normalizeWireApi(wireApi);
        this.requestTimeoutSeconds = requestTimeoutSeconds > 0 ? requestTimeoutSeconds : 60;
        this.maxRetries = maxRetries > 0 ? maxRetries : 2;
        this.maxTokens = maxTokens != null && maxTokens > 0 ? maxTokens : null;
    }

    public String apiKey() { return apiKey; }
    public String baseUrl() { return baseUrl; }
    public String model() { return model; }
    public String reviewModel() { return reviewModel; }
    public String wireApi() { return wireApi; }
    public int requestTimeoutSeconds() { return requestTimeoutSeconds; }
    public int maxRetries() { return maxRetries; }
    public Integer maxTokens() { return maxTokens; }
    public boolean available() { return apiKey != null && !apiKey.isBlank(); }

    private static int parseIntEnv(String name, int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static Integer parseOptionalIntEnv(String name) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalizeWireApi(String raw) {
        if (raw == null || raw.isBlank()) {
            return "chat_completions";
        }
        String normalized = raw.trim().toLowerCase()
            .replace('-', '_')
            .replace(' ', '_');
        return switch (normalized) {
            case "responses", "response" -> "responses";
            case "chat", "chat_completions", "chatcompletion", "chat_completions_api" -> "chat_completions";
            default -> "chat_completions";
        };
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
