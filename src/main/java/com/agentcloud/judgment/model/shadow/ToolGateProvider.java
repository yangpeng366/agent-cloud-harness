package com.agentcloud.judgment.model.shadow;

/**
 * M3 Tool Gate 阶段判断 Provider 接口。与 RouterProvider 同套 Provider-agnostic 契约；
 * 区别仅在 JudgmentKind=TOOL_GATE，action 取值：allow / block / approval_required。
 * 详见第 48、49、50 号笔记。
 */
public interface ToolGateProvider {
    JudgmentDecision decide(JudgmentRequest request);

    default String providerRef() {
        return "unknown";
    }
}