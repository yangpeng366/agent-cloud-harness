import { normalizeFacadeSurface } from "./facade-surface-plan.js";

export function buildChatFacadeRequest(input) {
    const normalized = normalizeRequestInput(input);
    const metadata = {
        task_mode: normalized.taskMode,
        session_id: normalized.sessionId,
        source_surface: "web_dialogue",
        created_via: "dialogue_workspace",
        title: normalized.title || normalized.derivedTitle,
        task_type: normalized.taskType,
        priority: normalized.taskPriority,
        auto_start: normalized.autoStart,
        auto_multi_round: normalized.autoMultiRound
    };
    if (normalized.goal) {
        metadata.goal = normalized.goal;
    }
    if (normalized.assignedWorker) {
        metadata.assigned_worker = normalized.assignedWorker;
    }
    if (normalized.modelMode) {
        metadata.model_mode = normalized.modelMode;
    }
    if (normalized.followupParentTaskId) {
        metadata.parent_task_id = normalized.followupParentTaskId;
        metadata.followup_parent_task_id = normalized.followupParentTaskId;
    }
    if (normalized.referencedTaskId) {
        metadata.task_id = normalized.referencedTaskId;
    } else if (normalized.continueCurrentTaskId) {
        metadata.task_id = normalized.continueCurrentTaskId;
    }
    return {
        model: normalized.facadeModel,
        stream: true,
        messages: [
            {
                role: "user",
                content: normalized.intent
            }
        ],
        metadata
    };
}

export function buildResponsesFacadeRequest(input) {
    const normalized = normalizeRequestInput(input);
    const metadata = {
        task_mode: normalized.taskMode,
        session_id: normalized.sessionId,
        source_surface: "web_dialogue",
        created_via: "dialogue_workspace",
        title: normalized.title || normalized.derivedTitle,
        task_type: normalized.taskType,
        priority: normalized.taskPriority,
        auto_start: normalized.autoStart,
        auto_multi_round: normalized.autoMultiRound
    };
    if (normalized.goal) {
        metadata.goal = normalized.goal;
    }
    if (normalized.assignedWorker) {
        metadata.assigned_worker = normalized.assignedWorker;
    }
    if (normalized.modelMode) {
        metadata.model_mode = normalized.modelMode;
    }
    if (normalized.followupParentTaskId) {
        metadata.parent_task_id = normalized.followupParentTaskId;
        metadata.followup_parent_task_id = normalized.followupParentTaskId;
    }
    if (normalized.referencedTaskId) {
        metadata.task_id = normalized.referencedTaskId;
    } else if (normalized.continueCurrentTaskId) {
        metadata.task_id = normalized.continueCurrentTaskId;
    }
    return {
        model: normalized.facadeModel,
        stream: true,
        input: normalized.intent,
        previous_response_id: null,
        metadata
    };
}

export function buildFacadeRequest(input) {
    const surface = normalizeFacadeSurface(input?.facadeSurface);
    if (surface === "responses") {
        return buildResponsesFacadeRequest(input);
    }
    return buildChatFacadeRequest(input);
}

function normalizeRequestInput(input) {
    const source = input || {};
    return {
        intent: requiredText(source.intent, "intent"),
        sessionId: requiredText(source.sessionId, "sessionId"),
        facadeModel: normalizeText(source.facadeModel) || "agentcloud-default",
        facadeSurface: normalizeFacadeSurface(source.facadeSurface),
        taskMode: normalizeText(source.taskMode) || "task_auto",
        title: normalizeText(source.title),
        derivedTitle: normalizeText(source.derivedTitle) || "untitled",
        goal: normalizeText(source.goal),
        assignedWorker: normalizeText(source.assignedWorker),
        modelMode: normalizeText(source.modelMode),
        followupParentTaskId: normalizeText(source.followupParentTaskId),
        referencedTaskId: normalizeText(source.referencedTaskId),
        continueCurrentTaskId: normalizeText(source.continueCurrentTaskId),
        taskType: normalizeText(source.taskType) || "continuation",
        taskPriority: normalizeText(source.taskPriority) || "high",
        autoStart: source.autoStart !== false,
        autoMultiRound: source.autoMultiRound === true
    };
}

function requiredText(value, field) {
    const text = normalizeText(value);
    if (!text) {
        throw new TypeError(`${field} is required`);
    }
    return text;
}

function normalizeText(value) {
    return typeof value === "string" && value.trim() ? value.trim() : "";
}
