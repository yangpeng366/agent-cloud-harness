package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worker round 超时数据校准：显式覆盖（-Dharness.worker.timeout.seconds /
 * HARNESS_WORKER_TIMEOUT_SECONDS）绝对优先；未覆盖时按 worker model_tier 取默认
 * （strong tier=600s，其余=300s）。默认值依据本地 agent_runs 历史（codex p95≈331s）。
 */
class WorkerExecutionTimeoutConfigTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("harness.worker.timeout.seconds");
    }

    @Test
    void defaultsTo300SecondsWhenUnset() {
        long resolved = ControlNodeGraph.resolveWorkerExecutionTimeoutSeconds();
        assertTrue(resolved >= 300, "default timeout should be 300s or higher, got " + resolved);
    }

    @Test
    void honorsSystemPropertyOverride() {
        System.setProperty("harness.worker.timeout.seconds", "600");
        assertEquals(600L, ControlNodeGraph.resolveWorkerExecutionTimeoutSeconds());
    }

    @Test
    void rejectsBelowMinimumAndFallsBackToDefault() {
        System.setProperty("harness.worker.timeout.seconds", "10");
        assertEquals(300L, ControlNodeGraph.resolveWorkerExecutionTimeoutSeconds());
    }

    @Test
    void rejectsInvalidValueAndFallsBackToDefault() {
        System.setProperty("harness.worker.timeout.seconds", "not-a-number");
        assertEquals(300L, ControlNodeGraph.resolveWorkerExecutionTimeoutSeconds());
    }

    private ControlNodeGraph graphWithDefaultRegistry() {
        WorkerRouter router = new WorkerRouter(new WorkerRegistry());
        return new ControlNodeGraph(
            null, null, null, null, router, null, null,
            null, null, null, null, null, null
        );
    }

    @Test
    void strongTierWorkerGetsHigherDataCalibratedTimeout() {
        ControlNodeGraph graph = graphWithDefaultRegistry();
        // codex 声明 model_tier=strong，历史 p95≈331s -> 600s
        assertEquals(600L, graph.effectiveWorkerTimeoutSeconds("codex"));
    }

    @Test
    void nonStrongTierWorkerGetsGeneralDefault() {
        ControlNodeGraph graph = graphWithDefaultRegistry();
        // openclaw-native 声明 model_tier=tool，历史 max≈105s -> 300s 足够
        assertEquals(300L, graph.effectiveWorkerTimeoutSeconds("openclaw-native"));
    }

    @Test
    void unknownWorkerFallsBackToGeneralDefault() {
        ControlNodeGraph graph = graphWithDefaultRegistry();
        assertEquals(300L, graph.effectiveWorkerTimeoutSeconds("definitely-missing-worker"));
    }

    @Test
    void explicitOverrideBeatsTierCalibration() {
        System.setProperty("harness.worker.timeout.seconds", "1200");
        ControlNodeGraph graph = graphWithDefaultRegistry();
        // 显式覆盖绝对优先，strong tier 也不再上浮到 600
        assertEquals(1200L, graph.effectiveWorkerTimeoutSeconds("codex"));
        assertEquals(1200L, graph.effectiveWorkerTimeoutSeconds("openclaw-native"));
    }

    @Test
    void stabilitySmoke1800sOverrideIsAcceptedAcrossTiers() {
        System.setProperty("harness.worker.timeout.seconds", "1800");
        ControlNodeGraph graph = graphWithDefaultRegistry();
        assertEquals(1800L, graph.effectiveWorkerTimeoutSeconds("codex"));
        assertEquals(1800L, graph.effectiveWorkerTimeoutSeconds("openclaw-native"));
        assertEquals(1800L, ControlNodeGraph.resolveWorkerExecutionTimeoutSeconds());
    }
}
