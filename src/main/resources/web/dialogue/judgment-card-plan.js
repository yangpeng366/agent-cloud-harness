export function buildJudgmentCardBody(input) {
    const source = input || {};
    const parts = [
        preview(firstNonBlank(source.rationale, source.reason, ""), 220),
        source.decisionRationale ? `Decision: ${preview(source.decisionRationale, 200)}` : "",
        source.progressDetail ? `Progress: ${preview(source.progressDetail, 160)}` : "",
        source.executionLine ? `Execution: ${preview(source.executionLine, 120)}` : "",
        summarizeLines(source.metrics, 2),
        summarizeLabeledRows(source.cognitionRows, 2)
    ].filter(Boolean);
    return parts.join("\n");
}

/**
 * 把 judgment_trace 视图里的长任务收口合同字段映射成 judgment card 输入。
 * decision_rationale / progress_detail / progress_summary 来自 JudgmentTraceView
 * （GET /tasks/{id}/judgment_trace）。同时兼容 snake_case 与 camelCase。
 */
export function mapClosureContractFields(trace) {
    const t = trace || {};
    return {
        decisionRationale: firstNonBlank(t.decision_rationale, t.decisionRationale),
        progressDetail: firstNonBlank(t.progress_detail, t.progressDetail),
        progressSummary: firstNonBlank(t.progress_summary, t.progressSummary)
    };
}

function summarizeLines(values, limit) {
    const lines = Array.isArray(values) ? values.filter(Boolean) : [];
    if (lines.length === 0) {
        return "";
    }
    return lines.slice(0, limit).join(" · ");
}

function summarizeLabeledRows(rows, limit) {
    const values = Array.isArray(rows)
        ? rows
            .filter((row) => row && firstNonBlank(row.label) && firstNonBlank(row.value))
            .map((row) => `${row.label}: ${row.value}`)
        : [];
    if (values.length === 0) {
        return "";
    }
    return values.slice(0, limit).join(" · ");
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
    }
    return "";
}

function preview(value, limit) {
    const text = firstNonBlank(value);
    if (!text) {
        return "";
    }
    if (text.length <= limit) {
        return text;
    }
    return `${text.slice(0, Math.max(0, limit - 1)).trimEnd()}…`;
}
