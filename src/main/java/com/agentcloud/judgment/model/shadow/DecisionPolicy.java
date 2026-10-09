package com.agentcloud.judgment.model.shadow;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 版本化阈值与降级策略。M0 仅落 schema；装载路径与覆盖规则后续在 harness-config 中固化（参见第 19、49 号笔记）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionPolicy(
    String version,
    String scope,
    Map<String, Double> routeThresholds,
    Map<String, Double> gateThresholds,
    Map<String, Double> verifierThresholds,
    String fallbackPolicy,
    String humanGatePolicy
) {
    public DecisionPolicy {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("policy version required");
        }
        if (scope == null || scope.isBlank()) scope = "global";
        if (routeThresholds == null) routeThresholds = Map.of();
        if (gateThresholds == null) gateThresholds = Map.of();
        if (verifierThresholds == null) verifierThresholds = Map.of();
        if (fallbackPolicy == null || fallbackPolicy.isBlank()) fallbackPolicy = "default_balanced";
        if (humanGatePolicy == null || humanGatePolicy.isBlank()) humanGatePolicy = "off";
    }

    public static DecisionPolicy shadowDefault() {
        return new DecisionPolicy(
                "shadow-default", "global",
                Map.of("fast_max", 0.30, "balanced_max", 0.70, "strong_min", 0.70),
                Map.of("block_min", 0.85, "approval_min", 0.50, "allow_min", 0.50),
                Map.of("accept_min", 0.70, "retry_min", 0.40, "handoff_min", 0.40),
                "default_balanced", "off"
        );
    }
}