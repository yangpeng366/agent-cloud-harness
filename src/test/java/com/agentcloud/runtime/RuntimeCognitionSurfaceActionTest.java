package com.agentcloud.runtime;

import com.agentcloud.model.RuntimeCognitionSurfaceView;
import com.agentcloud.runtime.model.RuntimeFactSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RuntimeCognitionSurfaceActionTest {

    @Test
    void executionSurfaceProjectsAgentActionsFromRuntimeFacts() {
        RuntimeFactSet.ExecutionBoundary boundary = new RuntimeFactSet.ExecutionBoundary(
            "exec_action",
            "completed",
            null,
            null,
            12L,
            "codex",
            List.of(),
            0,
            "action trace",
            Map.ofEntries(
                Map.entry("provider_turn_status", "cancelled"),
                Map.entry("provider_abort_reason", "user_interrupted"),
                Map.entry("provider_timeout_kind", "max_duration"),
                Map.entry("provider_activity_timeout_ms", 180_000L),
                Map.entry("provider_turn_max_duration_ms", 900_000L),
                Map.entry("partial_output_chars", 640),
                Map.entry("partial_timeout_min_output_chars", 200),
                Map.entry("proposed_actions", List.of(Map.of("action_type", "CHECKPOINT", "summary", "checkpoint now"))),
                Map.entry("accepted_actions", List.of(Map.of("action_type", "CHECKPOINT", "status", "accepted"))),
                Map.entry("rejected_actions", List.of(Map.of("action_type", "HANDOFF", "status", "rejected"))),
                Map.entry("approval_needed_actions", List.of(Map.of("action_type", "SPAWN_SUBTASK", "status", "needs_approval"))),
                Map.entry("context_requests", List.of("need architecture note")),
                Map.entry("completion_claim", "implementation complete"),
                Map.entry("handoff_target", "kimi"),
                Map.entry("risk_flags", List.of("fan_out_risk"))
            )
        );
        RuntimeFactSet facts = new RuntimeFactSet(
            "task_action",
            "session_action",
            "active",
            "continue",
            "codex",
            "latest",
            "continue",
            "next",
            null,
            null,
            null,
            null,
            null,
            List.of(),
            boundary,
            null,
            Map.of()
        );

        RuntimeCognitionSurfaceView surface = new RuntimeCognitionSurfaceAssembler().assemble(facts);

        assertNotNull(surface.execution());
        assertEquals("CHECKPOINT", surface.execution().proposedActions().get(0).get("action_type"));
        assertEquals("CHECKPOINT", surface.execution().acceptedActions().get(0).get("action_type"));
        assertEquals("HANDOFF", surface.execution().rejectedActions().get(0).get("action_type"));
        assertEquals("SPAWN_SUBTASK", surface.execution().approvalNeededActions().get(0).get("action_type"));
        assertEquals(List.of("need architecture note"), surface.execution().contextRequests());
        assertEquals("implementation complete", surface.execution().completionClaim());
        assertEquals("kimi", surface.execution().handoffTarget());
        assertEquals(List.of("fan_out_risk"), surface.execution().riskFlags());
        assertEquals("cancelled", surface.execution().providerTurnStatus());
        assertEquals("user_interrupted", surface.execution().providerAbortReason());
        assertEquals("max_duration", surface.execution().providerTimeoutKind());
        assertEquals(180_000L, surface.execution().providerActivityTimeoutMs());
        assertEquals(900_000L, surface.execution().providerTurnMaxDurationMs());
        assertEquals(640, surface.execution().partialOutputChars());
        assertEquals(200, surface.execution().partialTimeoutMinOutputChars());
    }
}
