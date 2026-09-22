function normalize(value) {
    return String(value || "").trim().toLowerCase();
}

export function buildOpenEyesMcpHealthPlan(eyesMcp = {}) {
    const available = eyesMcp.available === true;
    const status = normalize(eyesMcp.status)
        || (available ? "healthy" : "unknown");
    const toolCount = Number(eyesMcp.tool_count || eyesMcp.toolCount || 0);
    const server = [eyesMcp.server_name, eyesMcp.server_version]
        .filter(Boolean).join(" ");
    const error = eyesMcp.error || "";

    if (status === "disabled") {
        return { tone: "paused", label: "disabled", headline: "OpenEyes MCP 未启用", detail: "启动可达性检查处于关闭状态。" };
    }
    if (available || status === "healthy") {
        if (toolCount > 0) {
            return {
                tone: "done",
                label: "healthy",
                headline: "OpenEyes MCP 可用",
                detail: `${server || "openeyes"} · ${toolCount} tools`
            };
        }
        return { tone: "failed", label: "unhealthy", headline: "OpenEyes MCP 工具列表为空", detail: "stdio 初始化成功，但 tools/list 未返回工具。" };
    }
    return {
        tone: "failed",
        label: "unhealthy",
        headline: "OpenEyes MCP 不可用",
        detail: error || "stdio 握手或 tools/list 失败。"
    };
}
