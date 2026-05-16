import test from "node:test";
import assert from "node:assert/strict";
import { buildMessageSummaryStackPlan } from "../../main/resources/web/dialogue/message-summary-stack-plan.js";

test("message summary stack plan keeps only the freshest summary as primary", () => {
    const plan = buildMessageSummaryStackPlan([
        {
            role: "assistant",
            count: 3,
            latestAt: "2026-05-10T10:01:00Z",
            primarySignal: "completion · done",
            latestText: "assistant latest"
        },
        {
            role: "system",
            count: 2,
            latestAt: "2026-05-10T10:00:00Z",
            primarySignal: "action · paused",
            latestText: "system latest"
        }
    ]);

    assert.equal(plan.primary.role, "assistant");
    assert.equal(plan.secondary.length, 1);
    assert.equal(plan.secondary[0].role, "system");
    assert.equal(plan.secondary[0].primarySignal, "action · paused");
});

test("message summary stack plan stays empty when there are no summaries", () => {
    const plan = buildMessageSummaryStackPlan([]);

    assert.equal(plan.primary, null);
    assert.deepEqual(plan.secondary, []);
});

test("message summary stack plan sorts epoch-second timestamps correctly", () => {
    const plan = buildMessageSummaryStackPlan([
        {
            role: "assistant",
            count: 1,
            latestAt: 1778640974.6171476,
            primarySignal: "completion · active",
            latestText: "newer"
        },
        {
            role: "system",
            count: 1,
            latestAt: 1778640000.0,
            primarySignal: "action · paused",
            latestText: "older"
        }
    ]);

    assert.equal(plan.primary.role, "assistant");
    assert.equal(plan.primary.latestText, "newer");
});
