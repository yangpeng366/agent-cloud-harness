export function buildRouteBoxPlan(input) {
    const source = input || {};
    const worker = firstNonBlank(source.selectedWorker, "unassigned");
    const routeSource = firstNonBlank(source.routeSource, "router");
    const routeReason = firstNonBlank(source.routeReason, "");
    const taskType = firstNonBlank(source.taskType, "");
    const candidateWorkers = normalizeTextList(source.candidateWorkers);
    const routeChips = normalizeTextList(source.routeChips);
    const timelineCount = Array.isArray(source.cognitionTimeline) ? source.cognitionTimeline.length : 0;
    const detailGroupCount = [
        taskType ? 1 : 0,
        candidateWorkers.length > 0 ? 1 : 0,
        routeChips.length > 0 ? 1 : 0
    ].reduce((sum, count) => sum + count, 0);

    return {
        worker,
        routeSource,
        routeReason,
        taskType,
        candidateWorkers,
        routeChips,
        cognitionTimeline: Array.isArray(source.cognitionTimeline) ? source.cognitionTimeline : [],
        hasDrawer: detailGroupCount > 0 || timelineCount > 0,
        drawerSummary: buildDrawerSummary(detailGroupCount, timelineCount)
    };
}

function buildDrawerSummary(detailGroupCount, timelineCount) {
    if (detailGroupCount > 0 && timelineCount > 0) {
        return `展开 route 细节 · ${detailGroupCount} 组补充 / ${timelineCount} 条 timeline`;
    }
    if (timelineCount > 0) {
        return `展开 route timeline · ${timelineCount} 条`;
    }
    if (detailGroupCount > 0) {
        return `展开 route 细节 · ${detailGroupCount} 组补充`;
    }
    return "";
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
    }
    return "";
}

function normalizeTextList(values) {
    if (!Array.isArray(values)) {
        return [];
    }
    return values
        .filter((value) => typeof value === "string" && value.trim())
        .map((value) => value.trim());
}
