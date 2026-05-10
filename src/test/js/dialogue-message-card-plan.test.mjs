import test from "node:test";
import assert from "node:assert/strict";
import { buildMessageSignalPlan } from "../../main/resources/web/dialogue/message-card-plan.js";

test("default transcript plan prefers lifecycle signals over route and mode noise", () => {
    const plan = buildMessageSignalPlan({
        trigger: "continue",
        action_label: "resume task",
        completion_status: "done",
        selected_worker: "codex",
        route_source: "ready_worker_fallback",
        model_mode: "orchestrated",
        tool_chain_step_count: 3,
        learning_hint_applied: true
    });

    assert.deepEqual(
        plan.entries.map((entry) => entry.label),
        ["trigger", "event", "completion"]
    );
});

test("compact transcript plan can still surface route context when signal count is low", () => {
    const plan = buildMessageSignalPlan({
        selected_worker: "codex",
        route_source: "task_pinned",
        model_mode: "strong_only"
    }, { compact: true });

    assert.deepEqual(
        plan.entries.map((entry) => entry.label),
        ["route"]
    );
});

test("related-message plan keeps richer context including route and tools", () => {
    const plan = buildMessageSignalPlan({
        trigger: "continue",
        selected_worker: "codex",
        route_source: "task_pinned",
        tool_chain_step_count: 2,
        tool_chain_termination_reason: "finished",
        model_mode: "orchestrated"
    }, { relatedOnly: true });

    assert.deepEqual(
        plan.entries.map((entry) => entry.label),
        ["trigger", "route", "tools", "mode"]
    );
});
