const CHAT_COMPLETIONS_SURFACE = "chat_completions";
const RESPONSES_SURFACE = "responses";

export function normalizeFacadeSurface(value) {
    const text = typeof value === "string" ? value.trim().toLowerCase() : "";
    if (text === RESPONSES_SURFACE || text === "response") {
        return RESPONSES_SURFACE;
    }
    return CHAT_COMPLETIONS_SURFACE;
}

export function facadeSurfaceRequestPath(surface) {
    return normalizeFacadeSurface(surface) === RESPONSES_SURFACE
        ? "/v1/responses"
        : "/v1/chat/completions";
}

export function facadeSurfaceHashValue(surface) {
    return normalizeFacadeSurface(surface) === RESPONSES_SURFACE ? RESPONSES_SURFACE : "";
}

export function facadeSurfaceSummaryLabel(surface) {
    return normalizeFacadeSurface(surface) === RESPONSES_SURFACE
        ? "Responses façade"
        : "Chat façade";
}

export function readFacadeSurfaceFromHash(hashText, helpers = {}) {
    const firstNonBlank = helpers.firstNonBlank;
    if (typeof firstNonBlank !== "function") {
        throw new TypeError("firstNonBlank helper is required");
    }
    const params = new URLSearchParams(String(hashText || "").replace(/^#/, ""));
    return normalizeFacadeSurface(firstNonBlank(
        params.get("facade"),
        params.get("surface_facade")
    ));
}

export function writeFacadeSurfaceToParams(surface, params) {
    if (!(params instanceof URLSearchParams)) {
        throw new TypeError("URLSearchParams is required");
    }
    const hashValue = facadeSurfaceHashValue(surface);
    if (hashValue) {
        params.set("facade", hashValue);
        return params;
    }
    params.delete("facade");
    params.delete("surface_facade");
    return params;
}
