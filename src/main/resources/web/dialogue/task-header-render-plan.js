export function renderTaskHeaderHtml(headerPlan, helpers) {
    const renderCard = helpers?.renderCard;
    const renderAction = helpers?.renderAction;
    if (typeof renderCard !== "function" || typeof renderAction !== "function") {
        throw new TypeError("renderCard and renderAction helpers are required");
    }
    const overviewCards = Array.isArray(headerPlan?.overviewCards) ? headerPlan.overviewCards.filter(Boolean) : [];
    const secondaryActions = Array.isArray(headerPlan?.secondaryActions) ? headerPlan.secondaryActions.filter(Boolean) : [];
    return {
        focusLine: headerPlan?.focusLine || "idle",
        overviewHtml: overviewCards.map((item) => renderCard(item)).join(""),
        primaryActionHtml: renderAction(headerPlan?.primaryAction || null),
        secondaryActionHtml: secondaryActions.map((item) => renderAction(item)).join(""),
        secondaryHidden: secondaryActions.length === 0
    };
}
