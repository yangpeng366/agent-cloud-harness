import test from "node:test";
import assert from "node:assert/strict";
import {
    parseFacadeResponseBody,
    parseChatCompletionResponseBody,
    parseResponsesResponseBody
} from "../../main/resources/web/dialogue/facade-response-plan.js";

test("facade response plan parses minimal SSE completion body", () => {
    const completion = parseChatCompletionResponseBody({
        contentType: "text/event-stream; charset=utf-8",
        bodyText: [
            'data: {"id":"chatcmpl_stream_1","object":"chat.completion.chunk","created":1710000000,"model":"agentcloud-default","choices":[{"index":0,"delta":{"role":"assistant","content":"任务已推进"}}]}',
            "",
            'data: {"id":"chatcmpl_stream_1","object":"chat.completion.chunk","created":1710000000,"model":"agentcloud-default","choices":[{"index":0,"finish_reason":"stop"}],"agentcloud":{"session_id":"session_1","task_id":"task_1","reply_type":"task_progress","reply_source":"task_progress"}}',
            "",
            "data: [DONE]",
            "",
            ""
        ].join("\n")
    });

    assert.equal(completion.object, "chat.completion");
    assert.equal(completion.choices[0].message.content, "任务已推进");
    assert.equal(completion.agentcloud.reply_source, "task_progress");
});

test("facade response plan falls back to json parse within the same event-stream response body", () => {
    const completion = parseChatCompletionResponseBody({
        contentType: "text/event-stream",
        bodyText: JSON.stringify({
            id: "chatcmpl_json_1",
            object: "chat.completion",
            choices: [
                {
                    index: 0,
                    message: {
                        role: "assistant",
                        content: "已记录消息"
                    },
                    finish_reason: "stop"
                }
            ],
            agentcloud: {
                session_id: "session_2",
                reply_type: "chat_reply",
                reply_source: "session_ack"
            }
        })
    });

    assert.equal(completion.choices[0].message.content, "已记录消息");
    assert.equal(completion.agentcloud.reply_source, "session_ack");
});

test("facade response plan parses normal json payload and unwraps data envelope", () => {
    const completion = parseChatCompletionResponseBody({
        contentType: "application/json",
        bodyText: JSON.stringify({
            success: true,
            data: {
                id: "chatcmpl_json_2",
                object: "chat.completion",
                choices: [
                    {
                        index: 0,
                        message: {
                            role: "assistant",
                            content: "任务已完成"
                        },
                        finish_reason: "stop"
                    }
                ],
                agentcloud: {
                    task_id: "task_done_1",
                    reply_type: "task_result",
                    reply_source: "task_result"
                }
            }
        })
    });

    assert.equal(completion.choices[0].message.content, "任务已完成");
    assert.equal(completion.agentcloud.reply_type, "task_result");
});

test("facade response plan surfaces json error payloads", () => {
    assert.throws(() => parseChatCompletionResponseBody({
        contentType: "application/json",
        bodyText: JSON.stringify({
            success: false,
            message: "session is closed"
        })
    }), /session is closed/);
});

test("facade response plan parses minimal Responses SSE body", () => {
    const response = parseResponsesResponseBody({
        contentType: "text/event-stream; charset=utf-8",
        bodyText: [
            'data: {"type":"response.created","response":{"id":"resp_1","object":"response","created_at":1710000000,"model":"agentcloud-default","status":"completed","agentcloud":{"session_id":"session_1","task_id":"task_1","reply_type":"task_progress","reply_source":"task_progress"}}}',
            "",
            'data: {"type":"response.output_text.delta","output_index":0,"item_id":"msg_1","content_index":0,"delta":"任务已推进"}',
            "",
            'data: {"type":"response.output_text.done","output_index":0,"item_id":"msg_1","content_index":0,"text":"任务已推进"}',
            "",
            'data: {"type":"response.completed","response":{"id":"resp_1","object":"response","created_at":1710000000,"model":"agentcloud-default","status":"completed","output_text":"任务已推进","agentcloud":{"session_id":"session_1","task_id":"task_1","reply_type":"task_progress","reply_source":"task_progress"}}}',
            "",
            "data: [DONE]",
            "",
            ""
        ].join("\n")
    });

    assert.equal(response.object, "response");
    assert.equal(response.output_text, "任务已推进");
    assert.equal(response.agentcloud.reply_source, "task_progress");
});

test("facade response plan parses Responses json payload", () => {
    const response = parseResponsesResponseBody({
        contentType: "application/json",
        bodyText: JSON.stringify({
            id: "resp_2",
            object: "response",
            status: "completed",
            output: [
                {
                    id: "msg_2",
                    type: "message",
                    status: "completed",
                    role: "assistant",
                    content: [
                        {
                            type: "output_text",
                            text: "已记录消息",
                            annotations: []
                        }
                    ]
                }
            ],
            output_text: "已记录消息",
            agentcloud: {
                session_id: "session_2",
                reply_type: "chat_reply",
                reply_source: "session_ack"
            }
        })
    });

    assert.equal(response.object, "response");
    assert.equal(response.output_text, "已记录消息");
    assert.equal(response.agentcloud.reply_source, "session_ack");
});

test("facade response plan routes parsing by facade surface", () => {
    const chat = parseFacadeResponseBody({
        facadeSurface: "chat_completions",
        contentType: "application/json",
        bodyText: JSON.stringify({
            id: "chatcmpl_surface_1",
            object: "chat.completion",
            choices: [
                {
                    index: 0,
                    message: {
                        role: "assistant",
                        content: "chat facade ok"
                    },
                    finish_reason: "stop"
                }
            ]
        })
    });
    const responses = parseFacadeResponseBody({
        facadeSurface: "responses",
        contentType: "application/json",
        bodyText: JSON.stringify({
            id: "resp_surface_1",
            object: "response",
            status: "completed",
            output_text: "responses facade ok"
        })
    });

    assert.equal(chat.object, "chat.completion");
    assert.equal(chat.choices[0].message.content, "chat facade ok");
    assert.equal(responses.object, "response");
    assert.equal(responses.output_text, "responses facade ok");
});
