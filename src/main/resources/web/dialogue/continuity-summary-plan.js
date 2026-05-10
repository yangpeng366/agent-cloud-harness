export function buildContinuitySummaryPlan({
    summary = "",
    openQuestions = [],
    nextCandidates = [],
    summaryLimit = 160,
    chipPreviewLimit = 4
} = {}) {
    const chips = [
        ...toChipLines("open", openQuestions),
        ...toChipLines("next", nextCandidates)
    ];
    const normalizedSummary = typeof summary === "string" ? summary.trim() : "";
    const previewText = preview(normalizedSummary, summaryLimit) || "暂无 continuity summary";
    const hiddenChips = chips.slice(chipPreviewLimit);
    const hasLongSummary = normalizedSummary.length > summaryLimit;
    const parts = [];
    if (hiddenChips.length > 0) {
        parts.push(`还有 ${hiddenChips.length} 条 continuity chip`);
    }
    if (hasLongSummary) {
        parts.push("展开完整摘要");
    }
    return {
        previewText,
        visibleChips: chips.slice(0, chipPreviewLimit),
        hiddenChips,
        hasDrawer: parts.length > 0,
        drawerSummary: parts.join(" · ")
    };
}

function toChipLines(prefix, lines) {
    return (lines || [])
        .filter((line) => typeof line === "string" && line.trim())
        .map((line) => `${prefix}: ${line.trim()}`);
}

function preview(value, maxLength) {
    const text = typeof value === "string" ? value.trim() : "";
    if (!text) {
        return "";
    }
    if (text.length <= maxLength) {
        return text;
    }
    return `${text.slice(0, Math.max(0, maxLength - 1)).trimEnd()}…`;
}
