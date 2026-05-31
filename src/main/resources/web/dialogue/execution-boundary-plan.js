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
    const providerStdoutPath = firstNonBlank(
        boundary.provider_stdout_path,
        boundary.providerStdoutPath,
        metadata.provider_stdout_path,
        metadata.providerStdoutPath
    );
    const providerRunMetadataPath = firstNonBlank(
        boundary.provider_run_metadata_path,
        boundary.providerRunMetadataPath,
        metadata.provider_run_metadata_path,
        metadata.providerRunMetadataPath
    );
    const providerPromptPath = firstNonBlank(
        boundary.provider_prompt_path,
        boundary.providerPromptPath,
        metadata.provider_prompt_path,
        metadata.providerPromptPath
    );
    const labelParts = [
        status ? humanizeToken(status) : null,
        toolInvocationCount ? formatCount(toolInvocationCount, "call") : null,
        durationMs !== null ? formatDurationMs(durationMs) : null
    ].filter(Boolean);
    const chips = [
        executionId ? `执行: ${executionId}` : null,
        workerId ? `worker: ${workerId}` : null,
        providerRunDir ? `运行目录: ${providerRunDir}` : null,
        providerLastMessagePath ? `最后输出: ${providerLastMessagePath}` : null,
        providerEventLogPath ? `事件日志: ${providerEventLogPath}` : null,
        providerStdoutPath ? `标准输出: ${providerStdoutPath}` : null,
        providerRunMetadataPath ? `运行元数据: ${providerRunMetadataPath}` : null,
        providerPromptPath ? `提示词: ${providerPromptPath}` : null
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
        providerStdoutPath,
        providerRunMetadataPath,
        providerPromptPath,
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
    switch (text.trim().toLowerCase()) {
        case "tool_running":
            return "工具执行中";
        case "completed":
        case "done":
        case "success":
            return "完成";
        case "failed":
        case "error":
            return "失败";
        case "timeout":
            return "超时";
        case "partial_timeout":
            return "部分结果待确认";
        case "cancelled":
            return "已取消";
        default:
            break;
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
    if (unit === "call") {
        return `${value} 次工具调用`;
    }
    return `${value} ${unit}`;
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
