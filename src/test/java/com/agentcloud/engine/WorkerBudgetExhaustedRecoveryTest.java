package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Task;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单轮预算超时（executeOneRoundWithTimeout 抛出的 "Worker execution timed out after Ns"）不应被
 * 误分类为 worker_runtime_transient 进而触发 sibling-lane auto_handoff。验证：
 *  - classifyFailureClass 归类为 worker_budget_exhausted；
 *  - 首次恢复给一次 same_worker_retry（兜底一次性 CCX 停顿）；
 *  - 二次不再 auto_handoff，直接 human_gate 且 reason 含可操作提示。
 */
class WorkerBudgetExhaustedRecoveryTest {

    private static final String TIMEOUT_SUMMARY =
        "worker codex failed: Worker execution timed out after 600s: worker=codex";

    private ControlNodeGraph newGraph() {
        return new ControlNodeGraph(
            null, null, null, null, new WorkerRouter(new WorkerRegistry()), null, null,
            null, null, null, null, null, null
        );
    }

    private Task taskWithRetryCount(int sameWorkerRetryCount) {
        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
        meta.put("auto_same_worker_retry_count", sameWorkerRetryCount);
        return new Task(
            "task_budget", "session_budget", null, "budget timeout recovery", "active", "high",
            Instant.now(), Instant.now(), Instant.now(), null, null, null,
            "edit Editor page param completion", null, "codex", "scheduler", null, meta
        );
    }

    private Map<String, Object> failedWorkerMetadata() {
        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
        // 与 synthesizeFailedExecutionResult 一致：output_text 设为可读失败摘要，避免被
        // looksLikeEmptyOutputFailure 提前吞掉；failure_summary_readable 供 resolveRecoveryFailureText 读取。
        meta.put("failure_summary_readable", TIMEOUT_SUMMARY);
        meta.put("output_text", TIMEOUT_SUMMARY);
        meta.put("artifact_content", TIMEOUT_SUMMARY);
        meta.put("execution_status", "failed");
        meta.put("selected_worker", "codex");
        return meta;
    }

    @Test
    void classifyRoundBudgetTimeoutAsBudgetExhaustedNotTransient() throws Exception {
        ControlNodeGraph graph = newGraph();
        Method m = ControlNodeGraph.class.getDeclaredMethod(
            "classifyFailureClass", Task.class, Map.class, String.class);
        m.setAccessible(true);
        String failureClass = (String) m.invoke(graph, taskWithRetryCount(0), failedWorkerMetadata(), TIMEOUT_SUMMARY);
        assertEquals("worker_budget_exhausted", failureClass);
    }

    @Test
    void firstBudgetTimeoutGrantsOneSameWorkerRetry() throws Exception {
        ControlNodeGraph graph = newGraph();
        Object directive = invokeRecovery(graph, taskWithRetryCount(0));
        assertNotNull(directive);
        assertEquals("worker_budget_exhausted", field(directive, "failureClass"));
        assertTrue((boolean) field(directive, "sameWorkerRetry"));
        assertFalse((boolean) field(directive, "autoHandoff"));
    }

    @Test
    void secondBudgetTimeoutGoesToHumanGateWithoutSiblingHandoff() throws Exception {
        ControlNodeGraph graph = newGraph();
        Object directive = invokeRecovery(graph, taskWithRetryCount(1));
        assertNotNull(directive);
        assertEquals("worker_budget_exhausted", field(directive, "failureClass"));
        assertFalse((boolean) field(directive, "sameWorkerRetry"));
        assertFalse((boolean) field(directive, "autoHandoff"));
        String reason = (String) field(directive, "failureSummaryReadable");
        assertTrue(reason.contains("HARNESS_WORKER_TIMEOUT_SECONDS"),
            "human_gate reason should hint at raising timeout, got: " + reason);
        assertTrue(reason.contains("budget exhausted"),
            "human_gate reason should mention budget exhaustion, got: " + reason);
    }

    private Object invokeRecovery(ControlNodeGraph graph, Task task) throws Exception {
        Method m = ControlNodeGraph.class.getDeclaredMethod(
            "maybePlanFailureRecovery", Task.class, Map.class, String.class);
        m.setAccessible(true);
        return m.invoke(graph, task, failedWorkerMetadata(), null);
    }

    private Object field(Object directive, String accessor) throws Exception {
        Method m = directive.getClass().getDeclaredMethod(accessor);
        m.setAccessible(true);
        return m.invoke(directive);
    }
}
