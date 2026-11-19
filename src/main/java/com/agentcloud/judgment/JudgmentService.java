package com.agentcloud.judgment;

import com.agentcloud.judgment.model.ExecutionDecision;
import com.agentcloud.judgment.model.CompletionDecision;
import com.agentcloud.llm.LlmUsage;

/**
 * Runtime Judgment 接口。
 * 负责执行控制判断与完成度判断。
 */
public interface JudgmentService {
    ExecutionDecision judgeExecution(JudgmentContext context);
    CompletionDecision judgeCompletion(JudgmentContext context);

    /**
     * 判定器自身最近一次调用的 token 计量（W3-A：判定开销进同一账本）。
     *
     * <p>stage 取 {@code execution} / {@code completion}。默认实现返回 unavailable，
     * 旧实现与测试替身零改动即兼容；调用方必须区分 provider / estimated / unavailable，
     * 不得把 unavailable 当 0 消耗记账。
     */
    default LlmUsage lastUsage(String taskId, String stage) {
        return LlmUsage.unavailable();
    }
}
