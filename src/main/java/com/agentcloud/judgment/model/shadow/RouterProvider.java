package com.agentcloud.judgment.model.shadow;

/**
 * Router 阶段判断 Provider 接口。M1 Shadow 阶段由 ShadowRouterService 调用，
 * 写入 DecisionEvent 但不修改 WorkerRouter 既有路径；M2+ 再讨论把 decision 写回 envelope。
 * 参考第 48 号（Router 三类问题与降级）+ 第 49 号（Provider-agnostic 三件套）。
 */
public interface RouterProvider {
    JudgmentDecision decide(JudgmentRequest request);

    /**
     * Provider 不可用时由 ShadowRouterService 决定降级策略，本接口默认不感知。
     */
    default String providerRef() {
        return "unknown";
    }
}