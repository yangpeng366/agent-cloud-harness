package com.agentcloud.scorer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JevContextScorer -- production HTTP-based Jev scorer.
 *
 * Per docs/JEV_CONTEXT_SCORING_PLAN.md §1.2:
 *   - endpoint: `${baseUrl}/v1/systemone` (NOT OpenAI-compatible; not /v1/chat/completions)
 *   - request body: `{model: "jev-latest", state: {...}, questions: {name: {type: "noul", instructions: "..."}}}`
 *   - response: `{model, answers: {q: {type: "noul", noul: <0..1>}}, usage: {input_tokens, output_tokens}}`
 *   - Authorization: Bearer ${env:apiKeyEnvVar}
 *
 * **enabled=false 短路**: when config.enabled=false, decide/decideAll short-circuit to
 * KEEP_VERBATIM without network (per plan §1.1 + HARNESS_CHANGE_CONTRACT.md Contract-Additive
 * fallback contract).
 *
 * **This is the Step 1 skeleton**: HTTP plumbing is in place (HttpClient + Jackson + bearer
 * header + timeout), but full request/response parsing + retry/circuit-breaker + fallback
 * are wired in Step 2/3 per docs/JEV_CONTEXT_SCORING_PLAN.md §5. For Step 1 the public
 * surface is: enabled-aware short-circuit + decideAll preserves order + per-item fallback
 * to KEEP_VERBATIM on transient errors. Integration tests cover the Fake (see
 * JevContextScorerFakeTest); real HTTP path is covered by manual smoke (see .tmp/).
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
public final class JevContextScorer implements JevScorer {

    private final JevContextScorerConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public JevContextScorer(JevContextScorerConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.requestTimeoutMs()))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    /** Test-only constructor (in-memory config). */
    JevContextScorer(JevContextScorerConfig config, HttpClient httpClient, ObjectMapper objectMapper) {
        this.config = config;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isEnabled() { return config.isEnabled(); }

    @Override
    public JevDecision decide(JevRequestItem item) {
        if (!config.isEnabled()) {
            return new JevDecision(JevAction.KEEP_VERBATIM, 1.0);
        }
        if (item == null || item.text() == null) {
            return new JevDecision(JevAction.KEEP_VERBATIM, 1.0);
        }
        try {
            JevDecision d = decideOneHttp(item);
            return mapAction(d, item);
        } catch (Exception e) {
            // Fallback: per plan §10 风险表 + §1.2 fallback 路径, Jev 失败时保留逐字
            return new JevDecision(JevAction.KEEP_VERBATIM, 1.0);
        }
    }

    @Override
    public List<JevDecision> decideAll(List<JevRequestItem> items) {
        if (items == null || items.isEmpty()) return List.of();
        if (!config.isEnabled()) {
            return items.stream()
                .map(i -> new JevDecision(JevAction.KEEP_VERBATIM, 1.0))
                .toList();
        }
        List<JevDecision> out = new ArrayList<>(items.size());
        for (JevRequestItem item : items) {
            out.add(decide(item));
        }
        return out;
    }

    /** Map JevDecision probability -> JevAction via keepThreshold. */
    JevAction decideAction(double probability) {
        if (probability >= config.keepThreshold()) return JevAction.KEEP_VERBATIM;
        return JevAction.TRUNCATE_HEAD;
    }

    private JevDecision mapAction(JevDecision d, JevRequestItem item) {
        return new JevDecision(decideAction(d.probability()), d.probability());
    }

    /**
     * HTTP call to ${baseUrl}/v1/systemone.
     *
     * Step 1 scope: send batched questions request, parse top-level answers map.
     * Single-item case for now; Step 2 adds batch.
     */
    private JevDecision decideOneHttp(JevRequestItem item) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "jev-latest");
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", item.id());
        state.put("source", item.source());
        body.put("state", state);
        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put(item.id(), Map.of(
            "type", "noul",
            "instructions", "Is this content relevant and concrete enough to keep verbatim in context? content: " + item.text()
        ));
        body.put("questions", questions);

        String apiKey = System.getenv(config.apiKeyEnvVar());
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "env var " + config.apiKeyEnvVar() + " is not set (per plan §11 立项门槛 2)");
        }

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + "/v1/systemone"))
            .timeout(Duration.ofMillis(config.requestTimeoutMs()))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                "Jev HTTP " + resp.statusCode() + ": " + resp.body().substring(0, Math.min(200, resp.body().length())));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(resp.body(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> answers = (Map<String, Object>) parsed.get("answers");
        if (answers == null || answers.isEmpty()) {
            throw new IllegalStateException("Jev response missing answers: " + resp.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> firstAnswer = (Map<String, Object>) answers.values().iterator().next();
        double prob = ((Number) firstAnswer.get("noul")).doubleValue();
        return new JevDecision(JevAction.KEEP_VERBATIM, prob);  // action mapped by mapAction
    }
}