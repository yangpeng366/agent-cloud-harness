export function buildWorkerRoundArtifactPlan(message, artifactsByTaskId, selectedTaskId) {
    const type = normalizeMessageType(message?.message_type || message?.messageType);
    const taskId = firstNonBlank(message?.task_id, message?.taskId, message?.metadata?.task_id, message?.metadata?.taskId);
    if (type !== "worker_round" || !taskId) {
        return {
            visible: false,
            taskId: "",
            state: "hidden",
            artifacts: [],
            previewArtifacts: [],
            moreCount: 0,
            selected: false
        };
    }

    const cached = artifactsByTaskId instanceof Map
        ? artifactsByTaskId.get(taskId)
        : artifactsByTaskId?.[taskId];
    if (!Array.isArray(cached)) {
        return {
            visible: true,
            taskId,
            state: "loading",
            artifacts: [],
            previewArtifacts: [],
            moreCount: 0,
            selected: false
        };
    }
    if (cached.length === 0) {
        return {
            visible: false,
            taskId,
            state: "empty",
            artifacts: [],
            previewArtifacts: [],
            moreCount: 0,
            selected: false
        };
    }

    const previewArtifacts = cached.slice(0, 3);
    return {
        visible: true,
        taskId,
        state: "ready",
        artifacts: cached,
        previewArtifacts,
        moreCount: Math.max(0, cached.length - previewArtifacts.length),
        selected: taskId === selectedTaskId
    };
}

function normalizeMessageType(value) {
    return String(value || "").trim().toLowerCase();
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
        if (value !== null && value !== undefined && typeof value !== "string") {
            return String(value);
        }
    }
    return "";
}
