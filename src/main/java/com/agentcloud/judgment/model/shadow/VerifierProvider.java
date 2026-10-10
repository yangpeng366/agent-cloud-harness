package com.agentcloud.judgment.model.shadow;

/**
 * M3 Verifier 阶段判断 Provider 接口（按第 50 号笔记的 M 编号，M3 = Verifier 旁路）。
 * <p>
 * 与 RouterProvider / ToolGateProvider 同套 Provider-agnostic 契约；
 * 区别仅在 JudgmentKind=VERIFIER，action 取值 accept / retry_once / handoff。
 * 详见第 48、49、50 号笔记。
 */
public interface VerifierProvider {
    JudgmentDecision decide(JudgmentRequest request);

    default String providerRef() {
        return "unknown";
    }
}