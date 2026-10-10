package com.agentcloud.judgment.model.shadow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M3 Verifier 启发式 Provider（heuristic-shadow-v1）。
 * <p>
 * 按 JudgmentRequest.normalizedInput 三轴评分：
 *   quality    答案是否覆盖请求（0~1）
 *   grounded   事实主张是否由本轮已观察的证据支持（0~1）
 *   completion 是否存在一次重试即可弥补的明显缺口（0~1，0=已完成、1=完全缺失）
 * <p>
 * 决策：
 *   needs_human_or_handoff=true                              -> handoff
 *   高风险任务 (high_stakes=true) 强制 handoff_pending       -> handoff
 *   quality<0.40 或 grounded<0.40                           -> handoff
 *   completion>=0.40 且 quality>=0.70 且 grounded>=0.70     -> accept
 *   其余                                                     -> retry_once
 * <p>
 * 阈值与 DecisionPolicy.shadowDefault().verifierThresholds() 对齐：
 *   accept_min=0.70 / retry_min=0.40 / handoff_min=0.40
 * <p>
 * 异常一律 JudgmentDecision.fallback("verification_unavailable")，与第 48 条降级表对齐。
 */
public class HeuristicVerifierProvider implements VerifierProvider {

    public static final String PROVIDER_REF = "heuristic-shadow-v1";

    private static final double ACCEPT_MIN = 0.70;
    private static final double RETRY_MIN = 0.40;

    @Override
    public JudgmentDecision decide(JudgmentRequest request) {
        long started = System.nanoTime();
        try {
            Map<String, Object> input = request.normalizedInput() == null ? Map.of() : request.normalizedInput();
            double quality = readScore(input, "quality");
            double grounded = readScore(input, "grounded");
            double completion = readScore(input, "completion");
            boolean needsHuman = Boolean.TRUE.equals(input.get("needs_human"));
            boolean highStakes = Boolean.TRUE.equals(input.get("high_stakes"));
            boolean hasErrors = Boolean.TRUE.equals(input.get("has_errors"));

            Map<String, Double> scores = new LinkedHashMap<>();
            scores.put("quality", quality);
            scores.put("grounded", grounded);
            scores.put("completion", completion);

            Map<String, String> reasons = new LinkedHashMap<>();
            String action;
            if (needsHuman || highStakes) {
                action = "handoff";
                reasons.put("policy", "needs_human_or_high_stakes");
            } else if (quality < RETRY_MIN || grounded < RETRY_MIN) {
                action = "handoff";
                reasons.put("policy", "quality_or_grounded_below_handoff_min");
            } else if (hasErrors || completion >= 0.40) {
                action = "retry_once";
                reasons.put("policy", "completion_gap_or_errors");
            } else if (quality >= ACCEPT_MIN && grounded >= ACCEPT_MIN) {
                action = "accept";
                reasons.put("policy", "quality_and_grounded_above_accept_min");
            } else {
                action = "retry_once";
                reasons.put("policy", "borderline_quality_or_grounded");
            }

            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            return new JudgmentDecision(
                    request.judgmentId(),
                    action,
                    scores,
                    Math.max(quality, grounded),
                    request.policyVersion(),
                    false,
                    providerRef(),
                    latencyMs,
                    0.0,
                    reasons
            );
        } catch (RuntimeException e) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            return JudgmentDecision.fallback(request.judgmentId(), "handoff",
                    "verification_unavailable:" + e.getClass().getSimpleName(), providerRef(), latencyMs);
        }
    }

    @Override
    public String providerRef() {
        return PROVIDER_REF;
    }

    private static double readScore(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (v instanceof Number n) return clamp01(n.doubleValue());
        if (v instanceof String s) {
            try { return clamp01(Double.parseDouble(s)); } catch (NumberFormatException ignore) { return 0.0; }
        }
        return 0.0;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}