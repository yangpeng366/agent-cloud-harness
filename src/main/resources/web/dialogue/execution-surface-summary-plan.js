export function buildExecutionSurfaceSummaryPlan(surface = {}) {
    if (!surface || Object.keys(surface).length === 0) {
        return null;
    }
    const worker = firstNonBlank(surface.worker_id, surface.workerId);
    const status = firstNonBlank(surface.execution_status, surface.executionStatus);
    const timeoutKind = firstNonBlank(surface.provider_timeout_kind, surface.providerTimeoutKind);
    const abortReason = firstNonBlank(surface.provider_abort_reason, surface.providerAbortReason);
    const activityTimeoutMs = numericValue(
        surface.provider_activity_timeout_ms,
        surface.providerActivityTimeoutMs,
        surface.provider_turn_activity_timeout_ms,
        surface.providerTurnActivityTimeoutMs
    );
    const maxDurationMs = numericValue(surface.provider_turn_max_duration_ms, surface.providerTurnMaxDurationMs);
    const outputChars = numericValue(surface.partial_output_chars, surface.partialOutputChars);
    const threshold = numericValue(surface.partial_timeout_min_output_chars, surface.partialTimeoutMinOutputChars);
    const promptMode = firstNonBlank(surface.prompt_mode, surface.promptMode);
    const mountedRendered = booleanValue(surface.mounted_context_rendered, surface.mountedContextRendered);
    const mountedRenderUsed = booleanValue(surface.mounted_render_used, surface.mountedRenderUsed);
    const mountedInjected = booleanValue(surface.mounted_context_injected, surface.mountedContextInjected);
    const panelCount = numericValue(surface.mounted_context_panel_count, surface.mountedContextPanelCount);
    const nonEmptyPanelCount = numericValue(
        surface.mounted_context_non_empty_panel_count,
        surface.mountedContextNonEmptyPanelCount
    );
    const activeCount = numericValue(surface.mounted_active_count, surface.mountedActiveCount);
    const evidenceCount = numericValue(surface.mounted_evidence_count, surface.mountedEvidenceCount);
    const archiveCount = numericValue(surface.mounted_archive_count, surface.mountedArchiveCount);
    const renderedObjectCount = numericValue(
        surface.mounted_context_rendered_object_count,
        surface.mountedContextRenderedObjectCount
    );
    const hiddenObjectCount = numericValue(
        surface.mounted_context_hidden_object_count,
        surface.mountedContextHiddenObjectCount
    );
    const renderedSelectionTraceCount = numericValue(
        surface.mounted_context_rendered_selection_trace_count,
        surface.mountedContextRenderedSelectionTraceCount
    );
    const hiddenSelectionTraceCount = numericValue(
        surface.mounted_context_hidden_selection_trace_count,
        surface.mountedContextHiddenSelectionTraceCount
    );
    const budgetTruncated = booleanValue(
        surface.mounted_context_budget_truncated,
        surface.mountedContextBudgetTruncated
    );
    const parts = [
        worker ? `worker ${worker}` : null,
        status ? humanizeToken(status) || status : null,
        timeoutKind ? humanizeToken(timeoutKind) || timeoutKind : null,
        activityTimeoutMs !== null ? `activity ${formatDuration(activityTimeoutMs)}` : null,
        maxDurationMs !== null ? `max ${formatDuration(maxDurationMs)}` : null,
        outputChars !== null && threshold !== null ? `${outputChars}/${threshold} chars` : null,
        abortReason ? `abort ${humanizeToken(abortReason) || abortReason}` : null,
        promptMode ? `prompt ${humanizeToken(promptMode) || promptMode}` : null,
        mountedRendered === true ? "mounted rendered" : null,
        mountedRendered === false ? "mounted not rendered" : null,
        mountedRenderUsed === true ? "mounted used" : null,
        mountedRenderUsed === false ? "mounted unused" : null,
        mountedInjected === true ? "mounted injected" : null,
        mountedInjected === false ? "mounted not injected" : null,
        panelCount ? `${panelCount} panels` : null,
        nonEmptyPanelCount ? `${nonEmptyPanelCount} non-empty` : null,
        renderedObjectCount !== null || hiddenObjectCount !== null
            ? `${renderedObjectCount ?? 0}/${hiddenObjectCount ?? 0} objects`
            : null,
        renderedSelectionTraceCount !== null || hiddenSelectionTraceCount !== null
            ? `${renderedSelectionTraceCount ?? 0}/${hiddenSelectionTraceCount ?? 0} traces`
            : null,
        budgetTruncated === true ? "budget truncated" : null,
        activeCount ? `${activeCount} active` : null,
        evidenceCount ? `${evidenceCount} evidence` : null,
        archiveCount ? `${archiveCount} archive` : null
    ].filter(Boolean);
    return parts.length > 0 ? { label: "execution", value: parts.join(" · ") } : null;
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
        if (value !== null && value !== undefined && typeof value !== "string") {
            return value;
        }
    }
    return null;
}

function numericValue(...values) {
    for (const value of values) {
        if (value === null || value === undefined || value === "") {
            continue;
        }
        const number = Number(value);
        if (Number.isFinite(number)) {
            return number;
        }
    }
    return null;
}

function booleanValue(...values) {
    for (const value of values) {
        if (typeof value === "boolean") {
            return value;
        }
        if (typeof value === "string") {
            const normalized = value.trim().toLowerCase();
            if (normalized === "true") {
                return true;
            }
            if (normalized === "false") {
                return false;
            }
        }
    }
    return null;
}

function formatDuration(value) {
    const ms = Number(value);
    if (!Number.isFinite(ms) || ms < 0) {
        return "";
    }
    if (ms >= 60_000 && ms % 60_000 === 0) {
        return `${ms / 60_000}m`;
    }
    if (ms >= 1_000 && ms % 1_000 === 0) {
        return `${ms / 1_000}s`;
    }
    return `${ms}ms`;
}

function humanizeToken(value) {
    if (typeof value !== "string" || !value.trim()) {
        return "";
    }
    return value
        .trim()
        .replace(/[_-]+/g, " ")
        .replace(/\s+/g, " ");
}
