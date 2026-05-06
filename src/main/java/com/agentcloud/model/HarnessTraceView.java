package com.agentcloud.model;

import com.agentcloud.engine.router.WorkerRouter;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * AHE 风格的 Harness 复盘输入视图。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HarnessTraceView(
    String taskId,
    String taskStatus,
    String controlNode,
    String assignedWorker,
    String executionStatus,
    List<String> evidenceRefs,
    List<String> unfinishedItems,
    String recommendedAction,
    String recommendedNextStep,
    WorkerRouter.RouteResult routePreview,
    ExperimentRunRecord experimentRun,
    AgentRunRecord agentRun,
    Decision executionJudgment,
    Decision completionJudgment,
    List<ToolInvocationRecord> toolInvocations,
    List<AgentRunEventView> agentRunEvents,
    List<AgentRunArtifactView> agentArtifacts,
    Map<String, Object> harnessMetadata
) {
    public HarnessTraceView {
        if (taskId == null) taskId = "";
        if (taskStatus == null) taskStatus = "";
        if (controlNode == null) controlNode = "";
        if (assignedWorker == null) assignedWorker = "";
        if (executionStatus == null) executionStatus = "unknown";
        if (evidenceRefs == null) evidenceRefs = List.of();
        if (unfinishedItems == null) unfinishedItems = List.of();
        if (toolInvocations == null) toolInvocations = List.of();
        if (agentRunEvents == null) agentRunEvents = List.of();
        if (agentArtifacts == null) agentArtifacts = List.of();
        if (harnessMetadata == null) harnessMetadata = Map.of();
    }
}
