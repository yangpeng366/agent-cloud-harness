package com.agentcloud.runtime;

import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Decision;
import com.agentcloud.model.RuntimeCognitionSurfaceView;
import com.agentcloud.runtime.context.MountedContextPromptBudgetSupport;
import com.agentcloud.runtime.model.RuntimeFactSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把 RuntimeFactSet 收口成共享的 runtime cognition surface，
 * 供 live-flow / judgment 等读面共用，减少各自维护投影逻辑的漂移。
 */
public class RuntimeCognitionSurfaceAssembler {

    public RuntimeCognitionSurfaceView assemble(RuntimeFactSet facts) {
        RuntimeFactSet runtimeFacts = facts != null ? facts : RuntimeFactSet.empty(null);
        WorkerRouter.RouteResult routePreview = runtimeFacts.routePreview();
        RuntimeFactSet.ExecutionBoundary executionBoundary = runtimeFacts.executionBoundary();
        Decision executionJudgment = runtimeFacts.executionJudgment();
        Decision completionJudgment = runtimeFacts.completionJudgment();
        Map<String, Object> runtimeMetadata = runtimeFacts.metadata();
        Map<String, Object> executionMetadata = executionBoundary != null ? executionBoundary.metadata() : Map.of();

        RuntimeCognitionSurfaceView.RouteSurface routeSurface = routePreview == null ? null
            : new RuntimeCognitionSurfaceView.RouteSurface(
                blankToNull(routePreview.selectedWorker()),
                blankToNull(routePreview.routeSource()),
                blankToNull(routePreview.selectedModelTier()),
                blankToNull(routePreview.selectedExecutionRole()),
                blankToNull(routePreview.selectionScope()),
                routePreview.candidateWorkers() == null ? List.of() : routePreview.candidateWorkers(),
                blankToNull(routePreview.preferredWorkerHint()),
                routePreview.learningHintApplied(),
                blankToNull(routePreview.fallbackReason()),
                routeSkippedWorkerMetadata(routePreview.dispatchSkippedWorkers())
            );

        RuntimeCognitionSurfaceView.ExecutionSurface executionSurface = executionBoundary == null ? null
            : new RuntimeCognitionSurfaceView.ExecutionSurface(
                firstNonBlank(
                    blankToNull(executionBoundary.workerId()),
                    metadataString(executionMetadata, "selected_worker")
                ),
                blankToNull(executionBoundary.executionId()),
                blankToNull(executionBoundary.executionStatus()),
                metadataString(executionMetadata, "execution_backend"),
                metadataString(executionMetadata, "provider_id"),
                metadataString(executionMetadata, "provider_session_id"),
                metadataString(executionMetadata, "provider_thread_id"),
                metadataString(executionMetadata, "resume_provider_session_id"),
                metadataString(executionMetadata, "provider_error"),
                metadataString(executionMetadata, "provider_turn_status"),
                metadataString(executionMetadata, "provider_abort_reason"),
                metadataString(executionMetadata, "provider_timeout_kind"),
                metadataString(executionMetadata, "provider_failure_class"),
                metadataString(executionMetadata, "provider_failure_reason"),
                metadataBoolean(executionMetadata, "provider_retryable", runtimeMetadata),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "partial_output_chars"),
                    metadataInteger(runtimeMetadata, "partial_output_chars")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "partial_timeout_min_output_chars"),
                    metadataInteger(runtimeMetadata, "partial_timeout_min_output_chars")
                ),
                metadataStringList(executionMetadata, "provider_protocol_trace"),
                metadataString(executionMetadata, "provider_run_dir"),
                metadataString(executionMetadata, "provider_prompt_path"),
                metadataString(executionMetadata, "provider_stdout_path"),
                metadataString(executionMetadata, "provider_event_log_path"),
                metadataString(executionMetadata, "provider_last_message_path"),
                metadataString(executionMetadata, "provider_run_metadata_path"),
                executionBoundary.durationMs(),
                executionBoundary.toolInvocationCount(),
                executionBoundary.toolInvocationIds() == null ? List.of() : executionBoundary.toolInvocationIds(),
                proofSummary(
                    executionBoundary.toolInvocationIds(),
                    metadataStringList(executionMetadata, "evidence_refs").isEmpty()
                        ? metadataStringList(runtimeMetadata, "evidence_refs")
                        : metadataStringList(executionMetadata, "evidence_refs")
                ),
                blankToNull(executionBoundary.traceSummary()),
                firstNonBlank(
                    metadataString(executionMetadata, "prompt_mode"),
                    metadataString(runtimeMetadata, "prompt_mode")
                ),
                metadataBoolean(executionMetadata, "mounted_context_rendered", runtimeMetadata),
                metadataBoolean(executionMetadata, "mounted_render_used", runtimeMetadata),
                metadataBoolean(executionMetadata, "mounted_context_injected", runtimeMetadata),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_context_panel_count"),
                    metadataInteger(runtimeMetadata, "mounted_context_panel_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_context_non_empty_panel_count"),
                    metadataInteger(runtimeMetadata, "mounted_context_non_empty_panel_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_context_selection_trace_count"),
                    metadataInteger(runtimeMetadata, "mounted_context_selection_trace_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, MountedContextPromptBudgetSupport.RENDERED_PANEL_COUNT),
                    metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.RENDERED_PANEL_COUNT)
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, MountedContextPromptBudgetSupport.HIDDEN_PANEL_COUNT),
                    metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.HIDDEN_PANEL_COUNT)
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT),
                    metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT)
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT),
                    metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT)
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT),
                    metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT)
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT),
                    metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT)
                ),
                metadataBoolean(executionMetadata, MountedContextPromptBudgetSupport.BUDGET_TRUNCATED, runtimeMetadata),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_pinned_count"),
                    metadataInteger(runtimeMetadata, "mounted_pinned_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_active_count"),
                    metadataInteger(runtimeMetadata, "mounted_active_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_ancestor_count"),
                    metadataInteger(runtimeMetadata, "mounted_ancestor_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_sibling_count"),
                    metadataInteger(runtimeMetadata, "mounted_sibling_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_evidence_count"),
                    metadataInteger(runtimeMetadata, "mounted_evidence_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_index_count"),
                    metadataInteger(runtimeMetadata, "mounted_index_count")
                ),
                firstNonNullInt(
                    metadataInteger(executionMetadata, "mounted_archive_count"),
                    metadataInteger(runtimeMetadata, "mounted_archive_count")
                ),
                metadataStringList(executionMetadata, "evidence_refs").isEmpty()
                    ? metadataStringList(runtimeMetadata, "evidence_refs")
                    : metadataStringList(executionMetadata, "evidence_refs"),
                metadataStringList(executionMetadata, "unfinished_items").isEmpty()
                    ? metadataStringList(runtimeMetadata, "unfinished_items")
                    : metadataStringList(executionMetadata, "unfinished_items"),
                metadataMapList(executionMetadata, runtimeMetadata, "proposed_actions"),
                metadataMapList(executionMetadata, runtimeMetadata, "accepted_actions"),
                metadataMapList(executionMetadata, runtimeMetadata, "rejected_actions"),
                metadataMapList(executionMetadata, runtimeMetadata, "approval_needed_actions"),
                metadataStringList(executionMetadata, "context_requests").isEmpty()
                    ? metadataStringList(runtimeMetadata, "context_requests")
                    : metadataStringList(executionMetadata, "context_requests"),
                firstNonBlank(
                    metadataString(executionMetadata, "completion_claim"),
                    metadataString(runtimeMetadata, "completion_claim")
                ),
                firstNonBlank(
                    metadataString(executionMetadata, "handoff_target"),
                    metadataString(runtimeMetadata, "handoff_target")
                ),
                metadataStringList(executionMetadata, "risk_flags").isEmpty()
                    ? metadataStringList(runtimeMetadata, "risk_flags")
                    : metadataStringList(executionMetadata, "risk_flags")
            );

        RuntimeCognitionSurfaceView.JudgmentSurface executionJudgmentSurface =
            buildJudgmentSurface(executionJudgment, runtimeMetadata);
        RuntimeCognitionSurfaceView.JudgmentSurface completionJudgmentSurface =
            buildJudgmentSurface(completionJudgment, runtimeMetadata);

        String routedWorker = routeSurface != null ? routeSurface.selectedWorker() : null;
        String executedWorker = executionSurface != null ? executionSurface.workerId() : null;
        String executionPromptMode = executionSurface != null ? executionSurface.promptMode() : null;
        String executionJudgmentPromptMode = executionJudgmentSurface != null ? executionJudgmentSurface.promptMode() : null;
        String completionJudgmentPromptMode = completionJudgmentSurface != null ? completionJudgmentSurface.promptMode() : null;

        RuntimeCognitionSurfaceView.AlignmentSurface alignment = new RuntimeCognitionSurfaceView.AlignmentSurface(
            alignmentFlag(routedWorker, executedWorker),
            alignmentFlag(executionPromptMode, executionJudgmentPromptMode),
            alignmentFlag(executionPromptMode, completionJudgmentPromptMode)
        );

        return new RuntimeCognitionSurfaceView(
            routeSurface,
            executionSurface,
            executionJudgmentSurface,
            completionJudgmentSurface,
            alignment
        );
    }

    private RuntimeCognitionSurfaceView.JudgmentSurface buildJudgmentSurface(Decision decision,
                                                                             Map<String, Object> runtimeMetadata) {
        if (decision == null) {
            return null;
        }
        Map<String, Object> decisionMetadata = decision.metadata() == null ? Map.of() : decision.metadata();
        List<String> reopenCandidatePaths = metadataStringList(decisionMetadata, "reopen_candidate_paths");
        if (reopenCandidatePaths.isEmpty()) {
            reopenCandidatePaths = metadataStringList(runtimeMetadata, "reopen_candidate_paths");
        }
        return new RuntimeCognitionSurfaceView.JudgmentSurface(
            firstNonBlank(
                metadataString(decisionMetadata, "prompt_mode"),
                metadataString(runtimeMetadata, "prompt_mode")
            ),
            metadataBoolean(decisionMetadata, "needs_context_reopen", runtimeMetadata),
            metadataBoolean(decisionMetadata, "evidence_gap_detected", runtimeMetadata),
            metadataBoolean(decisionMetadata, "needs_archive_retrieval", runtimeMetadata),
            metadataBoolean(decisionMetadata, "needs_external_fact_refresh", runtimeMetadata),
            reopenCandidatePaths,
            firstNonBlank(
                metadataString(decisionMetadata, "reopen_summary"),
                reopenSummary(reopenCandidatePaths)
            ),
            metadataBoolean(decisionMetadata, "mounted_context_rendered", runtimeMetadata),
            metadataBoolean(decisionMetadata, "mounted_render_used", runtimeMetadata),
            metadataBoolean(decisionMetadata, "mounted_context_injected", runtimeMetadata),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_context_panel_count"),
                metadataInteger(runtimeMetadata, "mounted_context_panel_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_context_non_empty_panel_count"),
                metadataInteger(runtimeMetadata, "mounted_context_non_empty_panel_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_context_selection_trace_count"),
                metadataInteger(runtimeMetadata, "mounted_context_selection_trace_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, MountedContextPromptBudgetSupport.RENDERED_PANEL_COUNT),
                metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.RENDERED_PANEL_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, MountedContextPromptBudgetSupport.HIDDEN_PANEL_COUNT),
                metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.HIDDEN_PANEL_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT),
                metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT),
                metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT),
                metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT)
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT),
                metadataInteger(runtimeMetadata, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT)
            ),
            metadataBoolean(decisionMetadata, MountedContextPromptBudgetSupport.BUDGET_TRUNCATED, runtimeMetadata),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_pinned_count"),
                metadataInteger(runtimeMetadata, "mounted_pinned_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_active_count"),
                metadataInteger(runtimeMetadata, "mounted_active_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_ancestor_count"),
                metadataInteger(runtimeMetadata, "mounted_ancestor_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_sibling_count"),
                metadataInteger(runtimeMetadata, "mounted_sibling_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_evidence_count"),
                metadataInteger(runtimeMetadata, "mounted_evidence_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_index_count"),
                metadataInteger(runtimeMetadata, "mounted_index_count")
            ),
            firstNonNullInt(
                metadataInteger(decisionMetadata, "mounted_archive_count"),
                metadataInteger(runtimeMetadata, "mounted_archive_count")
            ),
            metadataStringList(decisionMetadata, "candidate_workers").isEmpty()
                ? metadataStringList(runtimeMetadata, "candidate_workers")
                : metadataStringList(decisionMetadata, "candidate_workers"),
            metadataStringList(decisionMetadata, "tool_invocation_ids").isEmpty()
                ? metadataStringList(runtimeMetadata, "tool_invocation_ids")
                : metadataStringList(decisionMetadata, "tool_invocation_ids"),
            proofSummary(
                metadataStringList(decisionMetadata, "tool_invocation_ids").isEmpty()
                    ? metadataStringList(runtimeMetadata, "tool_invocation_ids")
                    : metadataStringList(decisionMetadata, "tool_invocation_ids"),
                metadataStringList(decisionMetadata, "evidence_refs").isEmpty()
                    ? metadataStringList(runtimeMetadata, "evidence_refs")
                    : metadataStringList(decisionMetadata, "evidence_refs")
            ),
            metadataStringList(decisionMetadata, "evidence_refs").isEmpty()
                ? metadataStringList(runtimeMetadata, "evidence_refs")
                : metadataStringList(decisionMetadata, "evidence_refs"),
            metadataStringList(decisionMetadata, "unfinished_items").isEmpty()
                ? metadataStringList(runtimeMetadata, "unfinished_items")
                : metadataStringList(decisionMetadata, "unfinished_items")
        );
    }

    private String proofSummary(List<String> toolInvocationIds, List<String> evidenceRefs) {
        List<String> parts = new ArrayList<>();
        appendProofSummaryParts(parts, prefixedValues("tool", toolInvocationIds));
        appendProofSummaryParts(parts, prefixedValues("evidence", evidenceRefs));
        if (parts.isEmpty()) {
            return null;
        }
        return "proof=" + String.join(", ", parts);
    }

    private void appendProofSummaryParts(List<String> target, List<String> values) {
        if (values == null || values.isEmpty() || target.size() >= 2) {
            return;
        }
        for (String value : values) {
            String normalized = truncateProofLabel(value);
            if (normalized == null) {
                continue;
            }
            target.add(normalized);
            if (target.size() >= 2) {
                return;
            }
        }
    }

    private List<String> prefixedValues(String prefix, List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized == null) {
                continue;
            }
            result.add(prefix + ":" + normalized);
        }
        return result;
    }

    private String truncateProofLabel(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        String compact = normalized.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 72) {
            return compact;
        }
        return compact.substring(0, 69) + "...";
    }

    private String reopenSummary(List<String> reopenCandidatePaths) {
        if (reopenCandidatePaths == null || reopenCandidatePaths.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        appendProofSummaryParts(parts, prefixedValues("reopen", reopenCandidateLabels(reopenCandidatePaths)));
        if (parts.isEmpty()) {
            return null;
        }
        return "reopen=" + String.join(", ", parts);
    }

    private List<String> reopenCandidateLabels(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String label = reopenCandidateLabel(value);
            if (label != null) {
                result.add(label);
            }
        }
        return result;
    }

    private String reopenCandidateLabel(String targetPath) {
        String normalized = blankToNull(targetPath);
        if (normalized == null) {
            return null;
        }
        String[] tokens = normalized.split("/");
        if (tokens.length == 0) {
            return normalized;
        }
        String tail = tokens[tokens.length - 1];
        if (tail == null || tail.isBlank()) {
            return normalized;
        }
        if ("messages".equals(tail) || "artifacts".equals(tail) || "tool_invocations".equals(tail) || "decisions".equals(tail)) {
            return tail;
        }
        if (tokens.length >= 2) {
            String parent = tokens[tokens.length - 2];
            if ("checkpoints".equals(parent) || "packets".equals(parent)) {
                return parent + ":" + tail;
            }
        }
        return tail;
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private Boolean metadataBoolean(Map<String, Object> primary, String key, Map<String, Object> fallback) {
        Boolean value = objectBoolean(primary != null ? primary.get(key) : null);
        return value != null ? value : objectBoolean(fallback != null ? fallback.get(key) : null);
    }

    private Integer metadataInteger(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> metadataStringList(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return List.of();
        }
        Object value = metadata.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(item -> item != null && !item.toString().isBlank())
                .map(Object::toString)
                .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private List<Map<String, Object>> metadataMapList(Map<String, Object> primary,
                                                      Map<String, Object> fallback,
                                                      String key) {
        List<Map<String, Object>> selected = metadataMapList(primary, key);
        return selected.isEmpty() ? metadataMapList(fallback, key) : selected;
    }

    private List<Map<String, Object>> metadataMapList(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return List.of();
        }
        Object value = metadata.get(key);
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        ArrayList<Map<String, Object>> items = new ArrayList<>();
        for (Object raw : iterable) {
            if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
                continue;
            }
            LinkedHashMap<String, Object> copied = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copied.put(entry.getKey().toString(), entry.getValue());
                }
            }
            if (!copied.isEmpty()) {
                items.add(copied);
            }
        }
        return List.copyOf(items);
    }

    private Boolean objectBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    private List<Map<String, Object>> routeSkippedWorkerMetadata(List<WorkerRouter.RouteSkippedWorker> skippedWorkers) {
        if (skippedWorkers == null || skippedWorkers.isEmpty()) {
            return List.of();
        }
        return skippedWorkers.stream()
            .map(skipped -> {
                LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
                putIfNonBlank(metadata, "worker_id", skipped.workerId());
                putIfNonBlank(metadata, "reason", skipped.reason());
                putIfNonBlank(metadata, "provider_failure_class", skipped.providerFailureClass());
                putIfNonBlank(metadata, "provider_failure_reason", skipped.providerFailureReason());
                if (skipped.providerRetryable() != null) {
                    metadata.put("provider_retryable", skipped.providerRetryable());
                }
                return (Map<String, Object>) metadata;
            })
            .filter(metadata -> !metadata.isEmpty())
            .toList();
    }

    private void putIfNonBlank(Map<String, Object> target, String key, String value) {
        String normalized = blankToNull(value);
        if (target != null && key != null && !key.isBlank() && normalized != null) {
            target.put(key, normalized);
        }
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

    private Boolean alignmentFlag(String left, String right) {
        String normalizedLeft = blankToNull(left);
        String normalizedRight = blankToNull(right);
        if (normalizedLeft == null || normalizedRight == null) {
            return null;
        }
        return normalizedLeft.equals(normalizedRight);
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
