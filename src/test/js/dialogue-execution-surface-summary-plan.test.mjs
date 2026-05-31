import test from "node:test";
import assert from "node:assert/strict";
import { buildExecutionSurfaceSummaryPlan } from "../../main/resources/web/dialogue/execution-surface-summary-plan.js";

test("execution surface summary includes partial timeout diagnostics", () => {
    const plan = buildExecutionSurfaceSummaryPlan({
        worker_id: "codex",
        execution_status: "partial_timeout",
        provider_timeout_kind: "max_duration",
        provider_abort_reason: "user_interrupted",
        partial_output_chars: 640,
        partial_timeout_min_output_chars: 200
    });

    assert.equal(plan.label, "execution");
    assert.equal(plan.value, "worker codex · partial timeout · max duration · 640/200 chars · abort user interrupted");
});
