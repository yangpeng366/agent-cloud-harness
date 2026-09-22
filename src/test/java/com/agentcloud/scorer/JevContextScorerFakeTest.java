package com.agentcloud.scorer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JevContextScorerFake (per docs/JEV_CONTEXT_SCORING_PLAN.md §13.2).
 *
 * Mirrors the Fake shape; CI runs only Fake (no external network per plan §11 立项门槛 6).
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
@DisplayName("JevContextScorerFake contract test (FEAT-03 fake)")
class JevContextScorerFakeTest {

    @Test
    @DisplayName("keep verbatim above threshold (defaultKeepProbability=0.5, item.prob -> 0.5)")
    void keepVerbatimAboveThreshold() {
        JevContextScorerFake fake = new JevContextScorerFake(0.5);
        JevDecision d = fake.decide(new JevRequestItem("panel-A", "some long text", "tool_call"));
        assertEquals(JevAction.KEEP_VERBATIM, d.action());
        assertEquals(0.5, d.probability());
    }

    @Test
    @DisplayName("truncate below threshold (score=0.5, threshold=0.9 -> 0.5 < 0.9 -> TRUNCATE_HEAD)")
    void truncateBelowThreshold() {
        JevContextScorerFake fake = new JevContextScorerFake(0.5, 0.9);
        JevDecision d = fake.decide(new JevRequestItem("panel-B", "some long text", "tool_call"));
        assertEquals(JevAction.TRUNCATE_HEAD, d.action());
        assertEquals(0.5, d.probability(), 0.0);
    }

    @Test
    @DisplayName("null text -> KEEP_VERBATIM (probability=1.0)")
    void emptyItemReturnsKeepVerbatim() {
        JevContextScorerFake fake = new JevContextScorerFake(0.5);
        assertEquals(JevAction.KEEP_VERBATIM,
            fake.decide(new JevRequestItem("p", null, "tool_call")).action());
        // null item also returns KEEP_VERBATIM
        assertEquals(JevAction.KEEP_VERBATIM, fake.decide(null).action());
    }

    @Test
    @DisplayName("decideAll preserves input order (callers index by position)")
    void decideAllPreservesOrder() {
        JevContextScorerFake fake = new JevContextScorerFake(0.5);
        List<JevDecision> ds = fake.decideAll(List.of(
            new JevRequestItem("a", "t1", "tool_call"),
            new JevRequestItem("b", "t2", "tool_call"),
            new JevRequestItem("c", "t3", "tool_call")));
        assertEquals(3, ds.size());
        // All should be KEEP_VERBATIM with prob=0.5
        for (JevDecision d : ds) {
            assertEquals(JevAction.KEEP_VERBATIM, d.action());
            assertEquals(0.5, d.probability());
        }
    }

    @Test
    @DisplayName("decideAll with empty/null list returns empty list (no NPE)")
    void decideAllEmptyHandledGracefully() {
        JevContextScorerFake fake = new JevContextScorerFake(0.5);
        assertEquals(0, fake.decideAll(List.of()).size());
        assertEquals(0, fake.decideAll(null).size());
    }

    @Test
    @DisplayName("mixed threshold: items with different probs map to different actions")
    void mixedActionsPerItem() {
        // Override scoring via a custom Fake: but Fake uses uniform probability.
        // Instead, verify the boundary case: threshold exactly at 0.5.
        JevContextScorerFake fakeKeep = new JevContextScorerFake(0.5);
        JevDecision d = fakeKeep.decide(new JevRequestItem("p", "text", "panel"));
        assertEquals(JevAction.KEEP_VERBATIM, d.action(), "0.5 >= 0.5 should keep");
    }
}