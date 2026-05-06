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

        Map<String, Object> selected = (Map<String, Object>) method.invoke(graph, Map.ofEntries(
            Map.entry("image_input_count", 2),
            Map.entry("image_input_used", true),
            Map.entry("prompt_mode", "mounted_context_primary"),
            Map.entry("mounted_render_used", true),
            Map.entry("mounted_context_panel_count", 7),
            Map.entry("selected_worker", "kimi-local-doc"),
            Map.entry("selected_model_tier", "small"),
            Map.entry("execution_role", "executor"),
            Map.entry("why_selected", "selected by capability match"),
            Map.entry("preferred_worker_hint", "kimi-local-doc"),
            Map.entry("learning_hint_applied", true),
            Map.entry("fallback_reason", "fallback to any ready worker"),
            Map.entry("route_source", "learning_memory"),
            Map.entry("ignored", "should not leak")
        ));

        assertEquals("kimi-local-doc", selected.get("selected_worker"));
        assertEquals("small", selected.get("selected_model_tier"));
        assertEquals("executor", selected.get("execution_role"));
        assertEquals("selected by capability match", selected.get("why_selected"));
        assertEquals("kimi-local-doc", selected.get("preferred_worker_hint"));
        assertEquals(Boolean.TRUE, selected.get("learning_hint_applied"));
        assertEquals("fallback to any ready worker", selected.get("fallback_reason"));
        assertEquals("learning_memory", selected.get("route_source"));
        assertEquals(2, selected.get("image_input_count"));
        assertEquals(Boolean.TRUE, selected.get("image_input_used"));
        assertEquals("mounted_context_primary", selected.get("prompt_mode"));
        assertEquals(Boolean.TRUE, selected.get("mounted_render_used"));
        assertEquals(7, selected.get("mounted_context_panel_count"));
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
