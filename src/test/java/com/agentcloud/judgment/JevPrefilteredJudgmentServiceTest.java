package com.agentcloud.judgment;

import com.agentcloud.judgment.model.CompletionDecision;
import com.agentcloud.judgment.model.ExecutionDecision;
import com.agentcloud.model.Task;
import com.agentcloud.scorer.JevContextScorer;
import com.agentcloud.scorer.JevScorer;
import com.agentcloud.scorer.JevContextScorerFake;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JevPrefilteredJudgmentServiceTest -- decorator contract test.
 *
 * Per docs/JEV_CONTEXT_SCORING_PLAN.md §1.2 + HW-10 JevJudgmentPrefilter 合同:
 *   - enabled=true + KEEP_VERBATIM (high prob) -> skip LLM, source=jev_act
 *   - enabled=true + TRUNCATE_HEAD (low prob) -> skip LLM, source=skipped_by_jev
 *   - enabled=false OR jevScorer=null -> delegate directly (no Jev path)
 *   - Jev exception -> fallback to delegate (per plan §1.2 fallback)
 *
 * Uses JevContextScorerFake (no network) per plan §11 立项门槛 6.
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
@DisplayName("JevPrefilteredJudgmentService decorator test (FEAT-03 Step 3a)")
class JevPrefilteredJudgmentServiceTest {

    /** Stub delegate that records whether judgeExecution was called. */
    static class RecordingDelegate implements JudgmentService {
        int executionCalls = 0;
        int completionCalls = 0;
        final ExecutionDecision executionReturn = new ExecutionDecision(
            "delegate_execution", "from delegate", "", false, false, "");
        final CompletionDecision completionReturn = new CompletionDecision(
            "delegate_done", "high", "from delegate", "continue");

        @Override
        public ExecutionDecision judgeExecution(JudgmentContext context) {
            executionCalls++;
            return executionReturn;
        }

        @Override
        public CompletionDecision judgeCompletion(JudgmentContext context) {
            completionCalls++;
            return completionReturn;
        }
    }

    private static JudgmentContext sampleContext() {
        return new JudgmentContext(
            task(),
            null,
            "worker said the parser is fixed",
            "all tests pass",
            Map.of("tool", "codex"));
    }

