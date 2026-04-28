package com.agentcloud.engine;

import com.agentcloud.model.Task;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    @SuppressWarnings("unchecked")
    void selectLatestWorkerMetadataKeepsRouteAndFallbackFields() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod("selectLatestWorkerMetadata", Map.class);
        method.setAccessible(true);

        Map<String, Object> selected = (Map<String, Object>) method.invoke(graph, Map.of(
            "selected_worker", "kimi-local-doc",
            "selected_model_tier", "small",
            "execution_role", "executor",
            "why_selected", "selected by capability match",
            "preferred_worker_hint", "kimi-local-doc",
            "learning_hint_applied", true,
            "fallback_reason", "fallback to any ready worker",
            "route_source", "learning_memory",
            "ignored", "should not leak"
        ));

        assertEquals("kimi-local-doc", selected.get("selected_worker"));
        assertEquals("small", selected.get("selected_model_tier"));
        assertEquals("executor", selected.get("execution_role"));
        assertEquals("selected by capability match", selected.get("why_selected"));
        assertEquals("kimi-local-doc", selected.get("preferred_worker_hint"));
        assertEquals(Boolean.TRUE, selected.get("learning_hint_applied"));
        assertEquals("fallback to any ready worker", selected.get("fallback_reason"));
        assertEquals("learning_memory", selected.get("route_source"));
        assertTrue(!selected.containsKey("ignored"));
    }

    @Test
    void sameStateTreatsMetadataMutationAsStateChange() throws Exception {
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod("sameState", Task.class, Task.class);
        method.setAccessible(true);

        Task base = Task.create("task_1", "session_1", "demo", "active", "high")
            .withControlNode("scheduler")
            .withMetadata(Map.of("model_mode", "orchestrated", "orchestration_stage", "plan_pending"));
        Task changed = base.withMetadata(Map.of("model_mode", "orchestrated", "orchestration_stage", "execution_pending"));

        assertEquals(Boolean.FALSE, method.invoke(graph, base, changed));
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
