export function createChatCompletionStreamState() {
    return {
        id: "",
        created: null,
        model: "",
        role: "assistant",
        content: "",
        finishReason: "",
        agentcloud: null,
        chunkCount: 0,
        done: false
    };
}

export function drainSseEventPayloads(buffer) {
    const normalizedBuffer = String(buffer || "").replace(/\r\n/g, "\n");
    const payloads = [];
    let remaining = normalizedBuffer;
    let boundary = remaining.indexOf("\n\n");
    while (boundary !== -1) {
        const rawEvent = remaining.slice(0, boundary);
        remaining = remaining.slice(boundary + 2);
        const dataLines = rawEvent
            .split("\n")
            .filter((line) => line.startsWith("data:"))
            .map((line) => line.slice(5).trimStart());
        if (dataLines.length > 0) {
            payloads.push(dataLines.join("\n"));
        }
        boundary = remaining.indexOf("\n\n");
    }
    return {
        payloads,
        remaining
    };
}

export function consumeChatCompletionSsePayload(state, payload) {
    if (!state || typeof state !== "object") {
        throw new TypeError("stream state is required");
    }
    const text = String(payload || "").trim();
    if (!text) {
        return state;
    }
    if (text === "[DONE]") {
        state.done = true;
        return state;
    }
    const chunk = JSON.parse(text);
    state.chunkCount += 1;
    if (typeof chunk.id === "string" && chunk.id.trim() && !state.id) {
        state.id = chunk.id.trim();
    }
    if (chunk.created != null && state.created == null) {
        state.created = Number(chunk.created);
    }
    if (typeof chunk.model === "string" && chunk.model.trim() && !state.model) {
        state.model = chunk.model.trim();
    }
    if (chunk.agentcloud && typeof chunk.agentcloud === "object") {
        state.agentcloud = chunk.agentcloud;
    }
    const choice = Array.isArray(chunk.choices) ? chunk.choices[0] : null;
    const delta = choice?.delta || {};
    if (typeof delta.role === "string" && delta.role.trim()) {
        state.role = delta.role.trim();
    }
    if (typeof delta.content === "string" && delta.content) {
        state.content += delta.content;
    }
    const finishReason = firstNonBlank(choice?.finish_reason, choice?.finishReason);
    if (finishReason) {
        state.finishReason = finishReason;
    }
    return state;
}

export function finalizeChatCompletionStream(state) {
    if (!state || typeof state !== "object" || state.chunkCount <= 0) {
        throw new Error("no chat completion chunks received");
    }
    return {
        id: state.id || "chatcmpl_stream",
        object: "chat.completion",
        created: state.created ?? Math.floor(Date.now() / 1000),
        model: state.model || "agentcloud-default",
        choices: [
            {
                index: 0,
                message: {
                    role: state.role || "assistant",
                    content: state.content || ""
                },
                finish_reason: state.finishReason || "stop"
            }
        ],
        agentcloud: state.agentcloud || null
    };
}

export function parseChatCompletionSseText(text) {
    const streamState = createChatCompletionStreamState();
    let buffer = String(text || "").replace(/\r\n/g, "\n");
    const drained = drainSseEventPayloads(buffer);
    drained.payloads.forEach((payload) => {
        consumeChatCompletionSsePayload(streamState, payload);
    });
    const trailing = drained.remaining.trim();
    if (trailing) {
        throw new Error("incomplete SSE payload");
    }
    return finalizeChatCompletionStream(streamState);
}

function firstNonBlank(...values) {
    for (const value of values) {
        if (typeof value === "string" && value.trim()) {
            return value.trim();
        }
    }
    return "";
}
