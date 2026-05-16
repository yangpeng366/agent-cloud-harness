import test from "node:test";
import assert from "node:assert/strict";
import { buildPendingFacadeReply } from "../../main/resources/web/dialogue/facade-pending-plan.js";

test("pending facade reply is produced for non-message submit with scoped session and task", () => {
    const reply = buildPendingFacadeReply({
        sessionId: "session_1",
        taskId: "task_1",
        resolvedMode: "task"
    });

    assert.equal(reply.replyType, "task_pending");
    assert.equal(reply.replySource, "pending_submit");
    assert.equal(reply.sessionId, "session_1");
    assert.equal(reply.taskId, "task_1");
    assert.match(reply.inlineText, /已提交任务，正在推进/);
});

test("pending facade reply is also produced for default message path when it materializes task_auto", () => {
    const reply = buildPendingFacadeReply({
        sessionId: "session_1",
        taskId: "",
        resolvedMode: "message"
    });

    assert.equal(reply.replyType, "task_pending");
    assert.equal(reply.replySource, "pending_submit");
    assert.equal(reply.sessionId, "session_1");
    assert.equal(reply.taskId, "");
    assert.match(reply.inlineText, /已提交任务，正在推进/);
});

test("pending facade reply keeps task-bound message attach semantics separate from task progress", () => {
    const reply = buildPendingFacadeReply({
        sessionId: "session_1",
        taskId: "task_note_1",
        resolvedMode: "message"
    });

    assert.equal(reply.replyType, "chat_reply");
    assert.equal(reply.replySource, "pending_task_note");
    assert.equal(reply.sessionId, "session_1");
    assert.equal(reply.taskId, "task_note_1");
    assert.equal(reply.category, "message");
    assert.equal(reply.inlineText, "最近回执：已写入当前任务上下文。");
});
