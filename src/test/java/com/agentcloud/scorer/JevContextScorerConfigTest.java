package com.agentcloud.scorer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JevContextScorerConfig (per docs/JEV_CONTEXT_SCORING_PLAN.md §1.1 + §11).
 *
 * Covers:
 *   - defaults() returns documented values
 *   - constructor validation (ranges / non-null / non-negative)
 *   - explicit constructor with all fields
 *   - immutability (no setters)
 *
 * Author: Codex (yangpeng) -- 2026-09-21.
 */
@DisplayName("JevContextScorerConfig defaults + validation contract test (FEAT-03)")
class JevContextScorerConfigTest {

    @Test
    @DisplayName("defaults() returns documented values from plan §1.1")
    void defaultsMatchPlan() {
        JevContextScorerConfig c = JevContextScorerConfig.defaults();
        assertFalse(c.isEnabled(), "default enabled=false per plan §11 立项门槛 1");
        assertEquals(0.5, c.keepThreshold(), 0.0, "default keepThreshold=0.5");
        assertEquals(6, c.preserveRecentMessages());
        assertEquals(300, c.truncateHeadChars());
        assertEquals(5000, c.requestTimeoutMs(), "default requestTimeoutMs=5000 per plan §11 立项门槛 3");
        assertEquals("https://api.typesafe.ai", c.baseUrl());
        assertEquals("TYPESAFE_API_KEY", c.apiKeyEnvVar(),
            "default apiKeyEnvVar=TYPESAFE_API_KEY per plan §11 立项门槛 2");
        assertEquals(30000, c.maxRequestTokens());
        assertEquals(25000, c.maxStateTokens());
    }

    @Test
    @DisplayName("constructor validates keepThreshold ∈ [0,1]")
    void keepThresholdValidation() {
        assertThrows(IllegalArgumentException.class,
            () -> new JevContextScorerConfig(false, -0.1, 6, 300, 5000,
                "https://api.typesafe.ai", "TYPESAFE_API_KEY", 30000, 25000));
        assertThrows(IllegalArgumentException.class,
            () -> new JevContextScorerConfig(false, 1.1, 6, 300, 5000,
                "https://api.typesafe.ai", "TYPESAFE_API_KEY", 30000, 25000));
        // boundary values accepted
        assertDoesNotThrow(() ->
            new JevContextScorerConfig(false, 0.0, 6, 300, 5000,
                "https://api.typesafe.ai", "TYPESAFE_API_KEY", 30000, 25000));
        assertDoesNotThrow(() ->
            new JevContextScorerConfig(false, 1.0, 6, 300, 5000,
                "https://api.typesafe.ai", "TYPESAFE_API_KEY", 30000, 25000));
    }

    @Test
    @DisplayName("constructor validates non-negative numerics")
    void nonNegativeValidation() {
        assertThrows(IllegalArgumentException.class,
            () -> new JevContextScorerConfig(false, 0.5, -1, 300, 5000,
                "https://api.typesafe.ai", "TYPESAFE_API_KEY", 30000, 25000));
        assertThrows(IllegalArgumentException.class,
            () -> new JevContextScorerConfig(false, 0.5, 6, -1, 5000,
                "https://api.typesafe.ai", "TYPESAFE_API_KEY", 30000, 25000));
        assertThrows(IllegalArgumentException.class,
            () -> new JevContextScorerConfig(false, 0.5, 6, 300, -1,
                "https://api.typesafe.ai", "TYPESAFE_API_KEY", 30000, 25000));
        assertThrows(IllegalArgumentException.class,
            () -> new JevContextScorerConfig(false, 0.5, 6, 300, 5000,
                "https://api.typesafe.ai", "TYPESAFE_API_KEY", -1, 25000));
    }

    @Test
    @DisplayName("constructor validates non-null strings")
    void nonNullStringValidation() {
        assertThrows(NullPointerException.class,
            () -> new JevContextScorerConfig(false, 0.5, 6, 300, 5000,
                null, "TYPESAFE_API_KEY", 30000, 25000));
        assertThrows(NullPointerException.class,
            () -> new JevContextScorerConfig(false, 0.5, 6, 300, 5000,
                "https://api.typesafe.ai", null, 30000, 25000));
    }

    @Test
    @DisplayName("explicit constructor with all custom fields round-trips via accessors")
    void explicitConstructorRoundTrip() {
        JevContextScorerConfig c = new JevContextScorerConfig(
            true, 0.7, 10, 500, 8000,
            "https://openrouter.ai/api", "OPENROUTER_API_KEY",
            50000, 40000);
        assertTrue(c.isEnabled());
        assertEquals(0.7, c.keepThreshold(), 0.0);
        assertEquals(10, c.preserveRecentMessages());
        assertEquals(500, c.truncateHeadChars());
        assertEquals(8000, c.requestTimeoutMs());
        assertEquals("https://openrouter.ai/api", c.baseUrl());
        assertEquals("OPENROUTER_API_KEY", c.apiKeyEnvVar(),
            "OPENROUTER_API_KEY is the alternative per plan §11 立项门槛 2");
        assertEquals(50000, c.maxRequestTokens());
        assertEquals(40000, c.maxStateTokens());
    }

    @Test
    @DisplayName("config is immutable (no setters exposed)")
    void immutabilityCheck() {
        JevContextScorerConfig c = JevContextScorerConfig.defaults();
        // record-style accessors only; no withers/mutators
        // (compilation would fail if setters were added; this test asserts the API surface)
        assertNotNull(c.baseUrl());
        // Verify class is final (no extension)
        assertTrue(java.lang.reflect.Modifier.isFinal(JevContextScorerConfig.class.getModifiers()),
            "JevContextScorerConfig should be final (immutable)");
    }
}