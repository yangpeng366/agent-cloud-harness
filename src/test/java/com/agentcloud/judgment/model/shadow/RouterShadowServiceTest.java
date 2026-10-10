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

class RouterShadowServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void heuristicRoutesFastBalancedStrongAndHuman(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("router-shadow.jsonl");
        DecisionEventSink sink = new JsonlDecisionEventSink(target, 16);
        RouterShadowService service = new RouterShadowService(new HeuristicRouterProvider(), sink);

        JudgmentDecision fast = service.observe("run-1", "turn-1", "task-A",
                "worker:default", Map.of("difficulty", 0.10, "side_effect_risk", 0.05));
        JudgmentDecision balanced = service.observe("run-1", "turn-2", "task-A",
                "worker:default", Map.of("difficulty", 0.55, "side_effect_risk", 0.30));
        JudgmentDecision strong = service.observe("run-1", "turn-3", "task-A",
                "worker:default", Map.of("difficulty", 0.85, "side_effect_risk", 0.40, "needs_browser", true));
        JudgmentDecision humanByStake = service.observe("run-1", "turn-4", "task-A",
                "worker:default", Map.of("difficulty", 0.40, "side_effect_risk", 0.10, "high_stakes", true));
        JudgmentDecision humanByBudget = service.observe("run-1", "turn-5", "task-A",
                "worker:default", Map.of("difficulty", 0.20, "side_effect_risk", 0.05, "budget_exhausted", true));
        JudgmentDecision humanBySideEffect = service.observe("run-1", "turn-6", "task-A",
                "worker:default", Map.of("difficulty", 0.30, "side_effect_risk", 0.92));

        assertEquals("fast", fast.action());
        assertEquals("balanced", balanced.action());
        assertEquals("strong", strong.action());
        assertEquals("human_required", humanByStake.action());
        assertEquals("human_required", humanByBudget.action());
        assertEquals("human_required", humanBySideEffect.action());
        assertFalse(fast.degraded(), "启发式命中不应标 degraded");
        assertFalse(humanBySideEffect.degraded());

        List<String> lines = Files.readAllLines(target);
        assertEquals(6, lines.size(), "每观察一次 append 一行 JSONL");

        for (int i = 0; i < lines.size(); i++) {
            JsonNode n = MAPPER.readTree(lines.get(i));
            assertEquals("router", n.get("kind").asText(), "行 " + i + " kind=router");
            assertEquals("record_only", n.get("shadowMode").asText(), "M1 暂不 enforce");
            assertEquals("heuristic-shadow-v1", n.get("modelRef").asText(), "modelRef 来自 Provider");
            assertTrue(n.has("scores"));
        }
    }

    @Test
    void emptyInputMapsToBalancedFallback(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("router-shadow.jsonl");
        DecisionEventSink sink = new JsonlDecisionEventSink(target, 4);
        RouterShadowService service = new RouterShadowService(new HeuristicRouterProvider(), sink);

        JudgmentDecision decision = service.observe("run-2", "turn-1", "task-X", "worker:default", Map.of());

        assertEquals("balanced", decision.action(), "空 input 默认走 balanced（fallbackPolicy）");
        assertEquals("heuristic-shadow-v1", decision.modelRef());

        List<String> lines = Files.readAllLines(target);
        assertEquals(1, lines.size());
        JsonNode n = MAPPER.readTree(lines.get(0));
        assertEquals("router", n.get("kind").asText());
    }

    @Test
    void missingRunOrTurnRejected(@TempDir Path tmp) {
        DecisionEventSink sink = new JsonlDecisionEventSink(tmp.resolve("x.jsonl"));
        RouterShadowService service = new RouterShadowService(new HeuristicRouterProvider(), sink);

        assertThrows(IllegalArgumentException.class,
                () -> service.observe(null, "turn-1", "task-A", "subj", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.observe("run-1", "", "task-A", "subj", Map.of()));
    }

    @Test
    void decisionEventDigestHidesRawInput(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("router-shadow-digest.jsonl");
        DecisionEventSink sink = new JsonlDecisionEventSink(target, 4);
        RouterShadowService service = new RouterShadowService(new HeuristicRouterProvider(), sink);

        service.observe("run-3", "turn-1", "task-secret",
                "worker:codex", Map.of("difficulty", 0.5, "secret_token", "AKIA-REDACTED"));

        List<String> lines = Files.readAllLines(target);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertFalse(line.contains("AKIA-REDACTED"), "DecisionEvent JSONL 不应泄漏原始 input 值");
        assertTrue(line.contains("input_keys"));
        JsonNode n = MAPPER.readTree(line);
        JsonNode digest = n.get("inputDigest");
        assertNotNull(digest);
        assertEquals("worker:codex", digest.get("subject_ref").asText());
    }
}