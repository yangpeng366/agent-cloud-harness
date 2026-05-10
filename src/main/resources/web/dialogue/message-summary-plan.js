import { buildMessageSignalPlan } from "./message-card-plan.js";

export function buildMessageRoleSummary(messages, role, options = {}) {
    const normalizedRole = normalizeMessageRole(role);
    const roleMessages = (messages || []).filter((message) => normalizeMessageRole(message?.role) === normalizedRole);
    if (roleMessages.length === 0) {
        return null;
    }

    const previewLimit = numberOrFallback(options.previewLimit, 96);
    const topTypeLimit = numberOrFallback(options.topTypeLimit, 2);
    const latest = roleMessages[roleMessages.length - 1];
    const typeCounts = new Map();
    roleMessages.forEach((message) => {
        const type = normalizeMessageType(message?.message_type || message?.messageType) || "message";
        typeCounts.set(type, (typeCounts.get(type) || 0) + 1);
    });

    const topTypes = [...typeCounts.entries()]
        .sort((left, right) => right[1] - left[1])
        .slice(0, topTypeLimit)
        .map(([type, count]) => `${formatMessageType(type)} × ${count}`);
    const metadata = latest?.metadata || {};
    const signalTexts = buildMessageSignalPlan(metadata, { compact: true }).texts;
    return {
        role: normalizedRole,
        count: roleMessages.length,
        latestAt: latest?.created_at || latest?.createdAt,
        latestText: preview(latest?.content || "", previewLimit),
        latestTaskId: firstNonBlank(latest?.task_id, latest?.taskId),
        primarySignal: signalTexts[0] || "",
        topTypeLine: topTypes.length > 0 ? `top types · ${topTypes.join(" / ")}` : ""
    };
}

function normalizeMessageRole(role) {
    switch ((role || "").toLowerCase()) {
        case "assistant":
        case "system":
            return role.toLowerCase();
        default:
            return "user";
    }
}

function normalizeMessageType(type) {
    return (type || "").toLowerCase();
}

function formatMessageType(type) {
    switch (normalizeMessageType(type)) {
        case "task_brief":
            return "task brief";
        case "task_followup":
            return "task follow-up";
        case "task_note":
            return "task note";
        case "task_progress":
            return "task progress";
        case "task_result":
            return "task result";
        case "task_receipt":
            return "task receipt";
        case "task_action":
            return "task action";
        case "task_state":
            return "task state";
        case "user_note":
            return "user note";
        default:
            return firstNonBlank(type, "message");
    }
}

function preview(value, limit) {
    const text = typeof value === "string" ? value.trim() : "";
    if (!text) {
        return "";
    }
    if (text.length <= limit) {
        return text;
    }
    return `${text.slice(0, Math.max(0, limit - 1)).trimEnd()}…`;
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
    }
    return "";
}

function numberOrFallback(value, fallback) {
    const number = Number(value);
    return Number.isFinite(number) && number > 0 ? number : fallback;
}
