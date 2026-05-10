import { parseChatCompletionSseText } from "./facade-stream-plan.js";
import { parseResponsesSseText } from "./facade-responses-stream-plan.js";
import { normalizeFacadeSurface } from "./facade-surface-plan.js";

export function parseChatCompletionResponseBody(input) {
    const normalized = normalizeResponseInput(input);
    if (normalized.contentType.includes("text/event-stream")) {
        try {
            return parseChatCompletionSseText(normalized.bodyText);
        } catch (sseError) {
            try {
                return parseJsonCompletionPayload(normalized.bodyText);
            } catch (jsonError) {
                throw sseError;
            }
        }
    }
    return parseJsonCompletionPayload(normalized.bodyText);
}

export function parseResponsesResponseBody(input) {
    const normalized = normalizeResponseInput(input);
    if (normalized.contentType.includes("text/event-stream")) {
        try {
            return parseResponsesSseText(normalized.bodyText);
        } catch (sseError) {
            try {
                return parseJsonCompletionPayload(normalized.bodyText);
            } catch (jsonError) {
                throw sseError;
            }
        }
    }
    return parseJsonCompletionPayload(normalized.bodyText);
}

export function parseFacadeResponseBody(input) {
    const surface = normalizeFacadeSurface(input?.facadeSurface);
    if (surface === "responses") {
        return parseResponsesResponseBody(input);
    }
    return parseChatCompletionResponseBody(input);
}

function parseJsonCompletionPayload(bodyText) {
    const payload = JSON.parse(bodyText);
    if (payload && payload.success === false) {
        throw new Error(payload.message || "request failed");
    }
    return payload?.data ?? payload;
}

function normalizeResponseInput(input) {
    const source = input || {};
    return {
        contentType: String(source.contentType || "").toLowerCase(),
        bodyText: String(source.bodyText || "")
    };
}
