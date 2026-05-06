package com.agentcloud.runtime.model;

import java.util.Map;

/**
 * 统一 runtime continuation 决策对象。
 *
 * 第一轮先承接 RuntimeJudgmentService 的最小规则判断结果，
 * 后续可继续接入 prompt judgment、fact set 和 trace。
 */
public record ContinuationDecision(
    ContinuationAction action,
    String reason,
    String targetWorker,
    String derivedFrom,
    Map<String, Object> metadata
) {
    public ContinuationDecision {
        action = action == null ? ContinuationAction.CONTINUE : action;
        derivedFrom = derivedFrom == null || derivedFrom.isBlank()
            ? "runtime_judgment"
            : derivedFrom;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public String actionValue() {
        return action.value();
    }

    public static ContinuationDecision of(ContinuationAction action, String reason, String targetWorker) {
        return new ContinuationDecision(action, reason, targetWorker, "runtime_judgment", Map.of());
    }
}
