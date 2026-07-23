const TASK_ACTIVE_STATUSES = new Set(["active", "running", "in_progress", "queued", "starting"]);
const TASK_PAUSED_STATUSES = new Set(["paused", "waiting", "waiting_human", "human_gate"]);
const TASK_PARTIAL_STATUSES = new Set(["partial", "partially_done", "partially_complete"]);
const TASK_DONE_STATUSES = new Set(["done", "complete", "completed", "accepted"]);
const TASK_FAILED_STATUSES = new Set(["failed", "error", "cancelled", "canceled"]);

const RUN_ACTIVE_STATUSES = new Set(["running", "active", "queued", "starting", "in_progress"]);
const RUN_DONE_STATUSES = new Set(["completed", "succeeded", "success", "done"]);
const RUN_FAILED_STATUSES = new Set(["failed", "error", "crashed", "timeout", "timed_out", "cancelled", "canceled"]);

function normalize(value) {
    return String(value || "").trim().toLowerCase();
}

export function toneForConsoleTaskStatus(status, controlNode = "") {
    const normalizedStatus = normalize(status);
    const normalizedNode = normalize(controlNode);
    if (normalizedNode === "human_gate") {
        return "paused";
    }
    if (TASK_ACTIVE_STATUSES.has(normalizedStatus)) {
        return "active";
    }
    if (TASK_PAUSED_STATUSES.has(normalizedStatus)) {
        return "paused";
    }
    if (TASK_PARTIAL_STATUSES.has(normalizedStatus)) {
        return "partial";
    }
    if (TASK_DONE_STATUSES.has(normalizedStatus)) {
        return "done";
    }
    if (TASK_FAILED_STATUSES.has(normalizedStatus)) {
        return "failed";
    }
    return "default";
}

export function toneForConsoleRunStatus(status) {
    const normalizedStatus = normalize(status);
    if (RUN_DONE_STATUSES.has(normalizedStatus)) {
        return "done";
    }
    if (RUN_FAILED_STATUSES.has(normalizedStatus)) {
        return "failed";
    }
    if (RUN_ACTIVE_STATUSES.has(normalizedStatus)) {
        return "active";
    }
    return "default";
}

export function buildConsoleStatusLayerPlan({ taskStatus = "", taskControlNode = "", runStatus = "" } = {}) {
    return {
        task: {
            layer: "task",
            status: taskStatus || "active",
            tone: toneForConsoleTaskStatus(taskStatus || "active", taskControlNode)
        },
        workerRun: {
            layer: "worker_run",
            status: runStatus || "idle",
            tone: toneForConsoleRunStatus(runStatus || "idle")
        }
    };
}