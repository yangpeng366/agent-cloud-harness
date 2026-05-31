import test from "node:test";
import assert from "node:assert/strict";
import { buildMessageRoleSummary } from "../../main/resources/web/dialogue/message-summary-plan.js";

test("message role summary keeps only the strongest lifecycle signal and shorter preview", () => {
    const summary = buildMessageRoleSummary([
        {
            role: "assistant",
            message_type: "task_progress",
            content: "这是一段很长的进度说明，会被收成更短的摘要，避免默认 summary card 占太多高度。",
            created_at: "2026-05-10T10:00:00Z",
            task_id: "task_1",
            metadata: {
                trigger: "continue",
                completion_status: "done",
                route_source: "task_pinned"
            }
        },
        {
            role: "assistant",
            message_type: "task_result",
            content: "最终结果已经产出，当前任务可以结束。",
            created_at: "2026-05-10T10:01:00Z",
            task_id: "task_1",
            metadata: {
                completion_status: "done",
                acceptance_result: "accepted",
                route_source: "task_pinned"
            }
        }
    ], "assistant");

    assert.equal(summary.role, "assistant");
    assert.equal(summary.count, 2);
    assert.equal(summary.latestTaskId, "task_1");
    assert.equal(summary.primarySignal, "完成 · done");
    assert.equal(summary.topTypeLine, "top types · task progress × 1 / task result × 1");
    assert.ok(summary.latestText.length <= 96);
});

test("message role summary surfaces provider diagnostics from latest outcome", () => {
    const summary = buildMessageRoleSummary([
        {
            role: "assistant",
            message_type: "task_progress",
            content: "任务仍在恢复中。",
            created_at: "2026-05-10T10:02:00Z",
            task_id: "task_provider",
            metadata: {
                provider_error: "codex turn completion timed out",
                provider_failure_class: "provider_runtime_transient",
                selected_worker: "kimi",
                route_source: "capability_match"
            }
        }
    ], "assistant");

    assert.equal(summary.primarySignal, "诊断 · codex turn completion timed…");
});

test("message role summary returns null when role has no messages", () => {
    assert.equal(buildMessageRoleSummary([], "system"), null);
});
