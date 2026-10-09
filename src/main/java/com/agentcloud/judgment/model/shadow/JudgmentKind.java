package com.agentcloud.judgment.model.shadow;

/**
 * M0 Shadow 三类判断点。kind 决定 JudgmentDecision.action 枚举取值与降级策略（参见第 48、49 号笔记）。
 */
public enum JudgmentKind {
    ROUTER,
    TOOL_GATE,
    VERIFIER
}