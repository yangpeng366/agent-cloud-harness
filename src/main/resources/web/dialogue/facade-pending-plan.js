export function buildPendingFacadeReply(input) {
    const sessionId = normalizeText(input?.sessionId);
    const taskId = normalizeText(input?.taskId);
    const resolvedMode = normalizeText(input?.resolvedMode) || "message";
    const isTaskBoundMessage = resolvedMode === "message" && Boolean(taskId);
    const shouldShowPendingTask = (resolvedMode === "task") || (resolvedMode === "message" && !isTaskBoundMessage);
    if (!sessionId || (!shouldShowPendingTask && !isTaskBoundMessage)) {
        return null;
    }
    if (isTaskBoundMessage) {
        return {
            category: "message",
            toneClass: "signal--active",
            resolvedMode,
            replyType: "chat_reply",
            replySource: "pending_task_note",
            sessionId,
            taskId,
            toastText: `已写入当前任务上下文：${taskId}`,
            inlineText: "最近回执：已写入当前任务上下文。"
        };
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
