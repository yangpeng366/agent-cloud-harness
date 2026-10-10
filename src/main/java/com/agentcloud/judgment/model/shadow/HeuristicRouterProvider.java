package com.agentcloud.judgment.model.shadow;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M1 Shadow 阶段的确定性启发式 Router Provider。
 * <p>
 * 不调用任何外部模型；按 JudgmentRequest.normalizedInput 中的稳定字段
 * （difficulty / side_effect_risk / needs_browser / budget_exhausted / human_requested）
 * 映射到 logical tier（fast / balanced / strong / human_required）。
 * 阈值与 DecisionPolicy.shadowDefault().routeThresholds() 保持一致。
 * <p>
 * 失败 / 异常一律走 JudgmentDecision.fallback("default_balanced")，与第 48 条降级表对齐。
 */
public class HeuristicRouterProvider implements RouterProvider {

    public static final String PROVIDER_REF = "heuristic-shadow-v1";

    @Override
    public JudgmentDecision decide(JudgmentRequest request) {
        long started = System.nanoTime();
        try {
            Map<String, Object> input = request.normalizedInput() == null ? Map.of() : request.normalizedInput();
            double difficulty = readDouble(input, "difficulty", 0.5);
            double sideEffect = readDouble(input, "side_effect_risk", 0.0);
            boolean needsBrowser = Boolean.TRUE.equals(input.get("needs_browser"));
            boolean budgetExhausted = Boolean.TRUE.equals(input.get("budget_exhausted"));
            boolean humanRequested = Boolean.TRUE.equals(input.get("human_requested"));
            boolean highStakes = Boolean.TRUE.equals(input.get("high_stakes"));

            Map<String, Double> scores = new LinkedHashMap<>();
            scores.put("difficulty", difficulty);
            scores.put("side_effect_risk", sideEffect);

            String action;
            Map<String, String> reasons = new LinkedHashMap<>();
            if (humanRequested || highStakes) {
                action = "human_required";
                reasons.put("policy", "human_or_high_stakes_flag_set");
            } else if (budgetExhausted) {
                action = "human_required";
                reasons.put("policy", "budget_exhausted_short_circuit");
            } else if (sideEffect >= 0.85) {
                action = "human_required";
                reasons.put("policy", "side_effect_risk_above_human_threshold");
            } else if (difficulty >= 0.70 || needsBrowser) {
                action = "strong";
                reasons.put("policy", "difficulty_or_needs_browser_above_strong_min");
            } else if (difficulty >= 0.30) {
                action = "balanced";
                reasons.put("policy", "difficulty_in_balanced_band");
            } else {
                action = "fast";
                reasons.put("policy", "difficulty_below_fast_max");
            }

            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            return new JudgmentDecision(
                    request.judgmentId(),
                    action,
                    scores,
                    clamp01(difficulty),
                    request.policyVersion(),
                    false,
                    providerRef(),
                    latencyMs,
                    0.0,
                    reasons
            );
        } catch (RuntimeException e) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            return JudgmentDecision.fallback(request.judgmentId(), "balanced",
                    "heuristic_error:" + e.getClass().getSimpleName(), providerRef(), latencyMs);
        }
    }

    @Override
    public String providerRef() {
        return PROVIDER_REF;
    }

    private static double readDouble(Map<String, Object> input, String key, double fallback) {
        Object v = input.get(key);
        if (v instanceof Number n) {
            return clamp01(n.doubleValue());
        }
        if (v instanceof String s) {
            try {
                return clamp01(Double.parseDouble(s));
            } catch (NumberFormatException ignore) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    // 保留 HashMap 引用以兼容未来的 provider 工厂；不在本类直接使用。
    @SuppressWarnings("unused")
    private static Map<String, Object> emptyInput() {
        return new HashMap<>();
    }
}