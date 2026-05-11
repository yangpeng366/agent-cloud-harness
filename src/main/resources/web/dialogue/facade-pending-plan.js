export function buildPendingFacadeReply(input) {
    const sessionId = normalizeText(input?.sessionId);
    const taskId = normalizeText(input?.taskId);
    const resolvedMode = normalizeText(input?.resolvedMode) || "message";
    if (!sessionId || resolvedMode === "message") {
        return null;
    }
    return {
        category: "task",
        toneClass: "signal--active",
        resolvedMode,
        replyType: "task_pending",
        replySource: "pending_submit",
        sessionId,
        taskId,
        toastText: taskId
            ? `已提交任务：${taskId}，正在推进`
            : "已提交任务，正在推进",
        inlineText: taskId
            ? `最近回执：已提交任务，正在推进 ${taskId}。`
            : "最近回执：已提交任务，正在推进。"
    };
}

function normalizeText(value) {
    return typeof value === "string" && value.trim() ? value.trim() : "";
}
