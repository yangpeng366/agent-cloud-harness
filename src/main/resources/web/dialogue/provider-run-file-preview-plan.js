export function formatProviderRunFilePreview(file, fallbackKind = "") {
    const readMode = firstNonBlank(file?.read_mode, file?.readMode);
    const maxLines = firstNonBlank(file?.max_lines, file?.maxLines);
    const offsetBytes = firstNonBlank(file?.offset_bytes, file?.offsetBytes);
    const meta = [
        firstNonBlank(file?.kind, fallbackKind),
        firstNonBlank(file?.path),
        formatBytes(file?.size_bytes ?? file?.sizeBytes),
        formatReadWindow(readMode, maxLines, offsetBytes),
        file?.truncated ? `truncated at ${formatLimit(file?.limit_bytes ?? file?.limitBytes)} bytes` : null
    ].filter(Boolean).join(" · ");
    return [meta, file?.content || ""].filter(Boolean).join("\n\n");
}

export function formatProviderRunFilePreviewError(error, kind = "") {
    const prefix = kind ? `${kind} 读取失败` : "读取失败";
    const message = firstNonBlank(error?.message, error, "unknown error");
    return `${prefix}\n\n${message}`;
}

function formatBytes(value) {
    const number = Number(value);
    return Number.isFinite(number) ? `${number} bytes` : null;
}

function formatLimit(value) {
    const number = Number(value);
    return Number.isFinite(number) ? String(number) : "limit";
}

function formatReadWindow(readMode, maxLines, offsetBytes) {
    if (!readMode) {
        return null;
    }
    const parts = [readMode === "tail" ? "tail window" : "head window"];
    const lines = Number(maxLines);
    if (Number.isFinite(lines) && lines > 0) {
        parts.push(`${lines} lines`);
    }
    const offset = Number(offsetBytes);
    if (Number.isFinite(offset) && offset > 0) {
        parts.push(`offset ${offset}`);
    }
    return parts.join(" · ");
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
        if (value !== null && value !== undefined && typeof value !== "string") {
            return String(value);
        }
    }
    return "";
}
