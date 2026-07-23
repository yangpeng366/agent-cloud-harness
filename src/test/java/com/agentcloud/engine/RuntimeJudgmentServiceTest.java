package com.agentcloud.engine;

import com.agentcloud.model.Task;
import com.agentcloud.runtime.model.ContinuationAction;
import com.agentcloud.runtime.model.ContinuationDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeJudgmentServiceTest {

    private final RuntimeJudgmentService service = new RuntimeJudgmentService();

    private Task taskWithSubgoalStatus(List<String> statuses) {
        return new Task(
            "task_goal_1", "session_goal", null, "prove goal-driven continuation",
            "active", "high", Instant.now(), Instant.now(), Instant.now(),
            null, null, "ship a validated result", "next",
            "codex", "scheduler", null, null,
            Map.of("subgoal_status", statuses)
        );
    }

    @Test
    void allSubgoalsDoneHaltsBecauseGoalAchieved() {
        Task task = taskWithSubgoalStatus(List.of("done", "done"));
        ContinuationDecision decision = service.judge(task);
        assertEquals(ContinuationAction.HALT, decision.action());
        assertEquals("all subgoals done", decision.reason());
    }

    @Test
    void anySubgoalBlockedEscalatesToHumanGate() {
        Task task = taskWithSubgoalStatus(List.of("done", "blocked"));
        ContinuationDecision decision = service.judge(task);
        assertEquals(ContinuationAction.ESCALATE, decision.action());
        assertEquals("subgoal blocked requires human gate", decision.reason());
    }

    @Test
    void openSubgoalsContinue() {
        Task task = taskWithSubgoalStatus(List.of("done", "in_progress"));
        ContinuationDecision decision = service.judge(task);
        assertEquals(ContinuationAction.CONTINUE, decision.action());
    }

    @Test
    void noSubgoalStatusFallsBackToMetadataFlags() {
        Task task = new Task(
            "task_goal_2", "session_goal", null, "prove fallback",
            "active", "high", Instant.now(), Instant.now(), Instant.now(),
            null, null, "goal", "next", "codex", "scheduler", null, null,
            Map.of("auto_halt", true)
        );
        ContinuationDecision decision = service.judge(task);
        assertEquals(ContinuationAction.HALT, decision.action());
    }

    @Test
    void pausedTaskStillPausesRegardlessOfGoalProgress() {
        Task task = taskWithSubgoalStatus(List.of("done", "done"))
            .withStatus("paused");
        ContinuationDecision decision = service.judge(task);
        assertEquals(ContinuationAction.PAUSE, decision.action());
    }

    @Test
    void nullTaskHalts() {
        ContinuationDecision decision = service.judge(null);
        assertEquals(ContinuationAction.HALT, decision.action());
        assertNull(decision.targetWorker());
    }
}