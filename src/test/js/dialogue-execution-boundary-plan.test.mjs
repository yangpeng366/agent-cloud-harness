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

test("execution boundary facts expose provider run file chips from metadata", () => {
    const facts = buildExecutionBoundaryFacts({
        execution_boundary: {
            execution_status: "failed",
            metadata: {
                provider_run_dir: "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1",
                provider_last_message_path: "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\last_message.md",
                provider_event_log_path: "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\events.jsonl"
            }
        }
    });

    assert.equal(facts.providerRunDir, "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1");
    assert.equal(facts.providerLastMessagePath, "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\last_message.md");
    assert.equal(facts.providerEventLogPath, "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\events.jsonl");
    assert.deepEqual(facts.chips, [
        "run: D:\\tmp\\provider-runs\\codex\\task-1\\exec-1",
        "last: D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\last_message.md",
        "events: D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\events.jsonl"
    ]);
});
