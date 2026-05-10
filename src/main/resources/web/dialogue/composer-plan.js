export function buildComposerSubmissionPlan(input) {
    const normalized = normalizeComposerPlanInput(input);
    if (normalized.composerMode === "message") {
        return { requestedMode: "message", resolvedMode: "message", reason: "message", reasonLabel: "显式聊天模式" };
    }
    if (normalized.composerMode === "task") {
        return { requestedMode: "task", resolvedMode: "task", reason: "task", reasonLabel: "显式新任务模式" };
    }
    if (normalized.followupParentTaskId) {
        return { requestedMode: "auto", resolvedMode: "followup", reason: "followup", reasonLabel: "已绑定 follow-up parent" };
    }
    if (hasTaskIntentOverrides(normalized)) {
        return {
            requestedMode: "auto",
            resolvedMode: "task",
            reason: "metadata",
            reasonLabel: taskIntentOverrideReason(normalized)
        };
    }
    return { requestedMode: "auto", resolvedMode: "message", reason: "auto_message", reasonLabel: "默认聊天发送" };
}

export function hasTaskIntentOverrides(input) {
    const normalized = normalizeComposerPlanInput(input);
    return Boolean(
        normalized.advancedOpen
        || normalized.taskTitle
        || normalized.taskGoal
        || normalized.taskAssignedWorker
        || normalized.taskModelMode
        || !normalized.taskAutoStart
        || normalized.taskType !== "continuation"
        || normalized.taskPriority !== "high"
    );
}

export function taskIntentOverrideReason(input) {
    const normalized = normalizeComposerPlanInput(input);
    if (normalized.advancedOpen) {
        return "已展开高级参数";
    }
    if (normalized.taskTitle || normalized.taskGoal) {
        return "已填写任务标题或目标";
    }
    if (normalized.taskAssignedWorker) {
        return "已指定目标 worker";
    }
    if (normalized.taskModelMode) {
        return "已指定模型模式";
    }
    if (!normalized.taskAutoStart) {
        return "已切到 manual-start";
    }
    if (normalized.taskType !== "continuation") {
        return `任务类型=${normalized.taskType}`;
    }
    if (normalized.taskPriority !== "high") {
        return `优先级=${normalized.taskPriority}`;
    }
    return "当前输入更像新任务";
}

function normalizeComposerPlanInput(input) {
    const source = input || {};
    return {
        composerMode: normalizeMode(source.composerMode),
        followupParentTaskId: normalizeText(source.followupParentTaskId),
        advancedOpen: Boolean(source.advancedOpen),
        taskTitle: normalizeText(source.taskTitle),
        taskGoal: normalizeText(source.taskGoal),
        taskAssignedWorker: normalizeText(source.taskAssignedWorker),
        taskModelMode: normalizeText(source.taskModelMode),
        taskAutoStart: source.taskAutoStart !== false,
        taskType: normalizeText(source.taskType) || "continuation",
        taskPriority: normalizeText(source.taskPriority) || "high"
    };
}

function normalizeMode(value) {
    const normalized = normalizeText(value);
    return normalized || "auto";
}

function normalizeText(value) {
    return typeof value === "string" && value.trim() ? value.trim() : "";
}
