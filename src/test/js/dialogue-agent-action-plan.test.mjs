import test from "node:test";
import assert from "node:assert/strict";
import { buildAgentActionPlan } from "../../main/resources/web/dialogue/agent-action-plan.js";

test("agent action plan prioritizes approvals, rejected actions, then accepted actions", () => {
    const plan = buildAgentActionPlan([
        { id: "a1", action_type: "CHECKPOINT", status: "accepted", summary: "persist checkpoint" },
        { id: "a2", action_type: "REQUEST_CONTEXT", status: "needs_approval", summary: "need owner input" },
        { id: "a3", action_type: "HANDOFF", status: "rejected", summary: "missing target" }
    ]);

    assert.equal(plan.hasActions, true);
    assert.equal(plan.counts.total, 3);
    assert.equal(plan.counts.accepted, 1);
    assert.equal(plan.counts.rejected, 1);
    assert.equal(plan.counts.needsApproval, 1);
    assert.deepEqual(plan.visible.map((action) => action.id), ["a2", "a3", "a1"]);
    assert.match(plan.summary, /1 个待审批/);
    assert.match(plan.summary, /1 个已拒绝/);
    assert.match(plan.summary, /1 个已接受/);
});

test("agent action plan normalizes camelCase fields", () => {
    const plan = buildAgentActionPlan([
        {
            id: "a4",
            actionType: "WRITE_ARTIFACT",
            status: "accepted",
            riskLevel: "medium",
            requiresApproval: true,
            createdAt: "2026-05-27T03:00:00Z",
            payload: { artifact_type: "worker_output" }
        }
    ]);

    assert.equal(plan.visible[0].actionType, "WRITE_ARTIFACT");
    assert.equal(plan.visible[0].riskLevel, "medium");
    assert.equal(plan.visible[0].requiresApproval, true);
    assert.deepEqual(plan.visible[0].payload, { artifact_type: "worker_output" });
});
