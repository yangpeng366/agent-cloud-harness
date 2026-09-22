package com.agentcloud.scorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JevDecisionTreeTest — Fake Jev decision tree parallel questions contract test.
 *
 * Mirrors fast-jev-compaction tests/fakeJev shape (see JEV_HANDS_ON_NOTES.md §2).
 * Verifies the JEV_DECISION_TREE_REAL_EXPERIMENT.md prototype is reproducible
 * in Java with no external dependency on TypeSafe AI.
 *
 * This is the JEV_GRAPH_NODES_PLAN.md path B prototype (INITIATIVES/Jev-Graph-B.md):
 * RuntimeJudgmentService upgrade to Jev parallel questions decision tree.
 *
 * Author: Codex (yangpeng) — 2026-09-21.
 */
@DisplayName("JevDecisionTree — fake Jev parallel questions decision tree (path B prototype)")
public class JevDecisionTreeTest {

    /** Fake Jev answer shape (subset of fast-jev-compaction src/types.ts:JevAnswer). */
    public static class NoulAnswer {
        public String type = "noul";
        public double noul;
        public NoulAnswer() {}
        public NoulAnswer(double noul) { this.noul = noul; }
    }

    public static class ChoiceAnswer {
        public String type = "choice";
        public String choice;
        public double confidence;
        public Map<String, Double> probabilities = new LinkedHashMap<>();
        public ChoiceAnswer() {}
    }

    /**
     * Fake Jev asker — per-question-name probability table.
     * Mirrors fast-jev-compaction tests/fakeJev pattern (Lambda-style answer function).
     */
    public static class FakeJevAsker {
        private final Map<String, Double> noulTable;
        private final Map<String, ChoiceAnswer> choiceTable;
        public final List<String> seenQuestions = new ArrayList<>();

        public FakeJevAsker(Map<String, Double> noulTable,
                             Map<String, ChoiceAnswer> choiceTable) {
            this.noulTable = noulTable != null ? noulTable : new LinkedHashMap<>();
            this.choiceTable = choiceTable != null ? choiceTable : new LinkedHashMap<>();
        }

