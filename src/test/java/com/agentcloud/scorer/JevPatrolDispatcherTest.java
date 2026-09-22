package com.agentcloud.scorer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JevPatrolDispatcherTest -- fake Jev contract test for Jev-Patrol-L2.
 * (See docs/INITIATIVES/Jev-Patrol-L2.md and docs/JEV_PATROL_INTEGRATION_PLAN.md §L2.)
 *
 * Mirrors the path B JevDecisionTreeTest fake Jev shape (no TYPESAFE_API_KEY).
 *
 * Contract: A patrol dispatcher scores each due project against 4 noul questions
 * (progress / blocker / urgency / confidence) and routes the project into one
 * of four buckets per docs/JEV_PATROL_INTEGRATION_PLAN.md §L2:
 *   - ACT          : progress >= 0.7 AND urgency >= 0.7  -> trigger Codex worker round
 *   - HUMAN_REVIEW : progress < 0.3 AND confidence < 0.3 -> emit long Feishu card, skip round
 *   - BLOCKED      : blocker >= 0.7                     -> write Bitable blocker field, skip
 *   - SKIP         : else                                -> next round
 *
 * BLOCKED takes precedence over ACT (blocker short-circuits).
 *
 * THRESHOLDS dict is exposed so callers (auto-deploy / shadow mode) can
 * observe and (in future) override.
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
@DisplayName("Jev patrol dispatcher contract test (Jev-Patrol-L2 fake Jev)")
public class JevPatrolDispatcherTest {

    /** THRESHOLDS dict (mirrors classifying_rag_passages pattern from JEV_OFFICIAL_SKILL_ABSORPTION.md). */
    static final Map<String, Double> THRESHOLDS = Map.ofEntries(
        Map.entry("act.progress_min", 0.7),
        Map.entry("act.urgency_min", 0.7),
        Map.entry("human_review.progress_max", 0.3),
        Map.entry("human_review.confidence_max", 0.3),
        Map.entry("blocker.min", 0.7)
    );

    /** Fake Jev scoring 4 noul questions with deterministic probs. */
    static Map<String, Double> fakeJev4Noul(
            Map<String, Double> overrideProbs,
            String projectName,
            String recentState) {
        // Default all 4 nouls to 0.5 (ambiguous)
        Map<String, Double> probs = new LinkedHashMap<>();
        probs.put("progress", 0.5);
        probs.put("blocker", 0.5);
        probs.put("urgency", 0.5);
        probs.put("confidence", 0.5);
        if (overrideProbs != null) {
            probs.putAll(overrideProbs);
        }
        return probs;
    }

    /** 4 routes (production-shape enum). */
    enum Route { ACT, HUMAN_REVIEW, BLOCKED, SKIP }

    /** Decision record (production-shape: project name + route + 4 noul probs + 阈值 pass/fail). */
    record PatrolDecision(
        String projectName,
        Route route,
        Map<String, Double> noulProbs,
        boolean blockerPrecedenceTriggered,
        String reason
    ) {}

    /** Routing logic (mirrors JEV_PATROL_INTEGRATION_PLAN.md §L2 step 3). */
    static PatrolDecision route(String projectName, Map<String, Double> noulProbs) {
        double progress = noulProbs.get("progress");
        double blocker = noulProbs.get("blocker");
        double urgency = noulProbs.get("urgency");
        double confidence = noulProbs.get("confidence");

        // BLOCKED takes precedence over ACT (blocker short-circuits)
        if (blocker >= THRESHOLDS.get("blocker.min")) {
            return new PatrolDecision(projectName, Route.BLOCKED, noulProbs, true,
                "blocker=" + blocker + " >= " + THRESHOLDS.get("blocker.min"));
        }
        // ACT: high progress + high urgency
        if (progress >= THRESHOLDS.get("act.progress_min")
            && urgency >= THRESHOLDS.get("act.urgency_min")) {
            return new PatrolDecision(projectName, Route.ACT, noulProbs, false,
                "progress=" + progress + " urgency=" + urgency + " both >= 0.7");
        }
        // HUMAN_REVIEW: low progress + low confidence
        if (progress < THRESHOLDS.get("human_review.progress_max")
            && confidence < THRESHOLDS.get("human_review.confidence_max")) {
            return new PatrolDecision(projectName, Route.HUMAN_REVIEW, noulProbs, false,
                "progress=" + progress + " confidence=" + confidence + " both < 0.3");
        }
        // SKIP: ambiguous (next round)
        return new PatrolDecision(projectName, Route.SKIP, noulProbs, false,
            "ambiguous: progress=" + progress + " urgency=" + urgency
                + " confidence=" + confidence);
    }

