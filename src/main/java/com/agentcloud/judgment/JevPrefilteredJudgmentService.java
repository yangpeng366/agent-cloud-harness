package com.agentcloud.judgment;

import com.agentcloud.judgment.model.CompletionDecision;
import com.agentcloud.judgment.model.ExecutionDecision;
import com.agentcloud.scorer.JevAction;
import com.agentcloud.scorer.JevScorer;
import com.agentcloud.scorer.JevDecision;
import com.agentcloud.scorer.JevRequestItem;

import java.util.Map;

/**
 * JevPrefilteredJudgmentService -- decorator wrapping JudgmentService with Jev prefilter.
 *
 * Per docs/JEV_CONTEXT_SCORING_PLAN.md §1.2 + HW-10 JevJudgmentPrefilter 合同:
 *   - enabled=true: Jev scores state -> high prob (KEEP_VERBATIM) skip LLM (source=jev_act);
 *                   low prob (TRUNCATE_HEAD) skip LLM (source=skipped_by_jev).
 *   - enabled=false OR jevScorer=null: delegate directly (no Jev path).
 *
 * This is the FEAT-03 Step 3a integration point: decorator pattern (non-invasive,
 * does NOT modify PromptBasedJudgmentService). Main.java wiring happens in
 * Step 3b when the feature flag is enabled.
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
public class JevPrefilteredJudgmentService implements JudgmentService {

    private final JudgmentService delegate;
    private final JevScorer jevScorer;

    public JevPrefilteredJudgmentService(JudgmentService delegate, JevScorer jevScorer) {
        this.delegate = delegate;
        this.jevScorer = jevScorer;
    }

    /** Convenience: no Jev path (delegate only). */
    public JevPrefilteredJudgmentService(JudgmentService delegate) {
        this(delegate, null);
    }

    @Override
    public ExecutionDecision judgeExecution(JudgmentContext context) {
        JevDecision jevDecision = tryPrefilter(context);
        if (jevDecision == null) {
            return delegate.judgeExecution(context);
        }
        if (jevDecision.action() == JevAction.KEEP_VERBATIM) {
            return defaultExecution("jev_act", jevDecision.probability());
        }
        if (jevDecision.action() == JevAction.TRUNCATE_HEAD) {
            return defaultExecution("skipped_by_jev", jevDecision.probability());
        }
        // Future: ambiguous (PRESERVE_RECENT or score==threshold) -> fall through
        return delegate.judgeExecution(context);
    }

    @Override
    public CompletionDecision judgeCompletion(JudgmentContext context) {
        JevDecision jevDecision = tryPrefilter(context);
        if (jevDecision == null) {
            return delegate.judgeCompletion(context);
        }
        // Same source labels as judgeExecution (contract symmetry per plan §1.2)
        if (jevDecision.action() == JevAction.KEEP_VERBATIM) {
            return defaultCompletion("jev_act", jevDecision.probability());
        }
        if (jevDecision.action() == JevAction.TRUNCATE_HEAD) {
            return defaultCompletion("skipped_by_jev", jevDecision.probability());
        }
        return delegate.judgeCompletion(context);
    }

    /** Returns Jev decision if prefilter applies, null otherwise. */
    private JevDecision tryPrefilter(JudgmentContext context) {
        if (jevScorer == null) return null;
        if (!jevScorer.isEnabled()) return null;
        if (context == null) return null;
        JevRequestItem item = new JevRequestItem(
            context.task() != null && context.task().id() != null
                ? "judgment:" + context.task().id()
                : "judgment:unknown",
            summarizeContext(context),
            "judgment");
        try {
            return jevScorer.decide(item);
        } catch (Exception e) {
            // Fallback per plan §1.2: Jev 异常时 delegate 仍然可用
            return null;
        }
    }

    private String summarizeContext(JudgmentContext context) {
        if (context == null) return "";
        StringBuilder sb = new StringBuilder();
        if (context.task() != null) sb.append("task=").append(context.task().id()).append(";");
        if (context.workerOutput() != null) {
            String wo = context.workerOutput();
            sb.append("workerOutput=").append(wo.length() > 200 ? wo.substring(0, 197) + "..." : wo).append(";");
        }
        if (context.completionCriteria() != null) {
            sb.append("criteria=").append(context.completionCriteria()).append(";");
        }
        return sb.toString();
    }

    private ExecutionDecision defaultExecution(String source, double prob) {
        Map<String, Object> runtimeFacts = Map.of("runtime_facts", Map.of(
            "jev_prefilter_decision", Map.of(
                "action", source,
                "llm_called", false,
                "probability", prob
            )
        ));
        return new ExecutionDecision(
            "continue",
            source + " jev_prob=" + String.format("%.2f", prob),
            "", false, false, false, false, false, false, "", "", "",
            runtimeFacts);
    }

    private CompletionDecision defaultCompletion(String source, double prob) {
        Map<String, Object> runtimeFacts = Map.of("runtime_facts", Map.of(
            "jev_prefilter_decision", Map.of(
                "action", source,
                "llm_called", false,
                "probability", prob
            )
        ));
        return new CompletionDecision(
            "done",
            "high",
            source + " jev_prob=" + String.format("%.2f", prob),
            "continue",
            runtimeFacts);
    }

    /** Test-only: expose JevScorer for assertions. */
    public JevScorer jevScorer() { return jevScorer; }
    /** Test-only: expose delegate. */
    public JudgmentService delegate() { return delegate; }
}
