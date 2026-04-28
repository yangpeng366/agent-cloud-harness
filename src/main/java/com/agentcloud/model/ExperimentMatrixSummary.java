package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExperimentMatrixSummary(
    String experimentName,
    Integer totalRuns,
    List<String> supportedModes,
    List<ModeSummary> modeSummaries,
    List<CaseComparison> caseComparisons
) {
    public ExperimentMatrixSummary {
        if (experimentName == null) experimentName = "";
        if (totalRuns == null || totalRuns < 0) totalRuns = 0;
        if (supportedModes == null) supportedModes = List.of();
        if (modeSummaries == null) modeSummaries = List.of();
        if (caseComparisons == null) caseComparisons = List.of();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModeSummary(
        String modelMode,
        Integer runCount,
        Integer completedCount,
        Integer acceptedCount,
        Integer rejectedCount,
        Integer needsFollowupCount,
        Double totalCost,
        Double averageCost,
        Integer totalHandoffs,
        Integer totalResumes,
        Integer totalHumanGates,
        Double completionRate,
        Double acceptanceRate,
        Integer runsWithRouteData,
        Integer runsWithLearningHint,
        Integer learningHintAppliedCount,
        Double learningHintAppliedRate,
        Map<String, Integer> routeSourceCounts,
        Integer runsWithToolChainData,
        Double averageToolChainStepCount,
        Integer maxToolChainStepCount,
        Map<String, Integer> toolExecutionModeCounts,
        Map<String, Integer> toolChainTerminationReasonCounts
    ) {
        public ModeSummary {
            if (modelMode == null) modelMode = "";
            if (runCount == null || runCount < 0) runCount = 0;
            if (completedCount == null || completedCount < 0) completedCount = 0;
            if (acceptedCount == null || acceptedCount < 0) acceptedCount = 0;
            if (rejectedCount == null || rejectedCount < 0) rejectedCount = 0;
            if (needsFollowupCount == null || needsFollowupCount < 0) needsFollowupCount = 0;
            if (totalCost == null || totalCost < 0) totalCost = 0.0;
            if (averageCost == null || averageCost < 0) averageCost = 0.0;
            if (totalHandoffs == null || totalHandoffs < 0) totalHandoffs = 0;
            if (totalResumes == null || totalResumes < 0) totalResumes = 0;
            if (totalHumanGates == null || totalHumanGates < 0) totalHumanGates = 0;
            if (completionRate == null || completionRate < 0) completionRate = 0.0;
            if (acceptanceRate == null || acceptanceRate < 0) acceptanceRate = 0.0;
            if (runsWithRouteData == null || runsWithRouteData < 0) runsWithRouteData = 0;
            if (runsWithLearningHint == null || runsWithLearningHint < 0) runsWithLearningHint = 0;
            if (learningHintAppliedCount == null || learningHintAppliedCount < 0) learningHintAppliedCount = 0;
            if (learningHintAppliedRate == null || learningHintAppliedRate < 0) learningHintAppliedRate = 0.0;
            if (routeSourceCounts == null) routeSourceCounts = Map.of();
            if (runsWithToolChainData == null || runsWithToolChainData < 0) runsWithToolChainData = 0;
            if (averageToolChainStepCount == null || averageToolChainStepCount < 0) averageToolChainStepCount = 0.0;
            if (maxToolChainStepCount == null || maxToolChainStepCount < 0) maxToolChainStepCount = 0;
            if (toolExecutionModeCounts == null) toolExecutionModeCounts = Map.of();
            if (toolChainTerminationReasonCounts == null) toolChainTerminationReasonCounts = Map.of();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaseComparison(
        String taskCaseKey,
        String taskTitle,
        String taskLengthBucket,
        Map<String, ExperimentRunRecord> runsByMode
    ) {
        public CaseComparison {
            if (taskCaseKey == null) taskCaseKey = "";
            if (taskTitle == null) taskTitle = "";
            if (taskLengthBucket == null || taskLengthBucket.isBlank()) taskLengthBucket = "unspecified";
            if (runsByMode == null) runsByMode = Map.of();
        }
    }
}
