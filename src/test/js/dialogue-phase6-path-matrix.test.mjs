import test from "node:test";
import assert from "node:assert/strict";
import { buildChatFacadeRequest } from "../../main/resources/web/dialogue/composer-request-plan.js";
import { parseChatCompletionResponseBody } from "../../main/resources/web/dialogue/facade-response-plan.js";
import { buildFacadeReplyFeedback } from "../../main/resources/web/dialogue/facade-reply-plan.js";
import { buildFacadeReplyHighlightPlan } from "../../main/resources/web/dialogue/facade-reply-highlight-plan.js";

test("phase6 path matrix covers message_only path end to end", () => {
    const request = buildChatFacadeRequest({
        intent: "先记一条草稿",
        sessionId: "session_message_1",
        facadeModel: "agentcloud-default",
        taskMode: "message_only",
        derivedTitle: "先记一条草稿"
    });
    assert.equal(request.metadata.task_mode, "message_only");

    const completion = parseChatCompletionResponseBody({
        contentType: "application/json",
        bodyText: JSON.stringify({
            id: "chatcmpl_message_1",
            object: "chat.completion",
            choices: [
                {
                    index: 0,
                    message: {
                        role: "assistant",
                        content: "已记录到当前会话"
                    },
                    finish_reason: "stop"
                }
            ],
            agentcloud: {
                session_id: "session_message_1",
                reply_type: "chat_reply",
                reply_source: "session_ack"
            }
        })
    });
    const feedback = buildFacadeReplyFeedback({
        resolvedMode: "message",
        replyType: completion.agentcloud.reply_type,
        replySource: completion.agentcloud.reply_source,
        sessionId: completion.agentcloud.session_id,
        taskId: "",
        taskStatus: "",
        intent: request.messages[0].content,
        referencedTaskTitle: ""
    });
    const highlight = buildFacadeReplyHighlightPlan([
        {
            id: "msg_chat_reply",
            role: "assistant",
            message_type: "chat_reply"
        }
    ], feedback, "");

    assert.match(feedback.toastText, /已记录消息/);
    assert.equal(highlight, null);
});

test("phase6 path matrix covers task_required auto-start path end to end", () => {
    const request = buildChatFacadeRequest({
        intent: "继续整理方案",
        sessionId: "session_task_1",
        facadeModel: "agentcloud-strong",
        taskMode: "task_required",
        title: "整理方案",
        derivedTitle: "继续整理方案"
    });
    assert.equal(request.metadata.task_mode, "task_required");
    assert.equal(request.metadata.auto_start, true);

    const completion = parseChatCompletionResponseBody({
        contentType: "text/event-stream",
        bodyText: [
            'data: {"id":"chatcmpl_task_1","object":"chat.completion.chunk","created":1710000010,"model":"agentcloud-strong","choices":[{"index":0,"delta":{"role":"assistant","content":"任务已推进"}}]}',
            "",
            'data: {"id":"chatcmpl_task_1","object":"chat.completion.chunk","created":1710000010,"model":"agentcloud-strong","choices":[{"index":0,"finish_reason":"stop"}],"agentcloud":{"session_id":"session_task_1","task_id":"task_auto_1","task_status":"active","reply_type":"task_progress","reply_source":"task_progress"}}',
            "",
            "data: [DONE]",
            "",
            ""
        ].join("\n")
    });
    const feedback = buildFacadeReplyFeedback({
        resolvedMode: "task",
        replyType: completion.agentcloud.reply_type,
        replySource: completion.agentcloud.reply_source,
        sessionId: completion.agentcloud.session_id,
        taskId: completion.agentcloud.task_id,
        taskStatus: completion.agentcloud.task_status,
        intent: request.messages[0].content,
        referencedTaskTitle: ""
    });
    const scopedReply = {
        ...feedback,
        replyType: completion.agentcloud.reply_type,
        replySource: completion.agentcloud.reply_source
    };
    const highlight = buildFacadeReplyHighlightPlan([
        {
            id: "msg_task_progress",
            role: "assistant",
            message_type: "task_progress",
            task_id: "task_auto_1"
        }
    ], scopedReply, "task_auto_1");

    assert.match(feedback.toastText, /任务已推进：task_auto_1 · active/);
    assert.equal(highlight?.badgeText, "latest progress");
});

