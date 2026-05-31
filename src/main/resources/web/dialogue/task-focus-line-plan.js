export function buildTaskFocusLineBase(task, flow = {}) {
    const taskStatus = firstNonBlank(task?.status, "active");
    const controlNode = firstNonBlank(task?.control_node, task?.controlNode, "intake");
    const taskMetadata = flow?.task?.metadata || task?.metadata || {};
    const recoveryStage = firstNonBlank(taskMetadata.recovery_stage, taskMetadata.recoveryStage);
    const autoHandoffTarget = firstNonBlank(taskMetadata.auto_handoff_target, taskMetadata.autoHandoffTarget);
    const failureClass = humanizeFailureClass(firstNonBlank(taskMetadata.failure_class, taskMetadata.failureClass));
    const executionStatus = firstNonBlank(
        taskMetadata.execution_status,
        taskMetadata.executionStatus,
        taskMetadata.worker_execution_status,
        taskMetadata.workerExecutionStatus
    );
    const parts = [taskStatus, controlNode];
    if (String(executionStatus).toLowerCase() === "partial_timeout") {
        parts.push("部分结果待确认");
    } else if (recoveryStage === "human_gate_required") {
        parts.push(failureClass ? `human gate · ${failureClass}` : "human gate");
    } else if (taskStatus.toLowerCase() === "active"
        && controlNode === "scheduler"
        && recoveryStage === "auto_handoff_scheduled"
        && autoHandoffTarget) {
        parts.push("handoff queued");
    }
    return parts.filter(Boolean).join(" / ");
}

function humanizeFailureClass(value) {
    switch (firstNonBlank(value)) {
        case "worker_runtime_transient":
            return "临时运行失败";
        case "task_environment_blocked":
            return "环境阻塞";
        case "worker_backend_deterministic":
            return "能力不匹配";
        case "partial_result_or_quality_risk":
            return "部分结果待确认";
        case "worker_execution_failed":
            return "执行失败";
        default:
            return "";
    }
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
