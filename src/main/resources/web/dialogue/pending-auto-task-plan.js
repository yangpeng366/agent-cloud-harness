export function buildPendingAutoTaskTracker(input) {
    const sessionId = normalizeText(input?.sessionId);
    const resolvedMode = normalizeText(input?.resolvedMode);
    const existingTaskId = normalizeText(input?.existingTaskId);
    const intent = normalizeText(input?.intent);
    const currentTaskIds = Array.isArray(input?.currentTaskIds)
        ? input.currentTaskIds.map((value) => normalizeText(value)).filter(Boolean)
        : [];
    const shouldTrack = (resolvedMode === "task" || resolvedMode === "message") && !existingTaskId;
    return {
        shouldTrack,
        sessionId,
        resolvedMode,
        intent,
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
    const candidates = tasks.filter((task) => {
        const taskId = normalizeText(task?.id);
        return taskId && !known.has(taskId);
    });
    const expectedIntent = normalizeText(tracker.intent);
    if (expectedIntent) {
        const matchedTask = candidates.find((task) => taskMatchesIntent(task, expectedIntent));
        return normalizeText(matchedTask?.id);
    }
    const newTask = candidates[0];
    return normalizeText(newTask?.id);
}

function taskMatchesIntent(task, expectedIntent) {
    const values = [
        task?.goal,
        task?.title,
        task?.metadata?.intent,
        task?.metadata?.goal
    ].map((value) => normalizeText(value));
    return values.some((value) => value === expectedIntent || value.includes(expectedIntent));
}

function normalizeText(value) {
    return typeof value === "string" && value.trim() ? value.trim() : "";
}
