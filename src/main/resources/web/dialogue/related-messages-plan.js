export function buildRelatedMessagesPlan(messages, options = {}) {
    const normalizedMessages = Array.isArray(messages) ? messages.filter(Boolean) : [];
    const previewLimit = positiveIntOrFallback(options.previewLimit, 3);
    const visibleMessages = normalizedMessages.slice(0, previewLimit);
    const hiddenMessages = normalizedMessages.slice(previewLimit);
    return {
        visibleMessages,
        hiddenMessages,
        visibleCount: visibleMessages.length,
        hiddenCount: hiddenMessages.length,
        hasDrawer: hiddenMessages.length > 0,
        drawerSummary: hiddenMessages.length > 0 ? `展开更多关联消息 · 还有 ${hiddenMessages.length} 条` : ""
    };
}

function positiveIntOrFallback(value, fallback) {
    const number = Number(value);
    return Number.isFinite(number) && number > 0 ? Math.floor(number) : fallback;
}
