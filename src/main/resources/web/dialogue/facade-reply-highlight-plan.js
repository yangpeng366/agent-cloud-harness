import { classifyFacadeReply } from "./facade-reply-kind.js";

export function buildFacadeReplyHighlightPlan(messages, scopedReply, currentTaskId = "") {
    const normalizedMessages = Array.isArray(messages) ? messages.filter(Boolean) : [];
    const replyKind = classifyFacadeReply(scopedReply);
    if (!scopedReply || !shouldHighlightScopedReply(scopedReply, replyKind)) {
        return null;
    }

    const preferredTaskId = firstNonBlank(scopedReply.taskId, currentTaskId);
    const candidate = [...normalizedMessages]
        .reverse()
        .find((message) => isMatchingReplyMessage(message, preferredTaskId));
    if (!candidate) {
        return null;
    }

    return {
        messageId: candidate.id,
        badgeText: replyKind.badgeText,
        badgeTone: replyKind.badgeTone
    };
}

function shouldHighlightScopedReply(scopedReply, replyKind) {
    return Boolean(scopedReply) && Boolean(replyKind?.badgeText);
}

function isMatchingReplyMessage(message, preferredTaskId) {
    const role = normalizeToken(message?.role);
    if (role !== "assistant" && role !== "system") {
        return false;
    }
    const messageType = normalizeToken(message?.message_type || message?.messageType);
    if (messageType !== "task_receipt" && messageType !== "task_progress" && messageType !== "task_result") {
        return false;
    }
    if (!preferredTaskId) {
        return true;
    }
    return firstNonBlank(message?.task_id, message?.taskId) === preferredTaskId;
}

function normalizeToken(value) {
    return firstNonBlank(value).toLowerCase();
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
    }
    return "";
}
