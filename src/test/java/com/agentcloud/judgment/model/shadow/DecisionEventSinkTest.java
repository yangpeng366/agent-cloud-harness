package com.agentcloud.judgment.model.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionEventSinkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void appendsOneJsonLinePerEventAndPreservesRing(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("decision.jsonl");
        JsonlDecisionEventSink sink = new JsonlDecisionEventSink(target, 8);

        DecisionEvent e1 = sample("evt-1", "run-A", "tool_gate", "approval_required", 0.82);
        DecisionEvent e2 = sample("evt-2", "run-A", "router", "balanced", 0.40);

        sink.append(e1);
        sink.append(e2);

        List<String> lines = Files.readAllLines(target);
        assertEquals(2, lines.size(), "JSONL 应写两行");

        JsonNode n1 = MAPPER.readTree(lines.get(0));
        JsonNode n2 = MAPPER.readTree(lines.get(1));
        assertEquals("tool_gate", n1.get("kind").asText());
        assertEquals("approval_required", n1.get("action").asText());
        assertEquals("router", n2.get("kind").asText());
        assertEquals("balanced", n2.get("action").asText());
        assertEquals("record_only", n1.get("shadowMode").asText(), "M0 必须 record_only");
        assertFalse(n1.get("degraded").asBoolean(), "正常样本 degraded=false");

        List<DecisionEvent> snap = sink.drainSnapshot();
        assertEquals(2, snap.size(), "ring 应保留两条");
        assertEquals("evt-1", snap.get(0).eventId());
        assertEquals("evt-2", snap.get(1).eventId());
    }

    @Test
    void ringKeepsAtMostCapEntries(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("decision.jsonl");
        JsonlDecisionEventSink sink = new JsonlDecisionEventSink(target, 3);

        for (int i = 0; i < 5; i++) {
            sink.append(sample("evt-" + i, "run-X", "router", "fast", 0.10));
        }

        assertEquals(5, Files.readAllLines(target).size(), "磁盘仍写满 5 行");
        List<DecisionEvent> snap = sink.drainSnapshot();
        assertEquals(3, snap.size(), "ring 截断到 cap=3");
        assertEquals("evt-2", snap.get(0).eventId());
        assertEquals("evt-3", snap.get(1).eventId());
        assertEquals("evt-4", snap.get(2).eventId());
    }

    @Test
    void missingRequiredFieldsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DecisionEvent(
                null, Instant.now(), "run", "turn", "task", "subj",
                "router", "v1", "v1", Map.of(), 0.0, "balanced",
                false, "mock", 0L, 0.0, Map.of(), Map.of(), "record_only"
        ));
        assertThrows(IllegalArgumentException.class, () -> new JudgmentRequest(
                null, "run", "turn", JudgmentKind.ROUTER, "subj",
                Map.of(), "v1", Map.of(), 1500L
        ));
        assertThrows(IllegalArgumentException.class, () -> new DecisionPolicy(
                null, "global", Map.of(), Map.of(), Map.of(), "default_balanced", "off"
        ));
        assertThrows(IllegalArgumentException.class, () -> new JudgmentDecision(
                null, "balanced", Map.of(), 0.0, "v1", false, "mock", 0L, 0.0, Map.of()
        ));
    }

    @Test
    void decisionPolicyShadowDefaultIsStable() {
        DecisionPolicy p = DecisionPolicy.shadowDefault();
        assertEquals("shadow-default", p.version());
        assertEquals("global", p.scope());
        assertEquals(0.30, p.routeThresholds().get("fast_max"));
        assertEquals(0.85, p.gateThresholds().get("block_min"));
        assertEquals(0.70, p.verifierThresholds().get("accept_min"));
        assertEquals("default_balanced", p.fallbackPolicy());
        assertNotNull(p);
    }

    @Test
    void fallbackDecisionMarksDegraded() {
        JudgmentDecision d = JudgmentDecision.fallback("j-1", "approval_required", "jev-unavailable", "jev-mock", 12L);
        assertTrue(d.degraded(), "降级决策必须标 degraded=true");
        assertEquals("approval_required", d.action());
        assertEquals("approval_required_only", d.reasonCodes().get("fallback") == null ? null : "approval_required_only");
    }

    private DecisionEvent sample(String eventId, String runId, String kind, String action, double confidence) {
        return new DecisionEvent(
                eventId, Instant.parse("2026-10-09T01:23:45Z"),
                runId, "turn-1", "task-X", "subj:" + kind,
                kind, "shadow-default", "shadow-default",
                Map.of("risk", confidence), confidence, action,
                false, "jev-mock", 35L, 0.001,
                Map.of("policy", "shadow-default"),
                Map.of("path_hash", "abc123", "size_bytes", 1024),
                "record_only"
        );
    }
}