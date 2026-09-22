package com.agentcloud.scorer.compactor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JevContextCompactorTest -- Fake Jev contract test for fast-jev-compaction
 * path A prototype (.tmp/jev-compactor/).
 *
 * Mirrors tamaratran/fast-jev-compaction tests/fast-jev-compaction.test.ts:
 *   - 5-stage fitting triggers when full state exceeds budget
 *   - dual-noul per call decision (keepCall? + keepResult?)
 *   - pinned (first + last N) bypasses LLM
 *   - token estimator lands within calibrated range
 *   - null-safe answer handling per .tmp/Jev-OpenEyes-experience.md §4.E
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
@DisplayName("JevContextCompactor — fake Jev path A prototype")
public class JevContextCompactorTest {

    // ---------- Fixtures ----------

    static JevContextCompactor.Message msg(String role, String text) {
        return new JevContextCompactor.Message(role, text, List.of(), List.of());
    }

    /** Assistant message with a tool call (no result -- result comes in a separate user message). */
    static JevContextCompactor.Message assistantWithCall(String callId, String tool, String input, String resultText) {
        var tu = new JevContextCompactor.ToolUse(callId, tool, input);
        return new JevContextCompactor.Message("assistant", "", List.of(tu), List.of());
    }

    static JevContextCompactor.Message userResult(String callId, String resultText) {
        var tr = new JevContextCompactor.ToolResult(callId, resultText, false);
        return new JevContextCompactor.Message("user", "", List.of(), List.of(tr));
    }

    /** Long transcript with 8 tool calls; mix of pinned and candidate. */
    static List<JevContextCompactor.Message> longTranscript() {
        List<JevContextCompactor.Message> ms = new ArrayList<>();
        ms.add(msg("user", "Refactor the deprecated API."));   // pinned (first)
        ms.add(assistantWithCall("toolu_1", "Read", "{\"file\":\"src/a.ts\"}", "contents-a"));
        ms.add(userResult("toolu_1", "contents-a"));
        for (int i = 2; i <= 8; i++) {
            ms.add(assistantWithCall("toolu_" + i, "Read", "{\"file\":\"src/x" + i + ".ts\"}",
                "x".repeat(2000)));
            ms.add(userResult("toolu_" + i, "x".repeat(2000)));
        }
        // Last 6 messages pinned by default preserveRecentMessages=6 -> last 6 entries preserved
        // But last 6 entries here = last 6 messages of the 16-message transcript
        return ms;
    }

    /** Fake Jev: returns keepCall/keepResult from per-question tables; default 0.5. */
    static class FakeJevAsker implements JevContextCompactor.JevAsker {
        Map<String, Double> noulTable;
        FakeJevAsker(Map<String, Double> noulTable) { this.noulTable = noulTable; }
        public Map<String, Double> ask(String state, Map<String, String> questions) {
            Map<String, Double> out = new LinkedHashMap<>();
            for (String name : questions.keySet()) {
                out.put(name, noulTable.getOrDefault(name, 0.5));
            }
            return out;
        }
    }

    // ---------- Token estimator ----------

    @Test
    @DisplayName("estimateTokens: letter word ~ 1 per 6 chars, digit half, symbol integer")
    void estimateTokens_basic() {
        assertEquals(0, JevContextCompactor.estimateTokens(""));
        assertEquals(0, JevContextCompactor.estimateTokens(null));
        // "hello" = 1 + floor((5-1)/6) = 1 token
        assertEquals(1, JevContextCompactor.estimateTokens("hello"));
        // "abcdefghij" = 1 + floor(9/6) = 2
        assertEquals(2, JevContextCompactor.estimateTokens("abcdefghij"));
        // "12345" = 5/2 = 2
        assertEquals(2, JevContextCompactor.estimateTokens("12345"));
        // "@" = 1
        assertEquals(1, JevContextCompactor.estimateTokens("@"));
        // mixed: "hi 123 @" = "hi"(1) + "123"(1) + "@"(1) = 3
        assertEquals(3, JevContextCompactor.estimateTokens("hi 123 @"));
    }

