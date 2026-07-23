import test from "node:test";
import assert from "node:assert/strict";

import {
    buildConsoleStatusLayerPlan,
    toneForConsoleRunStatus,
    toneForConsoleTaskStatus
} from "../../main/resources/web/console/console-status-tone-plan.js";

test("console task status tone follows task-level lifecycle semantics", () => {
    assert.equal(toneForConsoleTaskStatus("active"), "active");
    assert.equal(toneForConsoleTaskStatus("running"), "active");
    assert.equal(toneForConsoleTaskStatus("waiting_human"), "paused");
    assert.equal(toneForConsoleTaskStatus("active", "human_gate"), "paused");
    assert.equal(toneForConsoleTaskStatus("done"), "done");
    assert.equal(toneForConsoleTaskStatus("failed"), "failed");
    assert.equal(toneForConsoleTaskStatus("unknown"), "default");
});

test("console worker run tone stays separate from task lifecycle tone", () => {
    assert.equal(toneForConsoleRunStatus("running"), "active");
    assert.equal(toneForConsoleRunStatus("idle"), "default");
    assert.equal(toneForConsoleRunStatus("completed"), "done");
    assert.equal(toneForConsoleRunStatus("failed"), "failed");
    assert.equal(toneForConsoleRunStatus("timeout"), "failed");
});

test("console status layer plan keeps active task and failed worker run distinct", () => {
    const plan = buildConsoleStatusLayerPlan({
        taskStatus: "active",
        taskControlNode: "continue",
        runStatus: "failed"
    });

    assert.deepEqual(plan.task, {
        layer: "task",
        status: "active",
        tone: "active"
    });
    assert.deepEqual(plan.workerRun, {
        layer: "worker_run",
        status: "failed",
        tone: "failed"
    });
});

// UI 验收标准 #3: partial task status has its own tone, distinct from done and failed
test("console task status tone maps partial to partial", () => {
    assert.equal(toneForConsoleTaskStatus("partial"), "partial");
    assert.equal(toneForConsoleTaskStatus("partially_done"), "partial");
    assert.notEqual(toneForConsoleTaskStatus("partial"), "done");
    assert.notEqual(toneForConsoleTaskStatus("partial"), "failed");
    assert.notEqual(toneForConsoleTaskStatus("partial"), "active");
});
