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

test("task action plan switches human-gate tasks to recover-first", () => {
    const plan = buildTaskActionPlan({ status: "waiting_human", control_node: "human_gate" });
    assert.equal(plan.primary.action, "recover");
    assert.deepEqual(plan.secondary.map((item) => item.action), ["resume", "continue", "handoff"]);
});

test("task action plan surfaces manual-window follow-up note from task metadata", () => {
    const plan = buildTaskActionPlan({
        status: "waiting_human",
        control_node: "human_gate",
        metadata: {
            manual_window_required: true,
            recommended_manual_provider: "trae",
            manual_window_candidates: ["trae", "zcode"],
            manual_followup_instruction: "请切到 trae 窗口手动输入当前任务，完成后将结果回填到当前 task 再继续。"
        }
    });

    assert.equal(plan.primary.action, "recover");
    assert.equal(plan.contextNote?.tone, "manual-window");
    assert.match(plan.contextNote?.chip || "", /手动窗口：trae/);
    assert.match(plan.contextNote?.headline || "", /建议切到 trae 手动继续/);
    assert.match(plan.contextNote?.detail || "", /请切到 trae 窗口手动输入当前任务/);
    assert.match(plan.contextNote?.detail || "", /候选：trae、zcode/);
});

test("task action plan keeps failed interrupted tasks recoverable", () => {
    const plan = buildTaskActionPlan({ status: "failed", control_node: "human_gate" });
    assert.equal(plan.primary.action, "recover");
    assert.deepEqual(plan.secondary.map((item) => item.action), ["handoff"]);
});

test("task action plan removes primary action for terminal tasks", () => {
    const plan = buildTaskActionPlan({ status: "done", control_node: "end" });
    assert.equal(plan.primary, null);
    assert.deepEqual(plan.secondary.map((item) => item.action), ["handoff"]);
});