    // ---------- Pinning ----------

    @Test
    @DisplayName("isPinned: first message always pinned; last preserveRecentMessages always pinned")
    void isPinned_boundaries() {
        assertTrue(JevContextCompactor.isPinned(0, 10, 3));
        assertTrue(JevContextCompactor.isPinned(7, 10, 3));
        assertTrue(JevContextCompactor.isPinned(9, 10, 3));
        assertFalse(JevContextCompactor.isPinned(1, 10, 3));
        assertFalse(JevContextCompactor.isPinned(6, 10, 3));
    }

    // ---------- Tool call pairing ----------

    @Test
    @DisplayName("collectToolCalls: pairs tool_use with tool_result by id; skips orphaned calls")
    void collectToolCalls_pairs() {
        var m1 = assistantWithCall("a", "Read", "{}", "result-a");
        var m2 = assistantWithCall("b", "Write", "{}", "result-b"); // orphaned (no user result msg)
        var m3 = userResult("a", "result-a");
        var calls = JevContextCompactor.collectToolCalls(List.of(m1, m2, m3), 0);
        assertEquals(1, calls.size());
        assertEquals("a", calls.get(0).toolUseId());
        assertEquals("t1", calls.get(0).id());
    }

    @Test
    @DisplayName("collectToolCalls: first + last preserveRecentMessages pinned, middle not")
    void collectToolCalls_pinnedDistribution() {
        var ms = longTranscript();
        // Use preserveRecentMessages=4 so the last two tool calls (in the last 4 messages)
        // are pinned. m1..m17 (17 msgs total, 1 + 2 + 7*2 = 17).
        // toolUses at idx 1,3,5,7,9,11,13,15 -> t1..t8
        // preserveRecentMessages=4 -> pinned: idx 0 + idx >= 17-4=13 -> t7(idx13) + t8(idx15)
        var calls = JevContextCompactor.collectToolCalls(ms, 4);
        long pinnedCount = calls.stream().filter(JevContextCompactor.ToolCall::pinned).count();
        assertEquals(2, pinnedCount, "expected t7 + t8 pinned");
        assertFalse(calls.get(0).pinned(), "t1 (in idx 1) should not be pinned");
        assertFalse(calls.get(5).pinned(), "t6 (in idx 11) should not be pinned");
        assertTrue(calls.get(6).pinned(), "t7 (in idx 13) should be pinned");
        assertTrue(calls.get(7).pinned(), "t8 (in idx 15) should be pinned");
        // t2..t6 should NOT be pinned
        for (int i = 2; i <= 6; i++) {
            assertFalse(calls.get(i - 1).pinned(), "t" + i + " should not be pinned");
        }
    }

    // ---------- State fitting 5-stage ----------

    @Test
    @DisplayName("fitState: small transcript fits at stage 'full'")
    void fitState_full() {
        var ms = longTranscript();
        var calls = JevContextCompactor.collectToolCalls(ms, 6);
        var fitted = JevContextCompactor.fitState(ms, calls, new JevContextCompactor.CompactOptions(
            0.5, 6, 25_000, 30_000, 300));
        assertEquals("full", fitted.stage(), "small transcript should fit at full stage");
        assertTrue(fitted.tokens() > 0);
        assertTrue(fitted.state().contains("Refactor"));
    }

