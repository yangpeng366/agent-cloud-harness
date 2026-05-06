package com.agentcloud.runtime.model;

import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.Decision;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * phase-1 最小 runtime facts 聚合对象。
 *
 * 先把当前分散在 runtime context、judgment trace、tool trace、packet/checkpoint
 * 里的关键事实聚到一处，供 judgment / checkpoint / harness trace 复用。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuntimeFactSet(
    String taskId,
    String sessionId,
    String taskStatus,
    String controlNode,
    String assignedWorker,
    String latestOutput,
    String recommendedAction,
    String recommendedNextStep,
    TaskRuntimeContext runtimeContext,
    ResumePacket latestPacket,
    Checkpoint latestCheckpoint,
    Decision executionJudgment,
    Decision completionJudgment,
    List<ToolInvocationRecord> toolInvocations,
    WorkerRouter.RouteResult routePreview,
    Map<String, Object> metadata
) {
    public RuntimeFactSet {
        if (taskId == null) taskId = "";
        if (sessionId == null) sessionId = "";
        if (taskStatus == null) taskStatus = "";
        if (controlNode == null) controlNode = "";
        if (assignedWorker == null) assignedWorker = "";
        if (latestOutput == null) latestOutput = "";
        if (toolInvocations == null) toolInvocations = List.of();
        if (metadata == null) metadata = Map.of();
    }

    public static RuntimeFactSet empty(Task task) {
        return new RuntimeFactSet(
            task == null ? "" : task.id(),
            task == null ? "" : task.sessionId(),
            task == null ? "" : task.status(),
            task == null ? "" : task.controlNode(),
            task == null ? "" : task.assignedWorker(),
            "",
            null,
            task == null ? null : task.nextStep(),
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            Map.of()
        );
    }
}
