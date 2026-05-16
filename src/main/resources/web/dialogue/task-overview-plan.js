export function buildTaskOverviewPlan(task, context = {}) {
    const experimentMode = context.experimentMode || "ad hoc";
    const toolLabel = context.toolLabel || "none";
    const workerLabel = context.workerLabel || task?.assigned_worker || task?.assignedWorker || "unassigned";
    const focusWorker = context.focusWorker || "";
    const focusLineBase = context.focusLineBase || `${task?.status || "active"} / ${task?.control_node || task?.controlNode || "intake"}`;
    return {
        focusLine: focusWorker ? `${focusLineBase} / worker ${focusWorker}` : focusLineBase,
        cards: [
            { label: "任务 ID", value: task?.id || "n/a" },
            { label: "Worker", value: workerLabel },
            { label: "实验模式", value: experimentMode },
            { label: "Tool chain", value: toolLabel }
        ]
    };
}
