package com.agentcloud.scorer;

import java.util.List;

/**
 * Jev scoring interface contract.
 *
 * Production impl = {@link JevContextScorer} (JDK 21 HttpClient + typesafe.ai /v1/systemone).
 * Test impl = {@code JevContextScorerFake} (no network, rule-based).
 *
 * Contract note (per docs/JEV_CONTEXT_SCORING_PLAN.md §13.3):
 *   - Interface itself must be confirmed by maintainer at FEAT-03 sign-off;
 *     any field change triggers HARNESS_CHANGE_CONTRACT.md Contract-Additive flow.
 *   - decideAll MUST preserve input order (callers index by position).
 *   - decide MUST NOT return null (returns JevDecision with action=KEEP_VERBATIM
 *     and probability=1.0 when input is null/empty, matching fast-jev-compaction v0.2.0
 *     tests/fake-jev.ts fallback).
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
public interface JevScorer {
    JevDecision decide(JevRequestItem item);
    List<JevDecision> decideAll(List<JevRequestItem> items);

    /**
     * Whether this scorer is enabled (per docs/JEV_CONTEXT_SCORING_PLAN.md §1.1).
     * Default false: test stubs (Fake) are disabled by default; production
     * JevContextScorer overrides to read config.isEnabled().
     *
     * Decorators (e.g. JevPrefilteredJudgmentService) check this BEFORE calling
     * decide / decideAll so disabled scorers short-circuit to delegate.
     */
    default boolean isEnabled() { return false; }
}