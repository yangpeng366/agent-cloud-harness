import test from "node:test";
import assert from "node:assert/strict";
import { buildWorkerRoundActionPlan } from "../../main/resources/web/dialogue/worker-round-action-plan.js";

test("worker round partial timeout exposes continue and manual handoff actions", () => {
    const actions = buildWorkerRoundActionPlan({
        message_type: "worker_round",
        task_id: "task_123",
        metadata: {
            worker_id: "codex",
            execution_status: "partial_timeout"
        }
    });

    assert.deepEqual(actions.map((item) => item.action), [
        "continue-worker-thread",
        "prepare-worker-handoff"
    ]);
    assert.equal(actions[0].label, "继续 Codex thread");
    assert.equal(actions[0].taskId, "task_123");
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
