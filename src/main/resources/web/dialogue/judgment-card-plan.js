export function buildJudgmentCardBody(input) {
    const source = input || {};
    const parts = [
        preview(firstNonBlank(source.rationale, source.reason, ""), 220),
        source.executionLine ? `Execution: ${preview(source.executionLine, 120)}` : "",
        summarizeLines(source.metrics, 2),
        summarizeLabeledRows(source.cognitionRows, 2)
    ].filter(Boolean);
    return parts.join("\n");
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
