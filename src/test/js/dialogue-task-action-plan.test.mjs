import test from "node:test";
import assert from "node:assert/strict";
import { buildTaskActionPlan } from "../../main/resources/web/dialogue/task-action-plan.js";

test("task action plan keeps continue as primary for active tasks", () => {
    const plan = buildTaskActionPlan({ status: "active", control_node: "scheduler" });
    assert.equal(plan.primary.action, "continue");
    assert.deepEqual(plan.secondary.map((item) => item.action), ["pause", "escalate", "handoff"]);
});

test("task action plan switches paused tasks to resume-first", () => {
    const plan = buildTaskActionPlan({ status: "paused", control_node: "packet" });
    assert.equal(plan.primary.action, "resume");
    assert.deepEqual(plan.secondary.map((item) => item.action), ["continue", "escalate", "handoff"]);
});

test("task action plan switches human-gate tasks to resume-first", () => {
    const plan = buildTaskActionPlan({ status: "waiting_human", control_node: "human_gate" });
    assert.equal(plan.primary.action, "resume");
    assert.deepEqual(plan.secondary.map((item) => item.action), ["continue", "handoff"]);
});

test("task action plan removes primary action for terminal tasks", () => {
    const plan = buildTaskActionPlan({ status: "done", control_node: "end" });
    assert.equal(plan.primary, null);
    assert.deepEqual(plan.secondary.map((item) => item.action), ["handoff"]);
});
