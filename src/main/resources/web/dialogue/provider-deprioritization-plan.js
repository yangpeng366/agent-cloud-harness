export function buildProviderDeprioritizationPlan(input) {
    const source = input || {};
    const providerDeprioritized = booleanValue(
        source.providerDeprioritized,
        source.provider_deprioritized,
        source.recoveryProviderDeprioritized,
        source.recovery_provider_deprioritized
    );
    const deprioritizedProvider = firstNonBlank(
        source.deprioritizedProvider,
        source.deprioritized_provider,
        source.recoveryDeprioritizedProvider,
        source.recovery_deprioritized_provider
    );
    const reason = firstNonBlank(
        source.deprioritizationReason,
        source.deprioritization_reason,
        source.recoveryDeprioritizationReason,
        source.recovery_deprioritization_reason
    );
    if (providerDeprioritized !== true || !deprioritizedProvider) {
        return {
            providerDeprioritized: false,
            deprioritizedProvider: "",
            reason: "",
            chip: "",
            headline: "",
            detail: ""
        };
    }
    return {
        providerDeprioritized: true,
        deprioritizedProvider,
        reason,
        chip: `recovery避开 ${deprioritizedProvider}`,
        headline: `恢复阶段会优先避开 ${deprioritizedProvider}`,
        detail: humanizeReason(reason)
    };
}

function humanizeReason(reason) {
    if (reason === "recent transient provider failures") {
        return "最近窗口内出现了临时 provider 失败，恢复建议会先尝试其他 provider。";
    }
    return firstNonBlank(reason, "");
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
    }
    return "";
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
