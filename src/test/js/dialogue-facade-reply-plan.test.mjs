import test from "node:test";
import assert from "node:assert/strict";
import { buildFacadeReplyFeedback } from "../../main/resources/web/dialogue/facade-reply-plan.js";
import { scopedFacadeReply } from "../../main/resources/web/dialogue/facade-reply-scope.js";

test("message reply keeps chat-first feedback wording", () => {
    const feedback = buildFacadeReplyFeedback({
        resolvedMode: "message",
        replyType: "chat_reply",
        replySource: "session_ack",
        intent: "先记一条草稿",
        referencedTaskTitle: ""
    });

    assert.equal(feedback.category, "message");
    assert.equal(feedback.replyType, "chat_reply");
    assert.equal(feedback.replySource, "session_ack");
    assert.equal(feedback.toastText, "已记录消息：先记一条草稿");
    assert.equal(feedback.inlineText, "最近回执：已记录为会话消息。");
});

test("task receipt reply surfaces recorded wording", () => {
    const feedback = buildFacadeReplyFeedback({
        resolvedMode: "task",
        replyType: "task_receipt",
        replySource: "task_receipt",
        taskId: "task_123",
        taskStatus: "active"
    });

    assert.equal(feedback.category, "receipt");
    assert.equal(feedback.replyType, "task_receipt");
    assert.equal(feedback.replySource, "task_receipt");
    assert.equal(feedback.toastText, "任务已记录：task_123 · active");
    assert.equal(feedback.inlineText, "最近回执：任务已记录，当前 active。");
});

test("task result reply surfaces terminal wording", () => {
    const feedback = buildFacadeReplyFeedback({
        resolvedMode: "task",
        replyType: "task_result",
        replySource: "task_result",
        sessionId: "session_1",
        taskId: "task_done",
        taskStatus: "done"
    });

    assert.equal(feedback.category, "result");
    assert.equal(feedback.replyType, "task_result");
    assert.equal(feedback.replySource, "task_result");
    assert.equal(feedback.sessionId, "session_1");
    assert.equal(feedback.taskId, "task_done");
    assert.equal(feedback.toastText, "任务已完成：task_done · done");
    assert.equal(feedback.inlineText, "最近回执：任务已完成，当前 done。");
});

test("scoped façade reply only survives matching session and task", () => {
    const reply = buildFacadeReplyFeedback({
        resolvedMode: "task",
        replyType: "task_progress",
        replySource: "task_progress",
        sessionId: "session_a",
        taskId: "task_a",
        taskStatus: "active"
    });

    assert.equal(scopedFacadeReply(reply, "session_a", "task_a")?.taskId, "task_a");
    assert.equal(scopedFacadeReply(reply, "session_b", "task_a"), null);
    assert.equal(scopedFacadeReply(reply, "session_a", "task_b"), null);
});
