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

test("provider diagnostics surface before route in message signals", () => {
    const plan = buildMessageSignalPlan({
        provider_error: "codex turn completion timed out",
        provider_turn_status: "timeout",
        selected_worker: "kimi",
        route_source: "capability_match"
    });

    assert.deepEqual(
        plan.entries.map((entry) => entry.label),
        ["provider", "route"]
    );
    assert.equal(plan.texts[0], "provider · codex turn completion timed…");
});

test("backfilled worker round diagnostics remain visible in transcript signals", () => {
    const plan = buildMessageSignalPlan({
        created_via: "worker_round_backfill_projection",
        provider_id: "codex",
        provider_failure_reason: "codex turn completion timed out",
        provider_timeout_kind: "activity_timeout",
        selected_worker: "codex",
        execution_status: "timeout"
    });

    assert.deepEqual(
        plan.entries.map((entry) => entry.label),
        ["provider", "route"]
    );
    assert.equal(plan.texts[0], "provider · codex turn completion timed…");
});

test("partial timeout diagnostics include output threshold in provider signal", () => {
    const plan = buildMessageSignalPlan({
        execution_status: "partial_timeout",
        provider_timeout_kind: "max_duration",
        partial_output_chars: 640,
        partial_timeout_min_output_chars: 200,
        selected_worker: "codex"
    });

    assert.deepEqual(
        plan.entries.map((entry) => entry.label),
        ["provider", "route"]
    );
    assert.equal(plan.texts[0], "provider · 部分结果待确认 · 达到最大时长 · 已有输出 640…");
    assert.equal(plan.entries[0].value, "部分结果待确认 · 达到最大时长 · 已有输出 640/200 字符");
    assert.equal(plan.entries[0].value.includes("partial timeout"), false);
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
