import test from "node:test";
import assert from "node:assert/strict";
import { classifyFacadeReply } from "../../main/resources/web/dialogue/facade-reply-kind.js";

test("facade reply kind classifies task progress consistently across surfaces", () => {
    const kind = classifyFacadeReply({
        resolvedMode: "task",
        replyType: "task_progress",
        replySource: "task_progress"
    });

    assert.equal(kind.category, "progress");
    assert.equal(kind.toneClass, "signal--active");
    assert.equal(kind.badgeTone, "active");
    assert.equal(kind.badgeText, "最新进展");
    assert.equal(kind.inlineVerb, "任务已推进");
    assert.equal(kind.toastVerb, "任务已推进");
});

test("facade reply kind does not downgrade task progress when chat mode uses task_auto", () => {
    const kind = classifyFacadeReply({
        resolvedMode: "message",
        replyType: "task_progress",
        replySource: "task_progress"
    });

    assert.equal(kind.category, "progress");
    assert.equal(kind.badgeText, "最新进展");
});

test("facade reply kind keeps manual receipt semantics separate from plain message ack", () => {
    const receipt = classifyFacadeReply({
        resolvedMode: "task",
        replyType: "task_receipt",
        replySource: "task_receipt"
    });
    const message = classifyFacadeReply({
        resolvedMode: "message",
        replyType: "chat_reply",
        replySource: "session_ack"
    });

    assert.equal(receipt.category, "receipt");
    assert.equal(receipt.badgeText, "最新回执");
    assert.equal(receipt.badgeTone, "manual");
    assert.equal(message.category, "message");
    assert.equal(message.badgeText, "");
});

test("facade reply kind keeps terminal task result affordance aligned", () => {
    const result = classifyFacadeReply({
        resolvedMode: "task",
        replyType: "task_result",
        replySource: "task_result"
    });

    assert.equal(result.category, "result");
    assert.equal(result.toneClass, "signal--done");
    assert.equal(result.badgeTone, "done");
    assert.equal(result.badgeText, "最新结果");
    assert.equal(result.inlineVerb, "任务已完成");
    assert.equal(result.toastVerb, "任务已完成");
});

test("facade reply kind preserves worker round affordance", () => {
    const round = classifyFacadeReply({
        resolvedMode: "task",
        replyType: "worker_round",
        replySource: "worker_round"
    });

    assert.equal(round.category, "worker_round");
    assert.equal(round.toneClass, "signal--active");
    assert.equal(round.badgeTone, "active");
    assert.equal(round.badgeText, "最新回合");
    assert.equal(round.inlineVerb, "执行回合已更新");
    assert.equal(round.toastVerb, "执行回合已更新");
});

test("facade reply kind source does not expose English latest badges", async () => {
    const { readFile } = await import("node:fs/promises");
    const source = await readFile(new URL("../../main/resources/web/dialogue/facade-reply-kind.js", import.meta.url), "utf8");

    assert.doesNotMatch(source, /latest (progress|receipt|result|round)/);
});
