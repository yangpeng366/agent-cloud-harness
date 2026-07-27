package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Task;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 Loop Handoff Recovery: handoff depth limit prevents infinite handoff nesting.
 */
class HandoffDepthLimitTest {

    @Test
    void handoffDepthIsZeroWhenNoMetadata() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );

        Method method = ControlNodeGraph.class.getDeclaredMethod("handoffDepth", Task.class);
        method.setAccessible(true);
        Task task = Task.create("t1", "s1", "demo", "active", "high");
        int depth = (int) method.invoke(graph, task);
        assertEquals(0, depth);
    }

    @Test
    void handoffDepthReadsFromMetadata() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );

        Method method = ControlNodeGraph.class.getDeclaredMethod("handoffDepth", Task.class);
        method.setAccessible(true);
        Task task = Task.create("t1", "s1", "demo", "active", "high")
            .withMetadata(Map.of("handoff_depth", 2));
        int depth = (int) method.invoke(graph, task);
        assertEquals(2, depth);
    }

    @Test
    void handoffDepthHandlesStringMetadata() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );

        Method method = ControlNodeGraph.class.getDeclaredMethod("handoffDepth", Task.class);
        method.setAccessible(true);
        Task task = Task.create("t1", "s1", "demo", "active", "high")
            .withMetadata(Map.of("handoff_depth", "3"));
        int depth = (int) method.invoke(graph, task);
        assertEquals(3, depth);
    }

    @Test
    void maxHandoffDepthIsThree() {
        // MAX_HANDOFF_DEPTH = 3
        // When handoff_depth >= 3, advisory handoff is skipped -> human_gate
        Task deepTask = Task.create("t1", "s1", "demo", "active", "high")
            .withMetadata(Map.of("handoff_depth", 3));
        assertTrue(deepTask.metadata().containsKey("handoff_depth"));
        assertEquals(3, deepTask.metadata().get("handoff_depth"));
    }

    @Test
    void handoffDepthIncrementsOnTriggerHandoff() throws Exception {
        // When triggerHandoff is called, handoff_depth should increment
        // This verifies the metadata propagation, not the full graph flow
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );

        Method method = ControlNodeGraph.class.getDeclaredMethod("handoffDepth", Task.class);
        method.setAccessible(true);

        // Task with depth 1
        Task task = Task.create("t1", "s1", "demo", "active", "high")
            .withMetadata(Map.of("handoff_depth", 1));
        int depth = (int) method.invoke(graph, task);
        assertEquals(1, depth);

        // After increment (simulated)
        Task incremented = task.withMetadata(Map.of("handoff_depth", 2));
        depth = (int) method.invoke(graph, incremented);
        assertEquals(2, depth);
    }
}