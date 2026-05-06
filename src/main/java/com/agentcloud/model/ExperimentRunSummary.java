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
    double averageStrongModelCostRatio
) {
}
