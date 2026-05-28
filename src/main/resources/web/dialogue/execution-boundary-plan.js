export function buildExecutionBoundaryFacts(flow, tools = []) {
    const boundary = flow?.execution_boundary || flow?.executionBoundary || {};
    const metadata = boundary.metadata || {};
    const status = firstNonBlank(
        boundary.execution_status,
        boundary.executionStatus,
        metadata.execution_status,
        metadata.executionStatus
    );
    const durationMs = numberOrNull(
        boundary.duration_ms,
        boundary.durationMs
    );
    const toolInvocationCount = numericValue(
        boundary.tool_invocation_count,
        boundary.toolInvocationCount
    ) ?? (Array.isArray(tools) ? tools.length : null);
    const workerId = firstNonBlank(
        boundary.worker_id,
        boundary.workerId
    );
    const traceSummary = firstNonBlank(
        boundary.trace_summary,
        boundary.traceSummary
    );
    const executionId = firstNonBlank(
        boundary.execution_id,
        boundary.executionId
    );
    const providerRunDir = firstNonBlank(
        boundary.provider_run_dir,
        boundary.providerRunDir,
        metadata.provider_run_dir,
        metadata.providerRunDir
    );
    const providerLastMessagePath = firstNonBlank(
        boundary.provider_last_message_path,
        boundary.providerLastMessagePath,
        metadata.provider_last_message_path,
        metadata.providerLastMessagePath
    );
    const providerEventLogPath = firstNonBlank(
        boundary.provider_event_log_path,
        boundary.providerEventLogPath,
        metadata.provider_event_log_path,
        metadata.providerEventLogPath
    );
    const labelParts = [
        status ? humanizeToken(status) : null,
        toolInvocationCount ? formatCount(toolInvocationCount, "call") : null,
        durationMs !== null ? formatDurationMs(durationMs) : null
    ].filter(Boolean);
    const chips = [
        executionId ? `exec: ${executionId}` : null,
        workerId ? `worker: ${workerId}` : null,
        providerRunDir ? `run: ${providerRunDir}` : null,
        providerLastMessagePath ? `last: ${providerLastMessagePath}` : null,
        providerEventLogPath ? `events: ${providerEventLogPath}` : null
    ].filter(Boolean);
    return {
        status,
        durationMs,
        toolInvocationCount,
        workerId,
        traceSummary,
        executionId,
        providerRunDir,
        providerLastMessagePath,
        providerEventLogPath,
        label: labelParts.length > 0 ? labelParts.join(" · ") : null,
        chips
    };
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
    }
    return "";
}

function humanizeToken(value) {
    const text = firstNonBlank(value);
    if (!text) {
        return "";
    }
    return text
        .split(/[_\s-]+/)
        .filter(Boolean)
        .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
        .join(" ");
}

function numericValue(...values) {
    for (const value of values) {
        if (typeof value === "number" && Number.isFinite(value)) {
            return value;
        }
        if (typeof value === "string" && value.trim()) {
            const parsed = Number(value);
            if (Number.isFinite(parsed)) {
                return parsed;
            }
        }
    }
    return null;
}

function numberOrNull(...values) {
    const numeric = numericValue(...values);
    return numeric === null ? null : numeric;
}

function formatCount(value, unit) {
    if (!Number.isFinite(value)) {
        return null;
    }
    if (value === 1) {
        return `1 ${unit}`;
    }
    return `${value} ${unit}s`;
}

function formatDurationMs(value) {
    if (!Number.isFinite(value)) {
        return null;
    }
    if (value < 1000) {
        return `${Math.round(value)} ms`;
    }
    const seconds = value / 1000;
    if (seconds < 10) {
        return `${seconds.toFixed(1)} s`;
    }
    return `${Math.round(seconds)} s`;
}
