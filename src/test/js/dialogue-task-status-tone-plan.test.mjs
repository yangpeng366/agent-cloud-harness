import test from "node:test";
import assert from "node:assert/strict";
import { toneForStatus, toneForPinnedTaskOutcome } from "../../main/resources/web/dialogue/task-status-tone-plan.js";

test("toneForStatus maps waiting_human to paused, not failed", () => {
    assert.equal(toneForStatus("waiting_human"), "paused");
    assert.equal(toneForStatus("WAITING_HUMAN"), "paused");
});

test("toneForStatus maps active and running to active", () => {
    assert.equal(toneForStatus("active"), "active");
    assert.equal(toneForStatus("running"), "active");
});

test("toneForStatus maps paused and waiting to paused", () => {
    assert.equal(toneForStatus("paused"), "paused");
    assert.equal(toneForStatus("waiting"), "paused");
});

test("toneForStatus maps done to done", () => {
    assert.equal(toneForStatus("done"), "done");
});

test("toneForStatus maps failed to failed", () => {
    assert.equal(toneForStatus("failed"), "failed");
});

test("toneForStatus falls back to default for unknown status", () => {
    assert.equal(toneForStatus(""), "default");
    assert.equal(toneForStatus(null), "default");
    assert.equal(toneForStatus("weird"), "default");
});

test("toneForPinnedTaskOutcome maps waiting_human to paused, not failed", () => {
    assert.equal(toneForPinnedTaskOutcome("waiting_human", "human_gate"), "paused");
    assert.equal(toneForPinnedTaskOutcome("active", "human_gate"), "paused");
    assert.equal(toneForPinnedTaskOutcome("paused", ""), "paused");
});

test("toneForPinnedTaskOutcome maps active to active and done to done", () => {
    assert.equal(toneForPinnedTaskOutcome("active", "scheduler"), "active");
    assert.equal(toneForPinnedTaskOutcome("running", ""), "active");
    assert.equal(toneForPinnedTaskOutcome("done", "end"), "done");
});

test("toneForPinnedTaskOutcome maps failed to failed", () => {
    assert.equal(toneForPinnedTaskOutcome("failed", ""), "failed");
});

test("toneForPinnedTaskOutcome never renders waiting_human as failed even with empty control node", () => {
    assert.equal(toneForPinnedTaskOutcome("waiting_human", ""), "paused");
});

// UI 验收标准 #3: partial 与 done 都能看到已完成的 subgoals 与产物
test("toneForStatus maps partial to partial", () => {
    assert.equal(toneForStatus("partial"), "partial");
    assert.equal(toneForStatus("PARTIAL"), "partial");
});

test("toneForPinnedTaskOutcome maps partial to partial", () => {
    assert.equal(toneForPinnedTaskOutcome("partial", "scheduler"), "partial");
    assert.equal(toneForPinnedTaskOutcome("partial", ""), "partial");
});

test("toneForPinnedTaskOutcome distinguishes partial from done and active", () => {
    assert.notEqual(toneForPinnedTaskOutcome("partial", ""), "done");
    assert.notEqual(toneForPinnedTaskOutcome("partial", ""), "active");
    assert.notEqual(toneForPinnedTaskOutcome("partial", ""), "failed");
});
