const ACTIONS = {
    continue: { action: "continue", label: "继续推进" },
    pause: { action: "pause", label: "暂停" },
    resume: { action: "resume", label: "恢复" },
    escalate: { action: "escalate", label: "转人工处理" },
    handoff: { action: "handoff", label: "移交 Worker" }
};

export function buildTaskActionPlan(task) {
    const status = String(task?.status || "active").toLowerCase();
    const controlNode = String(task?.control_node || task?.controlNode || "intake").toLowerCase();
    if (status === "paused") {
        return {
            primary: ACTIONS.resume,
            secondary: [ACTIONS.continue, ACTIONS.escalate, ACTIONS.handoff]
        };
    }
    if (status === "waiting_human" || status === "waiting" || controlNode === "human_gate") {
        return {
            primary: ACTIONS.resume,
            secondary: [ACTIONS.continue, ACTIONS.handoff]
        };
    }
    if (status === "done" || status === "failed" || controlNode === "end") {
        return {
            primary: null,
            secondary: [ACTIONS.handoff]
        };
    }
    return {
        primary: ACTIONS.continue,
        secondary: [ACTIONS.pause, ACTIONS.escalate, ACTIONS.handoff]
    };
}
