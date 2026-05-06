package com.agentcloud.judgment;

import com.agentcloud.model.Task;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Judgment 过程所需的运行时上下文。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JudgmentContext(
    Task task,
    TaskRuntimeContext runtimeContext,
    String workerOutput,
    String completionCriteria,
    Map<String, Object> latestWorkerMetadata
) {
    public JudgmentContext {
        if (runtimeContext == null) {
            runtimeContext = new TaskRuntimeContext(
                task,
                null,
                null,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null
            );
        }
        if (workerOutput == null) workerOutput = "";
        if (completionCriteria == null) completionCriteria = "";
        if (latestWorkerMetadata == null) latestWorkerMetadata = Map.of();
    }
}
