package com.agentcloud.judgment.model.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifierShadowServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void highQualityGroundedAccept(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("verifier.jsonl");
        VerifierShadowService service = new VerifierShadowService(new HeuristicVerifierProvider(),
                new JsonlDecisionEventSink(jsonl));

        JudgmentDecision d = service.observe("run-1", "turn-1", "task-A", "worker:codex",
                0.92, 0.88, 0.05, false, false);

        assertEquals("accept", d.action());
        assertFalse(d.degraded());
        assertEquals(0.92, d.confidence());

        List<String> lines = Files.readAllLines(jsonl);
        assertEquals(1, lines.size());
        JsonNode n = MAPPER.readTree(lines.get(0));
        assertEquals("verifier", n.get("kind").asText());
        assertEquals("accept", n.get("action").asText());
        assertEquals("heuristic-shadow-v1", n.get("modelRef").asText());
        assertEquals("record_only", n.get("shadowMode").asText());
        assertTrue(n.has("scores"));
    }

    @Test
    void gapMapsToRetryOnce(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("verifier.jsonl");
        VerifierShadowService service = new VerifierShadowService(new HeuristicVerifierProvider(),
                new JsonlDecisionEventSink(jsonl));

        JudgmentDecision d = service.observe("run-1", "turn-2", "task-A", "worker:codex",
                0.60, 0.55, 0.50, false, false);

        assertEquals("retry_once", d.action());
        assertEquals("completion_gap_or_errors", d.reasonCodes().get("policy"));
    }

    @Test
    void lowQualityForcesHandoff(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("verifier.jsonl");
        VerifierShadowService service = new VerifierShadowService(new HeuristicVerifierProvider(),
                new JsonlDecisionEventSink(jsonl));

        JudgmentDecision d = service.observe("run-1", "turn-3", "task-A", "worker:codex",
                0.20, 0.85, 0.10, false, false);

        assertEquals("handoff", d.action());
        assertEquals("quality_or_grounded_below_handoff_min", d.reasonCodes().get("policy"));
    }

    @Test
    void highStakesForcesHandoffEvenWithHighScores(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("verifier.jsonl");
        VerifierShadowService service = new VerifierShadowService(new HeuristicVerifierProvider(),
                new JsonlDecisionEventSink(jsonl));

        JudgmentDecision d = service.observe("run-1", "turn-4", "task-A", "worker:codex",
                0.95, 0.95, 0.0, true, false);

        assertEquals("handoff", d.action(), "高风险任务即使高分也强制 handoff（参见 48 条 verifier 决策表）");
        assertEquals("needs_human_or_high_stakes", d.reasonCodes().get("policy"));
    }

    @Test
    void needsHumanForcesHandoff(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("verifier.jsonl");
        VerifierShadowService service = new VerifierShadowService(new HeuristicVerifierProvider(),
                new JsonlDecisionEventSink(jsonl));

        JudgmentDecision d = service.observe("run-1", "turn-5", "task-A", "worker:codex",
                0.90, 0.90, 0.0, false, true);

        assertEquals("handoff", d.action());
    }

    @Test
    void providerRuntimeExceptionMarksHandoff(@TempDir Path tmp) throws Exception {
        Path jsonl = tmp.resolve("verifier.jsonl");
        VerifierProvider throwing = new VerifierProvider() {
            @Override
            public JudgmentDecision decide(JudgmentRequest request) {
                throw new IllegalStateException("verifier offline");
            }
            @Override
            public String providerRef() {
                return "fake-throwing-vf";
            }
        };
        VerifierShadowService service = new VerifierShadowService(throwing, new JsonlDecisionEventSink(jsonl));

        JudgmentDecision d = service.observe("run-1", "turn-6", "task-A", "worker:codex",
                0.90, 0.90, 0.0, false, false);

        assertEquals("handoff", d.action(), "Provider 异常时降级 handoff（verification_unavailable）");
        assertTrue(d.degraded());
        assertEquals("fake-throwing-vf", d.modelRef());
        assertNotNull(d.reasonCodes().get("fallback"));

        List<String> lines = Files.readAllLines(jsonl);
        JsonNode n = MAPPER.readTree(lines.get(0));
        assertTrue(n.get("degraded").asBoolean());
    }

    @Test
    void missingRunOrTurnRejected(@TempDir Path tmp) {
        VerifierShadowService service = new VerifierShadowService(new HeuristicVerifierProvider(),
                new JsonlDecisionEventSink(tmp.resolve("x.jsonl")));
        assertThrows(IllegalArgumentException.class,
                () -> service.observe(null, "turn-1", "task-A", "subj", 0.5, 0.5, 0.0, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> service.observe("run-1", "", "task-A", "subj", 0.5, 0.5, 0.0, false, false));
    }

    @Test
    void rejectsNullProviderOrSink() {
        assertThrows(IllegalArgumentException.class, () -> new VerifierShadowService(null,
                new JsonlDecisionEventSink(Path.of("x"))));
        VerifierProvider p = new HeuristicVerifierProvider();
        assertThrows(IllegalArgumentException.class, () -> new VerifierShadowService(p, null));
    }
}