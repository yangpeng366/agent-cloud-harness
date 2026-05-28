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
            Map.of(
                "proposed_actions", List.of(Map.of("action_type", "CHECKPOINT", "summary", "checkpoint now")),
                "accepted_actions", List.of(Map.of("action_type", "CHECKPOINT", "status", "accepted")),
                "rejected_actions", List.of(Map.of("action_type", "HANDOFF", "status", "rejected")),
                "approval_needed_actions", List.of(Map.of("action_type", "SPAWN_SUBTASK", "status", "needs_approval")),
                "context_requests", List.of("need architecture note"),
                "completion_claim", "implementation complete",
                "handoff_target", "kimi",
                "risk_flags", List.of("fan_out_risk")
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
    }
}
