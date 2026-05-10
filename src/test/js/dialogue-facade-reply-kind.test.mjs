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
    assert.equal(kind.badgeText, "latest progress");
    assert.equal(kind.inlineVerb, "任务已推进");
    assert.equal(kind.toastVerb, "任务已推进");
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
    assert.equal(receipt.badgeText, "latest receipt");
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
    assert.equal(result.badgeText, "latest result");
    assert.equal(result.inlineVerb, "任务已完成");
    assert.equal(result.toastVerb, "任务已完成");
});
