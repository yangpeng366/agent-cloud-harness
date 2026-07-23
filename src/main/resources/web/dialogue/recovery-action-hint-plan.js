// Recovery action hint for waiting_human / human_gate display.
// Extracted from dialogue/app.js for testability.

export function recoveryActionHint(failureClass, recoveryStage, waitingReason) {
    // P3: goal_progress_blocked 场景
    if (waitingReason && waitingReason.includes("subgoal blocked")) {
        return "子目标被阻塞，请解除阻塞或调整子目标";
    }
    if (firstNonBlank(recoveryStage) !== "等待人工确认") {
        return "";
    }
    switch (firstNonBlank(failureClass)) {
        case "环境阻塞":
            return "先修环境后继续";
        case "部分结果待确认":
            return "先复核已有结果";
        default:
            return "";
    }
}

function firstNonBlank(...values) {
    for (const v of values) {
        if (v !== null && v !== undefined && String(v).trim() !== "") {
            return v;
        }
    }
    return null;
}
