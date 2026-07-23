import test from "node:test";
import assert from "node:assert/strict";

import { buildTaskSubgoalProgressPlan } from "../../main/resources/web/dialogue/task-subgoal-progress-plan.js";

test("task subgoal progress plan exposes done and unfinished rows", () => {
    const plan = buildTaskSubgoalProgressPlan({
        progress_summary: "1/3 subgoals done; 1 blocked",
        subgoals: ["先补文档", "补测试", "接 UI"],
        subgoal_status: [
            { title: "先补文档", status: "done" },
            { title: "补测试", status: "blocked" },
            { title: "接 UI", status: "in_progress" }
        ]
    });

    assert.equal(plan.summary, "1/3 subgoals done; 1 blocked");
    assert.equal(plan.total, 3);
    assert.equal(plan.doneCount, 1);
    assert.equal(plan.openCount, 2);
    assert.deepEqual(plan.doneTitles, ["先补文档"]);
    assert.deepEqual(plan.openTitles, ["补测试", "接 UI"]);
    assert.deepEqual(plan.rows.map((row) => row.label), ["目标进度", "已完成子目标", "未完成子目标"]);
    assert.equal(plan.rows.find((row) => row.label === "已完成子目标").title, "先补文档");
    assert.equal(plan.rows.find((row) => row.label === "未完成子目标").title, "补测试、接 UI");
});

test("task subgoal progress plan falls back to pending rows from subgoals", () => {
    const plan = buildTaskSubgoalProgressPlan({
        progress_summary: "0/2 subgoals done",
        subgoals: ["整理需求", "补验收"]
    });

    assert.equal(plan.summary, "0/2 subgoals done");
    assert.equal(plan.doneCount, 0);
    assert.equal(plan.openCount, 2);
    assert.deepEqual(plan.doneTitles, []);
    assert.deepEqual(plan.openTitles, ["整理需求", "补验收"]);
    assert.equal(plan.rows.find((row) => row.label === "未完成子目标").title, "整理需求、补验收");
});

test("task subgoal progress plan can render summary-only progress", () => {
    const plan = buildTaskSubgoalProgressPlan({
        progress_summary: "2/2 subgoals done"
    });

    assert.equal(plan.summary, "2/2 subgoals done");
    assert.equal(plan.total, 0);
    assert.deepEqual(plan.rows, [{ label: "目标进度", title: "2/2 subgoals done" }]);
});