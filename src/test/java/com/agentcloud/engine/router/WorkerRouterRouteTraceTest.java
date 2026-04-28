package com.agentcloud.engine.router;

import com.agentcloud.engine.LearningMemoryService;
import com.agentcloud.model.LearningMemory;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.model.Worker;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.LearningMemoryDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerRouterRouteTraceTest {

    @TempDir
    Path tempDir;

    @Test
    void routeResultCarriesSelectedWorkerTierAndRole() {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(new Worker(
            "small-executor",
            "kimi",
            List.of("continuation"),
            List.of(),
            List.of(),
            Map.of("api_key", true),
            Map.of("model_tier", "small", "primary_role", "executor"),
            false,
            true
        ));
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("continuation"));

        assertEquals("small-executor", route.selectedWorker());
        assertEquals("kimi", route.selectedWorkerType());
        assertEquals("small", route.selectedModelTier());
        assertEquals("executor", route.selectedExecutionRole());
        assertEquals(route.routeReason(), route.whySelected());
        assertNull(route.fallbackReason());
    }

    @Test
    void routeResultExplainsCapabilityFallback() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("continuation"));

        assertNotNull(route.selectedWorker());
        assertEquals("ready_fallback", route.routeSource());
        assertEquals(route.routeReason(), route.whySelected());
        assertTrue(route.fallbackReason().contains("fallback to any ready worker"));
        assertTrue(route.candidateWorkers().size() >= 1);
    }

    @Test
    void orchestratedPlannerStagePrefersStrongTier() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "model_mode", "orchestrated",
            "orchestration_stage", "plan_pending"
        )));

        assertEquals("codex", route.selectedWorker());
        assertEquals("strong", route.selectedModelTier());
        assertTrue(route.routeReason().contains("model tier preference (strong)"));
    }

    @Test
    void orchestratedExecutionStagePrefersSmallTier() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "model_mode", "orchestrated",
            "orchestration_stage", "execution_pending"
        )));

        assertEquals("kimi", route.selectedWorker());
        assertEquals("small", route.selectedModelTier());
        assertTrue(route.routeReason().contains("model tier preference (small)"));
    }

    @Test
    void learningMemoryHintIsAppliedWhenPreferredWorkerIsReadyAndCapable() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("worker-router-learning-memory.db"))) {
            WorkerRouter router = new WorkerRouter(new WorkerRegistry(), learningMemoryService(db, "routing:coding:codex"));

            WorkerRouter.RouteResult route = router.selectWorker(task("coding"));

            assertEquals("codex", route.selectedWorker());
            assertEquals("learning_memory", route.routeSource());
            assertEquals("codex", route.preferredWorkerHint());
            assertTrue(route.learningHintApplied());
            assertEquals(route.routeReason(), route.whySelected());
            assertNull(route.fallbackReason());
        }
    }

    @Test
    void learningMemoryHintExplainsFallbackWhenModelTierNarrowsCandidateSet() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("worker-router-learning-memory-fallback.db"))) {
            WorkerRouter router = new WorkerRouter(new WorkerRegistry(), learningMemoryService(db, "routing:coding:codex"));

            WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
                "task_type", "coding",
                "model_mode", "small_only"
            )));

            assertEquals("kimi", route.selectedWorker());
            assertEquals("capability_match", route.routeSource());
            assertEquals("codex", route.preferredWorkerHint());
            assertFalse(route.learningHintApplied());
            assertTrue(route.routeReason().contains("model tier preference (small)"));
            assertTrue(route.fallbackReason().contains("not in current candidate set"));
        }
    }

    private Task task(String taskType) {
        return task(taskType, Map.of("task_type", taskType));
    }

    private Task task(String taskType, Map<String, Object> metadata) {
        return new Task(
            "task_1",
            "session_1",
            null,
            "trace test",
            "active",
            "high",
            Instant.now(),
            Instant.now(),
            Instant.now(),
            null,
            null,
            null,
            "verify route trace",
            null,
            null,
            "intake",
            null,
            metadata
        );
    }

    private LearningMemoryService learningMemoryService(DatabaseManager db, String hintKey) {
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        LearningMemoryDao learningMemoryDao = db.jdbi().onDemand(LearningMemoryDao.class);

        Session session = Session.create("session_route_memory", "worker router learning memory", "active");
        sessionDao.insert(session);
        taskDao.insert(new Task(
            "task_route_memory",
            session.id(),
            null,
            "routing preference seed",
            "active",
            "high",
            Instant.now(),
            Instant.now(),
            Instant.now(),
            null,
            null,
            null,
            "seed preferred worker hint",
            null,
            "codex",
            "continue",
            null,
            Map.of("task_type", "coding")
        ));
        learningMemoryDao.insert(new LearningMemory(
            "lm_route_memory",
            session.id(),
            "task_route_memory",
            null,
            "routing_preference",
            "stable_hint",
            hintKey,
            "Prefer codex for coding tasks.",
            0.9d,
            5,
            Map.of("source", "test"),
            Map.of("category", "routing_preference")
        ));
        return new LearningMemoryService(learningMemoryDao);
    }
}
