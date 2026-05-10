import test from "node:test";
import assert from "node:assert/strict";
import { requestFacadeCompletion } from "../../main/resources/web/dialogue/facade-client-plan.js";

test("facade client plan posts chat completions request to chat facade path", async () => {
    let capturedPath = "";
    let capturedBody = "";
    const completion = await requestFacadeCompletion("chat_completions", {
        model: "agentcloud-default",
        messages: [{ role: "user", content: "记一条消息" }],
        stream: true,
        metadata: { task_mode: "message_only", session_id: "session_client_chat" }
    }, {
        fetchImpl: async (path, options) => {
            capturedPath = path;
            capturedBody = options.body;
            return {
                ok: true,
                headers: {
                    get(name) {
                        return name.toLowerCase() === "content-type" ? "application/json" : "";
                    }
                },
                async text() {
                    return JSON.stringify({
                        id: "chatcmpl_client_1",
                        object: "chat.completion",
                        choices: [
                            {
                                index: 0,
                                message: { role: "assistant", content: "已记录消息" },
                                finish_reason: "stop"
                            }
                        ],
                        agentcloud: {
                            session_id: "session_client_chat",
                            reply_type: "chat_reply",
                            reply_source: "session_ack"
                        }
                    });
                }
            };
        }
    });

    assert.equal(capturedPath, "/v1/chat/completions");
    assert.match(capturedBody, /"task_mode":"message_only"/);
    assert.equal(completion.object, "chat.completion");
    assert.equal(completion.agentcloud.reply_source, "session_ack");
});

test("facade client plan posts responses request to responses facade path", async () => {
    let capturedPath = "";
    let capturedBody = "";
    const response = await requestFacadeCompletion("responses", {
        model: "agentcloud-default",
        input: "推进任务",
        stream: true,
        metadata: { task_mode: "task_required", session_id: "session_client_resp" }
    }, {
        fetchImpl: async (path, options) => {
            capturedPath = path;
            capturedBody = options.body;
            return {
                ok: true,
                headers: {
                    get(name) {
                        return name.toLowerCase() === "content-type" ? "application/json" : "";
                    }
                },
                async text() {
                    return JSON.stringify({
                        id: "resp_client_1",
                        object: "response",
                        status: "completed",
                        output_text: "任务已推进",
                        agentcloud: {
                            session_id: "session_client_resp",
                            task_id: "task_client_resp",
                            task_status: "active",
                            reply_type: "task_progress",
                            reply_source: "task_progress"
                        }
                    });
                }
            };
        }
    });

    assert.equal(capturedPath, "/v1/responses");
    assert.match(capturedBody, /"task_mode":"task_required"/);
    assert.match(capturedBody, /"input":"推进任务"/);
    assert.equal(response.object, "response");
    assert.equal(response.agentcloud.reply_source, "task_progress");
});

test("facade client plan surfaces http error payloads", async () => {
    await assert.rejects(() => requestFacadeCompletion("chat_completions", {
        model: "agentcloud-default",
        messages: [{ role: "user", content: "x" }],
        stream: true,
        metadata: { task_mode: "message_only", session_id: "session_client_error" }
    }, {
        fetchImpl: async () => ({
            ok: false,
            status: 400,
            async json() {
                return { message: "session is closed" };
            }
        })
    }), /session is closed/);
});
