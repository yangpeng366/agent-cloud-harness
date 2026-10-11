package com.agentcloud.judgment.model.shadow.stats;

import com.agentcloud.judgment.model.shadow.DecisionEvent;
import com.agentcloud.judgment.model.shadow.DecisionEventSink;
import com.agentcloud.judgment.model.shadow.HeuristicRouterProvider;
import com.agentcloud.judgment.model.shadow.HeuristicToolGateProvider;
import com.agentcloud.judgment.model.shadow.HeuristicVerifierProvider;
import com.agentcloud.judgment.model.shadow.JsonlDecisionEventSink;
import com.agentcloud.judgment.model.shadow.RouterShadowService;
import com.agentcloud.judgment.model.shadow.ToolGateShadowService;
import com.agentcloud.judgment.model.shadow.VerifierShadowService;
import com.agentcloud.judgment.model.shadow.stats.DecisionEventReasonStats.ReasonSummary;
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

class DecisionEventReasonStatsTest {

    @Test
    void emptyFileReturnsZeroSummary(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("empty.jsonl");
        Files.writeString(jsonl, "");
        ReasonSummary s = DecisionEventReasonStats.compute(jsonl);
        assertEquals(0L, s.totalEvents());
        assertEquals(0L, s.skippedLines());
        assertNotNull(s.reasonsByKind());
    }

    @Test
    void missingFileReturnsZero(@TempDir Path tmp) throws Exception {
        ReasonSummary s = DecisionEventReasonStats.compute(tmp.resolve("nope.jsonl"));
        assertEquals(0L, s.totalEvents());
    }

    @Test
    void aggregatesReasonsAcrossKinds(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("mixed.jsonl");
        DecisionEventSink sink = new JsonlDecisionEventSink(jsonl, 64);
        RouterShadowService router = new RouterShadowService(new HeuristicRouterProvider(), sink);
        ToolGateShadowService gate = new ToolGateShadowService(new HeuristicToolGateProvider(), sink);
        VerifierShadowService verifier = new VerifierShadowService(new HeuristicVerifierProvider(), sink);

        // router: 1 human_required (high_stakes), 1 strong (difficulty), 1 fast (low)
        router.observe("run-A", "turn-1", "task-A", "worker:codex",
                Map.of("difficulty", 0.92, "high_stakes", true));
        router.observe("run-A", "turn-2", "task-A", "worker:codex",
                Map.of("difficulty", 0.85, "needs_browser", true));
        router.observe("run-A", "turn-3", "task-A", "worker:openclaw",
                Map.of("difficulty", 0.10));

        // tool_gate: 1 block (destructive), 1 approval_required (network)
        gate.observe(new ToolInvocationRecord(
                "tinv-1", "run-A", "task-A", "codex", "exec-1",
                "delete_file", Map.of("path", "/tmp/x"),
                "blocked", "blocked", false, 0,
                List.of(), Instant.now(), Map.of()));
        gate.observe(new ToolInvocationRecord(
                "tinv-2", "run-A", "task-A", "codex", "exec-1",
                "bash", Map.of("command", "curl https://example.com/x"),
                "approval-pending", "approval_required", true, 0,
                List.of(), Instant.now(), Map.of()));

        // verifier: 1 accept, 1 handoff (low quality)
        verifier.observe("run-A", "turn-1", "task-A", "worker:codex",
                0.92, 0.88, 0.05, false, false);
        verifier.observe("run-A", "turn-2", "task-A", "worker:codex",
                0.20, 0.85, 0.10, false, false);

        ReasonSummary s = DecisionEventReasonStats.compute(jsonl);

        // router reasons
        assertEquals(1L, s.reasonsByKind().getOrDefault("router:human_or_high_stakes_flag_set", 0L));
        assertEquals(1L, s.reasonsByKind().getOrDefault("router:difficulty_or_needs_browser_above_strong_min", 0L));
        assertEquals(1L, s.reasonsByKind().getOrDefault("router:difficulty_below_fast_max", 0L));

        // tool_gate reasons
        assertEquals(1L, s.reasonsByKind().getOrDefault("tool_gate:destructive_detected", 0L));
        assertEquals(1L, s.reasonsByKind().getOrDefault("tool_gate:network_egress_or_credential_use", 0L));

        // verifier reasons
        assertEquals(1L, s.reasonsByKind().getOrDefault("verifier:quality_and_grounded_above_accept_min", 0L));
        assertEquals(1L, s.reasonsByKind().getOrDefault("verifier:quality_or_grounded_below_handoff_min", 0L));

        // cross-tab
        assertEquals(1L, s.reasonsByKindAndAction().getOrDefault("router:human_required:human_or_high_stakes_flag_set", 0L));
        assertEquals(1L, s.reasonsByKindAndAction().getOrDefault("tool_gate:block:destructive_detected", 0L));
        assertEquals(1L, s.reasonsByKindAndAction().getOrDefault("verifier:handoff:quality_or_grounded_below_handoff_min", 0L));

        assertEquals(0L, s.degradedReasons().size(), "heuristic 默认不 degraded，degradedReasons 为空");
    }

    @Test
    void corruptedLinesAreSkipped(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("corrupt.jsonl");
        Files.writeString(jsonl,
                "{\"eventId\":\"e1\",\"runId\":\"r\",\"turnId\":\"t\",\"kind\":\"router\",\"action\":\"fast\",\"degraded\":false,\"modelRef\":\"m\",\"occurredAt\":\"2026-10-11T01:00:00Z\",\"shadowMode\":\"record_only\",\"reasonCodes\":{\"policy\":\"difficulty_below_fast_max\"}}\n"
              + "this is not json\n"
              + "{ broken\n"
              + "{\"eventId\":\"e2\",\"runId\":\"r\",\"turnId\":\"t\",\"kind\":\"router\",\"action\":\"balanced\",\"degraded\":false,\"modelRef\":\"m\",\"occurredAt\":\"2026-10-11T01:00:01Z\",\"shadowMode\":\"record_only\",\"reasonCodes\":{\"policy\":\"difficulty_in_balanced_band\"}}\n"
        );
        ReasonSummary s = DecisionEventReasonStats.compute(jsonl);
        assertEquals(2L, s.totalEvents(), "坏行跳过");
        assertEquals(2L, s.skippedLines());
        assertEquals(1L, s.reasonsByKind().getOrDefault("router:difficulty_below_fast_max", 0L));
        assertEquals(1L, s.reasonsByKind().getOrDefault("router:difficulty_in_balanced_band", 0L));
    }

    @Test
    void computeOverInMemoryListHandlesNulls(@TempDir Path tmp) {
        ReasonSummary s = DecisionEventReasonStats.compute(List.of());
        assertEquals(0L, s.totalEvents());

        DecisionEvent evt = new DecisionEvent(
                "e3", Instant.parse("2026-10-11T02:00:00Z"),
                "run-X", "turn-1", "task-X", "worker:w",
                "router", "shadow-default", "shadow-default",
                Map.of("difficulty", 0.5), 0.5, "balanced",
                false, "heuristic-shadow-v1", 12L, 0.0,
                Map.of("policy", "difficulty_in_balanced_band"),
                Map.of(), "record_only");
        ReasonSummary s2 = DecisionEventReasonStats.compute(java.util.Arrays.asList(evt, null));
        assertEquals(1L, s2.totalEvents(), "null 元素应跳过");
        assertTrue(s2.reasonsByKind().containsKey("router:difficulty_in_balanced_band"));
    }
}