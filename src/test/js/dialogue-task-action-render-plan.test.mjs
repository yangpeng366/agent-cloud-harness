import test from "node:test";
import assert from "node:assert/strict";
import { renderTaskActionHtml } from "../../main/resources/web/dialogue/task-action-render-plan.js";

test("task action render plan emits one primary action and secondary drawer actions", () => {
    const rendered = renderTaskActionHtml({
        primary: { action: "continue", label: "继续推进" },
        secondary: [
            { action: "pause", label: "暂停" },
            { action: "escalate", label: "转人工处理" },
            { action: "handoff", label: "移交 Worker" }
        ]
    }, {
        renderButton: (item, ghost) => `<button data-action="${item.action}" data-ghost="${ghost}">${item.label}</button>`,
        renderEmpty: (text) => `<p>${text}</p>`
    });

    assert.match(rendered.primaryHtml, /data-action="continue"/);
    assert.match(rendered.secondaryHtml, /data-action="pause"/);
    assert.match(rendered.secondaryHtml, /data-action="escalate"/);
    assert.doesNotMatch(rendered.secondaryHtml, /data-action="handoff"/);
    assert.equal(rendered.drawerHidden, false);
});

test("task action render plan falls back to empty primary for terminal tasks", () => {
    const rendered = renderTaskActionHtml({
        primary: null,
        secondary: [{ action: "handoff", label: "移交 Worker" }]
    }, {
        renderButton: (item) => `<button data-action="${item.action}">${item.label}</button>`,
        renderEmpty: (text) => `<p>${text}</p>`
    });

    assert.match(rendered.primaryHtml, /当前任务已到终态/);
    assert.equal(rendered.secondaryHtml, "");
    assert.equal(rendered.drawerHidden, false);
});
