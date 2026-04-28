package com.agentcloud.model;

import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 面向 live flow 验证的一站式聚合视图。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskLiveFlowView(
    Task task,
    ResumePacket latestPacket,
    WorkerRouter.RouteResult routePreview,
    TaskRuntimeContext runtimeContext,
    JudgmentTraceView judgmentTrace,
    List<Checkpoint> checkpoints,
    List<LearningMemory> learningMemories,
    List<ToolInvocationRecord> toolInvocations,
    List<SessionMessage> relatedMessages,
    ExperimentRunRecord experimentRun
) {}
