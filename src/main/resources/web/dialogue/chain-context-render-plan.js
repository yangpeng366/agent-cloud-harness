export function renderChainContextListHtml(chainPlan, helpers) {
    const renderTask = helpers?.renderTask;
    const escapeHtml = helpers?.escapeHtml;
    if (typeof renderTask !== "function" || typeof escapeHtml !== "function") {
        throw new TypeError("renderTask and escapeHtml helpers are required");
    }
    const visibleTasks = Array.isArray(chainPlan?.visibleTasks) ? chainPlan.visibleTasks.filter(Boolean) : [];
    const hiddenTasks = Array.isArray(chainPlan?.hiddenTasks) ? chainPlan.hiddenTasks.filter(Boolean) : [];
    if (visibleTasks.length === 0 && hiddenTasks.length === 0) {
        return "";
    }
    return `
        <div class="chain-context__list">
            ${visibleTasks.map((task) => renderTask(task)).join("")}
        </div>
        ${hiddenTasks.length > 0 ? `
            <details class="inline-preview-drawer">
                <summary class="inline-preview-drawer__summary">${escapeHtml(chainPlan?.drawerSummary || "")}</summary>
                <div class="inline-preview-drawer__body chain-context__list">
                    ${hiddenTasks.map((task) => renderTask(task)).join("")}
                </div>
            </details>
        ` : ""}
    `;
}
