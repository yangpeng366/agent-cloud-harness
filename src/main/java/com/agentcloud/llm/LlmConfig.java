package com.agentcloud.llm;

/**
 * LLM 配置，从环境变量读取。
 */
public class LlmConfig {
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int requestTimeoutSeconds;
    private final int maxRetries;
    private final Integer maxTokens;

    public LlmConfig() {
        this.apiKey = System.getenv("OPENAI_API_KEY");
        this.baseUrl = System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1");
        this.model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
        this.requestTimeoutSeconds = parseIntEnv("OPENAI_TIMEOUT_SECONDS", 60);
        this.maxRetries = parseIntEnv("OPENAI_MAX_RETRIES", 2);
        this.maxTokens = parseOptionalIntEnv("OPENAI_MAX_TOKENS");
    }

    public String apiKey() { return apiKey; }
    public String baseUrl() { return baseUrl; }
    public String model() { return model; }
    public int requestTimeoutSeconds() { return requestTimeoutSeconds; }
    public int maxRetries() { return maxRetries; }
    public Integer maxTokens() { return maxTokens; }
    public boolean available() { return apiKey != null && !apiKey.isBlank(); }

    private int parseIntEnv(String name, int defaultValue) {
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

    private Integer parseOptionalIntEnv(String name) {
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
}
