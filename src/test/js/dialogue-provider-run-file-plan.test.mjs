import test from "node:test";
import assert from "node:assert/strict";
import { buildProviderRunFilePlan } from "../../main/resources/web/dialogue/provider-run-file-plan.js";

test("provider run file plan exposes available execution surface files", () => {
    const plan = buildProviderRunFilePlan({
        task: { id: "task_1" },
        runtime_cognition_surface: {
            execution: {
                provider_run_dir: "D:\\tmp\\provider-runs\\codex\\task_1\\exec_1",
                provider_last_message_path: "D:\\tmp\\provider-runs\\codex\\task_1\\exec_1\\last_message.md",
                provider_event_log_path: "D:\\tmp\\provider-runs\\codex\\task_1\\exec_1\\events.jsonl",
                provider_run_metadata_path: "D:\\tmp\\provider-runs\\codex\\task_1\\exec_1\\metadata.json"
            }
        }
    });

    assert.equal(plan.taskId, "task_1");
    assert.equal(plan.runDir, "D:\\tmp\\provider-runs\\codex\\task_1\\exec_1");
    assert.deepEqual(plan.files.map((file) => file.kind), ["last_message", "events", "metadata"]);
    assert.match(plan.files[0].previewLabel, /last_message\.md$/);
});

test("provider run file plan returns no actions without task id", () => {
    const plan = buildProviderRunFilePlan({
        runtime_cognition_surface: {
            execution: {
                provider_last_message_path: "D:\\tmp\\provider-runs\\last_message.md"
            }
        }
    });

    assert.deepEqual(plan.files, []);
});
