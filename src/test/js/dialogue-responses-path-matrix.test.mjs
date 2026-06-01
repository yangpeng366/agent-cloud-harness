import test from "node:test";
import assert from "node:assert/strict";
import { buildFacadeRequest } from "../../main/resources/web/dialogue/composer-request-plan.js";
import { parseFacadeResponseBody } from "../../main/resources/web/dialogue/facade-response-plan.js";
import { buildFacadeReplyFeedback } from "../../main/resources/web/dialogue/facade-reply-plan.js";
import { buildFacadeReplyHighlightPlan } from "../../main/resources/web/dialogue/facade-reply-highlight-plan.js";

test("responses path matrix covers message_only request and reply consumption", () => {
    const request = buildFacadeRequest({
        intent: "先用 responses 记一条消息",
        sessionId: "session_resp_message",
        facadeSurface: "responses",
        derivedTitle: "先用 responses 记一条消息",
        taskMode: "message_only"
    });
    assert.equal(request.input, "先用 responses 记一条消息");
    assert.equal(request.metadata.task_mode, "message_only");

    const response = parseFacadeResponseBody({
        facadeSurface: "responses",
        contentType: "application/json",
        bodyText: JSON.stringify({
            id: "resp_message_1",
            object: "response",
            status: "completed",
            output_text: "已记录到当前会话",
            agentcloud: {
                session_id: "session_resp_message",
                reply_type: "chat_reply",
                reply_source: "session_ack"
            }
        })
    });
    const feedback = buildFacadeReplyFeedback({
        resolvedMode: "message",
        replyType: response.agentcloud.reply_type,
        replySource: response.agentcloud.reply_source,
        sessionId: response.agentcloud.session_id,
        taskId: "",
        taskStatus: "",
        intent: request.input,
        referencedTaskTitle: ""
    });
    const highlight = buildFacadeReplyHighlightPlan([
        { id: "msg_resp_chat", role: "assistant", message_type: "chat_reply" }
    ], feedback, "");

    assert.match(feedback.toastText, /已记录消息/);
    assert.equal(highlight, null);
});

test("responses path matrix covers task_required progress path end to end", () => {
    const request = buildFacadeRequest({
        intent: "用 responses 推进任务",
        sessionId: "session_resp_task",
        facadeSurface: "responses",
        derivedTitle: "用 responses 推进任务",
        taskMode: "task_required"
    });
    assert.equal(request.input, "用 responses 推进任务");
    assert.equal(request.metadata.task_mode, "task_required");

    const response = parseFacadeResponseBody({
        facadeSurface: "responses",
        contentType: "text/event-stream",
        bodyText: [
            'data: {"type":"response.created","response":{"id":"resp_task_1","object":"response","created_at":1710000000,"model":"agentcloud-default","status":"completed","agentcloud":{"session_id":"session_resp_task","task_id":"task_resp_1","task_status":"active","reply_type":"task_progress","reply_source":"task_progress"}}}',
            "",
            'data: {"type":"response.output_text.delta","output_index":0,"item_id":"msg_1","content_index":0,"delta":"任务已推进"}',
            "",
            'data: {"type":"response.completed","response":{"id":"resp_task_1","object":"response","created_at":1710000000,"model":"agentcloud-default","status":"completed","output_text":"任务已推进","agentcloud":{"session_id":"session_resp_task","task_id":"task_resp_1","task_status":"active","reply_type":"task_progress","reply_source":"task_progress"}}}',
            "",
            "data: [DONE]",
            "",
            ""
        ].join("\n")
    });
    const feedback = buildFacadeReplyFeedback({
        resolvedMode: "task",
        replyType: response.agentcloud.reply_type,
        replySource: response.agentcloud.reply_source,
        sessionId: response.agentcloud.session_id,
        taskId: response.agentcloud.task_id,
        taskStatus: response.agentcloud.task_status,
        intent: request.input,
        referencedTaskTitle: ""
    });
    const highlight = buildFacadeReplyHighlightPlan([
        { id: "msg_resp_progress", role: "assistant", message_type: "task_progress", task_id: "task_resp_1" }
    ], {
        ...feedback,
        replyType: response.agentcloud.reply_type,
        replySource: response.agentcloud.reply_source
    }, "task_resp_1");

    assert.match(feedback.toastText, /任务已推进：task_resp_1 · active/);
    assert.equal(highlight?.badgeText, "最新进展");
});
