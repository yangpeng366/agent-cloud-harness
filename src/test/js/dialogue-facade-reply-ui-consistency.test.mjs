import test from "node:test";
import assert from "node:assert/strict";
import { classifyFacadeReply } from "../../main/resources/web/dialogue/facade-reply-kind.js";
import { buildFacadeReplyFeedback } from "../../main/resources/web/dialogue/facade-reply-plan.js";
import { buildFacadeReplyHighlightPlan } from "../../main/resources/web/dialogue/facade-reply-highlight-plan.js";

test("task progress reply stays aligned across toast, inline state, and transcript badge", () => {
    const input = {
        resolvedMode: "task",
        replyType: "task_progress",
        replySource: "task_progress",
        sessionId: "session_1",
        taskId: "task_1",
        taskStatus: "active"
    };

    const kind = classifyFacadeReply(input);
    const feedback = buildFacadeReplyFeedback(input);
    const highlight = buildFacadeReplyHighlightPlan([
        { id: "m1", role: "assistant", message_type: "task_progress", task_id: "task_1" }
    ], input, "task_1");

    assert.equal(kind.category, "progress");
    assert.equal(feedback.toneClass, kind.toneClass);
    assert.match(feedback.toastText, /任务已推进/);
    assert.match(feedback.inlineText, /任务已推进/);
    assert.equal(highlight.badgeText, kind.badgeText);
    assert.equal(highlight.badgeTone, kind.badgeTone);
});

test("task result reply stays aligned across toast, inline state, and transcript badge", () => {
    const input = {
        resolvedMode: "task",
        replyType: "task_result",
        replySource: "task_result",
        sessionId: "session_1",
        taskId: "task_done",
        taskStatus: "done"
    };

    const kind = classifyFacadeReply(input);
    const feedback = buildFacadeReplyFeedback(input);
    const highlight = buildFacadeReplyHighlightPlan([
        { id: "m1", role: "assistant", message_type: "task_result", task_id: "task_done" }
    ], input, "task_done");

    assert.equal(kind.category, "result");
    assert.equal(feedback.toneClass, kind.toneClass);
    assert.match(feedback.toastText, /任务已完成/);
    assert.match(feedback.inlineText, /任务已完成/);
    assert.equal(highlight.badgeText, kind.badgeText);
    assert.equal(highlight.badgeTone, kind.badgeTone);
});

test("plain message ack keeps feedback but does not create transcript latest badge", () => {
    const input = {
        resolvedMode: "message",
        replyType: "chat_reply",
        replySource: "session_ack",
        sessionId: "session_1",
        taskId: ""
    };

    const kind = classifyFacadeReply(input);
    const feedback = buildFacadeReplyFeedback({
        ...input,
        intent: "先记一条会话消息"
    });
    const highlight = buildFacadeReplyHighlightPlan([
        { id: "m1", role: "assistant", message_type: "chat_reply" }
    ], input, "");

    assert.equal(kind.category, "message");
    assert.equal(feedback.toneClass, kind.toneClass);
    assert.match(feedback.toastText, /已记录消息/);
    assert.match(feedback.inlineText, /已记录为会话消息/);
    assert.equal(highlight, null);
});
