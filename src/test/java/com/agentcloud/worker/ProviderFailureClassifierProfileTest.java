package com.agentcloud.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderFailureClassifierProfileTest {

    @Test
    void classifiesQuotaExhausted() {
        ProviderFailureClassifier.ProfileFailureClassification result =
            ProviderFailureClassifier.classifyProfileFailure("failed", "quota exceeded for this billing period");
        assertNotNull(result);
        assertEquals("quota_exhausted", result.failureClass());
        assertTrue(result.retryable());
    }

    @Test
    void classifiesRateLimit() {
        ProviderFailureClassifier.ProfileFailureClassification result =
            ProviderFailureClassifier.classifyProfileFailure("failed", "rate limit exceeded");
        assertNotNull(result);
        assertEquals("quota_exhausted", result.failureClass());
    }

    @Test
    void classifiesAuthBlocked() {
        ProviderFailureClassifier.ProfileFailureClassification result =
            ProviderFailureClassifier.classifyProfileFailure("failed", "api key invalid or revoked");
        assertNotNull(result);
        assertEquals("auth_blocked", result.failureClass());
        assertFalse(result.retryable());
    }

    @Test
    void classifiesForbidden() {
        ProviderFailureClassifier.ProfileFailureClassification result =
            ProviderFailureClassifier.classifyProfileFailure("failed", "403 Forbidden");
        assertNotNull(result);
        assertEquals("auth_blocked", result.failureClass());
    }

    @Test
    void classifiesBackendUnavailable() {
        ProviderFailureClassifier.ProfileFailureClassification result =
            ProviderFailureClassifier.classifyProfileFailure("failed", "503 Service Unavailable");
        assertNotNull(result);
        assertEquals("backend_unavailable", result.failureClass());
        assertTrue(result.retryable());
    }

    @Test
    void classifiesBadGateway() {
        ProviderFailureClassifier.ProfileFailureClassification result =
            ProviderFailureClassifier.classifyProfileFailure("failed", "502 Bad Gateway");
        assertNotNull(result);
        assertEquals("backend_unavailable", result.failureClass());
    }

    @Test
    void classifiesProfileMisconfigured() {
        ProviderFailureClassifier.ProfileFailureClassification result =
            ProviderFailureClassifier.classifyProfileFailure("failed", "unknown model_provider: foo");
        assertNotNull(result);
        assertEquals("profile_misconfigured", result.failureClass());
        assertFalse(result.retryable());
    }

    @Test
    void classifiesDispatchPreflightFailed() {
        ProviderFailureClassifier.ProfileFailureClassification result =
            ProviderFailureClassifier.classifyProfileFailure("failed", "dispatch preflight failed");
        assertNotNull(result);
        assertEquals("dispatch_preflight_failed", result.failureClass());
        assertTrue(result.retryable());
    }

    @Test
    void returnsNullForUnknownFailure() {
        ProviderFailureClassifier.ProfileFailureClassification result =
            ProviderFailureClassifier.classifyProfileFailure("failed", "some generic error");
        assertNull(result);
    }

    @Test
    void returnsNullForEmptyMessage() {
        ProviderFailureClassifier.ProfileFailureClassification result =
            ProviderFailureClassifier.classifyProfileFailure("failed", "");
        assertNull(result);
    }
}