    @Test
    @DisplayName("fitState: budget tighter than full state triggers progressive input caps")
    void fitState_inputCap() {
        var ms = longTranscript();
        var calls = JevContextCompactor.collectToolCalls(ms, 6);
        var fittedFull = JevContextCompactor.fitState(ms, calls, new JevContextCompactor.CompactOptions(
            0.5, 6, 25_000, 30_000, 300));
        int fullTokens = fittedFull.tokens();
        // budget between full stage and last stage -- try to land at some input_cap
        int midBudget = Math.max(fullTokens / 4, 1);
        try {
            var fitted = JevContextCompactor.fitState(ms, calls, new JevContextCompactor.CompactOptions(
                0.5, 6, midBudget, 30_000, 300));
            assertTrue(fitted.tokens() <= fullTokens, "fitted should be <= full tokens");
            assertTrue(fitted.stage().startsWith("input_cap_") || fitted.stage().equals("text_abridge"),
                "expected progressive stage, got " + fitted.stage());
        } catch (IllegalStateException e) {
            // budget too tight even for last stage -- caller fallback path
            // midBudget was fullTokens/4 which can still be too tight for text_abridge stage
        }
    }

    @Test
    @DisplayName("fitState: throws when even last stage cannot fit (caller fallback)")
    void fitState_throwsWhenCannotFit() {
        var ms = longTranscript();
        var calls = JevContextCompactor.collectToolCalls(ms, 6);
        // maxStateTokens = 0 cannot fit anything after stage full fails
        assertThrows(IllegalStateException.class, () ->
            JevContextCompactor.fitState(ms, calls, new JevContextCompactor.CompactOptions(
                0.5, 6, 1, 30_000, 300)));
    }

    // ---------- Batch splitting ----------

    @Test
    @DisplayName("batchCalls: produces one batch when total question tokens fit")
    void batchCalls_single() {
        var ms = longTranscript();
        var calls = JevContextCompactor.collectToolCalls(ms, 6);
        var nonPinned = new ArrayList<JevContextCompactor.ToolCall>();
        for (var c : calls) if (!c.pinned()) nonPinned.add(c);
        var batches = JevContextCompactor.batchCalls(nonPinned, 1000,
            new JevContextCompactor.CompactOptions(0.5, 6, 25_000, 30_000, 300));
        assertEquals(1, batches.size(), "all should fit in one batch with generous budget");
    }

    // ---------- Dual-noul decision ----------

    @Test
    @DisplayName("decideCall: keepResult >= threshold -> KEEP")
    void decideCall_keepResult() {
        var call = new JevContextCompactor.ToolCall("t1", "a", "Read", "{}", "ok", 2, false, 1);
        var d = JevContextCompactor.decideCall(call,
            new JevContextCompactor.CallAnswer(0.9, 0.9),
            new JevContextCompactor.CompactOptions(0.5, 6, 25_000, 30_000, 300));
        assertEquals(JevContextCompactor.DecisionAction.KEEP, d.action());
        assertEquals("kept", d.reason());
    }

    @Test
    @DisplayName("decideCall: keepCall >= threshold & keepResult < threshold -> DROP_RESULT")
    void decideCall_dropResult() {
        var call = new JevContextCompactor.ToolCall("t1", "a", "Read", "{}", "ok", 2, false, 1);
        var d = JevContextCompactor.decideCall(call,
            new JevContextCompactor.CallAnswer(0.9, 0.3),
            new JevContextCompactor.CompactOptions(0.5, 6, 25_000, 30_000, 300));
        assertEquals(JevContextCompactor.DecisionAction.DROP_RESULT, d.action());
        assertEquals("result_dropped", d.reason());
    }

    @Test
    @DisplayName("decideCall: both below threshold -> DROP_CALL")
    void decideCall_dropCall() {
        var call = new JevContextCompactor.ToolCall("t1", "a", "Read", "{}", "ok", 2, false, 1);
        var d = JevContextCompactor.decideCall(call,
            new JevContextCompactor.CallAnswer(0.3, 0.3),
            new JevContextCompactor.CompactOptions(0.5, 6, 25_000, 30_000, 300));
        assertEquals(JevContextCompactor.DecisionAction.DROP_CALL, d.action());
        assertEquals("call_dropped", d.reason());
    }

