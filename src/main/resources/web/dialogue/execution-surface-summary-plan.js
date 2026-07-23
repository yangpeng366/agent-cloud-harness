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
        worker ? `执行方 ${worker}` : null,
        status ? humanizeExecutionStatus(status) : null,
        timeoutKind ? humanizeTimeoutKind(timeoutKind) : null,
        activityTimeoutMs !== null ? `活动超时 ${formatDuration(activityTimeoutMs)}` : null,
        maxDurationMs !== null ? `最大时长 ${formatDuration(maxDurationMs)}` : null,
        outputChars !== null && threshold !== null ? `已有输出 ${outputChars}/${threshold} 字符` : null,
        abortReason ? `中断原因 ${humanizeAbortReason(abortReason)}` : null,
        promptMode ? `提示词 ${humanizeToken(promptMode) || promptMode}` : null,
        mountedRendered === true ? "上下文已渲染" : null,
        mountedRendered === false ? "上下文未渲染" : null,
        mountedRenderUsed === true ? "上下文已使用" : null,
        mountedRenderUsed === false ? "上下文未使用" : null,
        mountedInjected === true ? "上下文已注入" : null,
        mountedInjected === false ? "上下文未注入" : null,
        panelCount ? `${panelCount} 个面板` : null,
        nonEmptyPanelCount ? `${nonEmptyPanelCount} 个非空` : null,
        renderedObjectCount !== null || hiddenObjectCount !== null
            ? `对象 ${renderedObjectCount ?? 0}/${hiddenObjectCount ?? 0}`
            : null,
        renderedSelectionTraceCount !== null || hiddenSelectionTraceCount !== null
            ? `选择轨迹 ${renderedSelectionTraceCount ?? 0}/${hiddenSelectionTraceCount ?? 0}`
            : null,
        budgetTruncated === true ? "预算已截断" : null,
        activeCount ? `${activeCount} 条活跃上下文` : null,
        evidenceCount ? `${evidenceCount} 条证据` : null,
        archiveCount ? `${archiveCount} 条归档` : null
    ].filter(Boolean);
    return parts.length > 0 ? { label: "执行回合", value: parts.join(" · ") } : null;
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

function humanizeExecutionStatus(value) {
    switch (String(value || "").trim().toLowerCase()) {
        case "partial_timeout":
            return "部分结果待确认";
        case "timeout":
            return "超时";
        case "failed":
        case "error":
            return "失败";
        case "completed":
        case "done":
        case "success":
            return "完成";
        case "cancelled":
            return "已取消";
        default:
            return humanizeToken(String(value)) || String(value);
    }
}

function humanizeTimeoutKind(value) {
    switch (String(value || "").trim().toLowerCase()) {
        case "max_duration":
            return "达到最大时长";
        case "activity_timeout":
            return "活动超时";
        case "user_interrupted":
            return "用户中断";
        default:
            return humanizeToken(String(value)) || String(value);
    }
}

function humanizeAbortReason(value) {
    switch (String(value || "").trim().toLowerCase()) {
        case "user_interrupted":
            return "用户中断";
        case "max_duration":
            return "达到最大时长";
        case "activity_timeout":
            return "活动超时";
        default:
            return humanizeToken(String(value)) || String(value);
    }
}
