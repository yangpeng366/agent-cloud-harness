export function renderMessageSummaryStackHtml(stackPlan, helpers) {
    const primary = stackPlan?.primary || null;
    if (!primary) {
        return "";
    }
    const renderCard = helpers?.renderCard;
    const renderBrief = helpers?.renderBrief;
    if (typeof renderCard !== "function" || typeof renderBrief !== "function") {
        throw new TypeError("renderCard and renderBrief helpers are required");
    }
    return `
        ${renderCard(primary)}
        ${(stackPlan.secondary || []).length > 0 ? `
            <div class="message-summary-briefs">
                ${(stackPlan.secondary || []).map((summary) => renderBrief(summary)).join("")}
            </div>
        ` : ""}
    `;
}
