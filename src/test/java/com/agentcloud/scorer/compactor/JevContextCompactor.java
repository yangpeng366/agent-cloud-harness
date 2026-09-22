package com.agentcloud.scorer.compactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JevContextCompactor — fast-jev-compaction 形态移植（path A prototype）。
 *
 * Mirrors tamaratran/fast-jev-compaction src/compact.ts + src/state.ts:
 *   - 5-stage state fitting (tool input 1000 -> 200 -> 60 -> text abridge -> collapse)
 *   - dual-noul per call decision (keepCall? + keepResult?)
 *   - pinned (first message + preserveRecentMessages last N) bypass LLM
 *   - pure-function token estimator (no tokenizer; calibrated 2-18% above true)
 *
 * This is a standalone prototype in .tmp/jev-compactor/. It does NOT
 * touch existing MountedContextView / JevContextScorer. Migration to
 * src/main/java requires maintainer sign-off (per Jev-Graph-B.md §11
 * 立项门槛 8+1 条 + DSH-贡献规范 §3).
 *
 * See:
 *   - FAST_JEV_COMPACTION_RESEARCH.md §3.1 路径 A
 *   - fast-jev-compaction/src/state.ts (fitState + estimateTokens + abridge)
 *   - fast-jev-compaction/src/compact.ts (questionsFor + decideCall + applyDecisions)
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
public final class JevContextCompactor {

    /** Token estimator calibrated constant. Letter = 1 + floor((len - 1) / 6). */
    public static final Pattern TOKEN_PIECES = Pattern.compile("[A-Za-z]+|\\d+|[^\\sA-Za-z\\d]");

    /** Successive caps on tool input included per call (5-stage fitting). */
    public static final int[] INPUT_CHARS = { 1000, 200, 60 };

    public static final int TEXT_HEAD = 400;
    public static final int TEXT_TAIL = 150;

    public static final int REQUEST_OVERHEAD_TOKENS = 20;

    private JevContextCompactor() {}

    /**
     * Token estimator without a tokenizer. Calibrated against Jev usage reports;
     * lands 2-18% above true counts. Plain chars/4 undercounts JSON-heavy states
     * by up to 40%.
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int tokens = 0;
        Matcher m = TOKEN_PIECES.matcher(text);
        while (m.find()) {
            String piece = m.group();
            char first = piece.charAt(0);
            if (first >= '0' && first <= '9') {
                tokens += piece.length() / 2;
            } else if ((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z')) {
                tokens += 1 + (piece.length() - 1) / 6;
            } else {
                tokens += 1; // 0.9 rounded up to integer
            }
        }
        return tokens;
    }

    /** One observable conversation message. */
    public record Message(String role, String text, List<ToolUse> toolUses, List<ToolResult> toolResults) {
        public Message {
            if (role == null) role = "";
            if (text == null) text = "";
            if (toolUses == null) toolUses = List.of();
            if (toolResults == null) toolResults = List.of();
        }
    }

    public record ToolUse(String toolUseId, String tool, String inputJson) {
        public ToolUse {
            if (toolUseId == null) toolUseId = "";
            if (tool == null) tool = "";
            if (inputJson == null) inputJson = "";
        }
    }

    public record ToolResult(String toolUseId, String text, boolean isError) {
        public ToolResult {
            if (toolUseId == null) toolUseId = "";
            if (text == null) text = "";
        }
    }

    /** One tool call (paired call + result) in the transcript. */
    public record ToolCall(String id, String toolUseId, String tool,
                           String inputJson, String resultText, int resultChars,
                           boolean pinned, int messageIndex) {}

    public record CompactOptions(double keepThreshold, int preserveRecentMessages,
                                 int maxStateTokens, int maxRequestTokens,
                                 int truncateHeadChars) {
        public CompactOptions {
            if (keepThreshold <= 0) keepThreshold = 0.5;
            if (preserveRecentMessages < 0) preserveRecentMessages = 6;
            if (maxStateTokens <= 0) maxStateTokens = 25_000;
            if (maxRequestTokens <= 0) maxRequestTokens = 30_000;
            if (truncateHeadChars < 0) truncateHeadChars = 300;
        }
    }

    public record CompactStats(int messagesBefore, int messagesAfter, int charsBefore, int charsAfter,
                               int calls, int kept, int resultsDropped, int callsDropped, int pinned,
                               int stateTokens, String stateStage, int requests, long ms) {}

    /** Per-call Jev answer (dual noul). */
    public record CallAnswer(double keepCall, double keepResult) {}

    public enum DecisionAction { KEEP, DROP_RESULT, DROP_CALL }

