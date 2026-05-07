package com.agentcloud.worker;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.worker.model.WorkerExecutionEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 按 worker 合同选择执行器的统一门面。
 * 当前区分 default / tool-aware / provider-native-cli 三条执行路径。
 */
public class WorkerExecutorRouter implements WorkerExecutor {
    private static final Logger log = LoggerFactory.getLogger(WorkerExecutorRouter.class);

    private final WorkerRegistry workerRegistry;
    private final WorkerExecutor defaultExecutor;
    private final WorkerExecutor toolAwareExecutor;
    private final ProviderCliWorkerExecutor providerCliExecutor;

    public WorkerExecutorRouter(WorkerRegistry workerRegistry,
                                WorkerExecutor defaultExecutor,
                                WorkerExecutor toolAwareExecutor,
                                ProviderCliWorkerExecutor providerCliExecutor) {
        this.workerRegistry = workerRegistry;
        this.defaultExecutor = defaultExecutor;
        this.toolAwareExecutor = toolAwareExecutor;
        this.providerCliExecutor = providerCliExecutor;
    }

    @Override
    public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
        WorkerExecutor executor = selectExecutor(workerId);
        Instant startedAt = Instant.now();
        String executionId = context.task().id() + ":" + workerId + ":" + startedAt.toEpochMilli();
        WorkerExecutionResult result = executor.executeOneRound(context, workerId);
        Instant finishedAt = Instant.now();
        WorkerExecutionEnvelope envelope = new WorkerExecutionEnvelope(
            executionId,
            context.task().sessionId(),
            context.task().id(),
            workerId,
            startedAt,
            finishedAt,
            Math.max(0L, finishedAt.toEpochMilli() - startedAt.toEpochMilli()),
            result != null ? result.executionStatus() : "unknown",
            result,
            readToolInvocationIds(result),
            new LinkedHashMap<>()
        );
        return WorkerExecutionResult.withEnvelope(
            result,
            envelope.executionId(),
            envelope.sessionId(),
            envelope.taskId(),
            envelope.workerId(),
            envelope.startedAt(),
            envelope.finishedAt(),
            envelope.toolInvocationIds(),
            envelope.metadata()
        );
    }

    private WorkerExecutor selectExecutor(String workerId) {
        Worker worker = workerRegistry.get(workerId);
        if (worker == null) {
            log.warn("Worker not found, fallback to default executor. worker={}", workerId);
            return defaultExecutor;
        }
        if (worker.suggestOnly()) {
            return defaultExecutor;
        }
        if (providerCliExecutor != null && providerCliExecutor.supports(workerId, worker)
            && !shouldPreferToolAware(worker)) {
            log.info("Routing worker to provider-native cli executor. worker={} type={}",
                worker.workerId(), worker.workerType());
            return providerCliExecutor;
        }
        if (worker.toolCapabilities() == null || worker.toolCapabilities().isEmpty()) {
            return defaultExecutor;
        }
        log.info("Routing worker to tool-aware executor. worker={} tools={}",
            worker.workerId(), worker.toolCapabilities());
        return toolAwareExecutor;
    }

    private boolean shouldPreferToolAware(Worker worker) {
        if (worker == null || worker.metadata() == null) {
            return worker != null && worker.toolCapabilities() != null && !worker.toolCapabilities().isEmpty();
        }
        Object backend = worker.metadata().get("execution_backend");
        if (backend != null && "provider_native_cli".equalsIgnoreCase(backend.toString())) {
            return false;
        }
        return worker.toolCapabilities() != null && !worker.toolCapabilities().isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<String> readToolInvocationIds(WorkerExecutionResult result) {
        if (result == null || result.metadata() == null) {
            return List.of();
        }
        Object value = result.metadata().get("tool_invocation_ids");
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
