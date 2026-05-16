import test from "node:test";
import assert from "node:assert/strict";
import { buildComposerSubmitContext } from "../../main/resources/web/dialogue/composer-submit-context-plan.js";

test("submit context locks continue-current to the selected non-terminal task", () => {
    const context = buildComposerSubmitContext({
        planResolvedMode: "task",
        selectedTaskId: "task_current_1",
        selectedTaskStatus: "active",
        continueCurrentChecked: true
    });

    assert.equal(context.taskMode, "task_required");
    assert.equal(context.continueCurrentRequested, true);
    assert.equal(context.continueCurrentTaskId, "task_current_1");
    assert.equal(context.referencedTaskId, "task_current_1");
    assert.equal(context.followupParentTaskId, "");
});

test("submit context does not continue a terminal task", () => {
    const context = buildComposerSubmitContext({
        planResolvedMode: "task",
        selectedTaskId: "task_done_1",
        selectedTaskStatus: "done",
        continueCurrentChecked: true
    });

    assert.equal(context.taskMode, "task_required");
    assert.equal(context.continueCurrentRequested, false);
    assert.equal(context.continueCurrentTaskId, "");
    assert.equal(context.referencedTaskId, "");
});

test("submit context keeps chat-first task_auto attached to current active task", () => {
    const context = buildComposerSubmitContext({
        planResolvedMode: "message",
        selectedTaskId: "task_active_1",
        selectedTaskStatus: "active",
        continueCurrentChecked: false
    });

    assert.equal(context.taskMode, "task_auto");
    assert.equal(context.referencedTaskId, "task_active_1");
    assert.equal(context.continueCurrentTaskId, "");
});

test("submit context keeps follow-up parent separate from continue-current", () => {
    const context = buildComposerSubmitContext({
        planResolvedMode: "followup",
        selectedTaskId: "task_selected_1",
        selectedTaskStatus: "active",
        followupParentTaskId: "task_parent_1",
        continueCurrentChecked: true
    });

    assert.equal(context.taskMode, "task_required");
    assert.equal(context.followupParentTaskId, "task_parent_1");
    assert.equal(context.continueCurrentRequested, false);
    assert.equal(context.continueCurrentTaskId, "");
    assert.equal(context.referencedTaskId, "");
});
