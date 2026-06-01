export function classifyFacadeReply(input) {
    const normalized = normalizeFacadeReplyInput(input);
    const sessionAck = normalized.replyType === "chat_reply" || normalized.replySource === "session_ack";
    const fallbackMessageMode = normalized.resolvedMode === "message" && !normalized.replyType && !normalized.replySource;
    if (sessionAck || fallbackMessageMode) {
        return {
            category: "message",
            toneClass: "signal--active",
            badgeTone: "active",
            badgeText: "",
            inlineVerb: "已记录",
            toastVerb: normalized.referencedTaskTitle ? "已写入当前任务上下文" : "已记录消息"
        };
    }
    if (normalized.replyType === "task_result" || normalized.replySource === "task_result") {
        return {
            category: "result",
            toneClass: "signal--done",
            badgeTone: "done",
            badgeText: "最新结果",
            inlineVerb: "任务已完成",
            toastVerb: "任务已完成"
        };
    }
    if (normalized.replyType === "task_progress" || normalized.replySource === "task_progress") {
        return {
            category: "progress",
            toneClass: "signal--active",
            badgeTone: "active",
            badgeText: "最新进展",
            inlineVerb: "任务已推进",
            toastVerb: "任务已推进"
        };
    }
    if (normalized.replyType === "worker_round" || normalized.replySource === "worker_round") {
        return {
            category: "worker_round",
            toneClass: "signal--active",
            badgeTone: "active",
            badgeText: "最新回合",
            inlineVerb: "执行回合已更新",
            toastVerb: "执行回合已更新"
        };
    }
    if (normalized.replyType === "task_receipt" || normalized.replySource === "task_receipt") {
        return {
            category: "receipt",
            toneClass: "signal--manual",
            badgeTone: "manual",
            badgeText: "最新回执",
            inlineVerb: "任务已记录",
            toastVerb: "任务已记录"
        };
    }
    if (normalized.resolvedMode === "followup") {
        return {
            category: "task",
            toneClass: "signal--manual",
            badgeTone: "manual",
            badgeText: "",
            inlineVerb: "follow-up 已提交",
            toastVerb: "follow-up 已发布"
        };
    }
    return {
        category: "task",
        toneClass: "signal--manual",
        badgeTone: "manual",
        badgeText: "",
        inlineVerb: "任务已提交",
        toastVerb: "任务已发布"
    };
}

function normalizeFacadeReplyInput(input) {
    const source = input || {};
    return {
        resolvedMode: normalizeText(source.resolvedMode) || "message",
        replyType: normalizeText(source.replyType),
        replySource: normalizeText(source.replySource),
        referencedTaskTitle: normalizeText(source.referencedTaskTitle)
    };
}

function normalizeText(value) {
    return typeof value === "string" && value.trim() ? value.trim() : "";
}
