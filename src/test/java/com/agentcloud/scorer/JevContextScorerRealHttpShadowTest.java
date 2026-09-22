package com.agentcloud.scorer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JevContextScorerRealHttpShadowTest {

    @Test
    void callsRealTypesafeHttpOnlyWhenShadowIsEnabled() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("JEV_REAL_HTTP_SHADOW")));
        Assumptions.assumeTrue(System.getenv("TYPESAFE_API_KEY") != null
            && !System.getenv("TYPESAFE_API_KEY").isBlank());

        JevContextScorer scorer = new JevContextScorer(JevContextScorerConfig.enabledDefaults());
        long started = System.nanoTime();
        JevDecision decision = scorer.decide(new JevRequestItem(
            "shadow:production-judgment",
            "task=shadow; workerOutput=Grounded patch and test evidence are present; criteria=verified build and test.",
            "judgment"
        ));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(decision.probability() >= 0.0 && decision.probability() <= 1.0);
        assertTrue(elapsedMs < 30_000);
        System.out.printf(
            "JEV_SHADOW action=%s probability=%.3f latency_ms=%d%n",
            decision.action(), decision.probability(), elapsedMs);
    }
}
