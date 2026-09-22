package com.agentcloud.server;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JevHealthEnricherTest -- contract test for FEAT-03 threshold 7 (runtime_facts.jev_*
 * + /judgment_trace dual channel). Mirrors OpenEyesHealthEnricherTest shape.
 *
 * Validates the jev_context_scoring health payload shape:
 *   - enabled defaults to false (off; FEAT-03 feature flag off at-rest)
 *   - scorer_class / prefilter_class are exposed by simple class name
 *
 * Author: Codex (yangpeng) -- 2026-09-22.
 */
class JevHealthEnricherTest {

    @Test
    void snapshotExposesExpectedKeys() {
        Map<String, Object> snapshot = JevHealthEnricher.snapshot();
        assertNotNull(snapshot);
        assertTrue(snapshot.containsKey("enabled"));
        assertTrue(snapshot.containsKey("scorer_class"));
        assertTrue(snapshot.containsKey("prefilter_class"));
    }

    @Test
    void snapshotEnabledDefaultsFalse() {
        Map<String, Object> snapshot = JevHealthEnricher.snapshot();
        assertEquals(false, snapshot.get("enabled"),
            "FEAT-03 feature flag defaults to off at-rest until Main.java wires it.");
    }

    @Test
    void snapshotScorerClassMatchesImplementation() {
        Map<String, Object> snapshot = JevHealthEnricher.snapshot();
        assertEquals("JevContextScorer", snapshot.get("scorer_class"));
    }

    @Test
    void snapshotPrefilterClassMatchesImplementation() {
        Map<String, Object> snapshot = JevHealthEnricher.snapshot();
        assertEquals("JevPrefilteredJudgmentService", snapshot.get("prefilter_class"));
    }

    @Test
    void snapshotIsImmutableSnapshot() {
        Map<String, Object> snapshot = JevHealthEnricher.snapshot();
        // Ensure the returned map is a snapshot (no surprise mutations)
        int initialSize = snapshot.size();
        snapshot.put("injected", "should-not-persist");
        Map<String, Object> second = JevHealthEnricher.snapshot();
        assertFalse(second.containsKey("injected"),
            "snapshot() must return a fresh map per call.");
        assertEquals(initialSize, second.size());
    }
}
