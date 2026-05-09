package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExperimentRunSummary(
    int runCount,
    Map<String, Integer> completionStatusCounts,
    Map<String, Integer> acceptanceResultCounts,
    Map<String, Integer> modelModeCounts,
    Map<String, Integer> routeSourceCounts,
    Map<String, Integer> executionActionCounts,
    Map<String, Integer> completionJudgmentStatusCounts,
    Map<String, Integer> completionAlignmentLevelCounts,
    int failureReasonCount,
    int recoverySuccessCount,
    int orchestrationClosedLoopObservedCount,
    int orchestratedRunCount,
    int runsWithRouteEvidenceCount,
    int runsWithExecutionJudgmentCount,
    int runsWithCompletionJudgmentCount,
    int runsWithClosedLoopEvidenceChainCount,
    int runsWithTracePointersCount,
    int runsWithJudgmentTracePointersCount,
    int runsWithTaskSurfaceRefsCount,
    int runsWithJudgmentSurfaceRefsCount,
    int runsWithToolTraceSurfaceRefsCount,
    int handoffCount,
    int resumeCount,
    int humanGateCount,
    double totalCost,
    double averageCost,
    double averageStrongModelCostRatio,
    Map<String, MountedContextPromptModeSummary> promptModeSummaries,
    Map<String, MountedContextPromptModeSummary> executionJudgmentPromptModeSummaries,
    Map<String, MountedContextPromptModeSummary> completionJudgmentPromptModeSummaries
) {
    public ExperimentRunSummary {
        if (completionStatusCounts == null) completionStatusCounts = Map.of();
        if (acceptanceResultCounts == null) acceptanceResultCounts = Map.of();
        if (modelModeCounts == null) modelModeCounts = Map.of();
        if (routeSourceCounts == null) routeSourceCounts = Map.of();
        if (executionActionCounts == null) executionActionCounts = Map.of();
        if (completionJudgmentStatusCounts == null) completionJudgmentStatusCounts = Map.of();
        if (completionAlignmentLevelCounts == null) completionAlignmentLevelCounts = Map.of();
        if (promptModeSummaries == null) promptModeSummaries = Map.of();
        if (executionJudgmentPromptModeSummaries == null) executionJudgmentPromptModeSummaries = Map.of();
        if (completionJudgmentPromptModeSummaries == null) completionJudgmentPromptModeSummaries = Map.of();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MountedContextPromptModeSummary(
        int runCount,
        int mountedContextRenderedCount,
        int mountedRenderUsedCount,
        int mountedContextInjectedCount,
        int runsWithMountedContextBudgetData,
        int mountedContextBudgetTruncatedCount,
        double mountedContextRenderedRate,
        double mountedRenderUsedRate,
        double mountedContextInjectedRate,
        double mountedContextBudgetTruncatedRate,
        double averageMountedContextPanelCount,
        double averageMountedContextRenderedObjectCount
    ) {
    }
}
