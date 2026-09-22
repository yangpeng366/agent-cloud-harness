function normalize(value) {
    return String(value == null ? "" : value).trim().toLowerCase();
}

function readJsonField(record, keys) {
    if (!record || typeof record !== "object") {
        return null;
    }
    const metadata = record.metadata && typeof record.metadata === "object" ? record.metadata : {};
    const argMap = record.argMap && typeof record.argMap === "object" ? record.argMap : {};
    for (const key of keys) {
        if (Object.prototype.hasOwnProperty.call(record, key) && record[key] != null && record[key] !== "") {
            return record[key];
        }
        if (Object.prototype.hasOwnProperty.call(metadata, key) && metadata[key] != null && metadata[key] !== "") {
            return metadata[key];
        }
        if (Object.prototype.hasOwnProperty.call(arguments, key) && arguments[key] != null && arguments[key] !== "") {
            return arguments[key];
        }
    }
    return null;
}

function parseNumber(value) {
    if (value === null || value === undefined || value === "") {
        return null;
    }
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
}

export function buildOpenEyesUiAssertionPlan({ task = {}, toolInvocations = [], expectations = null } = {}) {
    const eyesCalls = (Array.isArray(toolInvocations) ? toolInvocations : [])
        .filter((entry) => normalize(entry && entry.toolName) === "openeyes");
    const openEyesCalls = eyesCalls.length;
    const successCalls = eyesCalls.filter((entry) => entry && entry.success === true).length;
    const failureCalls = openEyesCalls - successCalls;
    const elapsedList = eyesCalls
        .map((entry) => parseNumber(entry && entry.elapsedMs))
        .filter((value) => value !== null);
    const totalElapsedMs = elapsedList.reduce((acc, value) => acc + value, 0);
    const lastCall = eyesCalls.length > 0 ? eyesCalls[eyesCalls.length - 1] : null;
    const lastSubcommand = lastCall ? String(readJsonField(lastCall, ["subcommand"]) || "") : "";

    if (openEyesCalls === 0) {
        return {
            tone: "default",
            status: "skipped",
            callCount: 0,
            successRate: null,
            totalElapsedMs: 0,
            averageElapsedMs: null,
            lastSubcommand: "",
            lastSuccess: null,
            headline: "OpenEyes UI assertion skipped",
            detail: "本次任务未触发 OpenEyes worker tool，无需结构化断言。"
        };
    }

    let expectationsList = null;
    if (Array.isArray(expectations)) {
        expectationsList = expectations;
    } else if (expectations && typeof expectations === "object") {
        expectationsList = [expectations];
    } else if (typeof expectations === "string" && expectations.trim()) {
        expectationsList = expectations.split(/\r?\n|,/).map((entry) => entry.trim()).filter(Boolean);
    }

    let matchedExpectation = null;
    if (expectationsList && expectationsList.length > 0) {
        for (const expected of expectationsList) {
            const target = normalize(expected);
            if (!target) {
                continue;
            }
            const matched = eyesCalls.find((entry) => {
                const sub = normalize(readJsonField(entry, ["subcommand"]));
                const summary = normalize(entry.summary);
                const metadata = entry.metadata && typeof entry.metadata === "object" ? entry.metadata : {};
                const firstTitle = normalize(metadata.openeyes_first_title);
                return sub.includes(target) || summary.includes(target) || firstTitle.includes(target);
            });
            if (matched) {
                matchedExpectation = { expectation: expected, matched: true, success: matched.success === true };
                break;
            }
        }
    }

    const successRate = openEyesCalls === 0 ? null : successCalls / openEyesCalls;
    const averageElapsedMs = elapsedList.length === 0 ? null : totalElapsedMs / elapsedList.length;

    if (matchedExpectation) {
        if (matchedExpectation.success) {
            return {
                tone: "done",
                status: "pass",
                callCount: openEyesCalls,
                successRate,
                totalElapsedMs,
                averageElapsedMs,
                lastSubcommand,
                lastSuccess: true,
                matchedExpectation: matchedExpectation.expectation,
                headline: "OpenEyes UI assertion 通过",
                detail: matchedExpectation.expectation + " · 命中 " + lastSubcommand
            };
        }
        return {
            tone: "failed",
            status: "fail",
            callCount: openEyesCalls,
            successRate,
            totalElapsedMs,
            averageElapsedMs,
            lastSubcommand,
            lastSuccess: false,
            matchedExpectation: matchedExpectation.expectation,
            headline: "OpenEyes UI assertion 失败",
            detail: matchedExpectation.expectation + " 调用未成功"
        };
    }

    const lastFailed = failureCalls > 0;
    return {
        tone: lastFailed ? "failed" : "default",
        status: lastFailed ? "fail" : "pass",
        callCount: openEyesCalls,
        successRate,
        totalElapsedMs,
        averageElapsedMs,
        lastSubcommand,
        lastSuccess: lastCall ? lastCall.success === true : null,
        headline: lastFailed ? "OpenEyes UI assertion 部分失败" : "OpenEyes UI assertion 无 expectations 命中",
        detail: "调用 " + openEyesCalls + " 次 · 成功 " + successCalls + " · 失败 " + failureCalls + " · 最近 " + lastSubcommand
    };
}

export function shouldRenderOpenEyesUiAssertion(eyesMcp = {}, selectedTask = null) {
    return selectedTask != null && eyesMcp.available === true && eyesMcp.status === "healthy";
}
