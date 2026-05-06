package com.agentcloud.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
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
        return send("default", config.model(), systemPrompt, userPrompt, List.of());
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, List<LlmImageInput> imageInputs) {
        return send("default", config.model(), systemPrompt, userPrompt, imageInputs);
    }

    @Override
    public String review(String systemPrompt, String userPrompt) {
        return send("review", config.reviewModel(), systemPrompt, userPrompt, List.of());
    }

    private String send(String channel,
                        String model,
                        String systemPrompt,
                        String userPrompt,
                        List<LlmImageInput> imageInputs) {
        if (!config.available()) {
            log.warn("LLM not configured (missing OPENAI_API_KEY), returning empty response");
            return "";
        }

        try {
            if ("responses".equals(config.wireApi())) {
                return sendResponses(channel, model, systemPrompt, userPrompt, imageInputs);
            }
            return sendChatCompletions(channel, model, systemPrompt, userPrompt, imageInputs);

        } catch (Exception e) {
            log.error("LLM chat failed", e);
            return "";
        }
    }

    private String sendChatCompletions(String channel,
                                       String model,
                                       String systemPrompt,
                                       String userPrompt,
                                       List<LlmImageInput> imageInputs) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        if (config.maxTokens() != null && config.maxTokens() > 0) {
            body.put("max_tokens", config.maxTokens());
        }
        ArrayNode messages = body.putArray("messages");

        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);

        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        if (imageInputs == null || imageInputs.isEmpty()) {
            userMsg.put("content", userPrompt);
        } else {
            ArrayNode userContent = userMsg.putArray("content");
            ObjectNode textItem = userContent.addObject();
            textItem.put("type", "text");
            textItem.put("text", userPrompt);
            for (LlmImageInput imageInput : imageInputs) {
                appendChatCompletionImage(userContent, imageInput);
            }
        }

        return sendJsonRequest(
            "chat_completions",
            channel,
            model,
            body,
            candidateUrls("chat_completions"),
            this::extractChatCompletionText
        );
    }

    private String sendResponses(String channel,
                                 String model,
                                 String systemPrompt,
                                 String userPrompt,
                                 List<LlmImageInput> imageInputs) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("instructions", systemPrompt);
        if (imageInputs == null || imageInputs.isEmpty()) {
            body.put("input", userPrompt);
        } else {
            ArrayNode input = body.putArray("input");
            ObjectNode userMessage = input.addObject();
            userMessage.put("role", "user");
            ArrayNode content = userMessage.putArray("content");
            ObjectNode textItem = content.addObject();
            textItem.put("type", "input_text");
            textItem.put("text", userPrompt);
            for (LlmImageInput imageInput : imageInputs) {
                appendResponsesImage(content, imageInput);
            }
        }
        if (config.maxTokens() != null && config.maxTokens() > 0) {
            body.put("max_output_tokens", config.maxTokens());
        }

        return sendJsonRequest(
            "responses",
            channel,
            model,
            body,
            candidateUrls("responses"),
            this::extractResponsesText
        );
    }

    private void appendChatCompletionImage(ArrayNode userContent, LlmImageInput imageInput) throws IOException {
        String imageUrl = toDataUrl(imageInput);
        if (imageUrl.isBlank()) {
            return;
        }
        ObjectNode imageItem = userContent.addObject();
        imageItem.put("type", "image_url");
        ObjectNode imageUrlNode = imageItem.putObject("image_url");
        imageUrlNode.put("url", imageUrl);
    }

    private void appendResponsesImage(ArrayNode content, LlmImageInput imageInput) throws IOException {
        String imageUrl = toDataUrl(imageInput);
        if (imageUrl.isBlank()) {
            return;
        }
        ObjectNode imageItem = content.addObject();
        imageItem.put("type", "input_image");
        imageItem.put("image_url", imageUrl);
    }

    private String toDataUrl(LlmImageInput imageInput) throws IOException {
        if (imageInput == null || imageInput.path() == null || imageInput.path().isBlank()) {
            return "";
        }
        Path path = Paths.get(imageInput.path()).toAbsolutePath().normalize();
        byte[] bytes = Files.readAllBytes(path);
        String mediaType = resolveMediaType(path, imageInput.mediaType());
        return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private String resolveMediaType(Path path, String explicitMediaType) throws IOException {
        if (explicitMediaType != null && !explicitMediaType.isBlank()) {
            return explicitMediaType;
        }
        String detected = Files.probeContentType(path);
        if (detected != null && !detected.isBlank()) {
            return detected;
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        return "application/octet-stream";
    }

    private String sendJsonRequest(String wireApi,
                                   String channel,
                                   String model,
                                   ObjectNode body,
                                   List<String> urls,
                                   ResponseBodyParser parser) throws Exception {
        String requestBody = MAPPER.writeValueAsString(body);
        for (String url : urls) {
            for (int attempt = 1; attempt <= Math.max(1, config.maxRetries()); attempt++) {
                try {
                    log.debug("LLM request wireApi={} channel={} url={} model={} attempt={}/{}",
                        wireApi, channel, url, model, attempt, config.maxRetries());

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
                        log.error("LLM request failed: wireApi={} channel={} url={} attempt={}/{}, status={}, contentType={}, bodySnippet={}",
                            wireApi, channel, url, attempt, config.maxRetries(), response.statusCode(), contentType, snippet(responseBody));
                        pauseBeforeRetry(attempt, config.maxRetries(), url, "http_status_" + response.statusCode());
                        continue;
                    }

                    if (responseBody == null || responseBody.isBlank()) {
                        log.error("LLM request returned empty body. wireApi={} channel={} url={} attempt={}/{}, contentType={}",
                            wireApi, channel, url, attempt, config.maxRetries(), contentType);
                        pauseBeforeRetry(attempt, config.maxRetries(), url, "empty_body");
                        continue;
                    }

                    String trimmedBody = responseBody.trim();
                    if (trimmedBody.startsWith("<")) {
                        log.error("LLM request returned non-JSON HTML/text body. wireApi={} channel={} url={} attempt={}/{}, contentType={}, bodySnippet={}",
                            wireApi, channel, url, attempt, config.maxRetries(), contentType, snippet(trimmedBody));
                        invalidUrls.add(url);
                        continue;
                    }

                    JsonNode root = MAPPER.readTree(trimmedBody);
                    String content = parser.parse(root);
                    if (content == null || content.isBlank()) {
                        log.warn("LLM response parsed empty content. wireApi={} channel={} url={} attempt={}/{}, bodySnippet={}",
                            wireApi, channel, url, attempt, config.maxRetries(), snippet(trimmedBody));
                        pauseBeforeRetry(attempt, config.maxRetries(), url, "empty_content");
                        continue;
                    }

                    preferredUrl = url;
                    log.debug("LLM response content length={} wireApi={} channel={} url={} attempt={}/{}",
                        content.length(), wireApi, channel, url, attempt, config.maxRetries());
                    return content;
                } catch (Exception attemptError) {
                    log.warn("LLM request attempt failed. wireApi={} channel={} url={} attempt={}/{}, message={}",
                        wireApi, channel, url, attempt, config.maxRetries(), attemptError.toString());
                    pauseBeforeRetry(attempt, config.maxRetries(), url, attemptError.getClass().getSimpleName());
                }
            }
        }
        return "";
    }

    private List<String> candidateUrls(String wireApi) {
        String configured = normalizeBaseUrl(config.baseUrl());
        List<String> urls = new ArrayList<>();
        URI uri = URI.create(configured);
        String path = uri.getPath();
        boolean rootPath = path == null || path.isBlank() || "/".equals(path);
        if ("responses".equals(wireApi)) {
            if (rootPath) {
                urls.add(joinUrl(configured, "v1/responses"));
            }
            urls.add(joinUrl(configured, "responses"));
        } else {
            if (rootPath) {
                urls.add(joinUrl(configured, "v1/chat/completions"));
            }
            urls.add(joinUrl(configured, "chat/completions"));
        }
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

    private String extractChatCompletionText(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return "";
        }
        return readTextContent(choices.get(0).path("message").path("content"));
    }

    private String extractResponsesText(JsonNode root) {
        String directOutputText = readTextContent(root.get("output_text"));
        if (!directOutputText.isBlank()) {
            return directOutputText;
        }

        JsonNode output = root.get("output");
        if (output == null || !output.isArray() || output.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode item : output) {
            if (!"message".equalsIgnoreCase(item.path("type").asText("message"))) {
                continue;
            }
            appendWithSeparator(sb, readTextContent(item.path("content")));
        }
        return sb.toString().trim();
    }

    private String readTextContent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                if (item == null || item.isNull()) {
                    continue;
                }
                if (item.isTextual()) {
                    appendWithSeparator(sb, item.asText(""));
                    continue;
                }
                String type = item.path("type").asText("");
                if ("output_text".equalsIgnoreCase(type)
                    || "text".equalsIgnoreCase(type)
                    || item.has("text")) {
                    appendWithSeparator(sb, item.path("text").asText(""));
                }
            }
            return sb.toString().trim();
        }
        if (node.isObject() && node.has("text")) {
            return node.path("text").asText("");
        }
        return "";
    }

    private void appendWithSeparator(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n");
        }
        sb.append(text.trim());
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

    @FunctionalInterface
    private interface ResponseBodyParser {
        String parse(JsonNode root);
    }
}
