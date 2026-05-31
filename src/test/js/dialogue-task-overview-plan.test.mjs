import test from "node:test";
import assert from "node:assert/strict";
import { buildTaskOverviewPlan } from "../../main/resources/web/dialogue/task-overview-plan.js";

test("task overview plan keeps focus line separate and reduces cards to stable essentials", () => {
    const plan = buildTaskOverviewPlan({
        id: "task_123",
        status: "active",
        control_node: "scheduler",
        assigned_worker: "codex"
    }, {
        experimentMode: "orchestrated",
        toolLabel: "2 calls"
    });

    assert.equal(plan.focusLine, "active / scheduler");
    assert.deepEqual(plan.cards.map((item) => item.label), ["任务 ID", "Worker", "实验模式", "工具链"]);
    assert.equal(plan.cards[1].value, "codex");
    assert.equal(plan.cards[2].value, "orchestrated");
    assert.equal(plan.cards[3].value, "2 次工具调用");
});

test("task overview plan accepts caller-provided focus line base for queued recovery states", () => {
    const plan = buildTaskOverviewPlan({
        id: "task_456",
        status: "active",
        control_node: "scheduler",
        assigned_worker: "openclaw-native"
    }, {
        focusWorker: "openclaw-native",
        focusLineBase: "active / scheduler / handoff queued",
        experimentMode: "orchestrated",
        toolLabel: "none"
    });

    assert.equal(plan.focusLine, "active / scheduler / handoff queued / worker openclaw-native");
    assert.equal(plan.cards[3].value, "无");
});
