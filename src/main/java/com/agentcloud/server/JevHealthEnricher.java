package com.agentcloud.server;

import com.agentcloud.judgment.JevPrefilteredJudgmentService;
import com.agentcloud.scorer.JevContextScorer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JevHealthEnricher -- exposes jev_context_scoring status for the /api/v1/health payload.
 *
 * Mirrors OpenEyesHealthEnricher shape so console can render a Jev card the same
 * way as the OpenEyes MCP card. The enabled flag defaults to false (off) until
 * the FEAT-03 feature flag is wired through Main.java; callers should treat
 * absent or false `enabled` as expected at-rest behavior.
 *
 * Author: Codex (yangpeng) -- 2026-09-22.
 */
public final class JevHealthEnricher {

    private JevHealthEnricher() {
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", false);
        payload.put("scorer_class", JevContextScorer.class.getSimpleName());
        payload.put("prefilter_class", JevPrefilteredJudgmentService.class.getSimpleName());
        return payload;
    }
}
