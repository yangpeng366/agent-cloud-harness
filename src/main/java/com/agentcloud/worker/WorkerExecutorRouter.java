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
 * 当前区分 default / tool-aware / provider-native-cli / provider-app-server 四条执行路径。
 */
public class WorkerExecutorRouter implements WorkerExecutor {
    private static final Logger log = LoggerFactory.getLogger(WorkerExecutorRouter.class);

    private final WorkerRegistry workerRegistry;
    private final WorkerExecutor defaultExecutor;
    private final WorkerExecutor toolAwareExecutor;
    private final ProviderCliWorkerExecutor providerCliExecutor;
    private final CodexAppServerWorkerExecutor codexAppServerExecutor;

    public WorkerExecutorRouter(WorkerRegistry workerRegistry,
                                WorkerExecutor defaultExecutor,
                                WorkerExecutor toolAwareExecutor,
                                ProviderCliWorkerExecutor providerCliExecutor,
                                CodexAppServerWorkerExecutor codexAppServerExecutor) {
        this.workerRegistry = workerRegistry;
        this.defaultExecutor = defaultExecutor;
        this.toolAwareExecutor = toolAwareExecutor;
        this.providerCliExecutor = providerCliExecutor;
        this.codexAppServerExecutor = codexAppServerExecutor;
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
        if (codexAppServerExecutor != null && codexAppServerExecutor.supports(workerId, worker)
            && shouldUseProviderAppServer(worker)) {
            log.info("Routing worker to provider app-server executor. worker={} type={}",
                worker.workerId(), worker.workerType());
            return codexAppServerExecutor;
        }
        if (providerCliExecutor != null && providerCliExecutor.supports(workerId, worker)
            && !shouldPreferToolAware(worker)) {
            log.info("Routing worker to provider-native cli executor. worker={} type={}",
                worker.workerId(), worker.workerType());
            return providerCliExecutor;
        }
        if (shouldPreferToolAware(worker)) {
            log.info("Routing worker to explicit tool-aware executor. worker={} type={}",
                worker.workerId(), worker.workerType());
            return toolAwareExecutor;
        }
        if (isExplicitProviderBackend(worker)) {
            throw new IllegalStateException("worker declares provider backend but no supported executor is available: "
                + worker.workerId() + " backend=" + metadataString(worker, "execution_backend"));
        }
        if (worker.suggestOnly()) {
            return defaultExecutor;
        }
        if (worker.toolCapabilities() == null || worker.toolCapabilities().isEmpty()) {
            return defaultExecutor;
        }
        log.info("Routing worker to tool-aware executor. worker={} tools={}",
            worker.workerId(), worker.toolCapabilities());
        return toolAwareExecutor;
    }

    private boolean isExplicitProviderBackend(Worker worker) {
        String backend = metadataString(worker, "execution_backend");
        return "provider_native_cli".equalsIgnoreCase(backend)
            || "provider_app_server".equalsIgnoreCase(backend);
    }

    private String metadataString(Worker worker, String key) {
        if (worker == null || worker.metadata() == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = worker.metadata().get(key);
        return value == null ? null : value.toString();
    }

    private boolean shouldPreferToolAware(Worker worker) {
        if (worker == null || worker.metadata() == null) {
            return false;
        }
        Object backend = worker.metadata().get("execution_backend");
        if (backend != null && "provider_native_cli".equalsIgnoreCase(backend.toString())) {
            return false;
        }
        if (backend != null && "tool_aware".equalsIgnoreCase(backend.toString())) {
            return true;
        }
        if (backend != null && ("provider_app_server".equalsIgnoreCase(backend.toString())
            || "default_llm".equalsIgnoreCase(backend.toString()))) {
            return false;
        }
        Object preferHarnessTools = worker.metadata().get("prefer_harness_tools");
        if (preferHarnessTools != null) {
            return Boolean.parseBoolean(preferHarnessTools.toString());
        }
        return false;
    }

    private boolean shouldUseProviderAppServer(Worker worker) {
        if (worker == null || worker.metadata() == null) {
            return false;
        }
        Object backend = worker.metadata().get("execution_backend");
        return backend != null && "provider_app_server".equalsIgnoreCase(backend.toString());
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
