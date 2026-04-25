package com.agentcloud.engine;

import com.agentcloud.model.Task;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ControlNodeGraphActionResolutionTest {

    @Test
    void checkpointPlusDoneResolvesToCheckpointThenDone() throws Exception {
        assertEquals("checkpoint_then_done", invokeResolveAction("checkpoint", "done", "high"));
    }

    @Test
    void plainCheckpointStillResolvesToCheckpoint() throws Exception {
        assertEquals("checkpoint", invokeResolveAction("checkpoint", "partially_done", "high"));
    }

    @Test
    void continuePlusDoneResolvesToDone() throws Exception {
        assertEquals("done", invokeResolveAction("continue", "done", "high"));
    }

    @Test
    void finalizeCompletedTaskClearsNextStepAndSetsCompletedAt() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod("finalizeCompletedTask", Task.class);
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withControlNode("scheduler")
            .withNextStep("stale-next-step");

        Task finalized = (Task) method.invoke(graph, task);

        assertEquals("done", finalized.status());
        assertEquals("end", finalized.controlNode());
        assertNull(finalized.nextStep());
        assertNotNull(finalized.completedAt());
    }

    private String invokeResolveAction(String executionAction, String completionStatus, String alignmentLevel) throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "resolveAction", String.class, String.class, String.class
        );
        method.setAccessible(true);
        return (String) method.invoke(graph, executionAction, completionStatus, alignmentLevel);
    }
}
