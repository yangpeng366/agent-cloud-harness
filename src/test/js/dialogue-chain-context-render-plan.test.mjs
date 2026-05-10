import test from "node:test";
import assert from "node:assert/strict";
import { renderChainContextListHtml } from "../../main/resources/web/dialogue/chain-context-render-plan.js";

test("chain context render plan keeps current task visible and pushes the rest behind drawer", () => {
    const html = renderChainContextListHtml({
        visibleTasks: [{ id: "task_2" }],
        hiddenTasks: [{ id: "task_1" }, { id: "task_3" }],
        drawerSummary: "展开完整迭代链 · 还有 2 个任务"
    }, {
        renderTask: (task) => `<button data-task-id="${task.id}"></button>`,
        escapeHtml: (value) => String(value)
    });

    assert.match(html, /chain-context__list/);
    assert.match(html, /data-task-id="task_2"/);
    assert.match(html, /inline-preview-drawer/);
    assert.match(html, /data-task-id="task_1"/);
    assert.match(html, /data-task-id="task_3"/);
});

test("chain context render plan omits drawer when there are no hidden tasks", () => {
    const html = renderChainContextListHtml({
        visibleTasks: [{ id: "task_1" }],
        hiddenTasks: [],
        drawerSummary: ""
    }, {
        renderTask: (task) => `<button data-task-id="${task.id}"></button>`,
        escapeHtml: (value) => String(value)
    });

    assert.match(html, /data-task-id="task_1"/);
    assert.doesNotMatch(html, /inline-preview-drawer/);
});
