package com.agentcloud.worker;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.providers.BuiltinAgentProviders;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.ActiveContext;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerExecutorRouterProviderNativeTest {

    @Test
    void routesCursorToProviderNativeExecutor() {
        WorkerRegistry registry = new WorkerRegistry();
        RecordingExecutor defaultExecutor = new RecordingExecutor("default");
        RecordingExecutor toolAwareExecutor = new RecordingExecutor("tool-aware");
        ProviderCliWorkerExecutor providerCliExecutor = new StubProviderCliWorkerExecutor("provider-native");
        WorkerExecutorRouter router = new WorkerExecutorRouter(
            registry,
            defaultExecutor,
            toolAwareExecutor,
            providerCliExecutor
        );

        WorkerExecutionResult result = router.executeOneRound(runtimeContext("cursor"), "cursor");

        assertEquals("provider-native", result.summary());
        assertEquals(0, defaultExecutor.calls);
        assertEquals(0, toolAwareExecutor.calls);
    }

    @Test
    void keepsCodexOnToolAwareExecutor() {
        WorkerRegistry registry = new WorkerRegistry();
        RecordingExecutor defaultExecutor = new RecordingExecutor("default");
        RecordingExecutor toolAwareExecutor = new RecordingExecutor("tool-aware");
        ProviderCliWorkerExecutor providerCliExecutor = new StubProviderCliWorkerExecutor("provider-native");
        WorkerExecutorRouter router = new WorkerExecutorRouter(
            registry,
            defaultExecutor,
            toolAwareExecutor,
            providerCliExecutor
        );

        WorkerExecutionResult result = router.executeOneRound(runtimeContext("codex"), "codex");

        assertEquals("tool-aware", result.summary());
        assertEquals(0, defaultExecutor.calls);
        assertEquals(1, toolAwareExecutor.calls);
    }

    private TaskRuntimeContext runtimeContext(String workerId) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_type", "coding");
        Task task = new Task(
            "task_router_provider",
            "session_router_provider",
            null,
            "router provider native",
            "active",
            "high",
            Instant.parse("2026-05-07T02:00:00Z"),
            Instant.parse("2026-05-07T02:00:00Z"),
            null,
            null,
            null,
            "summary",
            "verify router split",
            "route worker",
            workerId,
            "continue",
            null,
            metadata
        );
        ActiveContext activeContext = new ActiveContext(
            "router context",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "",
            "Task Focus: router split",
            12
        );
        return new TaskRuntimeContext(task, null, null, List.of(), List.of(), List.of(), List.of(), activeContext, null);
    }

    private static final class RecordingExecutor implements WorkerExecutor {
        private final String summary;
        private int calls;

        private RecordingExecutor(String summary) {
            this.summary = summary;
        }

        @Override
        public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
            calls++;
            return new WorkerExecutionResult(
                summary,
                summary,
                false,
                "",
                "",
                "",
                "medium",
                "completed",
                List.of(),
                List.of(),
                0,
                1L,
                Map.of("executor", summary)
            );
        }
    }

    private static final class StubProviderCliWorkerExecutor extends ProviderCliWorkerExecutor {
        private final String summary;

        private StubProviderCliWorkerExecutor(String summary) {
            super(registry(), null);
            this.summary = summary;
        }

        @Override
        public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
            return new WorkerExecutionResult(
                summary,
                summary,
                false,
                "",
                "",
                "",
                "medium",
                "completed",
                List.of(),
                List.of(),
                0,
                1L,
                Map.of("execution_backend", "provider_native_cli")
            );
        }

        private static AgentProviderRegistry registry() {
            AgentProviderRegistry registry = new AgentProviderRegistry();
            BuiltinAgentProviders.defaults().forEach(registry::register);
            return registry;
        }
    }
}
