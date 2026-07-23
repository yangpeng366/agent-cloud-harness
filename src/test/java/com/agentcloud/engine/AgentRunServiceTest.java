package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.AgentRunRecord;
import com.agentcloud.model.Task;
import com.agentcloud.model.Worker;
import com.agentcloud.store.AgentRunDao;
import com.agentcloud.worker.WorkerExecutionResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentRunServiceTest {

    @Test
    void recordWorkerRunPersistsManualWindowRoutingMetadata() {
        List<AgentRunRecord> inserted = new ArrayList<>();
        AgentRunService service = new AgentRunService(agentRunDao(inserted), null);
        Task task = new Task(
            "task_manual_window",
            "session_manual_window",
            null,
            "manual window routing",
            "waiting_human",
            "high",
            Instant.parse("2026-06-30T10:00:00Z"),
            Instant.parse("2026-06-30T10:00:00Z"),
            null,
            null,
            "user",
            null,
            "persist manual window routing metadata",
            null,
            null,
            "human_gate",
            null,
            Map.of("task_type", "coding", "provider_routing_policy", "free_first")
        );
        Worker worker = new Worker(
            "deveco",
            "deveco",
            List.of("coding"),
            List.of(),
            List.of(),
            Map.of(),
            Map.of("model_tier", "small", "primary_role", "executor"),
            false,
            true
        );
        WorkerRouter.RouteResult route = new WorkerRouter.RouteResult(
            task.id(),
            "deveco",
            List.of(),
            "manual window provider required",
            "manual_window_required",
            "coding",
            null,
            false,
            List.of("deveco", "codebuddy"),
            "deveco",
            "small",
            "executor",
            "candidate",
            "manual window provider required",
            null,
            null,
            null,
            null,
            null,
            true,
            List.of("deveco", "codebuddy"),
            List.of(),
            "manual_window_recommendation",
            true,
            "trae",
            List.of("trae", "zcode"),
            null,
            null,
            List.of()
        );
        WorkerExecutionResult result = new WorkerExecutionResult(
            "waiting for manual window",
            "waiting for manual window",
            false,
            null,
            null,
            "switch to trae window",
            "medium",
            "blocked",
            List.of(),
            List.of("manual step pending"),
            0,
            5_000L,
            Map.of()
        );

        AgentRunRecord record = service.recordCompletedWorkerRun(
            task,
            route,
            worker,
            result,
            Instant.parse("2026-06-30T10:00:00Z"),
            Instant.parse("2026-06-30T10:00:05Z")
        );

        assertEquals(record, inserted.getFirst());
        assertEquals(Boolean.TRUE, record.metadata().get("free_first_routing"));
        assertEquals("manual_window_recommendation", record.metadata().get("cost_route_stage"));
        assertEquals(Boolean.TRUE, record.metadata().get("manual_window_required"));
        assertEquals("trae", record.metadata().get("recommended_manual_provider"));
        assertEquals(
            "请切到 trae 窗口手动输入当前任务，完成后将结果回填到当前 task 再继续。",
            record.metadata().get("manual_followup_instruction")
        );
        assertEquals(List.of("trae", "zcode"), record.metadata().get("manual_window_candidates"));
    }

    @Test
    void recordWorkerRunPrefersProviderErrorForFailedDiagnostics() {
        List<AgentRunRecord> inserted = new ArrayList<>();
        AgentRunService service = new AgentRunService(agentRunDao(inserted), null);
        Task task = new Task(
            "task_codex_timeout",
            "session_codex_timeout",
            null,
            "codex timeout diagnosis",
            "active",
            "high",
            Instant.parse("2026-05-18T10:00:00Z"),
            Instant.parse("2026-05-18T10:00:00Z"),
            null,
            null,
            "user",
            null,
            "debug codex timeout",
            null,
            "codex",
            "scheduler",
            null,
            Map.of("task_type", "coding")
        );
        Worker worker = new Worker(
            "codex",
            "codex",
            List.of("coding"),
            List.of(),
            List.of(),
            Map.of(),
            Map.of("model_tier", "strong", "primary_role", "executor"),
            false,
            true
        );
        WorkerRouter.RouteResult route = new WorkerRouter.RouteResult(
            task.id(),
            "codex",
            List.of(),
            "selected codex",
            "default",
            "coding",
            null,
            false,
            List.of("codex"),
            "codex",
            "strong",
            "executor",
            "default_scope",
            "selected codex",
            null,
            null,
            null,
            null,
            null,
            false,
            List.of(),
            List.of(),
            null,
            false,
            null,
            List.of(),
            null,
            null,
            List.of(new WorkerRouter.RouteSkippedWorker(
                "codex",
                "thread not found during dispatch preflight",
                "provider_runtime_transient",
                "thread not found during dispatch preflight",
                true
            ))
        );
        WorkerExecutionResult result = new WorkerExecutionResult(
            "thread not found: 27316",
            "thread not found: 27316\nlarge command output...",
            false,
            "",
            "",
            "recover with fresh session",
            "low",
            "timeout",
            List.of(),
            List.of("codex output requires inspection"),
            0,
            150_000L,
            Map.of(
                "provider_error", "codex turn completion timed out",
                "provider_turn_status", "timeout",
                "provider_failure_class", "provider_runtime_transient",
                "provider_failure_reason", "codex turn completion timed out",
                "provider_retryable", true
            )
        );

        AgentRunRecord record = service.recordCompletedWorkerRun(
            task,
            route,
            worker,
            result,
            Instant.parse("2026-05-18T10:00:00Z"),
            Instant.parse("2026-05-18T10:02:30Z")
        );

        assertEquals(record, inserted.getFirst());
        assertEquals("timeout", record.status());
        assertEquals("codex turn completion timed out", record.summary());
        assertEquals("codex turn completion timed out", record.metadata().get("provider_error"));
        assertEquals("timeout", record.metadata().get("provider_turn_status"));
        assertEquals("provider_runtime_transient", record.metadata().get("provider_failure_class"));
        assertEquals("codex turn completion timed out", record.metadata().get("provider_failure_reason"));
        List<?> skippedWorkers = (List<?>) record.metadata().get("dispatch_skipped_workers");
        assertEquals(1, skippedWorkers.size());
        Map<?, ?> skipped = (Map<?, ?>) skippedWorkers.getFirst();
        assertEquals("codex", skipped.get("worker_id"));
        assertEquals("provider_runtime_transient", skipped.get("provider_failure_class"));
        assertEquals(Boolean.TRUE, skipped.get("provider_retryable"));
    }

    @SuppressWarnings("unchecked")
    private AgentRunDao agentRunDao(List<AgentRunRecord> inserted) {
        return (AgentRunDao) Proxy.newProxyInstance(
            AgentRunDao.class.getClassLoader(),
            new Class<?>[]{AgentRunDao.class},
            (proxy, method, args) -> {
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(method.getName())) {
                    return "testAgentRunDao";
                }
                return switch (method.getName()) {
                    case "insert" -> {
                        inserted.add((AgentRunRecord) args[0]);
                        yield null;
                    }
                    case "findById", "latestByTask" -> Optional.empty();
                    case "listByProvider", "listByProviderAndStatus", "search", "listRecent", "listActive" -> List.of();
                    case "insertRaw" -> null;
                    default -> {
                        Class<?> returnType = method.getReturnType();
                        if (returnType == List.class) {
                            yield List.of();
                        }
                        if (returnType == Optional.class) {
                            yield Optional.empty();
                        }
                        yield null;
                    }
                };
            }
        );
    }
}
