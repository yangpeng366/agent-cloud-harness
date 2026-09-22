package com.agentcloud.server;

import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.store.ToolInvocationDao;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates OpenEyes worker tool call metrics from the tool_invocations table.
 *
 * Used by /api/v1/health.eyes_mcp.tool_calls so that closing eyes_mcp.enabled=false
 * drops call_count/success_rate back to 0, matching FEAT-04 path B contract.
 */
public final class OpenEyesToolCallMetrics {

    public static final String TOOL_NAME = "openeyes";

    private OpenEyesToolCallMetrics() {
    }

    public static Map<String, Object> snapshot(ToolInvocationDao dao) {
        if (dao == null) {
            return disabled();
        }
        long total = dao.countByTool(TOOL_NAME);
        long success = dao.countByToolAndSuccess(TOOL_NAME);
        List<Integer> elapsedMillis = dao.elapsedMillisByTool(TOOL_NAME);
        List<ToolInvocationRecord> recent = dao.listRecentByTool(TOOL_NAME, 5);
        return snapshot(total, success, elapsedMillis, recent);
    }

    static Map<String, Object> snapshot(long total, long success, List<Integer> elapsedMillis, List<ToolInvocationRecord> recent) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("count", total);
        snapshot.put("success_count", success);
        snapshot.put("success_rate", total == 0 ? null : (double) success / (double) total);
        snapshot.put("p50_ms", percentile(elapsedMillis, 0.5));
        snapshot.put("p95_ms", percentile(elapsedMillis, 0.95));
        snapshot.put("last_subcommand", lastSubcommand(recent));
        snapshot.put("last_status", lastStatus(recent));
        snapshot.put("last_success", lastSuccess(recent));
        return snapshot;
    }

    private static Map<String, Object> disabled() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("count", 0L);
        snapshot.put("success_count", 0L);
        snapshot.put("success_rate", null);
        snapshot.put("p50_ms", null);
        snapshot.put("p95_ms", null);
        snapshot.put("last_subcommand", "");
        snapshot.put("last_status", "");
        snapshot.put("last_success", null);
        return snapshot;
    }

    static Integer percentile(List<Integer> values, double quantile) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        int[] sorted = values.stream().mapToInt(Integer::intValue).sorted().toArray();
        if (sorted.length == 1) {
            return sorted[0];
        }
        double rank = quantile * (sorted.length - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted[lower];
        }
        double fraction = rank - lower;
        int interpolated = (int) Math.round(sorted[lower] + fraction * (sorted[upper] - sorted[lower]));
        return interpolated;
    }

    private static String lastSubcommand(List<ToolInvocationRecord> recent) {
        ToolInvocationRecord latest = firstNonNull(recent);
        if (latest == null || latest.metadata() == null) {
            return "";
        }
        Object subcommand = latest.metadata().get("subcommand");
        return subcommand == null ? "" : subcommand.toString();
    }

    private static String lastStatus(List<ToolInvocationRecord> recent) {
        ToolInvocationRecord latest = firstNonNull(recent);
        if (latest == null) {
            return "";
        }
        return latest.status() == null ? "" : latest.status();
    }

    private static Boolean lastSuccess(List<ToolInvocationRecord> recent) {
        ToolInvocationRecord latest = firstNonNull(recent);
        if (latest == null) {
            return null;
        }
        return latest.success();
    }

    private static ToolInvocationRecord firstNonNull(List<ToolInvocationRecord> recent) {
        if (recent == null || recent.isEmpty()) {
            return null;
        }
        return recent.get(0);
    }
}