package com.agentcloud.server;

import com.agentcloud.engine.HarnessState;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.store.ToolInvocationDao;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenEyesHealthEnricherTest {

    @Test
    void snapshotReturnsUnknownForNullState() {
        Map<String, Object> snapshot = OpenEyesHealthEnricher.snapshot(null, null);
        assertEquals("unknown", snapshot.get("status"));
    }

    @Test
    void snapshotReturnsUnknownForNullToolInvocationDaoWhenStateMissing() {
        Map<String, Object> snapshot = OpenEyesHealthEnricher.snapshot(null, null);
        assertFalse(snapshot.containsKey("tool_calls"));
    }

    @Test
    void healthyStateAggregatesToolCallMetrics() {
        HarnessState.EyesMcpStatus status = new HarnessState.EyesMcpStatus(
            true, "healthy", "openeyes", "1.26.0", 13, 1200L, null, Instant.now());
        HarnessState state = new HarnessState(
            Instant.now(), false, List.of(), Map.of(), Map.of(), Map.of(), 0, status);
        ToolInvocationRecord latest = new ToolInvocationRecord(
            "invoke-1", "session", "task", "worker", "execution", OpenEyesToolCallMetrics.TOOL_NAME,
            Map.of("subcommand", "windows"), "found window", "succeeded", true, 180,
            List.of(), Instant.now(), Map.of("subcommand", "windows"));
        ToolInvocationDao dao = new MetricsDao(2L, 1L, List.of(120, 180), List.of(latest));

        Map<String, Object> snapshot = OpenEyesHealthEnricher.snapshot(state, dao);

        @SuppressWarnings("unchecked")
        Map<String, Object> toolCalls = (Map<String, Object>) snapshot.get("tool_calls");
        assertEquals("healthy", snapshot.get("status"));
        assertEquals(2L, toolCalls.get("count"));
        assertEquals(1L, toolCalls.get("success_count"));
        assertEquals(0.5, (Double) toolCalls.get("success_rate"), 0.0001);
        assertEquals(150, toolCalls.get("p50_ms"));
        assertEquals(177, toolCalls.get("p95_ms"));
        assertEquals("windows", toolCalls.get("last_subcommand"));
    }

    private record MetricsDao(
        long total,
        long success,
        List<Integer> elapsed,
        List<ToolInvocationRecord> recent
    ) implements ToolInvocationDao {
        @Override
        public void insertRaw(String id, String sessionId, String taskId, String workerId, String executionId,
                              String toolName, String arguments, String resultSummary, String status,
                              boolean success, Integer elapsedMs, String touchedPaths, Instant createdAt,
                              String metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ToolInvocationRecord> listByTask(String taskId, int limit) {
            return recent;
        }

        @Override
        public List<ToolInvocationRecord> listBySessionAndTask(String sessionId, String taskId, int limit) {
            return recent;
        }

        @Override
        public long countByTool(String toolName) {
            return total;
        }

        @Override
        public long countByToolAndSuccess(String toolName) {
            return success;
        }

        @Override
        public List<Integer> elapsedMillisByTool(String toolName) {
            return elapsed;
        }

        @Override
        public List<ToolInvocationRecord> listRecentByTool(String toolName, int limit) {
            return recent;
        }

        @Override
        public Handle getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, X extends Exception> R withHandle(HandleCallback<R, X> callback) throws X {
            throw new UnsupportedOperationException();
        }
    }
}
