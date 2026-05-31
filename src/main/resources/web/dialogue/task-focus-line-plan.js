export function buildTaskFocusLineBase(task, flow = {}) {
    const taskStatus = firstNonBlank(task?.status, "active");
    const controlNode = firstNonBlank(task?.control_node, task?.controlNode, "intake");
    const taskMetadata = flow?.task?.metadata || task?.metadata || {};
    const recoveryStage = firstNonBlank(taskMetadata.recovery_stage, taskMetadata.recoveryStage);
    const autoHandoffTarget = firstNonBlank(taskMetadata.auto_handoff_target, taskMetadata.autoHandoffTarget);
    const executionStatus = firstNonBlank(
        taskMetadata.execution_status,
        taskMetadata.executionStatus,
        taskMetadata.worker_execution_status,
        taskMetadata.workerExecutionStatus
    );
    const parts = [taskStatus, controlNode];
    if (String(executionStatus).toLowerCase() === "partial_timeout") {
        parts.push("partial timeout");
    } else if (recoveryStage === "human_gate_required") {
        parts.push("human gate");
    } else if (taskStatus.toLowerCase() === "active"
        && controlNode === "scheduler"
        && recoveryStage === "auto_handoff_scheduled"
        && autoHandoffTarget) {
        parts.push("handoff queued");
    }
    return parts.filter(Boolean).join(" / ");
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
