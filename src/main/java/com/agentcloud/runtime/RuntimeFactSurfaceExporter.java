package com.agentcloud.runtime;

import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.RuntimeCognitionSurfaceView;
import com.agentcloud.runtime.model.RuntimeFactSet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 shared runtime fact / cognition surface 压成适合 packet/checkpoint 持久化的轻量结构。
 */
public class RuntimeFactSurfaceExporter {
    private final RuntimeCognitionSurfaceAssembler runtimeCognitionSurfaceAssembler;

    public RuntimeFactSurfaceExporter() {
        this(new RuntimeCognitionSurfaceAssembler());
    }

    public RuntimeFactSurfaceExporter(RuntimeCognitionSurfaceAssembler runtimeCognitionSurfaceAssembler) {
        this.runtimeCognitionSurfaceAssembler = runtimeCognitionSurfaceAssembler != null
            ? runtimeCognitionSurfaceAssembler
            : new RuntimeCognitionSurfaceAssembler();
    }

    public Map<String, Object> exportRuntimeFacts(RuntimeFactSet factSet) {
        RuntimeFactSet facts = factSet != null ? factSet : RuntimeFactSet.empty(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "task_id", blankToNull(facts.taskId()));
        putIfPresent(payload, "session_id", blankToNull(facts.sessionId()));
        putIfPresent(payload, "task_status", blankToNull(facts.taskStatus()));
        putIfPresent(payload, "control_node", blankToNull(facts.controlNode()));
        putIfPresent(payload, "assigned_worker", blankToNull(facts.assignedWorker()));
        putIfPresent(payload, "latest_output", blankToNull(facts.latestOutput()));
        putIfPresent(payload, "recommended_action", blankToNull(facts.recommendedAction()));
        putIfPresent(payload, "recommended_next_step", blankToNull(facts.recommendedNextStep()));
        putIfPresent(payload, "execution_boundary", exportExecutionBoundary(facts.executionBoundary()));
        putIfPresent(payload, "route_preview", exportRoutePreview(facts.routePreview()));
        if (facts.metadata() != null && !facts.metadata().isEmpty()) {
            payload.put("metadata", new LinkedHashMap<>(facts.metadata()));
        }
        return payload;
    }

    public Map<String, Object> exportRuntimeCognitionSurface(RuntimeFactSet factSet) {
        RuntimeCognitionSurfaceView surface = runtimeCognitionSurfaceAssembler.assemble(factSet);
        if (surface == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "route", exportRouteSurface(surface.route()));
        putIfPresent(payload, "execution", exportExecutionSurface(surface.execution()));
        putIfPresent(payload, "execution_judgment", exportJudgmentSurface(surface.executionJudgment()));
        putIfPresent(payload, "completion_judgment", exportJudgmentSurface(surface.completionJudgment()));
        putIfPresent(payload, "alignment", exportAlignmentSurface(surface.alignment()));
        return payload;
    }

    private Map<String, Object> exportExecutionBoundary(RuntimeFactSet.ExecutionBoundary boundary) {
        if (boundary == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "execution_id", blankToNull(boundary.executionId()));
        putIfPresent(payload, "execution_status", blankToNull(boundary.executionStatus()));
        putIfPresent(payload, "started_at", blankToNull(boundary.startedAt()));
        putIfPresent(payload, "finished_at", blankToNull(boundary.finishedAt()));
        putIfPresent(payload, "duration_ms", boundary.durationMs());
        putIfPresent(payload, "worker_id", blankToNull(boundary.workerId()));
        putIfPresent(payload, "tool_invocation_ids", copyList(boundary.toolInvocationIds()));
        putIfPresent(payload, "tool_invocation_count", boundary.toolInvocationCount());
        putIfPresent(payload, "trace_summary", blankToNull(boundary.traceSummary()));
        if (boundary.metadata() != null && !boundary.metadata().isEmpty()) {
            payload.put("metadata", new LinkedHashMap<>(boundary.metadata()));
        }
        return payload;
    }

    private Map<String, Object> exportRoutePreview(WorkerRouter.RouteResult route) {
        if (route == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "selected_worker", blankToNull(route.selectedWorker()));
        putIfPresent(payload, "route_source", blankToNull(route.routeSource()));
        putIfPresent(payload, "selected_model_tier", blankToNull(route.selectedModelTier()));
        putIfPresent(payload, "selected_execution_role", blankToNull(route.selectedExecutionRole()));
        putIfPresent(payload, "selection_scope", blankToNull(route.selectionScope()));
        putIfPresent(payload, "candidate_workers", copyList(route.candidateWorkers()));
        putIfPresent(payload, "preferred_worker_hint", blankToNull(route.preferredWorkerHint()));
        putIfPresent(payload, "learning_hint_applied", route.learningHintApplied());
        putIfPresent(payload, "fallback_reason", blankToNull(route.fallbackReason()));
        putIfPresent(payload, "current_pinned_route", exportRouteDiagnostic(route.currentPinnedRoute()));
        putIfPresent(payload, "recovery_unpinned_recommendation", exportRouteDiagnostic(route.recoveryUnpinnedRecommendation()));
        return payload;
    }

