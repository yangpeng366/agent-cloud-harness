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
    assert.deepEqual(plan.cards.map((item) => item.label), ["任务 ID", "执行方", "实验模式", "工具链"]);
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
        focusLineBase: "active / scheduler / 移交已排队",
        experimentMode: "orchestrated",
        toolLabel: "none"
    });

    assert.equal(plan.focusLine, "active / scheduler / 移交已排队 / 执行方 openclaw-native");
    assert.equal(plan.cards[3].value, "无");
});

test("task overview defaults experiment mode to localized ad hoc label", async () => {
    const plan = buildTaskOverviewPlan({ id: "task_default" }, { toolLabel: "none" });

    assert.equal(plan.cards.find((item) => item.label === "实验模式").value, "临时任务");

    const { readFile } = await import("node:fs/promises");
    const overviewSource = await readFile(new URL("../../main/resources/web/dialogue/task-overview-plan.js", import.meta.url), "utf8");
    const appJs = await readFile(new URL("../../main/resources/web/dialogue/app.js", import.meta.url), "utf8");

    assert.doesNotMatch(overviewSource, /"ad hoc"/);
    assert.doesNotMatch(appJs, /"ad hoc"/);
    assert.match(overviewSource, /"临时任务"/);
    assert.match(appJs, /"临时任务"/);
});

test("task overview source avoids raw worker label in visible chrome", async () => {
    const { readFile } = await import("node:fs/promises");
    const appJs = await readFile(new URL("../../main/resources/web/dialogue/app.js", import.meta.url), "utf8");

    assert.doesNotMatch(appJs, /`worker \$\{workerLabel\}`/);
    assert.doesNotMatch(appJs, /`worker · \$\{workerLabel\}`/);
    assert.match(appJs, /`执行方 \$\{workerLabel\}`/);
    assert.match(appJs, /`执行方 · \$\{workerLabel\}`/);
});
