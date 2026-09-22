package com.agentcloud.scorer;

/**
 * Jev request item (production-shape record).
 *
 * One item = one candidate for Jev scoring (mounted panel / tool result /
 * handoff packet field). Score = (text length, field type, source tag) +
 * optional override rules (e.g. recent-msg whitelist).
 *
 * Used by both JevContextScorer (HTTP) and JevContextScorerFake (test).
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
public record JevRequestItem(
    String id,        // stable id (panel id, tool call id, field name, etc.)
    String text,      // candidate text (may be null -> PRESERVE_RECENT default)
    String source     // e.g. "tool_call" / "panel" / "user_msg" / "tool_result"
) {}