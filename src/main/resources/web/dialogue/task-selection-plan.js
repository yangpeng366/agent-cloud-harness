export function reconcileTaskSelection(input) {
    const tasks = Array.isArray(input?.tasks) ? input.tasks.filter(Boolean) : [];
    const selectedTaskId = normalizeText(input?.selectedTaskId);
    const currentSessionId = normalizeText(input?.currentSessionId);
    const liveFlowTaskId = normalizeText(input?.liveFlowTaskId);
    const liveFlowSessionId = normalizeText(input?.liveFlowSessionId);
    const facadeReplyTaskId = normalizeText(input?.facadeReplyTaskId);
    const facadeReplySessionId = normalizeText(input?.facadeReplySessionId);
    const hasSelectedTask = selectedTaskId
        && tasks.some((task) => normalizeText(task?.id) === selectedTaskId);
    const stickyTaskId = resolveStickyTaskId({
        selectedTaskId,
        currentSessionId,
        liveFlowTaskId,
        liveFlowSessionId,
        facadeReplyTaskId,
        facadeReplySessionId
    });
    const nextSelectedTaskId = hasSelectedTask
        ? selectedTaskId
        : (stickyTaskId || null);
    const keepLiveFlow = Boolean(liveFlowTaskId) && nextSelectedTaskId === liveFlowTaskId;

    return {
        selectedTaskId: nextSelectedTaskId || null,
        keepLiveFlow
    };
}

function resolveStickyTaskId(input) {
    const selectedTaskId = normalizeText(input?.selectedTaskId);
    if (!selectedTaskId) {
        return "";
    }
    const currentSessionId = normalizeText(input?.currentSessionId);
    const liveFlowTaskId = normalizeText(input?.liveFlowTaskId);
    const liveFlowSessionId = normalizeText(input?.liveFlowSessionId);
    if (selectedTaskId === liveFlowTaskId && (!currentSessionId || currentSessionId === liveFlowSessionId)) {
        return selectedTaskId;
    }
    const facadeReplyTaskId = normalizeText(input?.facadeReplyTaskId);
    const facadeReplySessionId = normalizeText(input?.facadeReplySessionId);
    if (selectedTaskId === facadeReplyTaskId && (!currentSessionId || currentSessionId === facadeReplySessionId)) {
        return selectedTaskId;
    }
    return "";
}

function normalizeText(value) {
    return typeof value === "string" && value.trim() ? value.trim() : "";
}
