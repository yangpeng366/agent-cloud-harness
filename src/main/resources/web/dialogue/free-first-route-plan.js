export function buildFreeFirstRoutePlan(input) {
    const source = input || {};
    const manualWindowRequired = booleanValue(
        source.manualWindowRequired,
        source.manual_window_required
    ) === true;
    const recommendedManualProvider = firstNonBlank(
        source.recommendedManualProvider,
        source.recommended_manual_provider
    );
    const costRouteStage = firstNonBlank(
        source.costRouteStage,
        source.cost_route_stage
    );
    const fallbackReason = firstNonBlank(
        source.fallbackReason,
        source.fallback_reason
    );
    const freeCandidateWorkers = normalizeTextList(
        source.freeCandidateWorkers,
        source.free_candidate_workers
    );
    const paidCandidateWorkers = normalizeTextList(
        source.paidCandidateWorkers,
        source.paid_candidate_workers
    );
    const manualWindowCandidates = normalizeTextList(
        source.manualWindowCandidates,
        source.manual_window_candidates
    );

    if (manualWindowRequired) {
        return {
            visible: true,
            chip: recommendedManualProvider
                ? `手动窗口：${recommendedManualProvider}`
                : "需要手动窗口",
            headline: recommendedManualProvider
                ? `自动链路已停下，建议切到 ${recommendedManualProvider} 手动继续。`
                : "自动链路已停下，需要手动窗口继续。",
            detail: buildManualWindowDetail(manualWindowCandidates, fallbackReason),
            costRouteStage,
            freeCandidateWorkers,
            paidCandidateWorkers,
            manualWindowCandidates
        };
    }

    if (costRouteStage === "paid_auto") {
        return {
            visible: true,
            chip: "已切付费自动链路",
            headline: "免费自动链路不可用，当前已回退到付费 provider。",
            detail: buildPaidFallbackDetail(fallbackReason, freeCandidateWorkers, paidCandidateWorkers),
            costRouteStage,
            freeCandidateWorkers,
            paidCandidateWorkers,
            manualWindowCandidates
        };
    }

    return {
        visible: false,
        chip: "",
        headline: "",
        detail: "",
        costRouteStage,
        freeCandidateWorkers,
        paidCandidateWorkers,
        manualWindowCandidates
    };
}

function buildManualWindowDetail(manualWindowCandidates, fallbackReason) {
    const candidateText = manualWindowCandidates.length > 0
        ? `候选：${manualWindowCandidates.join("、")}。`
        : "";
    const reasonText = humanizeFallbackReason(fallbackReason);
    return [reasonText, candidateText, "完成后把结果回填当前任务，再继续 verify 或 handoff。"]
        .filter(Boolean)
        .join(" ");
}

function buildPaidFallbackDetail(fallbackReason, freeCandidateWorkers, paidCandidateWorkers) {
    const reasonText = humanizeFallbackReason(fallbackReason);
    const freeText = freeCandidateWorkers.length > 0
        ? `免费候选：${freeCandidateWorkers.join("、")}。`
        : "";
    const paidText = paidCandidateWorkers.length > 0
        ? `付费候选：${paidCandidateWorkers.join("、")}。`
        : "";
    return [reasonText, freeText, paidText].filter(Boolean).join(" ");
}

function humanizeFallbackReason(reason) {
    const text = firstNonBlank(reason);
    if (!text) {
        return "";
    }
    if (/quota exhausted/i.test(text)) {
        return "免费 provider 额度已耗尽。";
    }
    if (/manual window required/i.test(text)) {
        return "当前没有可继续的自动 provider。";
    }
    if (/fallback to paid_auto/i.test(text)) {
        return "免费自动 provider 当前不可用，已切到付费自动链路。";
    }
    return text;
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
