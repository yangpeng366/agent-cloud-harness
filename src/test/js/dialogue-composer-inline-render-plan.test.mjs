import test from "node:test";
import assert from "node:assert/strict";
import { renderComposerInlineSignalsHtml } from "../../main/resources/web/dialogue/composer-inline-render-plan.js";

test("composer inline render plan surfaces facade reply and follow-up hint", () => {
    const html = renderComposerInlineSignalsHtml({
        sessionClosed: false,
        facadeReply: {
            inlineText: "最近回执：任务已推进，当前 active。",
            toneClass: "signal--active"
        },
        plan: { resolvedMode: "task", reasonLabel: "advanced open" },
        task: { id: "task_1", title: "整理方案" },
        followupParent: { id: "task_0", title: "原始任务" }
    }, {
        escapeHtml: (value) => String(value),
        preview: (value) => String(value).slice(0, 28)
    });

    assert.match(html, /signal--active/);
    assert.match(html, /最近回执：任务已推进/);
    assert.match(html, /follow-up parent：原始任务/);
});

test("composer inline render plan surfaces closed-session warning before other hints", () => {
    const html = renderComposerInlineSignalsHtml({
        sessionClosed: true,
        facadeReply: {
            inlineText: "最近回执：任务已推进。",
            toneClass: "signal--active"
        },
        plan: { resolvedMode: "message", reasonLabel: "" },
        task: null,
        followupParent: null
    }, {
        escapeHtml: (value) => String(value),
        preview: (value) => String(value).slice(0, 28)
    });

    assert.match(html, /signal--warn/);
    assert.match(html, /当前 session 已关闭/);
    assert.doesNotMatch(html, /任务已推进/);
    assert.match(html, /未选中 task；当前更接近纯 thread chat/);
});
