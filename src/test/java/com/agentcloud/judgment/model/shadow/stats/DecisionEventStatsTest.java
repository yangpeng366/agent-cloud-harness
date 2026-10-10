package com.agentcloud.judgment.model.shadow.stats;

import com.agentcloud.judgment.model.shadow.DecisionEvent;
import com.agentcloud.judgment.model.shadow.DecisionEventSink;
import com.agentcloud.judgment.model.shadow.HeuristicRouterProvider;
import com.agentcloud.judgment.model.shadow.JsonlDecisionEventSink;
import com.agentcloud.judgment.model.shadow.RouterShadowService;
import com.agentcloud.judgment.model.shadow.HeuristicToolGateProvider;
import com.agentcloud.judgment.model.shadow.ToolGateShadowService;
import com.agentcloud.judgment.model.shadow.HeuristicVerifierProvider;
import com.agentcloud.judgment.model.shadow.VerifierShadowService;
import com.agentcloud.judgment.model.shadow.stats.DecisionEventStats.Summary;
import com.agentcloud.model.ToolInvocationRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionEventStatsTest {

    @Test
    void emptyFileReturnsZeroSummary(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("empty.jsonl");
        Files.writeString(jsonl, "");
        Summary s = DecisionEventStats.compute(jsonl);
        assertEquals(0L, s.totalEvents());
        assertEquals(0L, s.degradedCount());
        assertEquals(0L, s.uniqueRunIds());
        assertEquals(0L, s.skippedLines());
        assertNotNull(s.eventsByKind());
    }

    @Test
    void missingFileReturnsZeroSummary(@TempDir Path tmp) throws Exception {
        Summary s = DecisionEventStats.compute(tmp.resolve("nope.jsonl"));
        assertEquals(0L, s.totalEvents());
    }

    @Test
    void computeAggregatesAcrossKinds(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("mixed.jsonl");
        DecisionEventSink sink = new JsonlDecisionEventSink(jsonl, 64);
        RouterShadowService router = new RouterShadowService(new HeuristicRouterProvider(), sink);
        ToolGateShadowService gate = new ToolGateShadowService(new HeuristicToolGateProvider(), sink);
        VerifierShadowService verifier = new VerifierShadowService(new HeuristicVerifierProvider(), sink);

        router.observe("run-A", "turn-1", "task-A", "worker:codex",
                Map.of("difficulty", 0.92, "high_stakes", true));
        router.observe("run-A", "turn-2", "task-A", "worker:codex",
                Map.of("difficulty", 0.10));
        router.observe("run-B", "turn-1", "task-B", "worker:openclaw",
                Map.of("difficulty", 0.55));

        gate.observe(new ToolInvocationRecord(
                "tinv-1", "run-A", "task-A", "codex", "exec-1",
                "delete_file", Map.of("path", "/tmp/x"),
                "blocked", "blocked", false, 0,
                List.of(), Instant.now(), Map.of()));
        gate.observe(new ToolInvocationRecord(
                "tinv-2", "run-A", "task-A", "codex", "exec-1",
                "read_file", Map.of("path", "/workspace/readme.md"),
                "ok", "succeeded", true, 12,
                List.of(), Instant.now(), Map.of()));

        verifier.observe("run-A", "turn-1", "task-A", "worker:codex",
                0.92, 0.88, 0.05, false, false);
        verifier.observe("run-A", "turn-2", "task-A", "worker:codex",
                0.30, 0.20, 0.10, false, false);

        Summary s = DecisionEventStats.compute(jsonl);
        assertEquals(7L, s.totalEvents());
        assertEquals(3L, s.eventsByKind().getOrDefault("router", 0L));
        assertEquals(2L, s.eventsByKind().getOrDefault("tool_gate", 0L));
        assertEquals(2L, s.eventsByKind().getOrDefault("verifier", 0L));

        assertEquals(2L, s.uniqueRunIds(), "run-A + run-B");

        assertEquals(0L, s.degradedCount(), "heuristic 默认不 degraded");

        assertEquals(1L, s.actionsByKind().getOrDefault("router:human_required", 0L));
        assertEquals(1L, s.actionsByKind().getOrDefault("router:fast", 0L));
        assertEquals(1L, s.actionsByKind().getOrDefault("router:balanced", 0L));
        assertEquals(1L, s.actionsByKind().getOrDefault("tool_gate:block", 0L));
        assertEquals(1L, s.actionsByKind().getOrDefault("tool_gate:allow", 0L));
        assertEquals(1L, s.actionsByKind().getOrDefault("verifier:accept", 0L));
        assertEquals(1L, s.actionsByKind().getOrDefault("verifier:handoff", 0L));

        assertTrue(s.modelRefsByKind().getOrDefault("router:heuristic-shadow-v1", 0L) > 0);
        assertTrue(s.firstEventMs() > 0L, "时间窗起点应 > 0");
        assertTrue(s.lastEventMs() >= s.firstEventMs());
    }

    @Test
    void corruptedLinesAreSkipped(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("corrupt.jsonl");
        Files.writeString(jsonl,
                "{\"eventId\":\"e1\",\"runId\":\"r\",\"turnId\":\"t\",\"kind\":\"router\",\"action\":\"fast\",\"degraded\":false,\"modelRef\":\"m\",\"occurredAt\":\"2026-10-11T01:00:00Z\",\"shadowMode\":\"record_only\"}\n"
              + "this is not json\n"
              + "{ broken\n"
              + "{\"eventId\":\"e2\",\"runId\":\"r\",\"turnId\":\"t\",\"kind\":\"router\",\"action\":\"balanced\",\"degraded\":false,\"modelRef\":\"m\",\"occurredAt\":\"2026-10-11T01:00:01Z\",\"shadowMode\":\"record_only\"}\n"
        );
        Summary s = DecisionEventStats.compute(jsonl);
        assertEquals(2L, s.totalEvents(), "坏行跳过");
        assertEquals(2L, s.skippedLines());
    }

    @Test
    void computeOverInMemoryListHandlesNulls(@TempDir Path tmp) {
        Summary s = DecisionEventStats.compute(List.of());
        assertEquals(0L, s.totalEvents());

        DecisionEvent evt = new DecisionEvent(
                "e3", Instant.parse("2026-10-11T02:00:00Z"),
                "run-X", "turn-1", "task-X", "worker:w",
                "router", "shadow-default", "shadow-default",
                Map.of("difficulty", 0.5), 0.5, "balanced",
                false, "heuristic-shadow-v1", 12L, 0.0,
                Map.of(), Map.of(), "record_only");
        Summary s2 = DecisionEventStats.compute(java.util.Arrays.asList(evt, null));
        assertEquals(1L, s2.totalEvents(), "null 元素应跳过");
        assertEquals(1L, s2.eventsByKind().getOrDefault("router", 0L));
    }
}