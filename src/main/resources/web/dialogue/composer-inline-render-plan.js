export function renderComposerInlineSignalsHtml(input, helpers) {
    const escapeHtml = helpers?.escapeHtml;
    const preview = helpers?.preview;
    if (typeof escapeHtml !== "function" || typeof preview !== "function") {
        throw new TypeError("escapeHtml and preview helpers are required");
    }
    const sessionClosed = input?.sessionClosed === true;
    const facadeReply = input?.facadeReply || null;
    const plan = input?.plan || { resolvedMode: "message", reasonLabel: "" };
    const task = input?.task || null;
    const followupParent = input?.followupParent || null;
    const lines = [];
    if (sessionClosed) {
        lines.push(`<span class="signal signal--warn">当前 session 已关闭，所以这轮输入不会被发送。</span>`);
    } else if (facadeReply?.inlineText) {
        const toneClass = facadeReply.toneClass ? ` ${escapeHtml(facadeReply.toneClass)}` : "";
        lines.push(`<span class="signal${toneClass}">${escapeHtml(facadeReply.inlineText)}</span>`);
    } else if (plan.resolvedMode === "message") {
        lines.push(`<span class="signal">当前会先按聊天消息写入 session。</span>`);
    } else if (plan.resolvedMode === "followup") {
        lines.push(`<span class="signal">当前是 follow-up 模式，这一轮会直接发布成 follow-up task。</span>`);
    } else {
        lines.push(`<span class="signal">当前会直接发布成新 task。</span>`);
        if (plan.reasonLabel) {
            lines.push(`<span class="signal">触发原因：${escapeHtml(plan.reasonLabel)}</span>`);
        }
    }
    if (followupParent) {
        lines.push(`<span class="signal">follow-up parent：${escapeHtml(preview(followupParent.title || followupParent.id, 28))}</span>`);
    } else if (task && plan.resolvedMode !== "message") {
        lines.push(`<span class="signal">当前选中 task 可作为下一轮 follow-up 起点。</span>`);
    } else if (!task) {
        lines.push(`<span class="signal">未选中 task；当前更接近纯 thread chat。</span>`);
    }
    return lines.join("");
}
