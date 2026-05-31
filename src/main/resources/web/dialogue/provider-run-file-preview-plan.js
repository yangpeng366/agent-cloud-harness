export function formatProviderRunFilePreview(file, fallbackKind = "") {
    const meta = [
        firstNonBlank(file?.kind, fallbackKind),
        firstNonBlank(file?.path),
        formatBytes(file?.size_bytes ?? file?.sizeBytes),
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
