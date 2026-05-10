export function buildExperimentSummaryPlan({
    experimentName = "",
    taskLabel = "",
    summaryChips = [],
    modeSummaries = [],
    currentMode = "",
    currentCase = null,
    supportedModes = [],
    promptModeSummaries = {},
    executionJudgmentPromptModeSummaries = {},
    completionJudgmentPromptModeSummaries = {}
} = {}) {
    const normalizedModeSummaries = Array.isArray(modeSummaries) ? modeSummaries.filter(Boolean) : [];
    const currentModeCard = normalizedModeSummaries.find(
        (modeSummary) => normalizeToken(modeSummary?.model_mode || modeSummary?.modelMode) === normalizeToken(currentMode)
    ) || normalizedModeSummaries[0] || null;
    const comparisonModeCards = normalizedModeSummaries.filter((modeSummary) => modeSummary !== currentModeCard);
    const hasPromptRollout =
        orderedPromptModeKeys(promptModeSummaries).length > 0
        || orderedPromptModeKeys(executionJudgmentPromptModeSummaries).length > 0
        || orderedPromptModeKeys(completionJudgmentPromptModeSummaries).length > 0;
    const caseModes = Array.isArray(supportedModes) && supportedModes.length > 0
        ? supportedModes
        : Object.keys(currentCase?.runs_by_mode || currentCase?.runsByMode || {});
    const hasCaseComparison = caseModes.length > 0;
    const secondaryParts = [];
    if (comparisonModeCards.length > 0) {
        secondaryParts.push(`${comparisonModeCards.length} mode 对比`);
    }
    if (hasPromptRollout) {
        secondaryParts.push("prompt rollout");
    }
    if (hasCaseComparison) {
        secondaryParts.push(`${caseModes.length} case 对照`);
    }
    return {
        experimentName: firstNonBlank(experimentName, "experiment"),
        taskLabel: firstNonBlank(taskLabel, "current task"),
        summaryChips: Array.isArray(summaryChips) ? summaryChips.filter(Boolean) : [],
        currentModeCard,
        comparisonModeCards,
        hasPromptRollout,
        hasCaseComparison,
        caseModes,
        hasDrawer: secondaryParts.length > 0,
        drawerSummary: secondaryParts.length > 0 ? `展开 experiment 对比 · ${secondaryParts.join(" / ")}` : ""
    };
}

function orderedPromptModeKeys(promptModeSummaries) {
    const preferredOrder = ["active_context_only", "mounted_context_shadow", "mounted_context_primary"];
    return Object.keys(promptModeSummaries || {})
        .filter((promptMode) => promptModeSummaries[promptMode] != null)
        .sort((left, right) => {
            const leftIndex = preferredOrder.indexOf(left);
            const rightIndex = preferredOrder.indexOf(right);
            const normalizedLeft = leftIndex === -1 ? Number.MAX_SAFE_INTEGER : leftIndex;
            const normalizedRight = rightIndex === -1 ? Number.MAX_SAFE_INTEGER : rightIndex;
            if (normalizedLeft !== normalizedRight) {
                return normalizedLeft - normalizedRight;
            }
            return left.localeCompare(right);
        });
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
    }
    return "";
}

function normalizeToken(value) {
    return firstNonBlank(value).toLowerCase();
}