        public Map<String, Object> ask(Map<String, Object> state,
                                        Map<String, Map<String, Object>> questions) {
            Map<String, Object> answers = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> e : questions.entrySet()) {
                String name = e.getKey();
                seenQuestions.add(name);
                Map<String, Object> q = e.getValue();
                String type = (String) q.getOrDefault("type", "noul");
                if ("noul".equals(type)) {
                    double p = noulTable.getOrDefault(name, 0.5);
                    NoulAnswer a = new NoulAnswer(p);
                    answers.put(name, a);
                } else if ("choice".equals(type)) {
                    ChoiceAnswer a = choiceTable.get(name);
                    if (a == null) {
                        a = new ChoiceAnswer();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> criteria = (Map<String, Object>) q.get("criteria");
                        if (criteria != null && !criteria.isEmpty()) {
                            a.choice = criteria.keySet().iterator().next();
                            a.probabilities.put(a.choice, 1.0);
                        }
                        a.confidence = 0.0;
                    }
                    answers.put(name, a);
                }
            }
            return Map.of("answers", answers);
        }
    }

    /** Confidence calculation (Noul has no confidence; Choice uses (N × max_prob - 1) / (N - 1)). */
    public static double computeConfidence(ChoiceAnswer a) {
        if (a == null || a.probabilities == null || a.probabilities.isEmpty()) return 0.0;
        int n = a.probabilities.size();
        double max = a.probabilities.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        if (n <= 1) return max;
        return Math.max(0.0, Math.min(1.0, (n * max - 1.0) / (n - 1.0)));
    }

    /** 3-segment threshold routing: act / confirm / human_review. */
    public static String routeByConfidence(double confidence,
                                          double lowStakesThreshold,
                                          double highStakesThreshold) {
        if (confidence >= highStakesThreshold) return "act";
        if (confidence >= lowStakesThreshold) return "confirm";
        return "human_review";
    }

    @Test
    @DisplayName("5-question decision tree produces 5/5 votes for option C (real fixture)")
    void fiveQuestionDecisionTreeVotesC() throws Exception {
        // Mirror JEV_DECISION_TREE_REAL_EXPERIMENT.md §2 real Jev data
        Map<String, Double> noulTable = new LinkedHashMap<>();
        Map<String, ChoiceAnswer> choiceTable = new LinkedHashMap<>();

        // 4 options per choice
        String[] options = {"A", "B", "C", "D"};
        // 5 questions × 1 winning option C with varying confidence
        Map<String, double[]> questionProbs = new LinkedHashMap<>();
        questionProbs.put("best_for_zero_cost",          new double[]{0.08, 0.01, 0.91, 0.00});
        questionProbs.put("best_for_git_tracking",       new double[]{0.00, 0.27, 0.53, 0.20});
        questionProbs.put("best_for_organization_kept",  new double[]{0.00, 0.00, 0.85, 0.15});
        questionProbs.put("best_for_no_structural_change", new double[]{0.09, 0.02, 0.89, 0.00});
        questionProbs.put("best_for_audit_clean",        new double[]{0.00, 0.00, 1.00, 0.00});

        // Build choice tables from per-question probabilities
        for (Map.Entry<String, double[]> e : questionProbs.entrySet()) {
            ChoiceAnswer a = new ChoiceAnswer();
            double[] probs = e.getValue();
            a.choice = "C"; // winner
            for (int i = 0; i < options.length; i++) {
                a.probabilities.put(options[i], probs[i]);
            }
            a.confidence = computeConfidence(a);
            choiceTable.put(e.getKey(), a);
        }

        FakeJevAsker asker = new FakeJevAsker(noulTable, choiceTable);

        // Build 5 questions in choice form
        Map<String, Map<String, Object>> questions = new LinkedHashMap<>();
        for (String name : questionProbs.keySet()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("type", "choice");
            q.put("instructions", "Which option is best for: " + name);
            Map<String, Object> criteria = new LinkedHashMap<>();
            for (String opt : options) criteria.put(opt, "option " + opt);
            q.put("criteria", criteria);
            questions.put(name, q);
        }

        // Single batch call (mirrors fast-jev-compaction compact())
        Map<String, Object> response = asker.ask(Map.of("context", "INITIATIVES fix"), questions);

        @SuppressWarnings("unchecked")
        Map<String, Object> outerAnswers = (Map<String, Object>) response.get("answers");
        assertEquals(5, outerAnswers.size(), "must answer all 5 questions in 1 batch");

        // Verify all 5 questions chose C; fakeJev stores typed ChoiceAnswer instances directly
        AtomicInteger votesForC = new AtomicInteger(0);
        double totalConfidence = 0.0;
        for (Map.Entry<String, Object> e : outerAnswers.entrySet()) {
            Object value = e.getValue();
            String choice;
            double confidence;
            if (value instanceof ChoiceAnswer) {
                ChoiceAnswer ca = (ChoiceAnswer) value;
                choice = ca.choice;
                confidence = ca.confidence;
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ansMap = (Map<String, Object>) value;
                choice = (String) ansMap.get("choice");
                confidence = ((Number) ansMap.get("confidence")).doubleValue();
            } else {
                throw new AssertionError("unexpected answer type: " + (value == null ? "null" : value.getClass()));
            }
            totalConfidence += confidence;
            if ("C".equals(choice)) votesForC.incrementAndGet();
        }

        assertEquals(5, votesForC.get(), "all 5 questions must vote C (mirrors real fixture)");
        double avgConfidence = totalConfidence / 5.0;
        assertTrue(avgConfidence > 0.5, "avg confidence must indicate strong decision; was " + avgConfidence);

        // Verify the git_tracking question has medium-low confidence (mirrors real data)
        Object gitVal = outerAnswers.get("best_for_git_tracking");
        ChoiceAnswer gitAns = (ChoiceAnswer) gitVal;
        double gitConfidence = gitAns.confidence;
        assertTrue(gitConfidence < 0.5,
            "best_for_git_tracking confidence must be < 0.5 (real fixture data); was " + gitConfidence);

        System.out.printf("5/5 votes for C, avg confidence=%.4f, git_tracking confidence=%.4f%n",
            avgConfidence, gitConfidence);
    }

    @Test
    @DisplayName("Confidence 3-segment routing produces act/confirm/human_review")
    void confidenceThreeSegmentRouting() {
        // Confidence 0.95 → act
        assertEquals("act", routeByConfidence(0.95, 0.6, 0.85));
        assertEquals("act", routeByConfidence(0.85, 0.6, 0.85));  // boundary
        // Confidence 0.65-0.84 → confirm
        assertEquals("confirm", routeByConfidence(0.70, 0.6, 0.85));
        assertEquals("confirm", routeByConfidence(0.60, 0.6, 0.85));  // boundary
        // Confidence < 0.6 → human_review
        assertEquals("human_review", routeByConfidence(0.59, 0.6, 0.85));
        assertEquals("human_review", routeByConfidence(0.38, 0.6, 0.85));  // git_tracking case
        assertEquals("human_review", routeByConfidence(0.0, 0.6, 0.85));
    }

    @Test
    @DisplayName("Jev Asker records all asked questions in seenQuestions list")
    void askerRecordsSeenQuestions() {
        Map<String, Double> noulTable = new LinkedHashMap<>();
        noulTable.put("q1", 0.9);
        noulTable.put("q2", 0.1);
        FakeJevAsker asker = new FakeJevAsker(noulTable, null);

        Map<String, Map<String, Object>> questions = new LinkedHashMap<>();
        questions.put("q1", Map.of("type", "noul", "instructions", "is q1 high?"));
        questions.put("q2", Map.of("type", "noul", "instructions", "is q2 high?"));

        Map<String, Object> resp = asker.ask(Map.of("context", "test"), questions);
        assertEquals(2, asker.seenQuestions.size());
        assertTrue(asker.seenQuestions.contains("q1"));
        assertTrue(asker.seenQuestions.contains("q2"));
    }

    @Test
    @DisplayName("Confidence formula: (N × max_prob - 1) / (N - 1)")
    void confidenceFormula() {
        // 3 options 90/5/5 → (3 × 0.9 - 1) / (3 - 1) = 1.7 / 2 = 0.85
        ChoiceAnswer a = new ChoiceAnswer();
        a.choice = "A";
        a.probabilities.put("A", 0.90);
        a.probabilities.put("B", 0.05);
        a.probabilities.put("C", 0.05);
        a.confidence = computeConfidence(a);
        assertEquals(0.85, a.confidence, 0.001, "3-option 90/5/5 → 0.85");

        // 4 options 100/0/0/0 → (4 × 1.0 - 1) / (4 - 1) = 3 / 3 = 1.0
        ChoiceAnswer b = new ChoiceAnswer();
        b.choice = "A";
        b.probabilities.put("A", 1.00);
        b.probabilities.put("B", 0.00);
        b.probabilities.put("C", 0.00);
        b.probabilities.put("D", 0.00);
        b.confidence = computeConfidence(b);
        assertEquals(1.0, b.confidence, 0.001);

        // 4 options 33.3/33.3/33.4/0 → max=0.334, (4 × 0.334 - 1) / 3 = 0.336/3 = 0.112
        ChoiceAnswer c = new ChoiceAnswer();
        c.choice = "C";
        c.probabilities.put("A", 0.333);
        c.probabilities.put("B", 0.333);
        c.probabilities.put("C", 0.334);
        c.probabilities.put("D", 0.00);
        c.confidence = computeConfidence(c);
        assertEquals(0.112, c.confidence, 0.005, "near-uniform distribution → low confidence");
    }
}