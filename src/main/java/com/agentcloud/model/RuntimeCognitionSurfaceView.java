package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单任务诊断面里的 route / execution / judgment 对照视图。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuntimeCognitionSurfaceView(
    RouteSurface route,
    ExecutionSurface execution,
    JudgmentSurface executionJudgment,
    JudgmentSurface completionJudgment,
    AlignmentSurface alignment
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RouteSurface(
        String selectedWorker,
        String routeSource,
        String selectedModelTier,
        String selectedExecutionRole,
        String selectionScope,
        List<String> candidateWorkers,
        String preferredWorkerHint,
        Boolean learningHintApplied,
        String fallbackReason
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExecutionSurface(
        String workerId,
        String executionId,
        String executionStatus,
        Long durationMs,
        Integer toolInvocationCount,
        List<String> toolInvocationIds,
        String proofSummary,
        String traceSummary,
        String promptMode,
        Boolean mountedContextRendered,
        Boolean mountedRenderUsed,
        Boolean mountedContextInjected,
        Integer mountedContextPanelCount,
        Integer mountedContextNonEmptyPanelCount,
        Integer mountedContextSelectionTraceCount,
        Integer mountedContextRenderedPanelCount,
        Integer mountedContextHiddenPanelCount,
        Integer mountedContextRenderedObjectCount,
        Integer mountedContextHiddenObjectCount,
        Integer mountedContextRenderedSelectionTraceCount,
        Integer mountedContextHiddenSelectionTraceCount,
        Boolean mountedContextBudgetTruncated,
        Integer mountedPinnedCount,
        Integer mountedActiveCount,
        Integer mountedAncestorCount,
        Integer mountedSiblingCount,
        Integer mountedEvidenceCount,
        Integer mountedIndexCount,
        Integer mountedArchiveCount,
        List<String> evidenceRefs,
        List<String> unfinishedItems
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JudgmentSurface(
        String promptMode,
        Boolean needsContextReopen,
        List<String> reopenCandidatePaths,
        String reopenSummary,
        Boolean mountedContextRendered,
        Boolean mountedRenderUsed,
        Boolean mountedContextInjected,
        Integer mountedContextPanelCount,
        Integer mountedContextNonEmptyPanelCount,
        Integer mountedContextSelectionTraceCount,
        Integer mountedContextRenderedPanelCount,
        Integer mountedContextHiddenPanelCount,
        Integer mountedContextRenderedObjectCount,
        Integer mountedContextHiddenObjectCount,
        Integer mountedContextRenderedSelectionTraceCount,
        Integer mountedContextHiddenSelectionTraceCount,
        Boolean mountedContextBudgetTruncated,
        Integer mountedPinnedCount,
        Integer mountedActiveCount,
        Integer mountedAncestorCount,
        Integer mountedSiblingCount,
        Integer mountedEvidenceCount,
        Integer mountedIndexCount,
        Integer mountedArchiveCount,
        List<String> candidateWorkers,
        List<String> toolInvocationIds,
        String proofSummary,
        List<String> evidenceRefs,
        List<String> unfinishedItems
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AlignmentSurface(
        Boolean routeWorkerMatchesExecutionWorker,
        Boolean executionAndExecutionJudgmentPromptModeAligned,
        Boolean executionAndCompletionJudgmentPromptModeAligned
    ) {}
}
