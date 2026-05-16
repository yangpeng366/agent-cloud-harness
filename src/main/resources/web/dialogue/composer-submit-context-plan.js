export function buildComposerSubmitContext(input) {
    const normalized = normalizeInput(input);
    const continueCurrentRequested = normalized.planResolvedMode === "task"
        && normalized.continueCurrentChecked
        && !normalized.followupParentTaskId
        && Boolean(normalized.selectedTaskId)
        && !normalized.selectedTaskTerminal;
    const followupParentTaskId = normalized.planResolvedMode === "followup"
        ? (normalized.followupParentTaskId || normalized.selectedTaskId)
        : "";
    const referencedTaskId = continueCurrentRequested
        ? normalized.selectedTaskId
        : (normalized.planResolvedMode === "message" && normalized.selectedTaskId && !normalized.selectedTaskTerminal
            ? normalized.selectedTaskId
            : "");
    return {
        taskMode: normalized.planResolvedMode === "message" ? "task_auto" : "task_required",
        continueCurrentRequested,
        continueCurrentTaskId: continueCurrentRequested ? normalized.selectedTaskId : "",
        followupParentTaskId,
        referencedTaskId
    };
}

function normalizeInput(input) {
    const source = input || {};
    return {
        planResolvedMode: normalizeText(source.planResolvedMode) || "message",
        selectedTaskId: normalizeText(source.selectedTaskId),
        selectedTaskStatus: normalizeText(source.selectedTaskStatus),
        followupParentTaskId: normalizeText(source.followupParentTaskId),
        continueCurrentChecked: source.continueCurrentChecked === true,
        selectedTaskTerminal: isTerminalStatus(source.selectedTaskStatus)
    };
}

function isTerminalStatus(value) {
    const status = normalizeText(value).toLowerCase();
    return status === "done" || status === "failed";
}

function normalizeText(value) {
    return typeof value === "string" && value.trim() ? value.trim() : "";
}
