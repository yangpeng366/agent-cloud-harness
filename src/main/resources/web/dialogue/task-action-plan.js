import { buildFreeFirstRoutePlan } from "./free-first-route-plan.js";

const ACTIONS = {
    continue: { action: "continue", label: "继续推进" },
    pause: { action: "pause", label: "暂停" },
    resume: { action: "resume", label: "恢复" },
    recover: { action: "recover", label: "自动恢复" },
    escalate: { action: "escalate", label: "转人工处理" },
    handoff: { action: "handoff", label: "移交执行方" }
};

export function buildTaskActionPlan(task) {
    const status = String(task?.status || "active").toLowerCase();
    const controlNode = String(task?.control_node || task?.controlNode || "intake").toLowerCase();
    const metadata = task?.metadata && typeof task.metadata === "object" ? task.metadata : {};
    const contextNote = buildTaskActionContextNote(status, controlNode, metadata);
    if (status === "paused") {
        return {
            primary: ACTIONS.resume,
            secondary: [ACTIONS.continue, ACTIONS.escalate, ACTIONS.handoff],
            contextNote
        };
    }
    if (status === "failed") {
        return {
            primary: ACTIONS.recover,
            secondary: [ACTIONS.handoff],
            contextNote
        };
    }
    if (status === "waiting_human" || status === "waiting" || controlNode === "human_gate") {
        return {
            primary: ACTIONS.recover,
            secondary: [ACTIONS.resume, ACTIONS.continue, ACTIONS.handoff],
            contextNote
        };
    }
    if (status === "done" || controlNode === "end") {
        return {
            primary: null,
            secondary: [ACTIONS.handoff],
            contextNote
        };
    }
    return {
        primary: ACTIONS.continue,
        secondary: [ACTIONS.pause, ACTIONS.escalate, ACTIONS.handoff],
        contextNote
    };
}

function buildTaskActionContextNote(status, controlNode, metadata) {
    const waitingForHuman = status === "waiting_human"
        || status === "waiting"
        || controlNode === "human_gate";
    if (!waitingForHuman) {
        return null;
    }
    const manualWindowRequired = booleanValue(
        metadata.manual_window_required,
        metadata.manualWindowRequired
    ) === true;
    if (!manualWindowRequired) {
        return null;
    }
    const freeFirstRoute = buildFreeFirstRoutePlan(metadata);
    const recommendedManualProvider = firstNonBlank(
        metadata.recommended_manual_provider,
        metadata.recommendedManualProvider
    );
    const manualFollowupInstruction = firstNonBlank(
        metadata.manual_followup_instruction,
        metadata.manualFollowupInstruction
    );
    const manualWindowCandidates = normalizeTextList(
        metadata.manual_window_candidates,
        metadata.manualWindowCandidates
    );
    const candidateText = manualWindowCandidates.length > 1
        ? `候选：${manualWindowCandidates.join("、")}。`
        : "";
    return {
        tone: "manual-window",
        chip: recommendedManualProvider
            ? `手动窗口：${recommendedManualProvider}`
            : "手动窗口",
        headline: freeFirstRoute.headline || "自动链路已停下，需要手动窗口继续。",
        detail: [manualFollowupInstruction, candidateText, freeFirstRoute.detail]
            .filter(Boolean)
            .join(" ")
    };
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
    }
    return "";
}

function normalizeTextList(...candidates) {
    for (const values of candidates) {
        if (!Array.isArray(values)) {
            continue;
        }
        return values
            .filter((value) => typeof value === "string" && value.trim())
            .map((value) => value.trim());
    }
    return [];
}

function booleanValue(...values) {
    for (const value of values) {
        if (value === true || value === false) {
            return value;
        }
        if (typeof value === "string") {
            const normalized = value.trim().toLowerCase();
            if (normalized === "true") {
                return true;
            }
            if (normalized === "false") {
                return false;
            }
        }
    }
    return null;
}
