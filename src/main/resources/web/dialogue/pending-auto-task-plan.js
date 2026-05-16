export function buildPendingAutoTaskTracker(input) {
    const sessionId = normalizeText(input?.sessionId);
    const resolvedMode = normalizeText(input?.resolvedMode);
    const existingTaskId = normalizeText(input?.existingTaskId);
    const currentTaskIds = Array.isArray(input?.currentTaskIds)
        ? input.currentTaskIds.map((value) => normalizeText(value)).filter(Boolean)
        : [];
    const shouldTrack = (resolvedMode === "task" || resolvedMode === "message") && !existingTaskId;
    return {
        shouldTrack,
        sessionId,
        resolvedMode,
        knownTaskIds: currentTaskIds
    };
}

export function resolvePendingAutoTaskCandidate(input) {
    const tracker = input?.tracker || {};
    if (!tracker.shouldTrack) {
        return "";
    }
    const currentSessionId = normalizeText(input?.currentSessionId);
    if (!tracker.sessionId || !currentSessionId || tracker.sessionId !== currentSessionId) {
        return "";
    }
    const tasks = Array.isArray(input?.tasks) ? input.tasks : [];
    const known = new Set(Array.isArray(tracker.knownTaskIds) ? tracker.knownTaskIds : []);
    const newTask = tasks.find((task) => {
        const taskId = normalizeText(task?.id);
        return taskId && !known.has(taskId);
    });
    return normalizeText(newTask?.id);
}

function normalizeText(value) {
    return typeof value === "string" && value.trim() ? value.trim() : "";
}
