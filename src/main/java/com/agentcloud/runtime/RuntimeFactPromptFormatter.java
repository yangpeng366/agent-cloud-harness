package com.agentcloud.runtime;

import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.RuntimeCognitionSurfaceView;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.runtime.context.MountedContextPromptBudgetSupport;
import com.agentcloud.runtime.model.RuntimeFactSet;

import java.util.List;
import java.util.Map;

/**
 * 统一把 RuntimeFactSet / RuntimeCognitionSurface 渲染成 prompt 片段，
 * 供 execution 与 judgment 复用，减少两边维护文本格式的漂移。
 */
public class RuntimeFactPromptFormatter {
    private final RuntimeCognitionSurfaceAssembler runtimeCognitionSurfaceAssembler;

    public RuntimeFactPromptFormatter() {
        this(new RuntimeCognitionSurfaceAssembler());
    }

    public RuntimeFactPromptFormatter(RuntimeCognitionSurfaceAssembler runtimeCognitionSurfaceAssembler) {
        this.runtimeCognitionSurfaceAssembler = runtimeCognitionSurfaceAssembler == null
            ? new RuntimeCognitionSurfaceAssembler()
            : runtimeCognitionSurfaceAssembler;
    }

    public void append(StringBuilder sb, RuntimeFactSet factSet) {
        appendRuntimeFacts(sb, factSet);
        appendRuntimeCognitionSurface(sb, factSet);
    }

    private void appendRuntimeFacts(StringBuilder sb, RuntimeFactSet factSet) {
        if (sb == null || factSet == null) {
            return;
        }
        boolean hasExecutionBoundary = factSet.executionBoundary() != null;
        boolean hasRoutePreview = factSet.routePreview() != null;
        boolean hasToolInvocations = factSet.toolInvocations() != null && !factSet.toolInvocations().isEmpty();
        boolean hasMetadata = factSet.metadata() != null && !factSet.metadata().isEmpty();
        if (!hasExecutionBoundary && !hasRoutePreview && !hasToolInvocations && !hasMetadata) {
            return;
        }
        sb.append("Runtime Facts:\n");
        if (hasRoutePreview) {
            appendRoutePreview(sb, factSet.routePreview());
        }
        if (hasExecutionBoundary) {
            appendExecutionBoundary(sb, factSet.executionBoundary());
        }
        if (hasToolInvocations) {
            appendToolInvocations(sb, factSet.toolInvocations());
        }
        if (hasMetadata) {
            appendFactMetadata(sb, factSet.metadata());
        }
    }

    private void appendRuntimeCognitionSurface(StringBuilder sb, RuntimeFactSet factSet) {
        if (sb == null || factSet == null) {
            return;
        }
        RuntimeCognitionSurfaceView surface = runtimeCognitionSurfaceAssembler.assemble(factSet);
        if (surface == null) {
            return;
        }
        boolean hasRoute = surface.route() != null
            && (hasText(surface.route().selectedWorker()) || hasText(surface.route().routeSource()));
        boolean hasExecution = surface.execution() != null
            && (hasText(surface.execution().workerId())
            || hasText(surface.execution().executionStatus())
            || hasText(surface.execution().promptMode()));
        boolean hasExecutionJudgment = surface.executionJudgment() != null
            && (hasText(surface.executionJudgment().promptMode())
            || Boolean.TRUE.equals(surface.executionJudgment().needsContextReopen())
            || hasText(surface.executionJudgment().proofSummary()));
        boolean hasCompletionJudgment = surface.completionJudgment() != null
            && (hasText(surface.completionJudgment().promptMode())
            || hasText(surface.completionJudgment().proofSummary()));
        boolean hasAlignment = surface.alignment() != null
            && (surface.alignment().routeWorkerMatchesExecutionWorker() != null
            || surface.alignment().executionAndExecutionJudgmentPromptModeAligned() != null
            || surface.alignment().executionAndCompletionJudgmentPromptModeAligned() != null);
        if (!hasRoute && !hasExecution && !hasExecutionJudgment && !hasCompletionJudgment && !hasAlignment) {
            return;
        }
        sb.append("Runtime Cognition Surface:\n");
        if (hasRoute) {
            sb.append("Route Surface:\n");
            appendBullet(sb, "selected_worker", surface.route().selectedWorker());
            appendBullet(sb, "route_source", surface.route().routeSource());
            appendBullet(sb, "selected_model_tier", surface.route().selectedModelTier());
            appendBullet(sb, "selected_execution_role", surface.route().selectedExecutionRole());
        }
        if (hasExecution) {
            sb.append("Execution Surface:\n");
            appendBullet(sb, "worker_id", surface.execution().workerId());
            appendBullet(sb, "execution_status", surface.execution().executionStatus());
            appendBullet(sb, "prompt_mode", surface.execution().promptMode());
            appendBullet(sb, "proof_summary", surface.execution().proofSummary());
            appendBullet(sb, "mounted_render_used", surface.execution().mountedRenderUsed());
            appendBullet(sb, "mounted_context_budget", mountedBudgetSummary(
                surface.execution().mountedContextRenderedObjectCount(),
                surface.execution().mountedContextHiddenObjectCount(),
                surface.execution().mountedContextRenderedSelectionTraceCount(),
                surface.execution().mountedContextHiddenSelectionTraceCount(),
                surface.execution().mountedContextBudgetTruncated()
            ));
        }
        if (hasExecutionJudgment) {
            sb.append("Execution Judgment Surface:\n");
            appendBullet(sb, "prompt_mode", surface.executionJudgment().promptMode());
            appendBullet(sb, "needs_context_reopen", surface.executionJudgment().needsContextReopen());
            appendBullet(sb, "evidence_gap_detected", surface.executionJudgment().evidenceGapDetected());
            appendBullet(sb, "needs_archive_retrieval", surface.executionJudgment().needsArchiveRetrieval());
            appendBullet(sb, "needs_external_fact_refresh", surface.executionJudgment().needsExternalFactRefresh());
            appendBullet(sb, "reopen_summary", surface.executionJudgment().reopenSummary());
            appendBullet(sb, "proof_summary", surface.executionJudgment().proofSummary());
        }
        if (hasCompletionJudgment) {
            sb.append("Completion Judgment Surface:\n");
            appendBullet(sb, "prompt_mode", surface.completionJudgment().promptMode());
            appendBullet(sb, "proof_summary", surface.completionJudgment().proofSummary());
        }
        if (hasAlignment) {
            sb.append("Alignment Surface:\n");
            appendBullet(sb, "route_worker_matches_execution_worker",
                surface.alignment().routeWorkerMatchesExecutionWorker());
            appendBullet(sb, "execution_and_execution_judgment_prompt_mode_aligned",
                surface.alignment().executionAndExecutionJudgmentPromptModeAligned());
            appendBullet(sb, "execution_and_completion_judgment_prompt_mode_aligned",
                surface.alignment().executionAndCompletionJudgmentPromptModeAligned());
        }
    }

