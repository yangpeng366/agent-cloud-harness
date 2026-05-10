import { classifyFacadeReply } from "./facade-reply-kind.js";

export function buildFacadeReplyFeedback(input) {
    const normalized = normalizeFacadeReplyInput(input);
    const replyKind = classifyFacadeReply(normalized);
    if (replyKind.category === "message") {
        return {
            category: replyKind.category,
            toneClass: replyKind.toneClass,
            resolvedMode: normalized.resolvedMode,
            replyType: normalized.replyType,
            replySource: normalized.replySource,
            sessionId: normalized.sessionId,
            taskId: normalized.taskId,
            toastText: normalized.referencedTaskTitle
                ? `${replyKind.toastVerb}：${normalized.referencedTaskTitle}`
                : `已记录消息：${preview(normalized.intent, 24)}`,
            inlineText: normalized.referencedTaskTitle
                ? `最近回执：已写入当前任务上下文。`
                : "最近回执：已记录为会话消息。"
        };
    }
    return {
        category: replyKind.category,
        toneClass: replyKind.toneClass,
        resolvedMode: normalized.resolvedMode,
        replyType: normalized.replyType,
        replySource: normalized.replySource,
        sessionId: normalized.sessionId,
        taskId: normalized.taskId,
        toastText: normalized.taskId
            ? `${replyKind.toastVerb}：${normalized.taskId}${statusSuffix(normalized.taskStatus)}`
            : replyKind.inlineVerb,
        inlineText: `最近回执：${replyKind.inlineVerb}${statusLabel(normalized.taskStatus)}。`
    };
}

function normalizeFacadeReplyInput(input) {
    const source = input || {};
    return {
        resolvedMode: normalizeText(source.resolvedMode) || "message",
        replyType: normalizeText(source.replyType),
        replySource: normalizeText(source.replySource),
        sessionId: normalizeText(source.sessionId),
        taskId: normalizeText(source.taskId),
        taskStatus: normalizeText(source.taskStatus),
        intent: normalizeText(source.intent),
        referencedTaskTitle: normalizeText(source.referencedTaskTitle)
    };
}

function statusSuffix(status) {
    const value = normalizeText(status);
    return value ? ` · ${value}` : "";
}

function statusLabel(status) {
    const value = normalizeText(status);
    return value ? `，当前 ${value}` : "";
}

function preview(value, limit) {
    const text = normalizeText(value);
    if (!text) {
        return "";
    }
    if (text.length <= limit) {
        return text;
    }
    return `${text.slice(0, Math.max(0, limit - 1)).trimEnd()}…`;
}

function normalizeText(value) {
    return typeof value === "string" && value.trim() ? value.trim() : "";
}
