import test from "node:test";
import assert from "node:assert/strict";
import { reconcileTaskSelection } from "../../main/resources/web/dialogue/task-selection-plan.js";

test("task selection plan keeps selected task when task list still contains it", () => {
    const plan = reconcileTaskSelection({
        tasks: [{ id: "task_1" }, { id: "task_2" }],
        selectedTaskId: "task_1",
        currentSessionId: "session_1",
        liveFlowTaskId: "task_1",
        liveFlowSessionId: "session_1"
    });

    assert.equal(plan.selectedTaskId, "task_1");
    assert.equal(plan.keepLiveFlow, true);
});

test("task selection plan keeps sticky selection from recent facade reply in same session", () => {
    const plan = reconcileTaskSelection({
        tasks: [],
        selectedTaskId: "task_new_1",
        currentSessionId: "session_1",
        facadeReplyTaskId: "task_new_1",
        facadeReplySessionId: "session_1"
    });

    assert.equal(plan.selectedTaskId, "task_new_1");
    assert.equal(plan.keepLiveFlow, false);
});

test("task selection plan clears stale selection when no sticky evidence remains", () => {
    const plan = reconcileTaskSelection({
        tasks: [{ id: "task_1" }, { id: "task_2" }],
        selectedTaskId: "task_missing",
        currentSessionId: "session_1",
        liveFlowTaskId: "task_missing",
        liveFlowSessionId: "session_old"
    });

    assert.equal(plan.selectedTaskId, null);
    assert.equal(plan.keepLiveFlow, false);
});

test("task selection plan clears selection when nothing remains and no sticky evidence exists", () => {
    const plan = reconcileTaskSelection({
        tasks: [],
        selectedTaskId: "task_old",
        currentSessionId: "session_1",
        liveFlowTaskId: "task_old",
        liveFlowSessionId: "session_old"
    });

    assert.equal(plan.selectedTaskId, null);
    assert.equal(plan.keepLiveFlow, false);
});

test("task selection plan keeps session-only view when no task is explicitly selected", () => {
    const plan = reconcileTaskSelection({
        tasks: [{ id: "task_1" }, { id: "task_2" }],
        selectedTaskId: null,
        currentSessionId: "session_1"
    });

    assert.equal(plan.selectedTaskId, null);
    assert.equal(plan.keepLiveFlow, false);
});

test("task selection plan does not let older sticky evidence steal an existing explicit selection", () => {
    const plan = reconcileTaskSelection({
        tasks: [{ id: "task_auto_old" }, { id: "task_manual_new" }],
        selectedTaskId: "task_manual_new",
        currentSessionId: "session_1",
        liveFlowTaskId: "task_auto_old",
        liveFlowSessionId: "session_1",
        facadeReplyTaskId: "task_auto_old",
        facadeReplySessionId: "session_1"
    });

    assert.equal(plan.selectedTaskId, "task_manual_new");
    assert.equal(plan.keepLiveFlow, false);
});