    private void appendRoutePreview(StringBuilder sb, WorkerRouter.RouteResult routePreview) {
        sb.append("Route Preview:\n");
        appendBullet(sb, "selected_worker", routePreview.selectedWorker());
        appendBullet(sb, "selected_model_tier", routePreview.selectedModelTier());
        appendBullet(sb, "selected_execution_role", routePreview.selectedExecutionRole());
        appendBullet(sb, "selection_scope", routePreview.selectionScope());
        appendBullet(sb, "route_source", routePreview.routeSource());
        appendBullet(sb, "why_selected", routePreview.whySelected());
        appendBullet(sb, "fallback_reason", routePreview.fallbackReason());
        if (routePreview.candidateWorkers() != null && !routePreview.candidateWorkers().isEmpty()) {
            appendBullet(sb, "candidate_workers", String.join(", ", routePreview.candidateWorkers()));
        }
    }

    private void appendExecutionBoundary(StringBuilder sb, RuntimeFactSet.ExecutionBoundary executionBoundary) {
        sb.append("Execution Boundary:\n");
        appendBullet(sb, "execution_id", executionBoundary.executionId());
        appendBullet(sb, "execution_status", executionBoundary.executionStatus());
        appendBullet(sb, "execution_duration_ms", executionBoundary.durationMs());
        appendBullet(sb, "worker_id", executionBoundary.workerId());
        appendBullet(sb, "tool_invocation_count", executionBoundary.toolInvocationCount());
        appendBullet(sb, "trace_summary", executionBoundary.traceSummary());
        if (executionBoundary.toolInvocationIds() != null && !executionBoundary.toolInvocationIds().isEmpty()) {
            appendBullet(sb, "tool_invocation_ids", String.join(", ", executionBoundary.toolInvocationIds()));
        }
        if (executionBoundary.metadata() != null && !executionBoundary.metadata().isEmpty()) {
            appendMetadataLine(sb, executionBoundary.metadata(), "tool_execution_mode");
            appendMetadataLine(sb, executionBoundary.metadata(), "tool_chain_step_count");
            appendMetadataLine(sb, executionBoundary.metadata(), "tool_chain_termination_reason");
            appendMetadataLine(sb, executionBoundary.metadata(), "prompt_mode");
            appendMetadataLine(sb, executionBoundary.metadata(), "mounted_context_rendered");
            appendMetadataLine(sb, executionBoundary.metadata(), "mounted_render_used");
            appendMetadataLine(sb, executionBoundary.metadata(), "mounted_context_injected");
            appendMetadataLine(sb, executionBoundary.metadata(), "mounted_context_panel_count");
            appendMetadataLine(sb, executionBoundary.metadata(), "mounted_context_non_empty_panel_count");
            appendMetadataLine(sb, executionBoundary.metadata(), "mounted_context_selection_trace_count");
            appendMetadataLine(sb, executionBoundary.metadata(), "mounted_pinned_count");
            appendMetadataLine(sb, executionBoundary.metadata(), "mounted_active_count");
            appendMetadataLine(sb, executionBoundary.metadata(), "mounted_ancestor_count");
            appendMetadataLine(sb, executionBoundary.metadata(), "mounted_sibling_count");
            appendMetadataLine(sb, executionBoundary.metadata(), "mounted_evidence_count");
            appendMetadataLine(sb, executionBoundary.metadata(), "mounted_index_count");
            appendMetadataLine(sb, executionBoundary.metadata(), "mounted_archive_count");
            appendMetadataLine(sb, executionBoundary.metadata(), MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT);
            appendMetadataLine(sb, executionBoundary.metadata(), MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT);
            appendMetadataLine(sb, executionBoundary.metadata(), MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT);
            appendMetadataLine(sb, executionBoundary.metadata(), MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT);
            appendMetadataLine(sb, executionBoundary.metadata(), MountedContextPromptBudgetSupport.BUDGET_TRUNCATED);
            appendMetadataLine(sb, executionBoundary.metadata(), "grounded_output_present");
            appendMetadataLine(sb, executionBoundary.metadata(), "missing_required_current_round_write");
            appendMetadataLine(sb, executionBoundary.metadata(), "evidence_refs");
            appendMetadataLine(sb, executionBoundary.metadata(), "unfinished_items");
        }
    }