    private static Task task() {
        // Task record canonical ctor: 18 String/Instant fields. We only need id non-null.
        return new Task(
            "test-task-id", null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("Jev KEEP_VERBATIM (high prob) -> skip LLM, source=jev_act")
    void highProbSkipsLlmAsJevAct() {
        // Fake score=0.95, threshold=0.5 -> KEEP_VERBATIM
        JevScorer scorer = new JevContextScorerFake(0.95, 0.5);
        RecordingDelegate delegate = new RecordingDelegate();
        JevPrefilteredJudgmentService svc = new JevPrefilteredJudgmentService(delegate, scorer);

        ExecutionDecision d = svc.judgeExecution(sampleContext());

        assertEquals(0, delegate.executionCalls, "delegate should NOT be called on KEEP_VERBATIM path");
        assertEquals("continue", d.action(), "default action=continue");
        assertTrue(d.reason().contains("jev_act"), "reason should contain source=jev_act, got: " + d.reason());
        assertTrue(d.reason().contains("0.95"), "reason should contain jev_prob=0.95");
    }

    @Test
    @DisplayName("Jev decisions expose runtime_facts for judgment trace")
    void runtimeFactsExposePrefilterDecision() {
        JevScorer scorer = new JevContextScorerFake(0.95, 0.5);
        JevPrefilteredJudgmentService svc = new JevPrefilteredJudgmentService(new RecordingDelegate(), scorer);

        ExecutionDecision d = svc.judgeExecution(sampleContext());
        CompletionDecision cd = svc.judgeCompletion(sampleContext());

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> executionFacts = (java.util.Map<String, Object>) d.runtimeFacts().get("runtime_facts");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> executionDecision = (java.util.Map<String, Object>) executionFacts.get("jev_prefilter_decision");
        assertEquals("jev_act", executionDecision.get("action"));
        assertEquals(false, executionDecision.get("llm_called"));
        assertEquals(0.95, executionDecision.get("probability"));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> completionFacts = (java.util.Map<String, Object>) cd.runtimeFacts().get("runtime_facts");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> completionDecision = (java.util.Map<String, Object>) completionFacts.get("jev_prefilter_decision");
        assertEquals("jev_act", completionDecision.get("action"));
        assertEquals(0.95, completionDecision.get("probability"));
    }

    @Test
    @DisplayName("Jev TRUNCATE_HEAD (low prob) -> skip LLM, source=skipped_by_jev")
    void lowProbSkipsLlmAsSkippedByJev() {
        // Fake score=0.05, threshold=0.5 -> TRUNCATE_HEAD
        JevScorer scorer = new JevContextScorerFake(0.05, 0.5);
        RecordingDelegate delegate = new RecordingDelegate();
        JevPrefilteredJudgmentService svc = new JevPrefilteredJudgmentService(delegate, scorer);

        ExecutionDecision d = svc.judgeExecution(sampleContext());

        assertEquals(0, delegate.executionCalls, "delegate should NOT be called on TRUNCATE_HEAD path");
        assertEquals("continue", d.action());
        assertTrue(d.reason().contains("skipped_by_jev"),
            "reason should contain source=skipped_by_jev, got: " + d.reason());
        assertTrue(d.reason().contains("0.05"), "reason should contain jev_prob=0.05");
    }

    @Test
    @DisplayName("disabled path: when scorer returns isEnabled()=false, decorator delegates")
    void disabledJevDelegatesDirectly() {
        // Simulate disabled Jev via a wrapper Fake with isEnabled()=false override.
        // Use null scorer path instead (covered by nullJevScorerDelegatesDirectly).
        // This test asserts that even if a scorer is wrapped, the decorator short-circuits.
        JevScorer scorer = new JevScorer() {
            @Override public com.agentcloud.scorer.JevDecision decide(com.agentcloud.scorer.JevRequestItem item) {
                throw new RuntimeException("disabled scorer should not be called");
            }
            @Override public java.util.List<com.agentcloud.scorer.JevDecision> decideAll(java.util.List<com.agentcloud.scorer.JevRequestItem> items) {
                throw new RuntimeException("disabled scorer should not be called");
            }
            @Override public boolean isEnabled() { return false; }
        };
        RecordingDelegate delegate = new RecordingDelegate();
        JevPrefilteredJudgmentService svc = new JevPrefilteredJudgmentService(delegate, scorer);

        ExecutionDecision d = svc.judgeExecution(sampleContext());
        assertEquals(1, delegate.executionCalls, "disabled scorer -> delegate called");
        assertEquals("delegate_execution", d.action());
    }
    @Test
    @DisplayName("null JevScorer -> delegate called (no Jev path)")
    void nullJevScorerDelegatesDirectly() {
        RecordingDelegate delegate = new RecordingDelegate();
        JevPrefilteredJudgmentService svc = new JevPrefilteredJudgmentService(delegate, null);

        ExecutionDecision d = svc.judgeExecution(sampleContext());
        CompletionDecision cd = svc.judgeCompletion(sampleContext());

        assertEquals(1, delegate.executionCalls);
        assertEquals(1, delegate.completionCalls);
        assertEquals("delegate_execution", d.action());
        assertEquals("delegate_done", cd.status());
    }

    @Test
    @DisplayName("completion path mirrors execution path (KEEP_VERBATIM -> source=jev_act)")
    void completionPathSymmetric() {
        JevScorer scorer = new JevContextScorerFake(0.95, 0.5);
        RecordingDelegate delegate = new RecordingDelegate();
        JevPrefilteredJudgmentService svc = new JevPrefilteredJudgmentService(delegate, scorer);

        CompletionDecision cd = svc.judgeCompletion(sampleContext());

        assertEquals(0, delegate.completionCalls, "delegate should NOT be called on KEEP_VERBATIM completion path");
        assertEquals("done", cd.status());
        assertEquals("high", cd.alignmentLevel());
        assertTrue(cd.reason().contains("jev_act"));
    }

    @Test
    @DisplayName("null JudgmentContext + null scorer -> delegate called with null context")
    void nullContextWithNullScorerDelegates() {
        RecordingDelegate delegate = new RecordingDelegate();
        JevPrefilteredJudgmentService svc = new JevPrefilteredJudgmentService(delegate, null);

        // Should not NPE
        assertDoesNotThrow(() -> {
            ExecutionDecision d = svc.judgeExecution(null);
            assertEquals("delegate_execution", d.action());
        });
        assertEquals(1, delegate.executionCalls);
    }
}
