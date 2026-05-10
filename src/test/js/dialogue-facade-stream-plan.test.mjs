import test from "node:test";
import assert from "node:assert/strict";
import {
    consumeChatCompletionSsePayload,
    createChatCompletionStreamState,
    drainSseEventPayloads,
    finalizeChatCompletionStream,
    parseChatCompletionSseText
} from "../../main/resources/web/dialogue/facade-stream-plan.js";

test("facade stream plan drains SSE payloads and reconstructs final completion", () => {
    const firstChunk = [
        'data: {"id":"chatcmpl_stream_1","object":"chat.completion.chunk","created":1710000000,"model":"agentcloud-default","choices":[{"index":0,"delta":{"role":"assistant","content":"任务已推进"}}]}',
        "",
        'data: {"id":"chatcmpl_stream_1","object":"chat.completion.chunk","created":1710000000,"model":"agentcloud-default","choices":[{"index":0,"finish_reason":"stop"}],"agentcloud":{"session_id":"session_1","task_id":"task_1","reply_type":"task_progress","reply_source":"task_progress"}}',
        "",
        "data: [DONE]",
        "",
        ""
    ].join("\n");

    const partial = firstChunk.slice(0, 132);
    const tail = firstChunk.slice(132);

    const firstDrain = drainSseEventPayloads(partial);
    assert.equal(firstDrain.payloads.length, 0);
    assert.ok(firstDrain.remaining.length > 0);

    const secondDrain = drainSseEventPayloads(firstDrain.remaining + tail);
    assert.deepEqual(secondDrain.payloads.map((payload) => payload.trim()), [
        '{"id":"chatcmpl_stream_1","object":"chat.completion.chunk","created":1710000000,"model":"agentcloud-default","choices":[{"index":0,"delta":{"role":"assistant","content":"任务已推进"}}]}',
        '{"id":"chatcmpl_stream_1","object":"chat.completion.chunk","created":1710000000,"model":"agentcloud-default","choices":[{"index":0,"finish_reason":"stop"}],"agentcloud":{"session_id":"session_1","task_id":"task_1","reply_type":"task_progress","reply_source":"task_progress"}}',
        "[DONE]"
    ]);
    assert.equal(secondDrain.remaining, "");

    const streamState = createChatCompletionStreamState();
    secondDrain.payloads.forEach((payload) => {
        consumeChatCompletionSsePayload(streamState, payload);
    });
    assert.equal(streamState.done, true);
    assert.equal(streamState.content, "任务已推进");
    assert.equal(streamState.finishReason, "stop");
    assert.equal(streamState.agentcloud.reply_type, "task_progress");

    const completion = finalizeChatCompletionStream(streamState);
    assert.equal(completion.object, "chat.completion");
    assert.equal(completion.choices[0].message.role, "assistant");
    assert.equal(completion.choices[0].message.content, "任务已推进");
    assert.equal(completion.choices[0].finish_reason, "stop");
    assert.equal(completion.agentcloud.reply_source, "task_progress");
});

test("facade stream plan requires at least one chunk before finalizing", () => {
    assert.throws(() => finalizeChatCompletionStream(createChatCompletionStreamState()), /no chat completion chunks received/);
});

test("facade stream plan parses complete SSE text directly", () => {
    const text = [
        'data: {"id":"chatcmpl_stream_2","object":"chat.completion.chunk","created":1710000001,"model":"agentcloud-default","choices":[{"index":0,"delta":{"role":"assistant","content":"已记录消息"}}]}',
        "",
        'data: {"id":"chatcmpl_stream_2","object":"chat.completion.chunk","created":1710000001,"model":"agentcloud-default","choices":[{"index":0,"finish_reason":"stop"}],"agentcloud":{"session_id":"session_2","reply_type":"chat_reply","reply_source":"session_ack"}}',
        "",
        "data: [DONE]",
        "",
        ""
    ].join("\n");

    const completion = parseChatCompletionSseText(text);
    assert.equal(completion.choices[0].message.content, "已记录消息");
    assert.equal(completion.agentcloud.reply_source, "session_ack");
});

test("facade stream plan rejects incomplete SSE text", () => {
    const incomplete = 'data: {"id":"chatcmpl_stream_3","object":"chat.completion.chunk"';
    assert.throws(() => parseChatCompletionSseText(incomplete), /incomplete SSE payload|Unexpected end of JSON input/);
});
