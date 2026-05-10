package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单任务 live flow 中的逐轮 cognition / drift timeline 条目。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuntimeCognitionTimelineEntryView(
    String stage,
    String label,
    String occurredAt,
    String workerId,
    String continuityAction,
    String checkpointType,
    String reason,
    String targetWorker,
    String promptMode,
    String routeSource,
    String executionStatus,
    Integer toolInvocationCount,
    Boolean needsContextReopen,
    Boolean evidenceGapDetected,
    Boolean needsArchiveRetrieval,
    Boolean needsExternalFactRefresh,
    List<String> reopenCandidatePaths,
    String reopenSummary,
    String proofSummary,
    Boolean mountedContextRendered,
    Boolean mountedRenderUsed,
    Boolean mountedContextInjected,
    Integer mountedContextPanelCount,
    Integer mountedContextRenderedObjectCount,
    Integer mountedContextHiddenObjectCount,
    Integer mountedContextRenderedSelectionTraceCount,
    Integer mountedContextHiddenSelectionTraceCount,
    Boolean mountedContextBudgetTruncated,
    Boolean alignedWithPreviousPromptMode,
    List<String> candidateWorkers,
    List<String> toolInvocationIds,
    List<String> evidenceRefs,
    List<String> unfinishedItems,
    String summary
) {}
