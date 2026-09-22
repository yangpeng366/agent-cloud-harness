package com.agentcloud.scorer.compactor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real Jev HTTP client (path A main path, 2026-09-21).
 *
 * Mirrors tamaratran/fast-jev-compaction src/client.ts + src/request.ts:
 *   - POST https://api.typesafe.ai/v1/systemone
 *   - Authorization: Bearer <TYPESAFE_API_KEY>
 *   - body: { model, state, questions }  (questions keyed by name -> { type, instructions })
 *   - response: { answers: { <name>: { type, noul, ... }, ... } }
 *
 * Pulled out as a separate module from JevAsker (per .tmp/Jev-OpenEyes-experience.md §4.B
 * recommendation: "库内部只做协议，caller 决定 fallback").
 *
 * Does NOT include retry/backoff -- fast-jev-compaction's retry loop is at the call site
 * (the async ask loop in compact.ts askBatch). For ACH integration, retry belongs in the
 * calling code (RuntimeJudgmentService), not in this transport class.
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
public final class JevHttpClient implements JevContextCompactor.JevAsker {

    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai/v1/systemone";
    public static final String DEFAULT_MODEL = "jev-latest";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(25);

    private static final Pattern NOUL_VALUE = Pattern.compile(
        "\\\"([a-zA-Z0-9_]+)\\\"\\s*:\\s*\\{[^{}]*?\\\"type\\\"\\s*:\\s*\\\"noul\\\"[^{}]*?\\\"noul\\\"\\s*:\\s*([0-9.\\-]+)");

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final HttpClient http;
    private final Duration timeout;

    public JevHttpClient() {
        this(System.getenv("TYPESAFE_API_KEY"));
    }

    public JevHttpClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL, DEFAULT_MODEL, DEFAULT_TIMEOUT);
    }

    public JevHttpClient(String apiKey, String baseUrl, String model, Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("TYPESAFE_API_KEY is not configured");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.timeout = timeout;
    }

    @Override
    public Map<String, Double> ask(String state, Map<String, String> questions) {
        String body = renderRequest(state, questions);
        HttpRequest req;
        try {
                req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(this.timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        } catch (Exception e) {
            throw new RuntimeException("JevHttpClient: build request failed: " + e.getMessage(), e);
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("JevHttpClient: send failed: " + e.getMessage(), e);
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("JevHttpClient: HTTP " + resp.statusCode() + ": "
                + resp.body().substring(0, Math.min(200, resp.body().length())));
        }
        return parseResponse(resp.body());
    }

    /** Render { model, state, questions } JSON body. */
    static String renderRequest(String state, Map<String, String> questions) {
        return renderRequest(DEFAULT_MODEL, state, questions);
    }

    static String renderRequest(
        String model, String state, Map<String, String> questions) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(escape(model)).append("\",");
        sb.append("\"state\":").append(state == null ? "null" : state).append(",");
        sb.append("\"questions\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : questions.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escape(e.getKey())).append("\":{\"type\":\"noul\",\"instructions\":\"")
              .append(escape(e.getValue())).append("\"}");
            first = false;
        }
        sb.append("}}");
        return sb.toString();
    }

    /** Parse answers[n].noul from response JSON. */
    static Map<String, Double> parseResponse(String responseBody) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (responseBody == null || responseBody.isEmpty()) return out;
        Matcher m = NOUL_VALUE.matcher(responseBody);
        while (m.find()) {
            String name = m.group(1);
            double noul;
            try {
                noul = Double.parseDouble(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            if (Double.isFinite(noul)) {
                out.put(name, Math.max(0.0, Math.min(1.0, noul)));
            }
        }
        return out;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Convenience for caller to check whether a real client is usable. */
    public static boolean isConfigured() {
        String key = System.getenv("TYPESAFE_API_KEY");
        return key != null && !key.isBlank();
    }
}
