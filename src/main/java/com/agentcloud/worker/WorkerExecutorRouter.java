package com.agentcloud.worker;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.TaskRuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按 worker 合同选择执行器的统一门面。
 * 当前只区分 default / tool-aware 两条执行路径。
 */
public class WorkerExecutorRouter implements WorkerExecutor {
    private static final Logger log = LoggerFactory.getLogger(WorkerExecutorRouter.class);

    private final WorkerRegistry workerRegistry;
    private final WorkerExecutor defaultExecutor;
    private final WorkerExecutor toolAwareExecutor;

    public WorkerExecutorRouter(WorkerRegistry workerRegistry,
                                WorkerExecutor defaultExecutor,
                                WorkerExecutor toolAwareExecutor) {
        this.workerRegistry = workerRegistry;
        this.defaultExecutor = defaultExecutor;
        this.toolAwareExecutor = toolAwareExecutor;
    }

    @Override
    public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
        WorkerExecutor executor = selectExecutor(workerId);
        return executor.executeOneRound(context, workerId);
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
        if (worker.toolCapabilities() == null || worker.toolCapabilities().isEmpty()) {
            return defaultExecutor;
        }
        log.info("Routing worker to tool-aware executor. worker={} tools={}",
            worker.workerId(), worker.toolCapabilities());
        return toolAwareExecutor;
    }
}