    public record CallDecision(String id, String tool, DecisionAction action, String reason,
                               double keepCall, double keepResult) {}

    /** Fitted state shape returned to caller (and re-sent to Jev with every batch). */
    public record FittedState(String state, int tokens, String stage) {}

    // ---------- Pinned ----------

    public static boolean isPinned(int index, int total, int preserveRecentMessages) {
        return index == 0 || index >= total - preserveRecentMessages;
    }

    // ---------- Tool call pairing ----------

    /** Pairs every tool_use with its tool_result by tool_use_id. Calls without a result are skipped. */
    public static List<ToolCall> collectToolCalls(List<Message> messages, int preserveRecentMessages) {
        Map<String, ToolResult> results = new LinkedHashMap<>();
        for (Message m : messages) {
            for (ToolResult r : m.toolResults()) {
                results.put(r.toolUseId(), r);
            }
        }
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            for (ToolUse tu : m.toolUses()) {
                ToolResult r = results.get(tu.toolUseId());
                if (r == null) continue;
                calls.add(new ToolCall(
                    "t" + (calls.size() + 1),
                    tu.toolUseId(),
                    tu.tool(),
                    tu.inputJson(),
                    r.text(),
                    r.text().length(),
                    isPinned(i, messages.size(), preserveRecentMessages),
                    i
                ));
            }
        }
        return calls;
    }

    // ---------- State fitting (5 stages) ----------

    public static String abridge(String text, int head, int tail) {
        if (text == null || text.length() <= head + tail + 40) return text == null ? "" : text;
        int omitted = text.length() - head - tail;
        return text.substring(0, head) + "\n[... " + omitted + " chars omitted ...]\n" + text.substring(text.length() - tail);
    }

    public static String truncateInput(String input, int limit) {
        if (input == null) return "";
        if (limit <= 0) return "...";
        if (input.length() <= limit) return input;
        return input.substring(0, limit) + "...";
    }

    /**
     * Fit the conversation into the state budget using successive stages.
     * Returns the fitted state string + final stage name + token estimate.
     * Throws if it still cannot fit after the last stage (caller fallback).
     */
    public static FittedState fitState(List<Message> messages, List<ToolCall> calls, CompactOptions opts) {
        // Stage 0: full state (tool inputs full + tool results truncated to short note)
        String stage = "full";
        String state = renderState(messages, calls, -1, false);
        int tokens = estimateTokens(state);
        if (tokens <= opts.maxStateTokens()) return new FittedState(state, tokens, stage);

        // Stage 1..3: progressively truncate tool inputs
        for (int cap : INPUT_CHARS) {
            stage = "input_cap_" + cap;
            state = renderState(messages, calls, cap, false);
            tokens = estimateTokens(state);
            if (tokens <= opts.maxStateTokens()) return new FittedState(state, tokens, stage);
        }

        // Stage 4: abridge long text (head + tail)
        stage = "text_abridge";
        state = renderState(messages, calls, INPUT_CHARS[INPUT_CHARS.length - 1], true);
        tokens = estimateTokens(state);
        if (tokens <= opts.maxStateTokens()) return new FittedState(state, tokens, stage);

        throw new IllegalStateException(
            "fitState cannot fit " + tokens + " tokens into budget " + opts.maxStateTokens());
    }

    /** Render conversation into Jev state shape. */
    static String renderState(List<Message> messages, List<ToolCall> calls, int inputCap, boolean abridgeText) {
        StringBuilder sb = new StringBuilder();
        sb.append("{ \"messages\": [");
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"role\":\"").append(m.role()).append("\",");
            String text = abridgeText && m.text().length() > TEXT_HEAD + TEXT_TAIL + 40
                ? abridge(m.text(), TEXT_HEAD, TEXT_TAIL) : m.text();
            sb.append("\"text\":\"").append(escape(text)).append("\",");
            sb.append("\"tool_uses\":[");
            for (int j = 0; j < m.toolUses().size(); j++) {
                ToolUse tu = m.toolUses().get(j);
                if (j > 0) sb.append(",");
                String input = inputCap > 0 ? truncateInput(tu.inputJson(), inputCap) : tu.inputJson();
                sb.append("{\"id\":\"").append(tu.toolUseId()).append("\",");
                sb.append("\"tool\":\"").append(tu.tool()).append("\",");
                sb.append("\"input\":\"").append(escape(input)).append("\"}");
            }
            sb.append("],\"tool_results\":[");
            for (int j = 0; j < m.toolResults().size(); j++) {
                ToolResult r = m.toolResults().get(j);
                if (j > 0) sb.append(",");
                sb.append("{\"tool_use_id\":\"").append(r.toolUseId()).append("\",");
                sb.append("\"note\":\"ok, ").append(r.text().length()).append(" chars (omitted)\",");
                sb.append("\"is_error\":").append(r.isError()).append("}");
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------- Batch splitting ----------

    /**
     * Splits candidate calls into batches whose questions, together with the
     * always-complete state, fit one request. Mirrors fast-jev-compaction
     * src/compact.ts batchCalls().
     */
    public static List<List<ToolCall>> batchCalls(List<ToolCall> candidates, int stateTokens, CompactOptions opts) {
        int budget = opts.maxRequestTokens() - stateTokens - REQUEST_OVERHEAD_TOKENS;
        List<List<ToolCall>> batches = new ArrayList<>();
        List<ToolCall> current = new ArrayList<>();
        int currentTokens = 0;
        for (ToolCall c : candidates) {
            int tokens = estimateTokens(renderQuestionsFor(c));
            if (!current.isEmpty() && currentTokens + tokens > budget) {
                batches.add(current);
                current = new ArrayList<>();
                currentTokens = 0;
            }
            if (current.isEmpty() && tokens > budget) {
                throw new IllegalStateException(
                    "state leaves no room for questions (~" + stateTokens + " of " + opts.maxRequestTokens() + " tokens)");
            }
            current.add(c);
            currentTokens += tokens;
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    /** Two-noul question pair for one call. Mirrors fast-jev-compaction questionsFor(). */
    public static String renderQuestionsFor(ToolCall call) {
        return "{\"call_" + call.id() + "\":{\"type\":\"noul\",\"instructions\":\"call " + call.id() + " should stay\"},"
             + "\"result_" + call.id() + "\":{\"type\":\"noul\",\"instructions\":\"result " + call.id() + " (" + call.resultChars() + " chars) should stay verbatim\"}}";
    }

    // ---------- Dual-noul decision ----------

    /**
     * Mirrors fast-jev-compaction src/compact.ts decideCall().
     * - keepResult >= threshold -> KEEP
     * - else keepCall >= threshold -> DROP_RESULT (truncate result)
     * - else -> DROP_CALL (delete call + result)
     * - pinned always KEEP (no LLM needed)
     */
    public static CallDecision decideCall(ToolCall call, CallAnswer answer, CompactOptions opts) {
        if (call.pinned()) {
            return new CallDecision(call.id(), call.tool(), DecisionAction.KEEP, "pinned",
                answer == null ? 1.0 : answer.keepCall(), answer == null ? 1.0 : answer.keepResult());
        }
        if (answer == null) {
            // No Jev answer -> keep by default (safer than drop). fast-jev-compaction does the same.
            return new CallDecision(call.id(), call.tool(), DecisionAction.KEEP, "no_jev_answer",
                1.0, 1.0);
        }
        if (answer.keepResult() >= opts.keepThreshold()) {
            return new CallDecision(call.id(), call.tool(), DecisionAction.KEEP, "kept",
                answer.keepCall(), answer.keepResult());
        }
        if (answer.keepCall() >= opts.keepThreshold()) {
            return new CallDecision(call.id(), call.tool(), DecisionAction.DROP_RESULT, "result_dropped",
                answer.keepCall(), answer.keepResult());
        }
        return new CallDecision(call.id(), call.tool(), DecisionAction.DROP_CALL, "call_dropped",
            answer.keepCall(), answer.keepResult());
    }

    public static String truncatedResultText(String text, boolean isError, int headChars) {
        if (text == null) return "";
        if (text.length() <= headChars + 120) return text;
        String head = headChars > 0 ? text.substring(0, headChars) + "\n" : "";
        return head + "[fast-jev-compaction truncated " + (text.length() - headChars)
             + " chars of this tool result" + (isError ? " (error)" : "")
             + "; re-run the tool if needed]";
    }

    // ---------- Result rebuild ----------

    /**
     * Rebuilds the conversation from decisions. A dropped call disappears with its result;
     * a dropped result keeps the call but truncates the result text.
     */
    public static List<Message> applyDecisions(List<Message> messages, List<CallDecision> decisions,
                                                List<ToolCall> calls, int headChars) {
        Map<String, CallDecision> byCallId = new LinkedHashMap<>();
        for (CallDecision d : decisions) byCallId.put(d.id(), d);
        Map<String, CallDecision> byToolUseId = new LinkedHashMap<>();
        for (int i = 0; i < decisions.size() && i < calls.size(); i++) {
            byToolUseId.put(calls.get(i).toolUseId(), decisions.get(i));
        }
        List<Message> kept = new ArrayList<>();
        for (Message m : messages) {
            List<ToolUse> tu = new ArrayList<>();
            for (ToolUse t : m.toolUses()) {
                CallDecision d = byToolUseId.get(t.toolUseId());
                if (d != null && d.action() == DecisionAction.DROP_CALL) continue;
                if (d != null && d.action() == DecisionAction.DROP_RESULT) {
                    // result is dropped but call stays
                    tu.add(t);
                    continue;
                }
                tu.add(t);
            }
            List<ToolResult> tr = new ArrayList<>();
            for (ToolResult r : m.toolResults()) {
                CallDecision d = byToolUseId.get(r.toolUseId());
                if (d != null && d.action() == DecisionAction.DROP_CALL) continue;
                if (d != null && d.action() == DecisionAction.DROP_RESULT) {
                    String text = truncatedResultText(r.text(), r.isError(), headChars);
                    tr.add(new ToolResult(r.toolUseId(), text, r.isError()));
                    continue;
                }
                tr.add(r);
            }
            if (m.text().trim().isEmpty() && tu.isEmpty() && tr.isEmpty()) continue;
            Message rebuilt = new Message(m.role(), m.text(), tu, tr);
            kept.add(rebuilt);
        }
        return kept;
    }

    // ---------- Top-level compact ----------

    /**
     * Compact a transcript by asking Jev, for every tool call outside the
     * pinned first and newest messages, whether the call and whether its
     * result must stay. The whole fitted history is sent as state with
     * every batch. Throws on Jev failure or fit failure (caller fallback).
     */
    public interface JevAsker {
        /** state + questions -> answers keyed by question name. */
        Map<String, Double> ask(String state, Map<String, String> questions);
    }

    public static CompactStats compact(List<Message> messages, JevAsker asker, CompactOptions opts) {
        long t0 = System.currentTimeMillis();
        List<ToolCall> calls = collectToolCalls(messages, opts.preserveRecentMessages());
        List<ToolCall> candidates = new ArrayList<>();
        for (ToolCall c : calls) if (!c.pinned()) candidates.add(c);
        int charsBefore = 0;
        for (Message m : messages) charsBefore += m.text().length()
            + m.toolUses().stream().mapToInt(t -> t.inputJson().length()).sum()
            + m.toolResults().stream().mapToInt(r -> r.text().length()).sum();

        FittedState fitted = new FittedState("", 0, "");
        List<List<ToolCall>> batches = new ArrayList<>();
        Map<String, CallAnswer> answers = new LinkedHashMap<>();
        if (!candidates.isEmpty()) {
            fitted = fitState(messages, calls, opts);
            batches = batchCalls(candidates, fitted.tokens(), opts);
            for (List<ToolCall> batch : batches) {
                Map<String, String> questions = new LinkedHashMap<>();
                for (ToolCall c : batch) {
                    questions.put("call_" + c.id(), "noul:call should stay");
                    questions.put("result_" + c.id(), "noul:result should stay verbatim");
                }
                Map<String, Double> raw = asker.ask(fitted.state(), questions);
                for (ToolCall c : batch) {
                    Double keepCall = raw.get("call_" + c.id());
                    Double keepResult = raw.get("result_" + c.id());
                    // Null-safe per .tmp/Jev-OpenEyes-experience.md §4.E
                    // (Boolean.TRUE.equals analog -> default to 1.0 keep)
                    answers.put(c.id(), new CallAnswer(
                        keepCall != null ? keepCall : 1.0,
                        keepResult != null ? keepResult : 1.0));
                }
            }
        }

        List<CallDecision> decisions = new ArrayList<>();
        for (ToolCall c : calls) {
            decisions.add(decideCall(c, answers.get(c.id()), opts));
        }

        // Rebuild not done here (caller controls drop semantics separately if needed).
        int charsAfter = charsBefore; // default: same; applyDecisions() trims result text
        int kept = 0, resultsDropped = 0, callsDropped = 0, pinned = 0;
        for (CallDecision d : decisions) {
            switch (d.action()) {
                case KEEP -> { if ("pinned".equals(d.reason())) pinned++; else kept++; }
                case DROP_RESULT -> resultsDropped++;
                case DROP_CALL -> callsDropped++;
            }
        }
        return new CompactStats(
            messages.size(), messages.size(),
            charsBefore, charsAfter,
            calls.size(), kept, resultsDropped, callsDropped, pinned,
            fitted.tokens(), fitted.stage(), batches.size(),
            System.currentTimeMillis() - t0
        );
    }
}