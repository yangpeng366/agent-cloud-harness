import test from "node:test";
import assert from "node:assert/strict";

import { buildOpenEyesUiAssertionPlan } from "../../main/resources/web/console/openeyes-ui-assertion-plan.js";

const invocation = (overrides) => Object.assign({
    id: "tool-1",
    sessionId: "session-ui-assertion",
    taskId: "task-ui-assertion",
    workerId: "openeyes-worker",
    toolName: "openeyes",
    argMap: { subcommand: "windows list" },
    summary: "[dry-run] would click @ (267,257)",
    success: true,
    elapsedMs: 110,
    createdAt: "2026-09-21T20:30:00Z",
    metadata: {
        subcommand: "windows list",
        openeyes_window_count: 5,
        openeyes_first_title: "飞书"
    }
}, overrides);

test("openeyes ui assertion plan reports skipped when no openeyes calls", () => {
    const plan = buildOpenEyesUiAssertionPlan({ toolInvocations: [] });
    assert.equal(plan.status, "skipped");
    assert.equal(plan.tone, "default");
    assert.equal(plan.callCount, 0);
});

test("openeyes ui assertion plan returns pass when expectation matches successful call", () => {
    const plan = buildOpenEyesUiAssertionPlan({
        toolInvocations: [invocation()],
        expectations: ["windows list"]
    });
    assert.equal(plan.status, "pass");
    assert.equal(plan.tone, "done");
    assert.equal(plan.matchedExpectation, "windows list");
    assert.equal(plan.callCount, 1);
    assert.equal(plan.successRate, 1);
    assert.equal(plan.lastSubcommand, "windows list");
});

test("openeyes ui assertion plan reports fail when matched call failed", () => {
    const plan = buildOpenEyesUiAssertionPlan({
        toolInvocations: [invocation({ success: false, summary: "windows list timed out" })],
        expectations: ["windows list"]
    });
    assert.equal(plan.status, "fail");
    assert.equal(plan.tone, "failed");
    assert.equal(plan.lastSuccess, false);
});

test("openeyes ui assertion plan reports fail when call exists but no expectation matched", () => {
    const plan = buildOpenEyesUiAssertionPlan({
        toolInvocations: [
            invocation(),
            invocation({ id: "tool-2", success: false, summary: "click failed" })
        ]
    });
    assert.equal(plan.status, "fail");
    assert.equal(plan.callCount, 2);
    assert.equal(plan.successRate, 0.5);
});

test("openeyes ui assertion plan aggregates elapsed and ignores other tools", () => {
    const plan = buildOpenEyesUiAssertionPlan({
        toolInvocations: [
            invocation({ id: "tool-3", elapsedMs: 50 }),
            Object.assign(invocation({ id: "tool-4", elapsedMs: 250 }), { toolName: "shell" }),
            invocation({ id: "tool-5", elapsedMs: 70 })
        ],
        expectations: ["windows list"]
    });
    assert.equal(plan.callCount, 2);
    assert.equal(plan.totalElapsedMs, 120);
    assert.equal(plan.averageElapsedMs, 60);
});