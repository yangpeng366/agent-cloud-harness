import test from "node:test";
import assert from "node:assert/strict";
import { renderTaskHeaderHtml } from "../../main/resources/web/dialogue/task-header-render-plan.js";

test("task header render plan composes focus line, overview cards, and action areas", () => {
    const rendered = renderTaskHeaderHtml({
        focusLine: "active / scheduler",
        overviewCards: [
            { label: "任务 ID", value: "task_1" },
            { label: "Worker", value: "codex" }
        ],
        primaryAction: { action: "continue", label: "继续推进" },
        secondaryActions: [{ action: "pause", label: "暂停" }]
    }, {
        renderCard: (item) => `<div data-card="${item.label}">${item.value}</div>`,
        renderAction: (item) => item ? `<button data-action="${item.action}">${item.label}</button>` : "<p>empty</p>"
    });

    assert.equal(rendered.focusLine, "active / scheduler");
    assert.match(rendered.overviewHtml, /data-card="任务 ID"/);
    assert.match(rendered.overviewHtml, /data-card="Worker"/);
    assert.match(rendered.primaryActionHtml, /data-action="continue"/);
    assert.match(rendered.secondaryActionHtml, /data-action="pause"/);
    assert.equal(rendered.secondaryHidden, false);
});

test("task header render plan hides secondary drawer when no secondary actions exist", () => {
    const rendered = renderTaskHeaderHtml({
        focusLine: "done / end",
        overviewCards: [{ label: "任务 ID", value: "task_1" }],
        primaryAction: null,
        secondaryActions: []
    }, {
        renderCard: (item) => `<div data-card="${item.label}">${item.value}</div>`,
        renderAction: (item) => item ? `<button data-action="${item.action}">${item.label}</button>` : "<p>empty</p>"
    });

    assert.match(rendered.overviewHtml, /data-card="任务 ID"/);
    assert.match(rendered.primaryActionHtml, /empty/);
    assert.equal(rendered.secondaryActionHtml, "");
    assert.equal(rendered.secondaryHidden, true);
});
