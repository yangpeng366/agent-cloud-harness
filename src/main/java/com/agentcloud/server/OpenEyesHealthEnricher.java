package com.agentcloud.server;

import com.agentcloud.engine.HarnessState;
import com.agentcloud.store.ToolInvocationDao;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Eyes-mcp channel payload enrichment for /api/v1/health.
 *
 * Returns status / available / server_name / server_version / tool_count /
 * duration_ms / error / checked_at + tool_calls count / p50_ms / p95_ms /
 * success_rate / last_subcommand / last_success when the channel reports
 * healthy and the tool_invocation table is reachable.
 */
public final class OpenEyesHealthEnricher {

    private OpenEyesHealthEnricher() {
    }

    public static Map<String, Object> snapshot(HarnessState state, ToolInvocationDao toolInvocationDao) {
        if (state == null || state.eyesMcp() == null) {
            Map<String, Object> unknown = new LinkedHashMap<>();
            unknown.put("status", "unknown");
            return unknown;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", state.eyesMcp().status());
        snapshot.put("available", state.eyesMcp().available());
        snapshot.put("server_name", state.eyesMcp().serverName() == null ? "" : state.eyesMcp().serverName());
        snapshot.put("server_version", state.eyesMcp().serverVersion() == null ? "" : state.eyesMcp().serverVersion());
        snapshot.put("tool_count", state.eyesMcp().toolCount());
        snapshot.put("duration_ms", state.eyesMcp().durationMs());
        snapshot.put("error", state.eyesMcp().error() == null ? "" : state.eyesMcp().error());
        snapshot.put("checked_at", state.eyesMcp().checkedAt() == null ? "" : state.eyesMcp().checkedAt().toString());
        if ("healthy".equalsIgnoreCase(state.eyesMcp().status()) && toolInvocationDao != null) {
            snapshot.put("tool_calls", OpenEyesToolCallMetrics.snapshot(toolInvocationDao));
        }
        return snapshot;
    }
}
