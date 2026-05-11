import test from "node:test";
import assert from "node:assert/strict";
import { buildExecutionBoundaryFacts } from "../../main/resources/web/dialogue/execution-boundary-plan.js";

test("execution boundary facts keep trace summary and derive compact label", () => {
    const facts = buildExecutionBoundaryFacts({
        execution_boundary: {
            execution_status: "tool_running",
            duration_ms: 1420,
            tool_invocation_count: 2,
            worker_id: "codex",
            execution_id: "exec_1",
            trace_summary: "mounted context rendered and tool chain entered"
        }
    });

    assert.equal(facts.traceSummary, "mounted context rendered and tool chain entered");
    assert.equal(facts.label, "Tool Running · 2 calls · 1.4 s");
    assert.deepEqual(facts.chips, ["exec: exec_1", "worker: codex"]);
});

test("execution boundary facts fall back to tool list size when count is absent", () => {
    const facts = buildExecutionBoundaryFacts({
        execution_boundary: {
            execution_status: "completed"
        }
    }, [{ id: "tool_1" }]);

    assert.equal(facts.toolInvocationCount, 1);
    assert.equal(facts.label, "Completed · 1 call");
});
