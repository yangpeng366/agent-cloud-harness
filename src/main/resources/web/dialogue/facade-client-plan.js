import { parseFacadeResponseBody } from "./facade-response-plan.js";
import { facadeSurfaceRequestPath } from "./facade-surface-plan.js";

export async function requestFacadeCompletion(surface, requestBody, options = {}) {
    const fetchImpl = options.fetchImpl || globalThis.fetch;
    if (typeof fetchImpl !== "function") {
        throw new TypeError("fetch implementation is required");
    }
    const response = await fetchImpl(facadeSurfaceRequestPath(surface), {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify(requestBody)
    });
    if (!response.ok) {
        const payload = await response.json().catch(() => null);
        throw new Error(payload?.message || `HTTP ${response.status}`);
    }
    return readFacadeResponse(surface, response);
}

export async function readFacadeResponse(surface, response) {
    const contentType = (response.headers.get("Content-Type") || "").toLowerCase();
    const bodyText = await response.text();
    return parseFacadeResponseBody({
        facadeSurface: surface,
        contentType,
        bodyText
    });
}
