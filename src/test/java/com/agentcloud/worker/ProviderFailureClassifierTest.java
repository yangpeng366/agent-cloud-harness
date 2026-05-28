package com.agentcloud.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderFailureClassifierTest {

    @Test
    void classifiesThreadNotFoundAsRetryableRuntimeTransient() {
        ProviderFailureClassifier.Classification classification =
            ProviderFailureClassifier.classify("failed", "thread not found: 29180");

        assertEquals("provider_runtime_transient", classification.failureClass());
        assertTrue(classification.retryable());
    }

    @Test
    void classifiesOversizedOutputAsRetryableRuntimeTransient() {
        ProviderFailureClassifier.Classification classification =
            ProviderFailureClassifier.classify("failed", "provider failed: output too large, maximum output exceeded");

        assertEquals("provider_runtime_transient", classification.failureClass());
        assertTrue(classification.retryable());
    }

    @Test
    void providerFailureReasonIsReadableAndBounded() {
        ProviderFailureClassifier.Classification classification =
            ProviderFailureClassifier.classify("failed", "thread not found: 29180\n" + "x".repeat(500));

        assertEquals("provider_runtime_transient", classification.failureClass());
        assertTrue(classification.reason().startsWith("thread not found: 29180"));
        assertFalse(classification.reason().contains("\n"));
        assertTrue(classification.reason().length() <= 243);
    }

    @Test
    void classifiesMissingBinaryBeforeGenericFailedToStart() {
        ProviderFailureClassifier.Classification classification =
            ProviderFailureClassifier.classify("failed", "failed to start provider-native cli: Cannot run program \"missing-cli\"");

        assertEquals("provider_not_installed", classification.failureClass());
        assertFalse(classification.retryable());
    }

    @Test
    void classifiesAuthFailuresAsNonRetryableAuthRequired() {
        ProviderFailureClassifier.Classification classification =
            ProviderFailureClassifier.classify("failed", "login required before using provider");

        assertEquals("provider_auth_required", classification.failureClass());
        assertFalse(classification.retryable());
    }

    @Test
    void classifiesBadCliArgumentsAsProtocolError() {
        ProviderFailureClassifier.Classification classification =
            ProviderFailureClassifier.classify("failed", "error: unknown option '--output-format'");

        assertEquals("provider_protocol_error", classification.failureClass());
        assertTrue(classification.retryable());
    }
}
