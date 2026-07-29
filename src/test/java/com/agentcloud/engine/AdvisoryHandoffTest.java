package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Task;
import com.agentcloud.model.Worker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P2 Advisory Handoff: small-tier worker ESCALATE 时优先 handoff 给 strong-tier advisory worker。
 */
class AdvisoryHandoffTest {

    @Test
    void resolveAdvisoryHandoffReturnsStrongWorkerWhenSmallTierEscalates() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );

        Method method = ControlNodeGraph.class.getDeclaredMethod("resolveAdvisoryHandoff", Task.class, String.class);
        method.setAccessible(true);
        String advisoryWorker = (String) method.invoke(graph, null, "small");

        assertNotNull(advisoryWorker);
        Worker worker = router.getWorker(advisoryWorker);
        assertNotNull(worker);
        assertEquals("strong", worker.metadata().get("model_tier"));
    }

    @Test
    void resolveAdvisoryHandoffReturnsNullWhenCurrentTierIsStrong() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );

        Method method = ControlNodeGraph.class.getDeclaredMethod("resolveAdvisoryHandoff", Task.class, String.class);
        method.setAccessible(true);
        String advisoryWorker = (String) method.invoke(graph, null, "strong");

        assertNull(advisoryWorker);
    }

    @Test
    void resolveAdvisoryHandoffReturnsNullWhenTierIsNull() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );

        Method method = ControlNodeGraph.class.getDeclaredMethod("resolveAdvisoryHandoff", Task.class, String.class);
        method.setAccessible(true);
        String advisoryWorker = (String) method.invoke(graph, null, (String) null);

        assertNull(advisoryWorker);
    }

    @Test
    void resolveAdvisoryHandoffSkipsSuggestOnlyWorkers() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(new Worker("suggest-only-advisor", "codex",
            List.of("coding", "general"), List.of(), List.of(), Map.of(),
            Map.of("model_tier", "strong"), true, true));
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );

        Method method = ControlNodeGraph.class.getDeclaredMethod("resolveAdvisoryHandoff", Task.class, String.class);
        method.setAccessible(true);
        String advisoryWorker = (String) method.invoke(graph, null, "small");

        assertNotNull(advisoryWorker);
        assertNotEquals("suggest-only-advisor", advisoryWorker);
        Worker worker = router.getWorker(advisoryWorker);
        assertNotNull(worker);
        assertEquals("strong", worker.metadata().get("model_tier"));
        assertFalse(worker.suggestOnly());
    }

    @Test
    void resolveAdvisoryHandoffReturnsNullWhenTaskIsSmallOnly() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );
        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of("model_mode", "small_only"));

        Method method = ControlNodeGraph.class.getDeclaredMethod("resolveAdvisoryHandoff", Task.class, String.class);
        method.setAccessible(true);
        String advisoryWorker = (String) method.invoke(graph, task, "small");

        assertNull(advisoryWorker);
    }
    @Test
    void deriveWhyHandoffReturnsAdvisoryConsultWhenReasonSet() throws Exception {
        com.agentcloud.engine.memory.PacketBuilder builder = new com.agentcloud.engine.memory.PacketBuilder(
            null, null, null
        );

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withMetadata(Map.of("handoff_reason", "advisory_consult"));

        Method method = com.agentcloud.engine.memory.PacketBuilder.class.getDeclaredMethod(
            "deriveWhyHandoff", Task.class, String.class, String.class
        );
        method.setAccessible(true);
        String why = (String) method.invoke(builder, task, "pi", "codex");

        assertEquals("advisory_consult", why);
    }
    @Test
    void maybeEscalateSmallTierPartiallyDoneEscalatesToStrongTier() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybeEscalateSmallTierPartiallyDone", String.class, String.class, Map.class, Task.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex-free");
        Map<String, Object> latestWorkerMetadata = Map.of("selected_model_tier", "small");

        String result = (String) method.invoke(graph, "continue", "partially_done", latestWorkerMetadata, task);
        assertEquals("escalate", result);
    }

    @Test
    void maybeEscalateDoesNotEscalateWhenActionIsDone() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybeEscalateSmallTierPartiallyDone", String.class, String.class, Map.class, Task.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex-free");
        Map<String, Object> latestWorkerMetadata = Map.of("selected_model_tier", "small");

        String result = (String) method.invoke(graph, "done", "partially_done", latestWorkerMetadata, task);
        assertEquals("done", result);
    }

    @Test
    void maybeEscalateDoesNotEscalateWhenWorkerIsStrongTier() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybeEscalateSmallTierPartiallyDone", String.class, String.class, Map.class, Task.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex");
        Map<String, Object> latestWorkerMetadata = Map.of("selected_model_tier", "strong");

        String result = (String) method.invoke(graph, "continue", "partially_done", latestWorkerMetadata, task);
        assertEquals("continue", result);
    }

    @Test
    void maybeEscalateDoesNotEscalateWhenCompletionIsDone() throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);
        ControlNodeGraph graph = new ControlNodeGraph(
            null, null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );
        Method method = ControlNodeGraph.class.getDeclaredMethod(
            "maybeEscalateSmallTierPartiallyDone", String.class, String.class, Map.class, Task.class
        );
        method.setAccessible(true);

        Task task = Task.create("task_1", "session_1", "demo", "active", "high")
            .withAssignedWorker("codex-free");
        Map<String, Object> latestWorkerMetadata = Map.of("selected_model_tier", "small");

        String result = (String) method.invoke(graph, "continue", "done", latestWorkerMetadata, task);
        assertEquals("continue", result);
    }

}