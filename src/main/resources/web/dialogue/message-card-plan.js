export function buildMessageSignalPlan(metadata, options = {}) {
    const normalized = normalizeMessageSignalInput(metadata, options);
    const entries = messageSignalEntries(normalized.metadata);
    const visibleEntries = selectVisibleEntries(entries, normalized);
    return {
        entries: visibleEntries,
        texts: visibleEntries
            .map((entry) => signalText(entry.value, entry.label))
            .filter(Boolean)
    };
}

function selectVisibleEntries(entries, normalized) {
    if (normalized.relatedOnly) {
        return entries.slice(0, 4);
    }
    if (normalized.compact) {
        return entries
            .filter((entry) => entry.priority !== "low")
            .slice(0, 3);
    }
    return entries
        .filter((entry) => entry.priority === "high" || entry.priority === "medium")
        .slice(0, 3);
}

function messageSignalEntries(metadata = {}) {
    const route = messageRouteSignal(metadata);
    const tools = messageToolSignal(metadata);
    const modelMode = firstNonBlank(metadata.model_mode, metadata.modelMode);
    const actionLabel = firstNonBlank(metadata.action_label, metadata.actionLabel);
    const preferredWorkerHint = firstNonBlank(
        metadata.preferred_worker_hint,
        metadata.preferredWorkerHint
    );
    const learningHintApplied = booleanValue(
        metadata.learning_hint_applied,
        metadata.learningHintApplied
    );
    return [
        { value: metadata.trigger, tone: "default", label: "trigger", priority: "high" },
        { value: actionLabel, tone: "manual", label: "event", priority: "high" },
        { value: metadata.completion_status || metadata.completionStatus, tone: "done", label: "completion", priority: "high" },
        { value: metadata.acceptance_result || metadata.acceptanceResult, tone: "done", label: "accept", priority: "high" },
        { value: metadata.judgment_action || metadata.judgmentAction, tone: "auto", label: "action", priority: "high" },
        { value: route, tone: "active", label: "route", priority: "medium" },
        { value: tools, tone: "default", label: "tools", priority: "medium" },
        { value: modelMode ? humanizeToken(modelMode) || modelMode : null, tone: "active", label: "mode", priority: "low" },
        { value: messageLearningSignal(preferredWorkerHint, learningHintApplied), tone: "auto", label: "hint", priority: "low" }
    ].filter((entry) => firstNonBlank(entry.value));
}

function normalizeMessageSignalInput(metadata, options) {
    return {
        metadata: metadata || {},
        compact: options?.compact === true,
        relatedOnly: options?.relatedOnly === true
    };
}

function messageRouteSignal(metadata = {}) {
    const worker = firstNonBlank(
        metadata.selected_worker,
        metadata.selectedWorker,
        metadata.assigned_worker,
        metadata.assignedWorker,
        metadata.executor_worker,
        metadata.executorWorker
    );
    const source = firstNonBlank(
        metadata.route_source,
        metadata.routeSource
    );
    if (!worker) {
        return null;
    }
    return source ? `${worker} via ${humanizeToken(source) || source}` : worker;
}

function messageToolSignal(metadata = {}) {
    const traceSummary = firstNonBlank(
        metadata.tool_chain_trace_summary,
        metadata.toolChainTraceSummary
    );
    if (traceSummary) {
        return traceSummary.replace(/_/g, " ");
    }
    const stepCount = numberOrNull(
        metadata.tool_chain_step_count,
        metadata.toolChainStepCount
    );
    const terminationReason = firstNonBlank(
        metadata.tool_chain_termination_reason,
        metadata.toolChainTerminationReason
    );
    const executionMode = firstNonBlank(
        metadata.tool_execution_mode,
        metadata.toolExecutionMode
    );
    const parts = [
        stepCount ? formatCount(stepCount, "step") : null,
        terminationReason ? humanizeToken(terminationReason) || terminationReason : null,
        !terminationReason && executionMode ? humanizeToken(executionMode) || executionMode : null
    ].filter(Boolean);
    return parts.length > 0 ? parts.join(" · ") : null;
}

function messageLearningSignal(preferredWorkerHint, learningHintApplied) {
    if (!preferredWorkerHint && learningHintApplied == null) {
        return null;
    }
    if (preferredWorkerHint && learningHintApplied === true) {
        return `${preferredWorkerHint} applied`;
    }
    if (preferredWorkerHint && learningHintApplied === false) {
        return `${preferredWorkerHint} observed`;
    }
    if (learningHintApplied === true) {
        return "applied";
    }
    if (learningHintApplied === false) {
        return "observed";
    }
    return preferredWorkerHint;
}

function signalText(value, label) {
    const text = firstNonBlank(value);
    return text ? `${label} · ${preview(text, 28)}` : null;
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

function numberOrNull(...values) {
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
            if (value === "true") {
                return true;
            }
            if (value === "false") {
                return false;
            }
        }
    }
    return null;
}

function preview(value, limit) {
    const text = typeof value === "string" ? value.trim() : "";
    if (!text) {
        return "";
    }
    if (text.length <= limit) {
        return text;
    }
    return `${text.slice(0, Math.max(0, limit - 1)).trimEnd()}…`;
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

function formatCount(value, singular) {
    const count = numberOrNull(value);
    if (count == null) {
        return null;
    }
    return `${count} ${singular}${count === 1 ? "" : "s"}`;
}
