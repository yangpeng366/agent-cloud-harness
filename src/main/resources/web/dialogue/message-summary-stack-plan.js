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
    const date = new Date(value || 0);
    return Number.isNaN(date.getTime()) ? 0 : date.getTime();
}
