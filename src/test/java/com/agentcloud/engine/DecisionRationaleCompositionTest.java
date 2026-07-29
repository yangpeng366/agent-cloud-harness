package com.agentcloud.engine;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1 长任务收口合同：验证 decide 输出的 decision_rationale 显式引用 goal progress + execution 信号。
 * 通过反射调用 ControlNodeGraph.buildDecisionRationale，覆盖 all-done / blocked / open / no-subgoals / mixed。
 */
class DecisionRationaleCompositionTest {

    @Test
    void allSubgoalsDoneRationaleReferencesGoalCompletion() throws Exception {
        assertEquals("goal: 2/2 done; execution done (done, high alignment) -> done",
            buildDecisionRationale("done", List.of("done", "completed"), "done", "done", "high"));
    }

    @Test
    void blockedSubgoalRationaleReferencesBlockerAndHumanGate() throws Exception {
        String rationale = buildDecisionRationale("human_gate", List.of("done", "blocked"), "done", "done", "high");
        assertTrue(rationale.contains("1 blocked"), rationale);
        assertTrue(rationale.endsWith("-> human_gate"), rationale);
    }

    @Test
    void openSubgoalRationaleReferencesOpenCount() throws Exception {
        String rationale = buildDecisionRationale("checkpoint", List.of("done", "in_progress"), "done", "done", "high");
        assertTrue(rationale.contains("goal: 1/2 done"), rationale);
        assertTrue(rationale.contains("1 open"), rationale);
        assertTrue(rationale.endsWith("-> checkpoint"), rationale);
    }

    @Test
    void noSubgoalsRationaleFallsBackToExecutionSignal() throws Exception {
        assertEquals("no subgoals; execution done (done, high alignment) -> done",
            buildDecisionRationale("done", null, "done", "done", "high"));
    }

    @Test
    void mixedSubgoalsRationaleBreaksDownDoneBlockedOpen() throws Exception {
        assertEquals("goal: 1/3 done, 1 blocked, 1 open; execution continue (partially_done, medium alignment) -> continue",
            buildDecisionRationale("continue", List.of("done", "blocked", "pending"),
                "continue", "partially_done", "medium"));
    }

    private String buildDecisionRationale(String resolvedAction, Object subgoalStatus,
                                          String executionAction, String completionStatus,
                                          String alignmentLevel) throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "buildDecisionRationale",
            String.class, Object.class, String.class, String.class, String.class
        );
        method.setAccessible(true);
        return (String) method.invoke(graph, resolvedAction, subgoalStatus,
            executionAction, completionStatus, alignmentLevel);
    }
}
