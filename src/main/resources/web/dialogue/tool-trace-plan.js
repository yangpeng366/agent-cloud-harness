export function buildToolTraceStatusLabel(tool) {
    return firstNonBlank(tool?.status, tool?.success ? "succeeded" : "failed") || "unknown";
}

export function buildToolTraceSummary(tool, options = {}) {
    const preview = typeof options.preview === "function" ? options.preview : defaultPreview;
    const executionId = firstNonBlank(tool?.execution_id, tool?.executionId);
    const parts = [
        executionId ? `exec ${executionId}` : null,
        buildToolTraceTouchedPaths(tool),
        firstNonBlank(tool?.result_summary, tool?.resultSummary)
    ].filter(Boolean);
    return preview(parts.join(" · ") || "no summary", 220);
}

export function buildToolTraceTouchedPaths(tool) {
    const paths = tool?.touched_paths || tool?.touchedPaths || [];
    if (!Array.isArray(paths) || paths.length === 0) {
        return null;
    }
    const visible = paths.slice(0, 3).join(", ");
    return `paths ${visible}${paths.length > 3 ? ` +${paths.length - 3}` : ""}`;
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (value === undefined || value === null) {
            continue;
        }
        const text = String(value).trim();
        if (text) {
            return text;
        }
    }
    return "";
}

function defaultPreview(value, max = 220) {
    const text = firstNonBlank(value);
    if (text.length <= max) {
        return text;
    }
    return `${text.slice(0, max - 1)}…`;
}
