import test from "node:test";
import assert from "node:assert/strict";
import {
    buildComposerSubmissionPlan,
    hasTaskIntentOverrides,
    taskIntentOverrideReason
} from "../../main/resources/web/dialogue/composer-plan.js";

test("auto mode defaults to message when no task-only overrides exist", () => {
    const plan = buildComposerSubmissionPlan({
        composerMode: "auto",
        taskType: "continuation",
        taskPriority: "high",
        taskAutoStart: true
    });

    assert.equal(plan.resolvedMode, "message");
    assert.equal(plan.reasonLabel, "默认聊天发送");
    assert.equal(hasTaskIntentOverrides({ composerMode: "auto" }), false);
});

test("auto mode upgrades to task when advanced parameters are opened", () => {
    const plan = buildComposerSubmissionPlan({
        composerMode: "auto",
        advancedOpen: true
    });

    assert.equal(plan.resolvedMode, "task");
    assert.equal(plan.reasonLabel, "已展开高级参数");
    assert.equal(taskIntentOverrideReason({ advancedOpen: true }), "已展开高级参数");
});

test("auto mode upgrades to task when task-only fields are customized", () => {
    const plan = buildComposerSubmissionPlan({
        composerMode: "auto",
        taskAssignedWorker: "codex"
    });

    assert.equal(plan.resolvedMode, "task");
    assert.equal(plan.reasonLabel, "已指定目标 worker");
    assert.equal(hasTaskIntentOverrides({ taskAssignedWorker: "codex" }), true);
});

test("auto mode upgrades to task when continue current task is enabled", () => {
    const plan = buildComposerSubmissionPlan({
        composerMode: "auto",
        taskContinueCurrent: true
    });

    assert.equal(plan.resolvedMode, "task");
    assert.equal(plan.reasonLabel, "已切到继续当前任务");
    assert.equal(hasTaskIntentOverrides({ taskContinueCurrent: true }), true);
    assert.equal(taskIntentOverrideReason({ taskContinueCurrent: true }), "已切到继续当前任务");
});

test("follow-up parent always upgrades auto mode to followup", () => {
    const plan = buildComposerSubmissionPlan({
        composerMode: "auto",
        followupParentTaskId: "task_parent_1"
    });

    assert.equal(plan.resolvedMode, "followup");
    assert.equal(plan.reasonLabel, "已绑定 follow-up parent");
});

test("explicit modes still win over auto inference", () => {
    assert.equal(buildComposerSubmissionPlan({ composerMode: "message", advancedOpen: true }).resolvedMode, "message");
    assert.equal(buildComposerSubmissionPlan({ composerMode: "task" }).resolvedMode, "task");
});

test("follow-up is inferred from parent binding instead of explicit mode", () => {
    const plan = buildComposerSubmissionPlan({
        composerMode: "auto",
        followupParentTaskId: "task_parent_2",
        advancedOpen: true
    });

    assert.equal(plan.resolvedMode, "followup");
    assert.equal(plan.reasonLabel, "已绑定 follow-up parent");
});