    private Map<String, Object> exportRouteSurface(RuntimeCognitionSurfaceView.RouteSurface route) {
        if (route == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "selected_worker", blankToNull(route.selectedWorker()));
        putIfPresent(payload, "route_source", blankToNull(route.routeSource()));
        putIfPresent(payload, "selected_model_tier", blankToNull(route.selectedModelTier()));
        putIfPresent(payload, "selected_execution_role", blankToNull(route.selectedExecutionRole()));
        putIfPresent(payload, "selection_scope", blankToNull(route.selectionScope()));
        putIfPresent(payload, "candidate_workers", copyList(route.candidateWorkers()));
        putIfPresent(payload, "preferred_worker_hint", blankToNull(route.preferredWorkerHint()));
        putIfPresent(payload, "learning_hint_applied", route.learningHintApplied());
        putIfPresent(payload, "fallback_reason", blankToNull(route.fallbackReason()));
        return payload;
    }

    private Map<String, Object> exportRouteDiagnostic(WorkerRouter.RouteDiagnostic route) {
        if (route == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "selected_worker", blankToNull(route.selectedWorker()));
        putIfPresent(payload, "route_source", blankToNull(route.routeSource()));
        putIfPresent(payload, "task_type", blankToNull(route.taskType()));
        putIfPresent(payload, "selected_worker_type", blankToNull(route.selectedWorkerType()));
        putIfPresent(payload, "selected_model_tier", blankToNull(route.selectedModelTier()));
        putIfPresent(payload, "selected_execution_role", blankToNull(route.selectedExecutionRole()));
        putIfPresent(payload, "selection_scope", blankToNull(route.selectionScope()));
        putIfPresent(payload, "why_selected", blankToNull(route.whySelected()));
        putIfPresent(payload, "fallback_reason", blankToNull(route.fallbackReason()));
        putIfPresent(payload, "preferred_worker_hint", blankToNull(route.preferredWorkerHint()));
        putIfPresent(payload, "learning_hint_applied", route.learningHintApplied());
        putIfPresent(payload, "provider_deprioritized", route.providerDeprioritized());
        putIfPresent(payload, "deprioritized_provider", blankToNull(route.deprioritizedProvider()));
        putIfPresent(payload, "deprioritization_reason", blankToNull(route.deprioritizationReason()));
        putIfPresent(payload, "candidate_workers", copyList(route.candidateWorkers()));
        putIfPresent(payload, "fallback_workers", copyList(route.fallbackWorkers()));
        return payload;
    }

    private Map<String, Object> exportExecutionSurface(RuntimeCognitionSurfaceView.ExecutionSurface execution) {
        if (execution == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "worker_id", blankToNull(execution.workerId()));
        putIfPresent(payload, "execution_id", blankToNull(execution.executionId()));
        putIfPresent(payload, "execution_status", blankToNull(execution.executionStatus()));
        putIfPresent(payload, "duration_ms", execution.durationMs());
        putIfPresent(payload, "tool_invocation_count", execution.toolInvocationCount());
        putIfPresent(payload, "tool_invocation_ids", copyList(execution.toolInvocationIds()));
        putIfPresent(payload, "proof_summary", blankToNull(execution.proofSummary()));
        putIfPresent(payload, "trace_summary", blankToNull(execution.traceSummary()));
        putIfPresent(payload, "prompt_mode", blankToNull(execution.promptMode()));
        putIfPresent(payload, "mounted_context_rendered", execution.mountedContextRendered());
        putIfPresent(payload, "mounted_render_used", execution.mountedRenderUsed());
        putIfPresent(payload, "mounted_context_injected", execution.mountedContextInjected());
        putIfPresent(payload, "mounted_context_panel_count", execution.mountedContextPanelCount());
        putIfPresent(payload, "mounted_context_non_empty_panel_count", execution.mountedContextNonEmptyPanelCount());
        putIfPresent(payload, "mounted_context_selection_trace_count", execution.mountedContextSelectionTraceCount());
        putIfPresent(payload, "mounted_context_rendered_panel_count", execution.mountedContextRenderedPanelCount());
        putIfPresent(payload, "mounted_context_hidden_panel_count", execution.mountedContextHiddenPanelCount());
        putIfPresent(payload, "mounted_context_rendered_object_count", execution.mountedContextRenderedObjectCount());
        putIfPresent(payload, "mounted_context_hidden_object_count", execution.mountedContextHiddenObjectCount());
        putIfPresent(payload, "mounted_context_rendered_selection_trace_count", execution.mountedContextRenderedSelectionTraceCount());
        putIfPresent(payload, "mounted_context_hidden_selection_trace_count", execution.mountedContextHiddenSelectionTraceCount());
        putIfPresent(payload, "mounted_context_budget_truncated", execution.mountedContextBudgetTruncated());
        putIfPresent(payload, "mounted_pinned_count", execution.mountedPinnedCount());
        putIfPresent(payload, "mounted_active_count", execution.mountedActiveCount());
        putIfPresent(payload, "mounted_ancestor_count", execution.mountedAncestorCount());
        putIfPresent(payload, "mounted_sibling_count", execution.mountedSiblingCount());
        putIfPresent(payload, "mounted_evidence_count", execution.mountedEvidenceCount());
        putIfPresent(payload, "mounted_index_count", execution.mountedIndexCount());
        putIfPresent(payload, "mounted_archive_count", execution.mountedArchiveCount());
        putIfPresent(payload, "evidence_refs", copyList(execution.evidenceRefs()));
        putIfPresent(payload, "unfinished_items", copyList(execution.unfinishedItems()));
        return payload;
    }

