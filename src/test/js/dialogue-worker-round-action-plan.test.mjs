import test from "node:test";
import assert from "node:assert/strict";
import { buildWorkerRoundActionPlan } from "../../main/resources/web/dialogue/worker-round-action-plan.js";

test("worker round partial timeout exposes continue and manual handoff actions", () => {
    const actions = buildWorkerRoundActionPlan({
        message_type: "worker_round",
        id: "msg_round_1",
        task_id: "task_123",
        metadata: {
            worker_id: "codex",
            execution_status: "partial_timeout",
            provider_id: "codex",
            provider_thread_id: "thread_codex_123"
        }
    });

    assert.deepEqual(actions.map((item) => item.action), [
        "continue-worker-thread",
        "prepare-worker-handoff"
    ]);
    assert.equal(actions[0].label, "继续 Codex thread");
    assert.equal(actions[0].taskId, "task_123");
    assert.deepEqual(actions[0].requestBody, {
        continue_mode: "provider_thread",
        provider_id: "codex",
        provider_thread_id: "thread_codex_123",
        resume_provider_session_id: "thread_codex_123",
        source_worker_round_message_id: "msg_round_1"
    });
});

test("worker round completed status keeps transcript actions quiet", () => {
    const actions = buildWorkerRoundActionPlan({
        message_type: "worker_round",
        task_id: "task_123",
        metadata: {
            worker_id: "codex",
            execution_status: "completed"
        }
    });

    assert.deepEqual(actions, []);
});

test("worker round partial timeout can continue from provider session id fallback", () => {
    const actions = buildWorkerRoundActionPlan({
        message_type: "worker_round",
        id: "msg_round_session",
        task_id: "task_456",
        metadata: {
            selected_worker: "kimi",
            execution_status: "partial_timeout",
            provider_id: "kimi",
            provider_session_id: "session_kimi_456"
        }
    });

    assert.deepEqual(actions.map((item) => item.action), [
        "continue-worker-thread",
        "prepare-worker-handoff"
    ]);
    assert.equal(actions[0].label, "继续 kimi thread");
    assert.deepEqual(actions[0].requestBody, {
        continue_mode: "provider_thread",
        provider_id: "kimi",
        provider_thread_id: "session_kimi_456",
        resume_provider_session_id: "session_kimi_456",
        source_worker_round_message_id: "msg_round_session"
    });
});

test("worker round partial timeout preserves explicit resume provider session id", () => {
    const actions = buildWorkerRoundActionPlan({
        messageType: "worker_round",
        id: "msg_round_resume",
        taskId: "task_789",
        metadata: {
            workerId: "copilot",
            executionStatus: "partial_timeout",
            providerId: "copilot",
            providerThreadId: "thread_copilot_789",
            resumeProviderSessionId: "resume_copilot_789"
        }
    });

    assert.equal(actions[0].requestBody.provider_thread_id, "thread_copilot_789");
    assert.equal(actions[0].requestBody.resume_provider_session_id, "resume_copilot_789");
    assert.equal(actions[0].requestBody.provider_id, "copilot");
});

test("worker round partial timeout without thread only exposes manual handoff", () => {
    const actions = buildWorkerRoundActionPlan({
        message_type: "worker_round",
        id: "msg_round_2",
        task_id: "task_123",
        metadata: {
            worker_id: "codex",
            execution_status: "partial_timeout"
        }
    });

    assert.deepEqual(actions.map((item) => item.action), ["prepare-worker-handoff"]);
    assert.equal(actions[0].tone, "primary");
});

test("non worker messages do not expose worker round actions", () => {
    const actions = buildWorkerRoundActionPlan({
        message_type: "task_progress",
        task_id: "task_123",
        metadata: {
            execution_status: "partial_timeout"
        }
    });

    assert.deepEqual(actions, []);
});
