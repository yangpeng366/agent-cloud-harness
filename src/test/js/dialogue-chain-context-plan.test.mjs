import test from "node:test";
import assert from "node:assert/strict";
import { buildChainContextPlan } from "../../main/resources/web/dialogue/chain-context-plan.js";

test("chain context plan keeps current task primary and pushes the rest behind drawer", () => {
    const plan = buildChainContextPlan([
        { id: "task_1" },
        { id: "task_2" },
        { id: "task_3" }
    ], "task_2");

    assert.equal(plan.currentTask.id, "task_2");
    assert.equal(plan.previousTask.id, "task_1");
    assert.equal(plan.nextTask.id, "task_3");
    assert.equal(plan.visibleTasks.length, 1);
    assert.equal(plan.hiddenTasks.length, 2);
    assert.equal(plan.hasDrawer, true);
    assert.equal(plan.drawerSummary, "展开完整迭代链 · 还有 2 个任务");
});

test("chain context plan stays flat when there is only one task", () => {
    const plan = buildChainContextPlan([{ id: "task_1" }], "task_1");

    assert.equal(plan.currentTask.id, "task_1");
    assert.equal(plan.previousTask, null);
    assert.equal(plan.nextTask, null);
    assert.equal(plan.hasDrawer, false);
});