test("phase6 path matrix covers follow-up manual-start receipt path end to end", () => {
    const request = buildChatFacadeRequest({
        intent: "补一轮 follow-up",
        sessionId: "session_followup_1",
        facadeModel: "agentcloud-default",
        taskMode: "task_required",
        derivedTitle: "补一轮 follow-up",
        followupParentTaskId: "task_parent_1",
        autoStart: false
    });
    assert.equal(request.metadata.parent_task_id, "task_parent_1");
    assert.equal(request.metadata.auto_start, false);

    const completion = parseChatCompletionResponseBody({
        contentType: "application/json",
        bodyText: JSON.stringify({
            id: "chatcmpl_followup_1",
            object: "chat.completion",
            choices: [
                {
                    index: 0,
                    message: {
                        role: "assistant",
                        content: "manual-start follow-up 已记录"
                    },
                    finish_reason: "stop"
                }
            ],
            agentcloud: {
                session_id: "session_followup_1",
                task_id: "task_followup_1",
                task_status: "active",
                reply_type: "task_receipt",
                reply_source: "task_receipt"
            }
        })
    });
    const feedback = buildFacadeReplyFeedback({
        resolvedMode: "followup",
        replyType: completion.agentcloud.reply_type,
        replySource: completion.agentcloud.reply_source,
        sessionId: completion.agentcloud.session_id,
        taskId: completion.agentcloud.task_id,
        taskStatus: completion.agentcloud.task_status,
        intent: request.messages[0].content,
        referencedTaskTitle: ""
    });
    const scopedReply = {
        ...feedback,
        replyType: completion.agentcloud.reply_type,
        replySource: completion.agentcloud.reply_source
    };
    const highlight = buildFacadeReplyHighlightPlan([
        {
            id: "msg_task_receipt",
            role: "assistant",
            message_type: "task_receipt",
            task_id: "task_followup_1"
        }
    ], scopedReply, "task_followup_1");

    assert.match(feedback.toastText, /任务已记录：task_followup_1 · active/);
    assert.equal(highlight?.badgeText, "latest receipt");
});

test("phase6 path matrix covers manual-start continuity task note attach semantics", () => {
    const request = buildChatFacadeRequest({
        intent: "先记一轮，但不要继续",
        sessionId: "session_manual_1",
        facadeModel: "agentcloud-default",
        taskMode: "task_required",
        derivedTitle: "先记一轮，但不要继续",
        referencedTaskId: "task_existing_1",
        autoStart: false
    });
    assert.equal(request.metadata.task_id, "task_existing_1");
    assert.equal(request.metadata.auto_start, false);

    const completion = parseChatCompletionResponseBody({
        contentType: "application/json",
        bodyText: JSON.stringify({
            id: "chatcmpl_manual_1",
            object: "chat.completion",
            choices: [
                {
                    index: 0,
                    message: {
                        role: "assistant",
                        content: "等待手动继续"
                    },
                    finish_reason: "stop"
                }
            ],
            agentcloud: {
                session_id: "session_manual_1",
                task_id: "task_existing_1",
                task_status: "active",
                reply_type: "chat_reply",
                reply_source: "session_ack"
            }
        })
    });
    const feedback = buildFacadeReplyFeedback({
        resolvedMode: "task",
        replyType: completion.agentcloud.reply_type,
        replySource: completion.agentcloud.reply_source,
        sessionId: completion.agentcloud.session_id,
        taskId: completion.agentcloud.task_id,
        taskStatus: completion.agentcloud.task_status,
        intent: request.messages[0].content,
        referencedTaskTitle: ""
    });
    const highlight = buildFacadeReplyHighlightPlan([
        {
            id: "msg_chat_reply_manual",
            role: "assistant",
            message_type: "chat_reply",
            task_id: "task_existing_1"
        }
    ], feedback, "task_existing_1");

    assert.match(feedback.inlineText, /已记录为会话消息|已写入当前任务上下文/);
    assert.equal(highlight, null);
});

test("phase6 path matrix covers stream fallback within same response body", () => {
    const completion = parseChatCompletionResponseBody({
        contentType: "text/event-stream",
        bodyText: JSON.stringify({
            id: "chatcmpl_fallback_1",
            object: "chat.completion",
            choices: [
                {
                    index: 0,
                    message: {
                        role: "assistant",
                        content: "同响应内回退成功"
                    },
                    finish_reason: "stop"
                }
            ],
            agentcloud: {
                session_id: "session_fallback_1",
                task_id: "task_fallback_1",
                task_status: "active",
                reply_type: "task_progress",
                reply_source: "task_progress"
            }
        })
    });

    assert.equal(completion.choices[0].message.content, "同响应内回退成功");
    assert.equal(completion.agentcloud.reply_type, "task_progress");
});