    private void appendToolInvocations(StringBuilder sb, List<ToolInvocationRecord> toolInvocations) {
        sb.append("Recent Tool Invocations:\n");
        int max = Math.min(toolInvocations.size(), 3);
        for (int i = 0; i < max; i++) {
            ToolInvocationRecord record = toolInvocations.get(i);
            if (record == null) {
                continue;
            }
            String summary = firstNonBlank(
                record.toolName(),
                record.resultSummary(),
                record.executionId()
            );
            if (summary == null) {
                continue;
            }
            StringBuilder line = new StringBuilder("- ").append(summary);
            if (record.status() != null && !record.status().isBlank()) {
                line.append(" [").append(record.status()).append("]");
            }
            if (record.touchedPaths() != null && !record.touchedPaths().isEmpty()) {
                line.append(" paths=").append(String.join(", ", record.touchedPaths()));
            }
            sb.append(line).append("\n");
        }
    }

    private void appendFactMetadata(StringBuilder sb, Map<String, Object> metadata) {
        appendMetadataLine(sb, metadata, "execution_trace_summary");
        appendMetadataLine(sb, metadata, "has_execution_boundary");
        appendMetadataLine(sb, metadata, "has_route_preview");
        appendMetadataLine(sb, metadata, "has_latest_packet");
        appendMetadataLine(sb, metadata, "has_latest_checkpoint");
        appendMetadataLine(sb, metadata, "evidence_gap_detected");
        appendMetadataLine(sb, metadata, "needs_archive_retrieval");
        appendMetadataLine(sb, metadata, "needs_external_fact_refresh");
        appendMetadataLine(sb, metadata, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT);
        appendMetadataLine(sb, metadata, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT);
        appendMetadataLine(sb, metadata, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT);
        appendMetadataLine(sb, metadata, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT);
        appendMetadataLine(sb, metadata, MountedContextPromptBudgetSupport.BUDGET_TRUNCATED);
    }

    private void appendMetadataLine(StringBuilder sb, Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) {
            return;
        }
        String text = value.toString();
        if (text.isBlank()) {
            return;
        }
        if (text.length() > 400) {
            text = text.substring(0, 400) + "...";
        }
        sb.append("- ").append(key).append(": ").append(text).append("\n");
    }

    private void appendBullet(StringBuilder sb, String key, Object value) {
        if (value == null) {
            return;
        }
        String text = value.toString();
        if (text.isBlank()) {
            return;
        }
        sb.append("- ").append(key).append(": ").append(text).append("\n");
    }

    private String mountedBudgetSummary(Integer renderedObjectCount,
                                        Integer hiddenObjectCount,
                                        Integer renderedSelectionTraceCount,
                                        Integer hiddenSelectionTraceCount,
                                        Boolean budgetTruncated) {
        String objects = renderedObjectCount == null && hiddenObjectCount == null
            ? null
            : firstNonNullInt(renderedObjectCount, 0) + "/" + firstNonNullInt(hiddenObjectCount, 0) + " objects";
        String traces = renderedSelectionTraceCount == null && hiddenSelectionTraceCount == null
            ? null
            : firstNonNullInt(renderedSelectionTraceCount, 0) + "/" + firstNonNullInt(hiddenSelectionTraceCount, 0)
                + " traces";
        String truncated = Boolean.TRUE.equals(budgetTruncated) ? "budget truncated" : null;
        return firstNonBlank(
            joinSummary(objects, traces, truncated),
            joinSummary(objects, traces),
            truncated
        );
    }

    private Integer firstNonNullInt(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String joinSummary(String... parts) {
        if (parts == null || parts.length == 0) {
            return null;
        }
        return java.util.Arrays.stream(parts)
            .filter(this::hasText)
            .distinct()
            .reduce((left, right) -> left + " · " + right)
            .orElse(null);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
