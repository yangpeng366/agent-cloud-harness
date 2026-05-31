import test from "node:test";
import assert from "node:assert/strict";
import { buildExecutionSurfaceSummaryPlan } from "../../main/resources/web/dialogue/execution-surface-summary-plan.js";

test("execution surface summary includes partial timeout diagnostics", () => {
    const plan = buildExecutionSurfaceSummaryPlan({
        worker_id: "codex",
        execution_status: "partial_timeout",
        provider_timeout_kind: "max_duration",
        provider_abort_reason: "user_interrupted",
        provider_activity_timeout_ms: 180000,
        provider_turn_max_duration_ms: 900000,
        partial_output_chars: 640,
        partial_timeout_min_output_chars: 200
    });

    assert.equal(plan.label, "execution");
    assert.equal(
        plan.value,
        "worker codex · partial timeout · max duration · activity 3m · max 15m · 640/200 chars · abort user interrupted"
    );
});

test("execution surface summary accepts legacy activity timeout alias", () => {
    const plan = buildExecutionSurfaceSummaryPlan({
        provider_turn_activity_timeout_ms: "45000",
        provider_turn_max_duration_ms: "120000"
    });

    assert.equal(plan.value, "activity 45s · max 2m");
});
