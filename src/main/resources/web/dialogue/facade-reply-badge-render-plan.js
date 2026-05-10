export function renderFacadeReplyBadgeHtml(highlight, helpers) {
    const escapeHtml = helpers?.escapeHtml;
    if (typeof escapeHtml !== "function") {
        throw new TypeError("escapeHtml helper is required");
    }
    if (!highlight?.badgeText) {
        return "";
    }
    return `<span class="task-badge" data-tone="${escapeHtml(highlight.badgeTone || "active")}">${escapeHtml(highlight.badgeText || "latest reply")}</span>`;
}
