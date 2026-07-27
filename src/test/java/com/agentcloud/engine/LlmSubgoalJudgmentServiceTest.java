package com.agentcloud.engine;

import com.agentcloud.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmSubgoalJudgmentServiceTest {

    @Test
    void judgeReturnsDoneWhenLlmRespondsDone() {
        LlmClient stub = new StubLlmClient("done");
        LlmSubgoalJudgmentService svc = new LlmSubgoalJudgmentService(stub);
        String result = svc.judgeSubgoalStatus(
            "implement login page", "login page created", "in_progress", Map.of());
        assertEquals("done", result);
    }

    @Test
    void judgeReturnsBlockedWhenLlmRespondsBlocked() {
        LlmClient stub = new StubLlmClient("blocked");
        LlmSubgoalJudgmentService svc = new LlmSubgoalJudgmentService(stub);
        String result = svc.judgeSubgoalStatus(
            "fix database connection", "connection refused", "in_progress", Map.of());
        assertEquals("blocked", result);
    }

    @Test
    void judgeReturnsInProgressWhenLlmRespondsOngoing() {
        LlmClient stub = new StubLlmClient("in_progress");
        LlmSubgoalJudgmentService svc = new LlmSubgoalJudgmentService(stub);
        String result = svc.judgeSubgoalStatus(
            "write tests", "2 of 5 tests written", "in_progress", Map.of());
        assertEquals("in_progress", result);
    }

    @Test
    void judgeReturnsNullWhenLlmReturnsUnrecognizedResponse() {
        LlmClient stub = new StubLlmClient("maybe_something");
        LlmSubgoalJudgmentService svc = new LlmSubgoalJudgmentService(stub);
        String result = svc.judgeSubgoalStatus(
            "do something", "partial work", "in_progress", Map.of());
        assertNull(result);
    }

    @Test
    void judgeReturnsNullWhenDisabled() {
        LlmClient stub = new StubLlmClient("done");
        LlmSubgoalJudgmentService svc = new LlmSubgoalJudgmentService(stub, false);
        assertFalse(svc.isEnabled());
        String result = svc.judgeSubgoalStatus(
            "implement login page", "login page created", "in_progress", Map.of());
        assertNull(result);
    }

    @Test
    void judgeReturnsNullWhenClientIsNull() {
        LlmSubgoalJudgmentService svc = new LlmSubgoalJudgmentService(null);
        String result = svc.judgeSubgoalStatus(
            "implement login page", "login page created", "in_progress", Map.of());
        assertNull(result);
    }

    @Test
    void judgeReturnsNullWhenLlmThrowsException() {
        LlmClient stub = new StubLlmClient(null); // throws
        LlmSubgoalJudgmentService svc = new LlmSubgoalJudgmentService(stub);
        String result = svc.judgeSubgoalStatus(
            "do something", "work done", "in_progress", Map.of());
        assertNull(result);
    }

    @Test
    void isAmbiguousExecutionDetectsUnknownStatus() {
        assertTrue(LlmSubgoalJudgmentService.isAmbiguousExecution("unknown", "some output"));
        assertTrue(LlmSubgoalJudgmentService.isAmbiguousExecution("partial", "some output"));
        assertTrue(LlmSubgoalJudgmentService.isAmbiguousExecution("timeout", "some output"));
    }

    @Test
    void isAmbiguousExecutionReturnsFalseForClearStatus() {
        assertFalse(LlmSubgoalJudgmentService.isAmbiguousExecution("completed", "output"));
        assertFalse(LlmSubgoalJudgmentService.isAmbiguousExecution("failed", "output"));
        assertFalse(LlmSubgoalJudgmentService.isAmbiguousExecution("running", "output"));
    }

    @Test
    void isAmbiguousExecutionReturnsFalseWhenNoOutput() {
        assertFalse(LlmSubgoalJudgmentService.isAmbiguousExecution("unknown", ""));
        assertFalse(LlmSubgoalJudgmentService.isAmbiguousExecution("unknown", null));
    }

    @Test
    void judgeNormalizesVariants() {
        assertEquals("done", new LlmSubgoalJudgmentService(new StubLlmClient("Completed")).judgeSubgoalStatus("t", "o", "s", Map.of()));
        assertEquals("done", new LlmSubgoalJudgmentService(new StubLlmClient("FINISHED")).judgeSubgoalStatus("t", "o", "s", Map.of()));
        assertEquals("blocked", new LlmSubgoalJudgmentService(new StubLlmClient("Failed")).judgeSubgoalStatus("t", "o", "s", Map.of()));
        assertEquals("blocked", new LlmSubgoalJudgmentService(new StubLlmClient("stuck")).judgeSubgoalStatus("t", "o", "s", Map.of()));
        assertEquals("in_progress", new LlmSubgoalJudgmentService(new StubLlmClient("running")).judgeSubgoalStatus("t", "o", "s", Map.of()));
        assertEquals("in_progress", new LlmSubgoalJudgmentService(new StubLlmClient("partial")).judgeSubgoalStatus("t", "o", "s", Map.of()));
    }

    /** Stub LLM client for testing. */
    private static class StubLlmClient implements LlmClient {
        private final String response;
        StubLlmClient(String response) { this.response = response; }
        @Override
        public String chat(String systemPrompt, String userPrompt) {
            if (response == null) throw new RuntimeException("LLM error");
            return response;
        }
        @Override
        public String chat(String systemPrompt, String userPrompt, java.util.List<com.agentcloud.llm.LlmImageInput> imageInputs) {
            return chat(systemPrompt, userPrompt);
        }
        @Override
        public String review(String systemPrompt, String userPrompt) {
            return chat(systemPrompt, userPrompt);
        }
    }
}