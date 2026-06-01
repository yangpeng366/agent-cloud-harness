import test from "node:test";
import assert from "node:assert/strict";
import { buildWorkerRoundArtifactPlan } from "../../main/resources/web/dialogue/worker-round-artifact-plan.js";

test("worker round artifact plan shows loading before task artifacts are cached", () => {
    const plan = buildWorkerRoundArtifactPlan({
        message_type: "worker_round",
        task_id: "task_artifacts_1"
    }, new Map(), "task_artifacts_1");

    assert.equal(plan.visible, true);
    assert.equal(plan.state, "loading");
    assert.equal(plan.taskId, "task_artifacts_1");
    assert.deepEqual(plan.previewArtifacts, []);
});

test("worker round artifact plan hides empty cached artifact lists", () => {
    const plan = buildWorkerRoundArtifactPlan({
        message_type: "worker_round",
        task_id: "task_artifacts_2"
    }, new Map([["task_artifacts_2", []]]), "task_artifacts_2");

    assert.equal(plan.visible, false);
    assert.equal(plan.state, "empty");
});

test("worker round artifact plan previews first three artifacts and reports overflow", () => {
    const artifacts = [
        { artifact_id: "art_1" },
        { artifact_id: "art_2" },
        { artifact_id: "art_3" },
        { artifact_id: "art_4" }
    ];
    const plan = buildWorkerRoundArtifactPlan({
        message_type: "worker_round",
        task_id: "task_artifacts_3"
    }, new Map([["task_artifacts_3", artifacts]]), "task_artifacts_3");

    assert.equal(plan.visible, true);
    assert.equal(plan.state, "ready");
    assert.equal(plan.selected, true);
    assert.deepEqual(plan.previewArtifacts.map((artifact) => artifact.artifact_id), ["art_1", "art_2", "art_3"]);
    assert.equal(plan.moreCount, 1);
});

test("worker round artifact plan accepts task id from metadata and plain object cache", () => {
    const plan = buildWorkerRoundArtifactPlan({
        messageType: "worker_round",
        metadata: {
            taskId: "task_artifacts_4"
        }
    }, {
        task_artifacts_4: [{ artifact_id: "art_4" }]
    }, "other_task");

    assert.equal(plan.visible, true);
    assert.equal(plan.state, "ready");
    assert.equal(plan.selected, false);
    assert.equal(plan.previewArtifacts[0].artifact_id, "art_4");
});

test("worker round artifact plan ignores non worker-round messages", () => {
    const plan = buildWorkerRoundArtifactPlan({
        message_type: "task_progress",
        task_id: "task_artifacts_5"
    }, new Map([["task_artifacts_5", [{ artifact_id: "art_5" }]]]), "task_artifacts_5");

    assert.equal(plan.visible, false);
    assert.equal(plan.state, "hidden");
});
