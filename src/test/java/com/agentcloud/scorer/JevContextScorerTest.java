package com.agentcloud.scorer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JevContextScorer (real HTTP impl) without network.
 *
 * Per docs/JEV_CONTEXT_SCORING_PLAN.md §1.2 + §11:
 *   - enabled=false MUST short-circuit to KEEP_VERBATIM (no network)
 *   - decide/decideAll MUST NOT throw on null/empty input
 *   - decideAll MUST preserve input order
 *   - On HTTP failure: fallback to KEEP_VERBATIM (probability=1.0)
 *
 * These tests use the public constructor; HTTP path is covered by manual smoke
 * tests in .tmp/jev-promo-2026-09-21/. CI never makes real calls (plan §11 立项门槛 6).
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
@DisplayName("JevContextScorer (HTTP impl) contract test -- offline (FEAT-03)")
class JevContextScorerTest {

    @Test
    @DisplayName("enabled=false short-circuits to KEEP_VERBATIM (no network, no env var needed)")
    void disabledShortCircuits() {
        JevContextScorer scorer = new JevContextScorer(JevContextScorerConfig.defaults());
        // defaults().isEnabled() = false
        JevDecision d = scorer.decide(new JevRequestItem("p1", "text", "tool_call"));
        assertEquals(JevAction.KEEP_VERBATIM, d.action());
        assertEquals(1.0, d.probability());
    }

    @Test
    @DisplayName("decideAll with enabled=false returns same-size list of KEEP_VERBATIM")
    void disabledDecideAll() {
        JevContextScorer scorer = new JevContextScorer(JevContextScorerConfig.defaults());
        List<JevDecision> ds = scorer.decideAll(List.of(
            new JevRequestItem("a", "t1", "tool_call"),
            new JevRequestItem("b", "t2", "panel"),
            new JevRequestItem("c", "t3", "user_msg")));
        assertEquals(3, ds.size());
        for (JevDecision d : ds) {
            assertEquals(JevAction.KEEP_VERBATIM, d.action());
            assertEquals(1.0, d.probability());
        }
    }

    @Test
    @DisplayName("null/empty input handled gracefully (no NPE)")
    void nullInputHandled() {
        JevContextScorer scorer = new JevContextScorer(JevContextScorerConfig.defaults());
        assertEquals(JevAction.KEEP_VERBATIM, scorer.decide(null).action());
        assertEquals(JevAction.KEEP_VERBATIM,
            scorer.decide(new JevRequestItem("p", null, "tool_call")).action());
    }

    @Test
    @DisplayName("decideAll with empty/null list returns empty (no NPE)")
    void emptyDecideAll() {
        JevContextScorer scorer = new JevContextScorer(JevContextScorerConfig.defaults());
        assertEquals(0, scorer.decideAll(List.of()).size());
        assertEquals(0, scorer.decideAll(null).size());
    }

    @Test
    @DisplayName("enabled=true but env var missing -> decide returns KEEP_VERBATIM fallback (no NPE)")
    void enabledButMissingKeyFallsBack() {
        JevContextScorerConfig cfg = new JevContextScorerConfig(
            true, 0.5, 6, 300, 1000,
            "https://api.typesafe.ai",
            "TYPESAFE_API_KEY_NONEXISTENT_" + System.nanoTime(),  // unlikely to be set
            30000, 25000);
        JevContextScorer scorer = new JevContextScorer(cfg);
        // No env var -> throws IllegalStateException inside decideOneHttp
        // -> caught by decide() fallback -> KEEP_VERBATIM (probability=1.0)
        JevDecision d = scorer.decide(new JevRequestItem("p", "text", "tool_call"));
        assertEquals(JevAction.KEEP_VERBATIM, d.action(),
            "missing env var -> fallback KEEP_VERBATIM per plan §1.2");
    }

    @Test
    @DisplayName("enabled=true + decideAction maps probability >= keepThreshold -> KEEP_VERBATIM")
    void decideActionKeepsAboveThreshold() {
        JevContextScorer scorer = new JevContextScorer(JevContextScorerConfig.defaults());
        // Use package-private decideAction via decideAction test helper
        // decisionAction is package-private; test via reflection or via decide logic
        // Simpler: assert default config + private method via package access
        assertEquals(JevAction.KEEP_VERBATIM,
            scorer.decideAction(0.5),  // exactly at threshold
            "0.5 >= 0.5 should KEEP_VERBATIM");
        assertEquals(JevAction.KEEP_VERBATIM,
            scorer.decideAction(0.7));
        assertEquals(JevAction.TRUNCATE_HEAD,
            scorer.decideAction(0.3));
    }
}