    private Map<String, Object> exportJudgmentSurface(RuntimeCognitionSurfaceView.JudgmentSurface judgment) {
        if (judgment == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "prompt_mode", blankToNull(judgment.promptMode()));
        putIfPresent(payload, "needs_context_reopen", judgment.needsContextReopen());
        putIfPresent(payload, "evidence_gap_detected", judgment.evidenceGapDetected());
        putIfPresent(payload, "needs_archive_retrieval", judgment.needsArchiveRetrieval());
        putIfPresent(payload, "needs_external_fact_refresh", judgment.needsExternalFactRefresh());
        putIfPresent(payload, "reopen_candidate_paths", copyList(judgment.reopenCandidatePaths()));
        putIfPresent(payload, "reopen_summary", blankToNull(judgment.reopenSummary()));
        putIfPresent(payload, "mounted_context_rendered", judgment.mountedContextRendered());
        putIfPresent(payload, "mounted_render_used", judgment.mountedRenderUsed());
        putIfPresent(payload, "mounted_context_injected", judgment.mountedContextInjected());
        putIfPresent(payload, "mounted_context_panel_count", judgment.mountedContextPanelCount());
        putIfPresent(payload, "mounted_context_non_empty_panel_count", judgment.mountedContextNonEmptyPanelCount());
        putIfPresent(payload, "mounted_context_selection_trace_count", judgment.mountedContextSelectionTraceCount());
        putIfPresent(payload, "mounted_context_rendered_panel_count", judgment.mountedContextRenderedPanelCount());
        putIfPresent(payload, "mounted_context_hidden_panel_count", judgment.mountedContextHiddenPanelCount());
        putIfPresent(payload, "mounted_context_rendered_object_count", judgment.mountedContextRenderedObjectCount());
        putIfPresent(payload, "mounted_context_hidden_object_count", judgment.mountedContextHiddenObjectCount());
        putIfPresent(payload, "mounted_context_rendered_selection_trace_count", judgment.mountedContextRenderedSelectionTraceCount());
        putIfPresent(payload, "mounted_context_hidden_selection_trace_count", judgment.mountedContextHiddenSelectionTraceCount());
        putIfPresent(payload, "mounted_context_budget_truncated", judgment.mountedContextBudgetTruncated());
        putIfPresent(payload, "mounted_pinned_count", judgment.mountedPinnedCount());
        putIfPresent(payload, "mounted_active_count", judgment.mountedActiveCount());
        putIfPresent(payload, "mounted_ancestor_count", judgment.mountedAncestorCount());
        putIfPresent(payload, "mounted_sibling_count", judgment.mountedSiblingCount());
        putIfPresent(payload, "mounted_evidence_count", judgment.mountedEvidenceCount());
        putIfPresent(payload, "mounted_index_count", judgment.mountedIndexCount());
        putIfPresent(payload, "mounted_archive_count", judgment.mountedArchiveCount());
        putIfPresent(payload, "candidate_workers", copyList(judgment.candidateWorkers()));
        putIfPresent(payload, "tool_invocation_ids", copyList(judgment.toolInvocationIds()));
        putIfPresent(payload, "proof_summary", blankToNull(judgment.proofSummary()));
        putIfPresent(payload, "evidence_refs", copyList(judgment.evidenceRefs()));
        putIfPresent(payload, "unfinished_items", copyList(judgment.unfinishedItems()));
        return payload;
    }

    private Map<String, Object> exportAlignmentSurface(RuntimeCognitionSurfaceView.AlignmentSurface alignment) {
        if (alignment == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "route_worker_matches_execution_worker", alignment.routeWorkerMatchesExecutionWorker());
        putIfPresent(payload, "execution_and_execution_judgment_prompt_mode_aligned",
            alignment.executionAndExecutionJudgmentPromptModeAligned());
        putIfPresent(payload, "execution_and_completion_judgment_prompt_mode_aligned",
            alignment.executionAndCompletionJudgmentPromptModeAligned());
        return payload;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || key.isBlank() || value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return;
        }
        target.put(key, value);
    }

    private List<String> copyList(List<String> values) {
        return values == null || values.isEmpty() ? null : List.copyOf(values);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
