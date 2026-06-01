export function buildTaskOverviewPlan(task, context = {}) {
    const experimentMode = context.experimentMode || "ad hoc";
    const toolLabel = humanizeToolLabel(context.toolLabel || "none");
    const workerLabel = context.workerLabel || task?.assigned_worker || task?.assignedWorker || "未分配";
    const focusWorker = context.focusWorker || "";
    const focusLineBase = context.focusLineBase || `${task?.status || "active"} / ${task?.control_node || task?.controlNode || "intake"}`;
    return {
        focusLine: focusWorker ? `${focusLineBase} / 执行方 ${focusWorker}` : focusLineBase,
        cards: [
            { label: "任务 ID", value: task?.id || "n/a" },
            { label: "执行方", value: workerLabel },
            { label: "实验模式", value: experimentMode },
            { label: "工具链", value: toolLabel }
        ]
    };
}

function humanizeToolLabel(value) {
    const text = String(value || "").trim();
    const match = text.match(/^(\d+)\s+calls?$/i);
    if (match) {
        return `${match[1]} 次工具调用`;
    }
    if (!text || text.toLowerCase() === "none") {
        return "无";
    }
    return text;
}