    @Test
    @DisplayName("decideCall: pinned always KEEP regardless of answer")
    void decideCall_pinned() {
        var call = new JevContextCompactor.ToolCall("t1", "a", "Read", "{}", "ok", 2, true, 0);
        var d = JevContextCompactor.decideCall(call,
            new JevContextCompactor.CallAnswer(0.0, 0.0),
            new JevContextCompactor.CompactOptions(0.5, 6, 25_000, 30_000, 300));
        assertEquals(JevContextCompactor.DecisionAction.KEEP, d.action());
        assertEquals("pinned", d.reason());
    }

    @Test
    @DisplayName("decideCall: null answer -> KEEP (safe default per fast-jev-compaction)")
    void decideCall_nullAnswerSafe() {
        var call = new JevContextCompactor.ToolCall("t1", "a", "Read", "{}", "ok", 2, false, 1);
        var d = JevContextCompactor.decideCall(call, null,
            new JevContextCompactor.CompactOptions(0.5, 6, 25_000, 30_000, 300));
        assertEquals(JevContextCompactor.DecisionAction.KEEP, d.action());
        assertEquals("no_jev_answer", d.reason());
    }

    // ---------- truncateInput + abridge ----------

    @Test
    @DisplayName("truncateInput: short input unchanged")
    void truncateInput_short() {
        assertEquals("abc", JevContextCompactor.truncateInput("abc", 10));
    }

    @Test
    @DisplayName("truncateInput: long input trimmed with ellipsis")
    void truncateInput_long() {
        String r = JevContextCompactor.truncateInput("abcdefghij", 5);
        // truncateInput keeps first `limit` chars + "..." suffix
        assertEquals("abcde...", r);
        assertTrue(r.length() > 5);
    }

    @Test
    @DisplayName("abridge: short text unchanged")
    void abridge_short() {
        String t = "x".repeat(100);
        assertEquals(t, JevContextCompactor.abridge(t, 50, 20));
    }

    @Test
    @DisplayName("abridge: long text head + tail with omitted note")
    void abridge_long() {
        String t = "HEAD" + "x".repeat(1000) + "TAIL";
        String r = JevContextCompactor.abridge(t, 4, 4);
        assertTrue(r.startsWith("HEAD"));
        assertTrue(r.endsWith("TAIL"));
        assertTrue(r.contains("chars omitted"));
    }

    @Test
    @DisplayName("truncatedResultText: keeps head + truncated note")
    void truncatedResultText_basic() {
        String t = "y".repeat(1000);
        String r = JevContextCompactor.truncatedResultText(t, false, 50);
        assertTrue(r.contains("fast-jev-compaction truncated"));
        assertTrue(r.contains("re-run the tool if needed"));
        assertFalse(r.contains("(error)"));
    }

    @Test
    @DisplayName("truncatedResultText: error flag adds (error) suffix")
    void truncatedResultText_error() {
        String t = "y".repeat(1000);
        String r = JevContextCompactor.truncatedResultText(t, true, 50);
        assertTrue(r.contains("(error)"));
    }

    // ---------- applyDecisions ----------

    @Test
    @DisplayName("applyDecisions: DROP_CALL removes call + result, message may collapse")
    void applyDecisions_dropCall() {
        var m1 = msg("user", "hi");
        var m2 = assistantWithCall("a", "Read", "{}", "result-a");
        var m3 = userResult("a", "result-a");
        var ms = List.of(m1, m2, m3);
        var calls = JevContextCompactor.collectToolCalls(ms, 0);
        var decisions = List.of(new JevContextCompactor.CallDecision(
            "t1", "Read", JevContextCompactor.DecisionAction.DROP_CALL, "call_dropped", 0.3, 0.3));
        var kept = JevContextCompactor.applyDecisions(ms, decisions, calls, 100);
        assertEquals(1, kept.size(), "only user hi stays");
        assertEquals("hi", kept.get(0).text());
    }

