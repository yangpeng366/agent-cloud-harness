package com.agentcloud.scorer;

/**
 * Jev decision action enum.
 *
 * Per docs/JEV_CONTEXT_SCORING_PLAN.md §1.2:
 *   - KEEP_VERBATIM: keep probability >= keepThreshold (default 0.5) -> panel/text verbatim into context
 *   - TRUNCATE_HEAD: keep probability < keepThreshold -> head(truncateHeadChars) (default 300 chars)
 *   - PRESERVE_RECENT: special override -> latest preserveRecentMessages (default 6) are never pruned
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
public enum JevAction {
    KEEP_VERBATIM,
    TRUNCATE_HEAD,
    PRESERVE_RECENT
}