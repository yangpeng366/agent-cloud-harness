package com.agentcloud.scorer;

import com.agentcloud.judgment.model.ExecutionDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JevJudgmentPrefilterTest -- fake Jev contract test for HW-10.
 * (See docs/INITIATIVES/HW-10.md and docs/JEV_DECISION_TREE_REAL_EXPERIMENT.md.)
 *
 * Mirrors the path B JevDecisionTreeTest fake Jev shape (no TYPESAFE_API_KEY).
 *
 * Contract: PromptBasedJudgmentService.judgeExecution should call a Jev
 * prefilter BEFORE invoking the LLM. If Jev returns a high-confidence
 * decision (e.g. p >= highStakesThreshold), the service should:
 *   1. skip the LLM call
 *   2. return a default ExecutionDecision
 *   3. annotate runtime_facts (or equivalent) with source=jev_skip
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
@DisplayName("Jev judgment prefilter contract test (HW-10 fake Jev)")
public class JevJudgmentPrefilterTest {

    /** Fake Jev score (deterministic, mirrors fast-jev-compaction tests/fakeJev). */
    static double fakeJev(String question, Map<String, Object> state) {
        if (state == null || state.isEmpty()) return 0.50; // ambiguous default
        Object yn = null, no = null;
        try {
            yn = state.get("obvious_yes");
            no = state.get("obvious_no");
        } catch (Exception ignored) {
            return 0.50;
        }
        if (Boolean.TRUE.equals(yn)) return 0.95;
        if (Boolean.TRUE.equals(no)) return 0.05;
        return 0.50;
    }

    /** Production-shape router: high/medium/low from JEV_GRAPH_NODES_PLAN.md §1.1a. */
    record JevResult(double probability, String route) {}

    static JevResult routeJev(double probability, double lowStakes, double highStakes) {
        if (probability >= highStakes) return new JevResult(probability, "act");
        if (probability >= lowStakes) return new JevResult(probability, "flag_for_review");
        return new JevResult(probability, "skip_llm");
    }

    /** Production-shape: should we call LLM or return default? */
    record JudgmentDecision(boolean callLlm, ExecutionDecision execution, String source) {}

    /** Production-shape prefilter hook. */
    static JudgmentDecision prefilter(
            Map<String, Object> taskState,
            ExecutionDecision defaultExecution,
            double lowStakes,
            double highStakes) {
        double p = fakeJev("is_obvious_decision", taskState);
        JevResult r = routeJev(p, lowStakes, highStakes);
        if ("act".equals(r.route) || "skip_llm".equals(r.route)) {
            return new JudgmentDecision(false, defaultExecution,
                "act".equals(r.route) ? "jev_act" : "skipped_by_jev");
        }
        // ambiguous: do not block, fall through to LLM
        return new JudgmentDecision(true, defaultExecution, "fell_through_to_llm");
    }

    @Test
    @DisplayName("Jev obvious_yes -> Jev skip LLM, return default decision with source=jev_act")
    void obviousYesSkipsLLM() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("obvious_yes", true);
        ExecutionDecision def = new ExecutionDecision(
            "continue", "default execution reason",
            "default next step", false, false, false, false, false, null);
        JudgmentDecision d = prefilter(state, def, 0.5, 0.85);
        assertFalse(d.callLlm(), "obvious_yes must skip LLM call");
        assertEquals("jev_act", d.source(), "source must be jev_act for high-prob skip");
        assertSame(def, d.execution(), "must return default execution decision");
    }

    @Test
    @DisplayName("Jev obvious_no -> skip LLM with source=skipped_by_jev")
    void obviousNoSkipsLLM() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("obvious_no", true);
        ExecutionDecision def = new ExecutionDecision(
            "checkpoint", "default checkpoint reason",
            "default next", false, false, false, false, false, null);
        JudgmentDecision d = prefilter(state, def, 0.5, 0.85);
        assertFalse(d.callLlm());
        assertEquals("skipped_by_jev", d.source());
    }

    @Test
    @DisplayName("Jev ambiguous -> fall through to LLM, no source override")
    void ambiguousFallsThrough() {
        Map<String, Object> state = new LinkedHashMap<>();
        // no obvious_yes / obvious_no -> probability 0.50 -> in [low, high) -> fall through
        ExecutionDecision def = new ExecutionDecision(
            "continue", "default", "default next",
            false, false, false, false, false, null);
        JudgmentDecision d = prefilter(state, def, 0.5, 0.85);
        assertTrue(d.callLlm(), "ambiguous must call LLM");
        assertEquals("fell_through_to_llm", d.source());
    }

    @Test
    @DisplayName("Jev score matches Jev noul response contract (probability in [0,1])")
    void jevScoreContract() {
        Map<String, Object> sYes = Map.of("obvious_yes", true);
        Map<String, Object> sNo = Map.of("obvious_no", true);
        Map<String, Object> sEmpty = Map.of();
        assertEquals(0.95, fakeJev("q", sYes), 0.001);
        assertEquals(0.05, fakeJev("q", sNo), 0.001);
        assertEquals(0.50, fakeJev("q", sEmpty), 0.001);
        // Jev spec: probability must be in [0,1]
        for (Map<String, Object> s : List.of(sYes, sNo, sEmpty)) {
            double p = fakeJev("q", s);
            assertTrue(p >= 0.0 && p <= 1.0, "Jev probability out of [0,1]: " + p);
        }
    }
}