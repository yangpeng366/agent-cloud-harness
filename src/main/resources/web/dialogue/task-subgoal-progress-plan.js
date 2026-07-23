const DONE_STATUSES = new Set(["done", "complete", "completed", "accepted"]);
const OPEN_STATUSES = new Set(["pending", "in_progress", "blocked", "waiting_human", "human_gate", "paused", "active", "running"]);

function firstNonBlank(...values) {
    for (const value of values) {
        const text = stringValue(value);
        if (text) {
            return text;
        }
    }
    return "";
}

function stringValue(value) {
    return value == null ? "" : String(value).trim();
}

function hasMeaningfulValue(value) {
    if (value == null) {
        return false;
    }
    if (typeof value === "string") {
        return value.trim() !== "";
    }
    if (Array.isArray(value)) {
        return value.length > 0;
    }
    if (isPlainObject(value)) {
        return Object.keys(value).length > 0;
    }
    return true;
}

function isPlainObject(value) {
    return value != null && typeof value === "object" && !Array.isArray(value);
}

function resolveValue(sources, keys) {
    for (const source of sources) {
        if (!source) {
            continue;
        }
        for (const key of keys) {
            const value = source[key];
            if (hasMeaningfulValue(value)) {
                return value;
            }
        }
    }
    return null;
}

function normalizeStatus(value) {
    return stringValue(value).toLowerCase();
}

function isDoneStatus(status) {
    return DONE_STATUSES.has(normalizeStatus(status));
}

function isOpenStatus(status) {
    const normalized = normalizeStatus(status);
    return normalized !== "" && !DONE_STATUSES.has(normalized) && OPEN_STATUSES.has(normalized);
}

function buildProgressSummary(entries) {
    if (!entries.length) {
        return "";
    }
    const total = entries.length;
    const doneCount = entries.filter((entry) => isDoneStatus(entry.status)).length;
    const blockedCount = entries.filter((entry) => {
        const normalized = normalizeStatus(entry.status);
        return normalized === "blocked" || normalized === "waiting_human" || normalized === "human_gate";
    }).length;
    return blockedCount > 0
        ? `${doneCount}/${total} subgoals done; ${blockedCount} blocked`
        : `${doneCount}/${total} subgoals done`;
}

function uniqueTitles(values) {
    const seen = new Set();
    const titles = [];
    for (const value of values) {
        const title = stringValue(value);
        if (!title || seen.has(title)) {
            continue;
        }
        seen.add(title);
        titles.push(title);
    }
    return titles;
}

function formatTitleList(titles, max = 3) {
    const normalized = uniqueTitles(titles);
    if (!normalized.length) {
        return "";
    }
    if (normalized.length <= max) {
        return normalized.join("、");
    }
    return `${normalized.slice(0, max).join("、")} 等 ${normalized.length} 项`;
}

function extractSubgoalTitles(rawSubgoals) {
    const titles = [];
    appendSubgoalTitles(titles, rawSubgoals);
    return uniqueTitles(titles);
}

function appendSubgoalTitles(titles, value) {
    if (value == null) {
        return;
    }
    if (Array.isArray(value)) {
        value.forEach((item) => appendSubgoalTitles(titles, item));
        return;
    }
    if (isPlainObject(value)) {
        const directTitle = firstNonBlank(value.title, value.goal, value.name, value.summary);
        if (directTitle) {
            titles.push(directTitle);
            return;
        }
        for (const [key, nested] of Object.entries(value)) {
            const nestedTitle = isPlainObject(nested)
                ? firstNonBlank(nested.title, nested.goal, nested.name, nested.summary, key)
                : firstNonBlank(key, nested);
            if (nestedTitle) {
                titles.push(nestedTitle);
            }
        }
        return;
    }
    const title = stringValue(value);
    if (title) {
        titles.push(title);
    }
}

function isSingleStatusEntry(value) {
    return isPlainObject(value) && Boolean(firstNonBlank(value.status, value.state, value.title, value.goal, value.name, value.summary));
}

function createEntry(rawValue, fallbackTitle, fallbackStatus, index) {
    const fallbackLabel = stringValue(fallbackTitle) || `子目标 ${index + 1}`;
    if (isPlainObject(rawValue)) {
        return {
            title: firstNonBlank(rawValue.title, rawValue.goal, rawValue.name, rawValue.summary, fallbackLabel),
            status: firstNonBlank(rawValue.status, rawValue.state, fallbackStatus, "pending")
        };
    }
    const status = firstNonBlank(rawValue, fallbackStatus, "pending");
    const maybeTitle = stringValue(rawValue);
    const title = fallbackTitle ? fallbackLabel : (DONE_STATUSES.has(normalizeStatus(maybeTitle)) || OPEN_STATUSES.has(normalizeStatus(maybeTitle))
        ? fallbackLabel
        : maybeTitle || fallbackLabel);
    return { title, status };
}

function normalizeSubgoalEntries(rawSubgoalStatus, rawSubgoals) {
    const fallbackTitles = extractSubgoalTitles(rawSubgoals);
    if (hasMeaningfulValue(rawSubgoalStatus)) {
        if (Array.isArray(rawSubgoalStatus)) {
            return rawSubgoalStatus
                .map((item, index) => createEntry(item, fallbackTitles[index], "pending", index))
                .filter((entry) => stringValue(entry.title));
        }
        if (isSingleStatusEntry(rawSubgoalStatus)) {
            return [createEntry(rawSubgoalStatus, fallbackTitles[0], "pending", 0)];
        }
        if (isPlainObject(rawSubgoalStatus)) {
            return Object.entries(rawSubgoalStatus)
                .map(([key, value], index) => createEntry(value, key || fallbackTitles[index], "pending", index))
                .filter((entry) => stringValue(entry.title));
        }
        return [createEntry(rawSubgoalStatus, fallbackTitles[0], "pending", 0)];
    }
    return fallbackTitles.map((title, index) => ({ title, status: "pending" }));
}

export function buildTaskSubgoalProgressPlan(...sources) {
    const subgoalStatus = resolveValue(sources, ["subgoal_status", "subgoalStatus"]);
    const subgoals = resolveValue(sources, ["subgoals", "subGoals"]);
    const progressSummary = firstNonBlank(
        resolveValue(sources, ["progress_summary", "progressSummary"]),
        ""
    );
    const entries = normalizeSubgoalEntries(subgoalStatus, subgoals);
    if (!entries.length && !progressSummary) {
        return null;
    }
    const doneTitles = uniqueTitles(entries.filter((entry) => isDoneStatus(entry.status)).map((entry) => entry.title));
    const openTitles = uniqueTitles(entries.filter((entry) => isOpenStatus(entry.status)).map((entry) => entry.title));
    const summary = firstNonBlank(progressSummary, buildProgressSummary(entries));
    const rows = [];
    if (summary) {
        rows.push({ label: "目标进度", title: summary });
    }
    if (doneTitles.length) {
        rows.push({ label: "已完成子目标", title: formatTitleList(doneTitles) });
    }
    if (openTitles.length) {
        rows.push({ label: "未完成子目标", title: formatTitleList(openTitles) });
    }
    return {
        summary,
        total: entries.length,
        doneCount: doneTitles.length,
        openCount: openTitles.length,
        doneTitles,
        openTitles,
        rows
    };
}