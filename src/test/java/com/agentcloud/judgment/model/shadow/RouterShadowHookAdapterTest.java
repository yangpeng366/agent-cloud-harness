package com.agentcloud.judgment.model.shadow;

import com.agentcloud.model.Task;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterShadowHookAdapterTest {

    @Test
    void fillsEnvelopeMetadataFromHeuristicProvider(@TempDir Path dataDir) {
        Path jsonl = dataDir.resolve("adapter.jsonl");
        DecisionEventSink sink = new JsonlDecisionEventSink(jsonl);
        RouterShadowService service = new RouterShadowService(new HeuristicRouterProvider(), sink);
        RouterShadowHookAdapter adapter = new RouterShadowHookAdapter(service);

        Task task = Task.create("task-1", "session-9", "deploy patch", "active", "high");
        TaskRuntimeContext ctx = new TaskRuntimeContext(
                task, null, null, List.of(), List.of(), List.of(), List.of(),
                List.of(), null);
        String executionId = task.id() + ":codex:" + System.currentTimeMillis();

        Map<String, Object> meta = adapter.preEnvelope(ctx, "codex", executionId);

        assertNotNull(meta);
        assertEquals(5, meta.size(), "应返回 5 个 routing_* 字段");
        assertNotNull(meta.get("routing_decision_id"));
        assertEquals("strong", meta.get("routing_tier"), "high priority → strong");
        assertEquals(false, meta.get("routing_degraded"));
        assertEquals("heuristic-shadow-v1", meta.get("routing_provider"));
        assertTrue(meta.get("routing_scores") instanceof Map);

        List<DecisionEvent> snap = sink.drainSnapshot();
        assertEquals(1, snap.size(), "sink 应收到一条 DecisionEvent");
        DecisionEvent evt = snap.get(0);
        assertEquals("session-9", evt.runId(), "runId 来自 task.sessionId()");
        assertEquals(executionId, evt.turnId(), "turnId 来自 executionId");
        assertEquals("task-1", evt.taskId());
        assertEquals("router", evt.kind());
    }

    @Test
    void lowPriorityMapsToFast(@TempDir Path dataDir) {
        Path jsonl = dataDir.resolve("adapter-low.jsonl");
        DecisionEventSink sink = new JsonlDecisionEventSink(jsonl);
        RouterShadowService service = new RouterShadowService(new HeuristicRouterProvider(), sink);
        RouterShadowHookAdapter adapter = new RouterShadowHookAdapter(service);

        Task task = Task.create("task-2", "session-1", "minor edit", "active", "low");
        TaskRuntimeContext ctx = new TaskRuntimeContext(
                task, null, null, List.of(), List.of(), List.of(), List.of(),
                List.of(), null);

        Map<String, Object> meta = adapter.preEnvelope(ctx, "openclaw-native", "exec-2");
        assertEquals("fast", meta.get("routing_tier"), "low priority → fast");
    }

    @Test
    void nullContextReturnsEmpty(@TempDir Path dataDir) {
        Path jsonl = dataDir.resolve("adapter-null.jsonl");
        DecisionEventSink sink = new JsonlDecisionEventSink(jsonl);
        RouterShadowService service = new RouterShadowService(new HeuristicRouterProvider(), sink);
        RouterShadowHookAdapter adapter = new RouterShadowHookAdapter(service);

        Map<String, Object> meta = adapter.preEnvelope(null, "codex", "exec-x");
        assertTrue(meta.isEmpty(), "null context 必须 no-op");
    }

    @Test
    void providerRuntimeExceptionMarksDegraded(@TempDir Path dataDir) {
        Path jsonl = dataDir.resolve("adapter-degraded.jsonl");
        DecisionEventSink sink = new JsonlDecisionEventSink(jsonl);
        RouterProvider throwing = new RouterProvider() {
            @Override
            public JudgmentDecision decide(JudgmentRequest request) {
                throw new IllegalStateException("provider offline");
            }
            @Override
            public String providerRef() {
                return "fake-throwing";
            }
        };
        RouterShadowService service = new RouterShadowService(throwing, sink);
        RouterShadowHookAdapter adapter = new RouterShadowHookAdapter(service);

        Task task = Task.create("task-3", "session-2", "any", "active", "medium");
        TaskRuntimeContext ctx = new TaskRuntimeContext(
                task, null, null, List.of(), List.of(), List.of(), List.of(),
                List.of(), null);

        Map<String, Object> meta = adapter.preEnvelope(ctx, "codex", "exec-degraded");
        assertEquals("balanced", meta.get("routing_tier"), "Provider 异常时降级 balanced");
        assertEquals(true, meta.get("routing_degraded"));
        assertEquals("fake-throwing", meta.get("routing_provider"));
        assertNotNull(meta.get("routing_decision_id"));

        List<DecisionEvent> snap = sink.drainSnapshot();
        assertEquals(1, snap.size());
        assertTrue(snap.get(0).degraded(), "DecisionEvent 必须标 degraded=true");
    }

    @Test
    void adapterRejectsNullService() {
        assertThrows(IllegalArgumentException.class, () -> new RouterShadowHookAdapter(null));
    }
}