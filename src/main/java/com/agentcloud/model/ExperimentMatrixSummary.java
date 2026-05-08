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
        Integer orchestrationClosedLoopObservedCount,
        Integer orchestratedRunCount,
        Integer runsWithRouteData,
        Integer runsWithExecutionJudgment,
        Integer runsWithCompletionJudgment,
        Integer runsWithClosedLoopEvidenceChain,
        Integer runsWithTaskSurfaceRefs,
        Integer runsWithJudgmentSurfaceRefs,
        Integer runsWithToolTraceSurfaceRefs,
        Integer runsWithLearningHint,
        Integer learningHintAppliedCount,
        Double learningHintAppliedRate,
        Integer runsWithPromptModeData,
        Map<String, Integer> promptModeCounts,
        Integer runsWithMountedContextRendered,
        Integer runsWithMountedContextInjected,
        Double mountedContextRenderedRate,
        Double mountedContextInjectedRate,
        Double averageMountedContextPanelCount,
        Integer runsWithMountedContextBudgetData,
        Integer runsWithMountedContextBudgetTruncated,
        Double mountedContextBudgetTruncatedRate,
        Double averageMountedContextRenderedObjectCount,
        Double averageMountedContextHiddenObjectCount,
        Double averageMountedContextRenderedSelectionTraceCount,
        Double averageMountedContextHiddenSelectionTraceCount,
        Integer runsWithExecutionJudgmentPromptModeData,
        Map<String, Integer> executionJudgmentPromptModeCounts,
        Integer runsWithExecutionJudgmentMountedContextRendered,
        Integer runsWithExecutionJudgmentMountedContextInjected,
        Double executionJudgmentMountedContextRenderedRate,
        Double executionJudgmentMountedContextInjectedRate,
        Integer runsWithExecutionJudgmentMountedContextBudgetData,
        Integer runsWithExecutionJudgmentMountedContextBudgetTruncated,
        Double executionJudgmentMountedContextBudgetTruncatedRate,
        Double averageExecutionJudgmentMountedContextRenderedObjectCount,
        Double averageExecutionJudgmentMountedContextHiddenObjectCount,
        Double averageExecutionJudgmentMountedContextRenderedSelectionTraceCount,
        Double averageExecutionJudgmentMountedContextHiddenSelectionTraceCount,
        Integer runsWithCompletionJudgmentPromptModeData,
        Map<String, Integer> completionJudgmentPromptModeCounts,
        Integer runsWithCompletionJudgmentMountedContextRendered,
        Integer runsWithCompletionJudgmentMountedContextInjected,
        Double completionJudgmentMountedContextRenderedRate,
        Double completionJudgmentMountedContextInjectedRate,
        Integer runsWithCompletionJudgmentMountedContextBudgetData,
        Integer runsWithCompletionJudgmentMountedContextBudgetTruncated,
        Double completionJudgmentMountedContextBudgetTruncatedRate,
        Double averageCompletionJudgmentMountedContextRenderedObjectCount,
        Double averageCompletionJudgmentMountedContextHiddenObjectCount,
        Double averageCompletionJudgmentMountedContextRenderedSelectionTraceCount,
        Double averageCompletionJudgmentMountedContextHiddenSelectionTraceCount,
        Map<String, Integer> routeSourceCounts,
        Map<String, Integer> executionActionCounts,
        Map<String, Integer> completionJudgmentStatusCounts,
        Map<String, Integer> completionAlignmentLevelCounts,
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
            if (orchestrationClosedLoopObservedCount == null || orchestrationClosedLoopObservedCount < 0) {
                orchestrationClosedLoopObservedCount = 0;
            }
            if (orchestratedRunCount == null || orchestratedRunCount < 0) orchestratedRunCount = 0;
            if (runsWithRouteData == null || runsWithRouteData < 0) runsWithRouteData = 0;
            if (runsWithExecutionJudgment == null || runsWithExecutionJudgment < 0) runsWithExecutionJudgment = 0;
            if (runsWithCompletionJudgment == null || runsWithCompletionJudgment < 0) runsWithCompletionJudgment = 0;
            if (runsWithClosedLoopEvidenceChain == null || runsWithClosedLoopEvidenceChain < 0) {
                runsWithClosedLoopEvidenceChain = 0;
            }
            if (runsWithTaskSurfaceRefs == null || runsWithTaskSurfaceRefs < 0) runsWithTaskSurfaceRefs = 0;
            if (runsWithJudgmentSurfaceRefs == null || runsWithJudgmentSurfaceRefs < 0) {
                runsWithJudgmentSurfaceRefs = 0;
            }
            if (runsWithToolTraceSurfaceRefs == null || runsWithToolTraceSurfaceRefs < 0) {
                runsWithToolTraceSurfaceRefs = 0;
            }
            if (runsWithLearningHint == null || runsWithLearningHint < 0) runsWithLearningHint = 0;
            if (learningHintAppliedCount == null || learningHintAppliedCount < 0) learningHintAppliedCount = 0;
            if (learningHintAppliedRate == null || learningHintAppliedRate < 0) learningHintAppliedRate = 0.0;
            if (runsWithPromptModeData == null || runsWithPromptModeData < 0) runsWithPromptModeData = 0;
            if (promptModeCounts == null) promptModeCounts = Map.of();
            if (runsWithMountedContextRendered == null || runsWithMountedContextRendered < 0) {
                runsWithMountedContextRendered = 0;
            }
            if (runsWithMountedContextInjected == null || runsWithMountedContextInjected < 0) {
                runsWithMountedContextInjected = 0;
            }
            if (mountedContextRenderedRate == null || mountedContextRenderedRate < 0) {
                mountedContextRenderedRate = 0.0;
            }
            if (mountedContextInjectedRate == null || mountedContextInjectedRate < 0) {
                mountedContextInjectedRate = 0.0;
            }
            if (averageMountedContextPanelCount == null || averageMountedContextPanelCount < 0) {
                averageMountedContextPanelCount = 0.0;
            }
            if (runsWithMountedContextBudgetData == null || runsWithMountedContextBudgetData < 0) {
                runsWithMountedContextBudgetData = 0;
            }
            if (runsWithMountedContextBudgetTruncated == null || runsWithMountedContextBudgetTruncated < 0) {
                runsWithMountedContextBudgetTruncated = 0;
            }
            if (mountedContextBudgetTruncatedRate == null || mountedContextBudgetTruncatedRate < 0) {
                mountedContextBudgetTruncatedRate = 0.0;
            }
            if (averageMountedContextRenderedObjectCount == null || averageMountedContextRenderedObjectCount < 0) {
                averageMountedContextRenderedObjectCount = 0.0;
            }
            if (averageMountedContextHiddenObjectCount == null || averageMountedContextHiddenObjectCount < 0) {
                averageMountedContextHiddenObjectCount = 0.0;
            }
            if (averageMountedContextRenderedSelectionTraceCount == null
                || averageMountedContextRenderedSelectionTraceCount < 0) {
                averageMountedContextRenderedSelectionTraceCount = 0.0;
            }
            if (averageMountedContextHiddenSelectionTraceCount == null
                || averageMountedContextHiddenSelectionTraceCount < 0) {
                averageMountedContextHiddenSelectionTraceCount = 0.0;
            }
            if (runsWithExecutionJudgmentPromptModeData == null || runsWithExecutionJudgmentPromptModeData < 0) {
                runsWithExecutionJudgmentPromptModeData = 0;
            }
            if (executionJudgmentPromptModeCounts == null) executionJudgmentPromptModeCounts = Map.of();
            if (runsWithExecutionJudgmentMountedContextRendered == null
                || runsWithExecutionJudgmentMountedContextRendered < 0) {
                runsWithExecutionJudgmentMountedContextRendered = 0;
            }
            if (runsWithExecutionJudgmentMountedContextInjected == null
                || runsWithExecutionJudgmentMountedContextInjected < 0) {
                runsWithExecutionJudgmentMountedContextInjected = 0;
            }
            if (executionJudgmentMountedContextRenderedRate == null
                || executionJudgmentMountedContextRenderedRate < 0) {
                executionJudgmentMountedContextRenderedRate = 0.0;
            }
            if (executionJudgmentMountedContextInjectedRate == null
                || executionJudgmentMountedContextInjectedRate < 0) {
                executionJudgmentMountedContextInjectedRate = 0.0;
            }
            if (runsWithExecutionJudgmentMountedContextBudgetData == null
                || runsWithExecutionJudgmentMountedContextBudgetData < 0) {
                runsWithExecutionJudgmentMountedContextBudgetData = 0;
            }
            if (runsWithExecutionJudgmentMountedContextBudgetTruncated == null
                || runsWithExecutionJudgmentMountedContextBudgetTruncated < 0) {
                runsWithExecutionJudgmentMountedContextBudgetTruncated = 0;
            }
            if (executionJudgmentMountedContextBudgetTruncatedRate == null
                || executionJudgmentMountedContextBudgetTruncatedRate < 0) {
                executionJudgmentMountedContextBudgetTruncatedRate = 0.0;
            }
            if (averageExecutionJudgmentMountedContextRenderedObjectCount == null
                || averageExecutionJudgmentMountedContextRenderedObjectCount < 0) {
                averageExecutionJudgmentMountedContextRenderedObjectCount = 0.0;
            }
            if (averageExecutionJudgmentMountedContextHiddenObjectCount == null
                || averageExecutionJudgmentMountedContextHiddenObjectCount < 0) {
                averageExecutionJudgmentMountedContextHiddenObjectCount = 0.0;
            }
            if (averageExecutionJudgmentMountedContextRenderedSelectionTraceCount == null
                || averageExecutionJudgmentMountedContextRenderedSelectionTraceCount < 0) {
                averageExecutionJudgmentMountedContextRenderedSelectionTraceCount = 0.0;
            }
            if (averageExecutionJudgmentMountedContextHiddenSelectionTraceCount == null
                || averageExecutionJudgmentMountedContextHiddenSelectionTraceCount < 0) {
                averageExecutionJudgmentMountedContextHiddenSelectionTraceCount = 0.0;
            }
            if (runsWithCompletionJudgmentPromptModeData == null
                || runsWithCompletionJudgmentPromptModeData < 0) {
                runsWithCompletionJudgmentPromptModeData = 0;
            }
            if (completionJudgmentPromptModeCounts == null) completionJudgmentPromptModeCounts = Map.of();
            if (runsWithCompletionJudgmentMountedContextRendered == null
                || runsWithCompletionJudgmentMountedContextRendered < 0) {
                runsWithCompletionJudgmentMountedContextRendered = 0;
            }
            if (runsWithCompletionJudgmentMountedContextInjected == null
                || runsWithCompletionJudgmentMountedContextInjected < 0) {
                runsWithCompletionJudgmentMountedContextInjected = 0;
            }
            if (completionJudgmentMountedContextRenderedRate == null
                || completionJudgmentMountedContextRenderedRate < 0) {
                completionJudgmentMountedContextRenderedRate = 0.0;
            }
            if (completionJudgmentMountedContextInjectedRate == null
                || completionJudgmentMountedContextInjectedRate < 0) {
                completionJudgmentMountedContextInjectedRate = 0.0;
            }
            if (runsWithCompletionJudgmentMountedContextBudgetData == null
                || runsWithCompletionJudgmentMountedContextBudgetData < 0) {
                runsWithCompletionJudgmentMountedContextBudgetData = 0;
            }
            if (runsWithCompletionJudgmentMountedContextBudgetTruncated == null
                || runsWithCompletionJudgmentMountedContextBudgetTruncated < 0) {
                runsWithCompletionJudgmentMountedContextBudgetTruncated = 0;
            }
            if (completionJudgmentMountedContextBudgetTruncatedRate == null
                || completionJudgmentMountedContextBudgetTruncatedRate < 0) {
                completionJudgmentMountedContextBudgetTruncatedRate = 0.0;
            }
            if (averageCompletionJudgmentMountedContextRenderedObjectCount == null
                || averageCompletionJudgmentMountedContextRenderedObjectCount < 0) {
                averageCompletionJudgmentMountedContextRenderedObjectCount = 0.0;
            }
            if (averageCompletionJudgmentMountedContextHiddenObjectCount == null
                || averageCompletionJudgmentMountedContextHiddenObjectCount < 0) {
                averageCompletionJudgmentMountedContextHiddenObjectCount = 0.0;
            }
            if (averageCompletionJudgmentMountedContextRenderedSelectionTraceCount == null
                || averageCompletionJudgmentMountedContextRenderedSelectionTraceCount < 0) {
                averageCompletionJudgmentMountedContextRenderedSelectionTraceCount = 0.0;
            }
            if (averageCompletionJudgmentMountedContextHiddenSelectionTraceCount == null
                || averageCompletionJudgmentMountedContextHiddenSelectionTraceCount < 0) {
                averageCompletionJudgmentMountedContextHiddenSelectionTraceCount = 0.0;
            }
            if (routeSourceCounts == null) routeSourceCounts = Map.of();
            if (executionActionCounts == null) executionActionCounts = Map.of();
            if (completionJudgmentStatusCounts == null) completionJudgmentStatusCounts = Map.of();
            if (completionAlignmentLevelCounts == null) completionAlignmentLevelCounts = Map.of();
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