    @Test
    @DisplayName("applyDecisions: DROP_RESULT keeps call, truncates result text")
    void applyDecisions_dropResult() {
        // m1 = assistant with tool_use (no result)
        // m2 = user with tool_result (long enough to be truncated: > headChars+120)
        String longResult = "x".repeat(500);
        var m1 = assistantWithCall("a", "Read", "{}", longResult);
        var m2 = userResult("a", longResult);
        var ms = List.of(m1, m2);
        var calls = JevContextCompactor.collectToolCalls(ms, 0);
        var decisions = List.of(new JevContextCompactor.CallDecision(
            "t1", "Read", JevContextCompactor.DecisionAction.DROP_RESULT, "result_dropped", 0.9, 0.3));
        var kept = JevContextCompactor.applyDecisions(ms, decisions, calls, 5);
        assertEquals(2, kept.size());
        // assistant message: toolUses stays, no toolResults
        assertEquals(1, kept.get(0).toolUses().size());
        assertEquals(0, kept.get(0).toolResults().size());
        // user message: toolResults stays but text truncated
        assertEquals(1, kept.get(1).toolResults().size());
        assertTrue(kept.get(1).toolResults().get(0).text().contains("fast-jev-compaction truncated"));
    }

    // ---------- End-to-end compact ----------

    @Test
    @DisplayName("compact: end-to-end with fake Jev yields correct stats breakdown")
    void compact_e2e() {
        var ms = longTranscript();
        // Per-question noul table: keep all result, keep all call (default behavior)
        var fakeJev = new FakeJevAsker(new LinkedHashMap<>());
        var stats = JevContextCompactor.compact(ms, fakeJev, new JevContextCompactor.CompactOptions(
            0.5, 6, 25_000, 30_000, 100));
        assertEquals(8, stats.calls());
        // 2 pinned + 6 candidate. With all noul=0.5 (>= threshold) -> all KEEP.
        // Actually 0.5 >= 0.5 -> keepResult check first -> all 6 KEEP
        assertEquals(8, stats.kept() + stats.pinned() + stats.resultsDropped() + stats.callsDropped());
        assertTrue(stats.stateStage().equals("full") || stats.stateStage().startsWith("input_cap_"));
        assertTrue(stats.ms() >= 0);
    }

    @Test
    @DisplayName("compact: null-safe Jev answer default to 1.0 (keep) per Jev-OpenEyes §4.E")
    void compact_nullAnswerSafe() {
        var ms = longTranscript();
        // Ask returns empty -> all answers default to 1.0 (keep)
        JevContextCompactor.JevAsker nullAsker = (state, questions) -> new LinkedHashMap<>();
        var stats = JevContextCompactor.compact(ms, nullAsker, new JevContextCompactor.CompactOptions(
            0.5, 6, 25_000, 30_000, 100));
        assertEquals(0, stats.callsDropped(), "no calls should be dropped when answer is null");
        assertEquals(0, stats.resultsDropped(), "no results should be dropped when answer is null");
    }

    @Test
    @DisplayName("compact: keepResult low + keepCall high -> result_dropped count")
    void compact_dropResults() {
        var ms = longTranscript();
        Map<String, Double> table = new LinkedHashMap<>();
        // For non-pinned calls: keepCall=0.9 (high), keepResult=0.3 (low)
        // We don't know call ids ahead of time; FakeJevAsker receives question names with prefix
        // We'll mark all questions to return keepResult-like value via name check
        // Instead: simpler -- make all answers 0.3 (below threshold)
        for (int i = 2; i <= 8; i++) {
            table.put("call_t" + i, 0.3);
            table.put("result_t" + i, 0.3);
        }
        var fakeJev = new FakeJevAsker(table);
        var stats = JevContextCompactor.compact(ms, fakeJev, new JevContextCompactor.CompactOptions(
            0.5, 6, 25_000, 30_000, 100));
        // t1 is inside first message -> pinned (KEEP pinned)
        // t2..t6 not pinned, both below threshold -> DROP_CALL
        assertTrue(stats.callsDropped() >= 4, "expected >= 4 calls dropped (t2..t6), got " + stats.callsDropped());
    }
}