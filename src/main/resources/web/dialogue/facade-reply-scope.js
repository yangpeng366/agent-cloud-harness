export function scopedFacadeReply(reply, currentSessionId, currentTaskId) {
    if (!reply) {
        return null;
    }
    const sessionId = normalizeText(currentSessionId);
    const taskId = normalizeText(currentTaskId);
    if (reply.sessionId && sessionId && reply.sessionId !== sessionId) {
        return null;
    }
    if (reply.taskId && taskId && reply.taskId !== taskId) {
        return null;
    }
    return reply;
}

function normalizeText(value) {
    return typeof value === "string" && value.trim() ? value.trim() : "";
}
