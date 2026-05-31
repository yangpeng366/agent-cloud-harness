export function buildWorkerRoundActionPlan(message) {
    const type = normalizeMessageType(message?.message_type || message?.messageType);
    if (type !== "worker_round") {
        return [];
    }
    const taskId = firstNonBlank(message?.task_id, message?.taskId);
    if (!taskId) {
        return [];
    }
    const metadata = message?.metadata && typeof message.metadata === "object" ? message.metadata : {};
    const status = firstNonBlank(
        metadata.execution_status,
        metadata.executionStatus,
        metadata.provider_turn_status,
        metadata.providerTurnStatus
    );
    if (String(status || "").toLowerCase() !== "partial_timeout") {
        return [];
    }
    const worker = firstNonBlank(
        metadata.worker_id,
        metadata.workerId,
        metadata.provider_id,
        metadata.providerId,
        metadata.selected_worker,
        metadata.selectedWorker,
        metadata.assigned_worker,
        metadata.assignedWorker
    );
    const workerLabel = worker ? displayWorker(worker) : "worker";
    return [
        {
            action: "continue-worker-thread",
            label: `继续 ${workerLabel} thread`,
            taskId,
            tone: "primary"
        },
        {
            action: "prepare-worker-handoff",
            label: "手动移交",
            taskId,
            tone: "secondary"
        }
    ];
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
            return value;
        }
    }
    return "";
}

function displayWorker(worker) {
    const value = String(worker || "").trim();
    if (value.toLowerCase() === "codex") {
        return "Codex";
    }
    return value;
}
