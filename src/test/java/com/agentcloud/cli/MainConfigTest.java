package com.agentcloud.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainConfigTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("agentcloud.dispatch.preflight.warmup");
    }

    @Test
    void dispatchPreflightWarmupDefaultsToEnabled() {
        System.clearProperty("agentcloud.dispatch.preflight.warmup");

        assertTrue(Main.dispatchPreflightWarmupEnabled());
    }

    @Test
    void dispatchPreflightWarmupCanBeDisabledForBrowserAcceptanceRuns() {
        System.setProperty("agentcloud.dispatch.preflight.warmup", "false");

        assertFalse(Main.dispatchPreflightWarmupEnabled());
    }
}
