package com.agentcloud.scorer;

/**
 * Jev decision (production-shape record).
 *
 * Returned by JevScorer.decide / decideAll. Probability is in [0,1] and
 * reflects Jev's confidence that the item is worth keeping verbatim.
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
public record JevDecision(
    JevAction action,
    double probability
) {}