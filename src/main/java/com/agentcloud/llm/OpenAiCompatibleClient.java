package com.agentcloud.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 JDK HttpClient 的 OpenAI-compatible 客户端。
 * 第一版仅支持最小文本 chat 调用。
 */
public class OpenAiCompatibleClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long BASE_RETRY_BACKOFF_MS = 1500L;
    private final LlmConfig config;
    private final HttpClient httpClient;
    private final Set<String> invalidUrls = ConcurrentHashMap.newKeySet();
    private volatile String preferredUrl;

    public OpenAiCompatibleClient(LlmConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        if (!config.available()) {
            log.warn("LLM not configured (missing OPENAI_API_KEY), returning empty response");
            return "";
        }

        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", config.model());
            if (config.maxTokens() != null && config.maxTokens() > 0) {
                body.put("max_tokens", config.maxTokens());
            }
            ArrayNode messages = body.putArray("messages");

            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);

            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);

            String requestBody = MAPPER.writeValueAsString(body);
            for (String url : candidateUrls()) {
                for (int attempt = 1; attempt <= Math.max(1, config.maxRetries()); attempt++) {
                    try {
                        log.debug("LLM request url={} model={} attempt={}/{}", url, config.model(), attempt, config.maxRetries());

                        HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + config.apiKey())
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                            .build();

                        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                        String responseBody = response.body();
                        String contentType = response.headers().firstValue("Content-Type").orElse("");

                        if (response.statusCode() != 200) {
                            log.error("LLM request failed: url={}, attempt={}/{}, status={}, contentType={}, bodySnippet={}",
                                url, attempt, config.maxRetries(), response.statusCode(), contentType, snippet(responseBody));
                            pauseBeforeRetry(attempt, config.maxRetries(), url, "http_status_" + response.statusCode());
                            continue;
                        }

                        if (responseBody == null || responseBody.isBlank()) {
                            log.error("LLM request returned empty body. url={}, attempt={}/{}, contentType={}",
                                url, attempt, config.maxRetries(), contentType);
                            pauseBeforeRetry(attempt, config.maxRetries(), url, "empty_body");
                            continue;
                        }

                        String trimmedBody = responseBody.trim();
                        if (trimmedBody.startsWith("<")) {
                            log.error("LLM request returned non-JSON HTML/text body. url={}, attempt={}/{}, contentType={}, bodySnippet={}",
                                url, attempt, config.maxRetries(), contentType, snippet(trimmedBody));
                            invalidUrls.add(url);
                            continue;
                        }

                        JsonNode root = MAPPER.readTree(trimmedBody);
                        JsonNode choices = root.get("choices");
                        if (choices == null || !choices.isArray() || choices.isEmpty()) {
                            log.warn("LLM response has no choices. url={}, attempt={}/{}, bodySnippet={}",
                                url, attempt, config.maxRetries(), snippet(trimmedBody));
                            pauseBeforeRetry(attempt, config.maxRetries(), url, "missing_choices");
                            continue;
                        }

                        String content = choices.get(0).path("message").path("content").asText("");
                        preferredUrl = url;
                        log.debug("LLM response content length={} url={} attempt={}/{}",
                            content.length(), url, attempt, config.maxRetries());
                        return content;
                    } catch (Exception attemptError) {
                        log.warn("LLM request attempt failed. url={}, attempt={}/{}, message={}",
                            url, attempt, config.maxRetries(), attemptError.toString());
                        pauseBeforeRetry(attempt, config.maxRetries(), url, attemptError.getClass().getSimpleName());
                    }
                }
            }
            return "";

        } catch (Exception e) {
            log.error("LLM chat failed", e);
            return "";
        }
    }

    private List<String> candidateUrls() {
        String configured = normalizeBaseUrl(config.baseUrl());
        List<String> urls = new ArrayList<>();
        URI uri = URI.create(configured);
        String path = uri.getPath();
        boolean rootPath = path == null || path.isBlank() || "/".equals(path);
        if (rootPath) {
            urls.add(joinUrl(configured, "v1/chat/completions"));
        }
        urls.add(joinUrl(configured, "chat/completions"));
        List<String> distinct = urls.stream().distinct().toList();
        if (preferredUrl != null && distinct.contains(preferredUrl) && !invalidUrls.contains(preferredUrl)) {
            return List.of(preferredUrl);
        }
        return distinct.stream()
            .filter(url -> !invalidUrls.contains(url))
            .toList();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com/v1";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String joinUrl(String baseUrl, String suffix) {
        return baseUrl + "/" + suffix;
    }

    private String snippet(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() > 240 ? normalized.substring(0, 240) + "..." : normalized;
    }

    private void pauseBeforeRetry(int attempt, int maxRetries, String url, String reason) {
        if (attempt >= Math.max(1, maxRetries)) {
            return;
        }
        long delayMs = BASE_RETRY_BACKOFF_MS * attempt;
        log.debug("LLM retry backoff url={} attempt={}/{} reason={} delayMs={}",
            url, attempt, maxRetries, reason, delayMs);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("LLM retry backoff interrupted. url={} reason={}", url, reason);
        }
    }
}
