export function buildTaskOverviewPlan(task, context = {}) {
    const experimentMode = context.experimentMode || "ad hoc";
    const toolLabel = context.toolLabel || "none";
    return {
        focusLine: `${task?.status || "active"} / ${task?.control_node || task?.controlNode || "intake"}`,
        cards: [
            { label: "任务 ID", value: task?.id || "n/a" },
            { label: "Worker", value: task?.assigned_worker || task?.assignedWorker || "unassigned" },
            { label: "实验模式", value: experimentMode },
            { label: "Tool chain", value: toolLabel }
        ]
    };
}
