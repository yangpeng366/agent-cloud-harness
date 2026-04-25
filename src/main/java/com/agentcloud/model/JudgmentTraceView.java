package com.agentcloud.model;

import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 当前任务最近一次 judgment 诊断视图。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JudgmentTraceView(
    String taskId,
    String taskStatus,
    String controlNode,
    String assignedWorker,
    String latestOutput,
    String recommendedAction,
    String recommendedNextStep,
    Decision executionJudgment,
    Decision completionJudgment,
    TaskRuntimeContext runtimeContext
) {}
