package com.agentcloud.judgment.model.shadow;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Provider-agnostic 判断决策。
 * action 取值由 kind 约束：router=fast|balanced|strong|human_required,
 * tool_gate=allow|block|approval_required, verifier=accept|retry_once|handoff。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JudgmentDecision(
    String judgmentId,
    String action,
    Map<String, Double> scores,
    double confidence,
    String thresholdSetId,
    boolean degraded,
    String modelRef,
    long latencyMs,
    double costUnits,
    Map<String, String> reasonCodes
) {
    public JudgmentDecision {
        if (judgmentId == null || judgmentId.isBlank()) {
            throw new IllegalArgumentException("judgmentId required");
        }
        if (action == null || action.isBlank()) action = "balanced";
        if (scores == null) scores = Map.of();
        if (thresholdSetId == null || thresholdSetId.isBlank()) thresholdSetId = "shadow-default";
        if (modelRef == null || modelRef.isBlank()) modelRef = "unknown";
        if (reasonCodes == null) reasonCodes = Map.of();
    }

    public static JudgmentDecision fallback(String judgmentId, String action, String reason, String modelRef, long latencyMs) {
        return new JudgmentDecision(judgmentId, action, Map.of(), 0.0, "shadow-default", true,
                modelRef, latencyMs, 0.0, Map.of("fallback", reason));
    }
}