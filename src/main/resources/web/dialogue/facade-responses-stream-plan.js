import { drainSseEventPayloads } from "./facade-stream-plan.js";

export function createResponsesStreamState() {
    return {
        id: "",
        createdAt: null,
        model: "",
        outputText: "",
        agentcloud: null,
        response: null,
        eventCount: 0,
        done: false
    };
}

export function consumeResponsesSsePayload(state, payload) {
    if (!state || typeof state !== "object") {
        throw new TypeError("responses stream state is required");
    }
    const text = String(payload || "").trim();
    if (!text) {
        return state;
    }
    if (text === "[DONE]") {
        state.done = true;
        return state;
    }
    const event = JSON.parse(text);
    state.eventCount += 1;
    const type = typeof event.type === "string" ? event.type.trim() : "";
    if (event.response && typeof event.response === "object") {
        state.response = event.response;
        if (typeof event.response.id === "string" && event.response.id.trim()) {
            state.id = event.response.id.trim();
        }
        if (event.response.created_at != null) {
            state.createdAt = Number(event.response.created_at);
        }
        if (typeof event.response.model === "string" && event.response.model.trim()) {
            state.model = event.response.model.trim();
        }
        if (event.response.agentcloud && typeof event.response.agentcloud === "object") {
            state.agentcloud = event.response.agentcloud;
        }
        if (typeof event.response.output_text === "string") {
            state.outputText = event.response.output_text;
        }
    }
    if (type === "response.output_text.delta" && typeof event.delta === "string") {
        state.outputText += event.delta;
    }
    if (type === "response.output_text.done" && typeof event.text === "string") {
        state.outputText = event.text;
    }
    return state;
}

export function parseResponsesSseText(text) {
    const state = createResponsesStreamState();
    const drained = drainSseEventPayloads(String(text || "").replace(/\r\n/g, "\n"));
    drained.payloads.forEach((payload) => {
        consumeResponsesSsePayload(state, payload);
    });
    const trailing = drained.remaining.trim();
    if (trailing) {
        throw new Error("incomplete Responses SSE payload");
    }
    return finalizeResponsesStream(state);
}

export function finalizeResponsesStream(state) {
    if (!state || typeof state !== "object" || state.eventCount <= 0) {
        throw new Error("no responses stream events received");
    }
    return {
        ...(state.response && typeof state.response === "object" ? state.response : {}),
        id: state.id || "resp_stream",
        object: "response",
        created_at: state.createdAt ?? Math.floor(Date.now() / 1000),
        model: state.model || "agentcloud-default",
        output_text: state.outputText || "",
        agentcloud: state.agentcloud || null
    };
}
