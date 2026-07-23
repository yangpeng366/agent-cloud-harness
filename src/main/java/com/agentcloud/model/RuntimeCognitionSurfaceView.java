package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 单任务诊断面里的 route / execution / judgment 对照视图。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuntimeCognitionSurfaceView(
    RouteSurface route,
    ExecutionSurface execution,
    JudgmentSurface executionJudgment,
    JudgmentSurface completionJudgment,
    AlignmentSurface alignment,
    LegacyControlAuditSurface legacyControlAudit
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RouteSurface(
        String selectedWorker,
        String routeSource,
        String selectedProviderProfile,
        String preferredProviderProfile,
        String workflowStage,
        String selectedModelTier,
        String selectedExecutionRole,
        String selectionScope,
        List<String> candidateWorkers,
        String preferredWorkerHint,
        Boolean learningHintApplied,
        String fallbackReason,
        Boolean freeFirstRouting,
        List<String> freeCandidateWorkers,
        List<String> paidCandidateWorkers,
        String costRouteStage,
        Boolean manualWindowRequired,
        String recommendedManualProvider,
        String manualFollowupInstruction,
        List<String> manualWindowCandidates,
        List<Map<String, Object>> dispatchSkippedWorkers
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExecutionSurface(
        String workerId,
        String executionId,
        String executionStatus,
        String executionBackend,
        String providerId,
        String providerSessionId,
        String providerThreadId,
        String resumeProviderSessionId,
        String providerError,
        String providerTurnStatus,
        String providerAbortReason,
        String providerTimeoutKind,
        Long providerActivityTimeoutMs,
        Long providerTurnMaxDurationMs,
        String providerFailureClass,
        String providerFailureReason,
        Boolean providerRetryable,
        Integer partialOutputChars,
        Integer partialTimeoutMinOutputChars,
        List<String> providerProtocolTrace,
        String providerRunDir,
        String providerPromptPath,
        String providerStdoutPath,
        String providerEventLogPath,
        String providerLastMessagePath,
        String providerRunMetadataPath,
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
        List<String> unfinishedItems,
        List<Map<String, Object>> proposedActions,
        List<Map<String, Object>> acceptedActions,
        List<Map<String, Object>> rejectedActions,
        List<Map<String, Object>> approvalNeededActions,
        List<String> contextRequests,
        String completionClaim,
        String handoffTarget,
        List<String> riskFlags
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JudgmentSurface(
        String promptMode,
        Boolean needsContextReopen,
        Boolean evidenceGapDetected,
        Boolean needsArchiveRetrieval,
        Boolean needsExternalFactRefresh,
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LegacyControlAuditSurface(
        Boolean legacyControlRouteObserved,
        String requestMethod,
        String requestPath,
        String replacementMethod,
        String latestAction,
        String observedAt,
        String summary
    ) {}
}
