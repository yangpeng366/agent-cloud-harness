import test from "node:test";
import assert from "node:assert/strict";
import { buildFacadeReplyHighlightPlan } from "../../main/resources/web/dialogue/facade-reply-highlight-plan.js";

test("facade reply highlight plan marks the latest matching task reply message", () => {
    const plan = buildFacadeReplyHighlightPlan([
        { id: "m1", role: "user", message_type: "task_note", task_id: "task_1" },
        { id: "m2", role: "assistant", message_type: "task_receipt", task_id: "task_1" },
        { id: "m3", role: "assistant", message_type: "task_progress", task_id: "task_1" }
    ], {
        resolvedMode: "task",
        replyType: "task_progress",
        taskId: "task_1"
    }, "task_1");

    assert.equal(plan.messageId, "m3");
    assert.equal(plan.badgeText, "latest progress");
    assert.equal(plan.badgeTone, "active");
});

test("facade reply highlight plan ignores plain message acknowledgements", () => {
    const plan = buildFacadeReplyHighlightPlan([
        { id: "m1", role: "assistant", message_type: "task_receipt", task_id: "task_1" }
    ], {
        replyType: "message",
        taskId: ""
    });

    assert.equal(plan, null);
});

test("facade reply highlight plan requires a matching task reply when task id is known", () => {
    const plan = buildFacadeReplyHighlightPlan([
        { id: "m1", role: "assistant", message_type: "task_progress", task_id: "task_2" }
    ], {
        resolvedMode: "task",
        replyType: "task_progress",
        taskId: "task_1"
    }, "task_1");

    assert.equal(plan, null);
});
