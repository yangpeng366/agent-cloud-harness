export function buildMessageSummaryStackPlan(summaries) {
    const normalized = Array.isArray(summaries) ? summaries.filter(Boolean) : [];
    if (normalized.length === 0) {
        return {
            primary: null,
            secondary: []
        };
    }
    const ranked = [...normalized].sort(compareByLatestAtDesc);
    return {
        primary: ranked[0] || null,
        secondary: ranked.slice(1).map((summary) => ({
            role: summary.role,
            count: summary.count,
            latestAt: summary.latestAt,
            primarySignal: summary.primarySignal,
            latestText: summary.latestText
        }))
    };
}

function compareByLatestAtDesc(left, right) {
    return toMillis(right?.latestAt) - toMillis(left?.latestAt);
}

function toMillis(value) {
    const date = new Date(normalizeTimestampValue(value) || 0);
    return Number.isNaN(date.getTime()) ? 0 : date.getTime();
}

function normalizeTimestampValue(value) {
    if (value === null || value === undefined || value === "") {
        return value;
    }
    if (typeof value === "number") {
        return normalizeEpochNumber(value);
    }
    if (typeof value === "string") {
        const trimmed = value.trim();
        if (!trimmed) {
            return value;
        }
        const numeric = Number(trimmed);
        if (Number.isFinite(numeric) && /^-?\d+(?:\.\d+)?$/.test(trimmed)) {
            return normalizeEpochNumber(numeric);
        }
        return trimmed;
    }
    return value;
}

function normalizeEpochNumber(value) {
    if (!Number.isFinite(value)) {
        return value;
    }
    return Math.abs(value) < 1e12 ? value * 1000 : value;
}
