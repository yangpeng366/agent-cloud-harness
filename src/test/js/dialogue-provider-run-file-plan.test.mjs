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
                provider_stdout_path: "D:\\tmp\\provider-runs\\codex\\task_1\\exec_1\\stdout.log",
                provider_run_metadata_path: "D:\\tmp\\provider-runs\\codex\\task_1\\exec_1\\metadata.json",
                provider_prompt_path: "D:\\tmp\\provider-runs\\codex\\task_1\\exec_1\\prompt.txt"
            }
        }
    });

    assert.equal(plan.taskId, "task_1");
    assert.equal(plan.runDir, "D:\\tmp\\provider-runs\\codex\\task_1\\exec_1");
    assert.deepEqual(plan.files.map((file) => file.kind), ["last_message", "events", "stdout", "metadata", "prompt"]);
    assert.equal(plan.files[0].label, "最后输出");
    assert.match(plan.files[0].previewLabel, /^最后输出: .*last_message\.md$/);
    assert.equal(plan.files[0].tail, false);
    assert.equal(plan.files[0].stream, false);
    assert.equal(plan.files[0].query, "");
    assert.equal(plan.files.find((file) => file.kind === "events").tail, true);
    assert.equal(plan.files.find((file) => file.kind === "events").stream, true);
    assert.equal(plan.files.find((file) => file.kind === "events").maxLines, 80);
    assert.equal(plan.files.find((file) => file.kind === "events").query, "tail=true&max_lines=80");
    assert.equal(plan.files.find((file) => file.kind === "stdout").tail, true);
    assert.equal(plan.files.find((file) => file.kind === "stdout").stream, true);
    assert.equal(plan.files.find((file) => file.kind === "stdout").query, "tail=true&max_lines=80");
    assert.equal(plan.files.find((file) => file.kind === "metadata").stream, false);
});

test("provider run file plan accepts camelCase execution surface paths", () => {
    const plan = buildProviderRunFilePlan({
        taskId: "task_2",
        runtimeCognitionSurface: {
            execution: {
                providerRunDir: "D:\\tmp\\provider-runs\\codex\\task_2\\exec_1",
                providerLastMessagePath: "D:\\tmp\\provider-runs\\codex\\task_2\\exec_1\\last_message.md",
                providerEventLogPath: "D:\\tmp\\provider-runs\\codex\\task_2\\exec_1\\events.jsonl",
                providerStdoutPath: "D:\\tmp\\provider-runs\\codex\\task_2\\exec_1\\stdout.log",
                providerRunMetadataPath: "D:\\tmp\\provider-runs\\codex\\task_2\\exec_1\\metadata.json",
                providerPromptPath: "D:\\tmp\\provider-runs\\codex\\task_2\\exec_1\\prompt.txt"
            }
        }
    });

    assert.equal(plan.taskId, "task_2");
    assert.equal(plan.runDir, "D:\\tmp\\provider-runs\\codex\\task_2\\exec_1");
    assert.deepEqual(plan.files.map((file) => file.kind), ["last_message", "events", "stdout", "metadata", "prompt"]);
    assert.deepEqual(plan.files.map((file) => file.label), ["最后输出", "事件日志", "标准输出", "运行元数据", "提示词"]);
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
