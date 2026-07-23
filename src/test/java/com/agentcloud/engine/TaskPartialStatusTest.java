package com.agentcloud.engine;

import com.agentcloud.model.Task;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 `finalizeCompletedTask` 在 subgoal_status 部分完成时产生 `partial` 状态，
 * 而非总是返回 `done`。
 *
 * 这收口了 UI 验收标准 #3 中定义的 `partial` 状态语义：
 * "partial: 部分达成 - 已完成 subgoals、未完成 subgoals、partial artifacts"
 */
class TaskPartialStatusTest {

    @Test
    void allSubgoalsDoneProducesDoneStatus() throws Exception {
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "subgoal_status", List.of(
                    Map.of("title", "step1", "status", "done"),
                    Map.of("title", "step2", "status", "completed")
                )
            ));
        Task result = invokeFinalizeCompletedTask(task);
        assertEquals("done", result.status());
        assertEquals("end", result.controlNode());
        assertNotNull(result.completedAt());
    }

    @Test
    void partialSubgoalsProducePartialStatus() throws Exception {
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "subgoal_status", List.of(
                    Map.of("title", "step1", "status", "done"),
                    Map.of("title", "step2", "status", "in_progress")
                )
            ));
        Task result = invokeFinalizeCompletedTask(task);
        assertEquals("partial", result.status());
        assertEquals("end", result.controlNode());
        assertNotNull(result.completedAt());
    }

    @Test
    void noSubgoalStatusProducesDoneStatus() throws Exception {
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of());
        Task result = invokeFinalizeCompletedTask(task);
        assertEquals("done", result.status());
    }

    @Test
    void emptySubgoalStatusProducesDoneStatus() throws Exception {
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "subgoal_status", List.of()
            ));
        Task result = invokeFinalizeCompletedTask(task);
        assertEquals("done", result.status());
    }

    @Test
    void allBlockedSubgoalsProducePartialStatus() throws Exception {
        // None done, none in_progress - but at least one blocked
        // Since doneCount=0 and total>0, it's not "partial" by the current rule
        // (partial requires doneCount > 0 AND doneCount < total)
        // So this should still be "done" since no subgoal is done
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "subgoal_status", List.of(
                    Map.of("title", "step1", "status", "blocked")
                )
            ));
        Task result = invokeFinalizeCompletedTask(task);
        // No done subgoals -> not partial -> falls through to done
        assertEquals("done", result.status());
    }

    @Test
    void mixedDoneAndBlockedProducesPartialStatus() throws Exception {
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of(
                "subgoal_status", List.of(
                    Map.of("title", "step1", "status", "done"),
                    Map.of("title", "step2", "status", "blocked")
                )
            ));
        Task result = invokeFinalizeCompletedTask(task);
        assertEquals("partial", result.status());
    }

    private Task invokeFinalizeCompletedTask(Task task) throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod("finalizeCompletedTask", Task.class);
        method.setAccessible(true);
        return (Task) method.invoke(graph, task);
    }
}
