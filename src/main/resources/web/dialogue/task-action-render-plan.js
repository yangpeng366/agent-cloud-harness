export function renderTaskActionHtml(plan, helpers) {
    const renderButton = helpers?.renderButton;
    const renderEmpty = helpers?.renderEmpty;
    if (typeof renderButton !== "function" || typeof renderEmpty !== "function") {
        throw new TypeError("renderButton and renderEmpty helpers are required");
    }
    const primary = plan?.primary || null;
    const secondary = Array.isArray(plan?.secondary) ? plan.secondary.filter(Boolean) : [];
    const secondaryButtons = secondary
        .filter((item) => item.action !== "handoff")
        .map((item) => renderButton(item, true))
        .join("");
    return {
        primaryHtml: primary
            ? renderButton(primary, false)
            : renderEmpty("当前任务已到终态；如需继续处理，可使用移交或新建 follow-up。"),
        secondaryHtml: secondaryButtons,
        drawerHidden: secondary.length === 0
    };
}