    /** Multi-project dispatcher (mirrors JEV_PATROL_INTEGRATION_PLAN.md §L2 step 2-3). */
    static List<PatrolDecision> dispatchDueList(
            List<String> projectNames,
            Map<String, Map<String, Double>> perProjectProbs) {
        return projectNames.stream()
            .map(name -> {
                Map<String, Double> probs = perProjectProbs.getOrDefault(name, Map.of());
                Map<String, Double> scored = fakeJev4Noul(probs, name, "");
                return route(name, scored);
            })
            .toList();
    }

    @Test
    @DisplayName("high progress + high urgency + low blocker -> ACT")
    void actRouteHighProgressHighUrgency() {
        Map<String, Double> probs = Map.of(
            "progress", 0.9,
            "urgency", 0.9,
            "blocker", 0.1,
            "confidence", 0.5
        );
        PatrolDecision d = route("巴中方正云雀-见报成品接入预研", probs);
        assertEquals(Route.ACT, d.route(), "high progress + high urgency should ACT");
        assertEquals("巴中方正云雀-见报成品接入预研", d.projectName());
        assertFalse(d.blockerPrecedenceTriggered());
        assertEquals(0.9, d.noulProbs().get("progress"));
        assertEquals(0.9, d.noulProbs().get("urgency"));
    }

    @Test
    @DisplayName("blocker >= 0.7 takes precedence over ACT (progress=0.9 urgency=0.9 blocker=0.9 -> BLOCKED)")
    void blockedOverridesAct() {
        Map<String, Double> probs = Map.of(
            "progress", 0.9,
            "urgency", 0.9,
            "blocker", 0.9,
            "confidence", 0.5
        );
        PatrolDecision d = route("networkx 开源贡献", probs);
        assertEquals(Route.BLOCKED, d.route(), "blocker >= 0.7 must override ACT");
        assertTrue(d.blockerPrecedenceTriggered(),
            "blockerPrecedenceTriggered should be true when blocker >= 0.7");
    }

    @Test
    @DisplayName("low progress + low confidence -> HUMAN_REVIEW (long Feishu card path)")
    void humanReviewLowProgressLowConfidence() {
        Map<String, Double> probs = Map.of(
            "progress", 0.1,
            "urgency", 0.5,
            "blocker", 0.1,
            "confidence", 0.1
        );
        PatrolDecision d = route("auto-deploy 代码自动巡检", probs);
        assertEquals(Route.HUMAN_REVIEW, d.route(),
            "low progress + low confidence should HUMAN_REVIEW");
        assertFalse(d.blockerPrecedenceTriggered());
    }

    @Test
    @DisplayName("ambiguous (mid-range probs) -> SKIP for next round")
    void ambiguousSkips() {
        Map<String, Double> probs = Map.of(
            "progress", 0.5,
            "urgency", 0.5,
            "blocker", 0.1,
            "confidence", 0.5
        );
        PatrolDecision d = route("fastjson2 alibaba/fastjson2", probs);
        assertEquals(Route.SKIP, d.route(),
            "ambiguous mid-range probs should SKIP");
        assertFalse(d.blockerPrecedenceTriggered());
    }

    @Test
    @DisplayName("multi-project dueList routes independently (5 projects, 4 different routes)")
    void dispatchDueListIndependentRouting() {
        List<String> projects = List.of(
            "巴中方正云雀", "networkx", "agent-cloud-harness", "auto-deploy", "ccx"
        );
        Map<String, Map<String, Double>> probs = Map.of(
            "巴中方正云雀", Map.of("progress", 0.9, "urgency", 0.9, "blocker", 0.1, "confidence", 0.5),
            "networkx", Map.of("progress", 0.9, "urgency", 0.9, "blocker", 0.9, "confidence", 0.5),
            "agent-cloud-harness", Map.of("progress", 0.1, "urgency", 0.5, "blocker", 0.1, "confidence", 0.1),
            "auto-deploy", Map.of("progress", 0.5, "urgency", 0.5, "blocker", 0.1, "confidence", 0.5),
            "ccx", Map.of("progress", 0.95, "urgency", 0.85, "blocker", 0.05, "confidence", 0.7)
        );
        List<PatrolDecision> decisions = dispatchDueList(projects, probs);
        assertEquals(5, decisions.size());
        assertEquals(Route.ACT, decisions.get(0).route(), "巴中方正云雀 should ACT");
        assertEquals(Route.BLOCKED, decisions.get(1).route(), "networkx should BLOCKED");
        assertEquals(Route.HUMAN_REVIEW, decisions.get(2).route(), "agent-cloud-harness should HUMAN_REVIEW");
        assertEquals(Route.SKIP, decisions.get(3).route(), "auto-deploy should SKIP");
        assertEquals(Route.ACT, decisions.get(4).route(), "ccx should ACT");
    }
}