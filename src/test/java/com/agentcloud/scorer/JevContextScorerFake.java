package com.agentcloud.scorer;

import java.util.List;

/**
 * Test-only fake Jev implementation: no network, returns typed decision +
 * probability by built-in rules. Equivalent to fast-jev-compaction v0.2.0
 * tests/fake-jev.ts (per docs/JEV_CONTEXT_SCORING_PLAN.md §13.1).
 *
 * Strategy:
 *   - defaultKeepProbability: all items get this prob
 *   - threshold: gate (>= threshold -> KEEP_VERBATIM, else TRUNCATE_HEAD)
 *   - 1-arg ctor: score == threshold (backward compat with plan §13.1; no truncation in this mode)
 *   - 2-arg ctor: separate score and threshold (enables truncate-below tests)
 *   - decideAll: preserves input order; null/empty -> empty list
 *   - null/empty text -> KEEP_VERBATIM (probability=1.0) per plan §13.1 fallback
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
public final class JevContextScorerFake implements JevScorer {
    /** Fake is always 'enabled' (no feature flag; for tests, callers control via decorator or wrapping). */
    @Override public boolean isEnabled() { return true; }
    private final double defaultKeepProbability;
    private final double threshold;

    public JevContextScorerFake(double defaultKeepProbability) {
        this(defaultKeepProbability, defaultKeepProbability);
    }

    public JevContextScorerFake(double defaultKeepProbability, double threshold) {
        this.defaultKeepProbability = defaultKeepProbability;
        this.threshold = threshold;
    }

    @Override
    public JevDecision decide(JevRequestItem item) {
        if (item == null || item.text() == null) {
            return new JevDecision(JevAction.KEEP_VERBATIM, 1.0);
        }
        double p = scoreByRules(item);
        if (p >= threshold) {
            return new JevDecision(JevAction.KEEP_VERBATIM, p);
        }
        return new JevDecision(JevAction.TRUNCATE_HEAD, p);
    }

    @Override
    public List<JevDecision> decideAll(List<JevRequestItem> items) {
        if (items == null || items.isEmpty()) return List.of();
        return items.stream().map(this::decide).toList();
    }

    public double defaultKeepProbability() { return defaultKeepProbability; }
    public double threshold() { return threshold; }

    private double scoreByRules(JevRequestItem item) {
        return defaultKeepProbability;
    }
}