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
                provider_event_log_path: "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\events.jsonl",
                provider_stdout_path: "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\stdout.log",
                provider_run_metadata_path: "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\metadata.json",
                provider_prompt_path: "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\prompt.txt"
            }
        }
    });

    assert.equal(facts.providerRunDir, "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1");
    assert.equal(facts.providerLastMessagePath, "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\last_message.md");
    assert.equal(facts.providerEventLogPath, "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\events.jsonl");
    assert.equal(facts.providerStdoutPath, "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\stdout.log");
    assert.equal(facts.providerRunMetadataPath, "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\metadata.json");
    assert.equal(facts.providerPromptPath, "D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\prompt.txt");
    assert.deepEqual(facts.chips, [
        "run: D:\\tmp\\provider-runs\\codex\\task-1\\exec-1",
        "last: D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\last_message.md",
        "events: D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\events.jsonl",
        "stdout: D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\stdout.log",
        "meta: D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\metadata.json",
        "prompt: D:\\tmp\\provider-runs\\codex\\task-1\\exec-1\\prompt.txt"
    ]);
});

test("execution boundary facts accept camelCase provider run file fields on boundary", () => {
    const facts = buildExecutionBoundaryFacts({
        executionBoundary: {
            providerRunDir: "D:\\runs\\exec-2",
            providerStdoutPath: "D:\\runs\\exec-2\\stdout.log",
            providerRunMetadataPath: "D:\\runs\\exec-2\\metadata.json",
            providerPromptPath: "D:\\runs\\exec-2\\prompt.txt"
        }
    });

    assert.equal(facts.providerRunDir, "D:\\runs\\exec-2");
    assert.equal(facts.providerStdoutPath, "D:\\runs\\exec-2\\stdout.log");
    assert.equal(facts.providerRunMetadataPath, "D:\\runs\\exec-2\\metadata.json");
    assert.equal(facts.providerPromptPath, "D:\\runs\\exec-2\\prompt.txt");
    assert.deepEqual(facts.chips, [
        "run: D:\\runs\\exec-2",
        "stdout: D:\\runs\\exec-2\\stdout.log",
        "meta: D:\\runs\\exec-2\\metadata.json",
        "prompt: D:\\runs\\exec-2\\prompt.txt"
    ]);
});
