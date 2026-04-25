package com.agentcloud.judgment;

import com.agentcloud.judgment.model.ExecutionDecision;
import com.agentcloud.judgment.model.CompletionDecision;

/**
 * Runtime Judgment 接口。
 * 负责执行控制判断与完成度判断。
 */
public interface JudgmentService {
    ExecutionDecision judgeExecution(JudgmentContext context);
    CompletionDecision judgeCompletion(JudgmentContext context);
}
