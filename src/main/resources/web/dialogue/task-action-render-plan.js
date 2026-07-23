export function renderTaskActionHtml(plan, helpers) {
    const renderButton = helpers?.renderButton;
    const renderEmpty = helpers?.renderEmpty;
    if (typeof renderButton !== "function" || typeof renderEmpty !== "function") {
        throw new TypeError("renderButton and renderEmpty helpers are required");
    }
    const primary = plan?.primary || null;
    const secondary = Array.isArray(plan?.secondary) ? plan.secondary.filter(Boolean) : [];
    const noteHtml = renderTaskActionNote(plan?.contextNote);
    const secondaryButtons = secondary
        .filter((item) => item.action !== "handoff")
        .map((item) => renderButton(item, true))
        .join("");
    return {
        noteHtml,
        primaryHtml: primary
            ? renderButton(primary, false)
            : renderEmpty("当前任务已到终态；如需继续处理，可使用移交或新建 follow-up。"),
        secondaryHtml: secondaryButtons,
        drawerHidden: secondary.length === 0
    };
}

function renderTaskActionNote(note) {
    if (!note || typeof note !== "object") {
        return "";
    }
    const chip = firstNonBlank(note.chip);
    const headline = firstNonBlank(note.headline);
    const detail = firstNonBlank(note.detail);
    if (!chip && !headline && !detail) {
        return "";
    }
    const tone = normalizeNoteTone(note.tone);
    return `
        <div class="task-action-note task-action-note--${escapeHtml(tone)}">
            ${chip ? `<div class="task-action-note__chip">${escapeHtml(chip)}</div>` : ""}
            ${headline ? `<strong class="task-action-note__headline">${escapeHtml(headline)}</strong>` : ""}
            ${detail ? `<span class="task-action-note__detail">${escapeHtml(detail)}</span>` : ""}
        </div>
    `.trim();
}

function normalizeNoteTone(value) {
    const text = firstNonBlank(value).toLowerCase();
    if (!text) {
        return "info";
    }
    return text.replace(/[^a-z0-9-]+/g, "-");
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
    }
    return "";
}

function escapeHtml(value) {
    return String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}
