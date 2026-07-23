export function buildLegacyControlAuditPlan(input) {
    const source = input || {};
    const legacyControlRouteObserved = booleanValue(
        source.legacyControlRouteObserved,
        source.legacy_control_route_observed
    );
    const requestMethod = firstNonBlank(source.requestMethod, source.request_method, "GET").toUpperCase();
    const requestPath = firstNonBlank(source.requestPath, source.request_path);
    const replacementMethod = firstNonBlank(source.replacementMethod, source.replacement_method, "POST").toUpperCase();
    const latestAction = firstNonBlank(source.latestAction, source.latest_action);
    const summary = firstNonBlank(source.summary);
    const visible = legacyControlRouteObserved === true || Boolean(requestPath || summary);

    if (!visible) {
        return {
            visible: false,
            headline: "",
            detail: "",
            chip: ""
        };
    }

    const headline = requestMethod === "GET"
        ? "检测到历史 GET 控制调用"
        : "检测到历史控制兼容调用";
    const detail = buildLegacyControlDetail({
        requestMethod,
        requestPath,
        replacementMethod,
        latestAction,
        summary
    });

    return {
        visible: true,
        headline,
        detail,
        chip: requestMethod === "GET" ? "历史 GET 控制路由" : "历史控制兼容路由"
    };
}

function buildLegacyControlDetail(input) {
    const requestMethod = firstNonBlank(input?.requestMethod, "GET").toUpperCase();
    const requestPath = firstNonBlank(input?.requestPath);
    const replacementMethod = firstNonBlank(input?.replacementMethod, "POST").toUpperCase();
    const latestAction = firstNonBlank(input?.latestAction);
    const summary = firstNonBlank(input?.summary);
    if (!requestPath) {
        return summary || `最近一次历史控制调用应迁到 ${replacementMethod}。`;
    }
    const actionDetail = latestAction ? `；最近动作：${latestAction}` : "";
    return `最近一次是 ${requestMethod} ${requestPath}；调用方应迁到 ${replacementMethod}${actionDetail}。`;
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
        if (typeof value === "boolean") {
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
