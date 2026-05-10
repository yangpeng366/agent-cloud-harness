import test from "node:test";
import assert from "node:assert/strict";
import {
    facadeSurfaceHashValue,
    facadeSurfaceRequestPath,
    facadeSurfaceSummaryLabel,
    normalizeFacadeSurface,
    readFacadeSurfaceFromHash,
    writeFacadeSurfaceToParams
} from "../../main/resources/web/dialogue/facade-surface-plan.js";

test("facade surface plan defaults to chat completions", () => {
    assert.equal(normalizeFacadeSurface(""), "chat_completions");
    assert.equal(normalizeFacadeSurface(undefined), "chat_completions");
    assert.equal(facadeSurfaceRequestPath("chat_completions"), "/v1/chat/completions");
    assert.equal(facadeSurfaceHashValue("chat_completions"), "");
    assert.equal(facadeSurfaceSummaryLabel("chat_completions"), "Chat façade");
});

test("facade surface plan recognizes responses aliases", () => {
    assert.equal(normalizeFacadeSurface("responses"), "responses");
    assert.equal(normalizeFacadeSurface("response"), "responses");
    assert.equal(facadeSurfaceRequestPath("responses"), "/v1/responses");
    assert.equal(facadeSurfaceHashValue("responses"), "responses");
    assert.equal(facadeSurfaceSummaryLabel("responses"), "Responses façade");
});

test("facade surface plan reads and writes surface from hash params", () => {
    const firstNonBlank = (...values) => values.find((value) => typeof value === "string" && value.trim())?.trim() || null;
    const params = new URLSearchParams("session=session_1&task=task_1");

    writeFacadeSurfaceToParams("responses", params);
    assert.equal(params.get("facade"), "responses");
    assert.equal(readFacadeSurfaceFromHash(`#${params.toString()}`, { firstNonBlank }), "responses");

    writeFacadeSurfaceToParams("chat_completions", params);
    assert.equal(params.get("facade"), null);
    assert.equal(readFacadeSurfaceFromHash(`#${params.toString()}`, { firstNonBlank }), "chat_completions");
});
