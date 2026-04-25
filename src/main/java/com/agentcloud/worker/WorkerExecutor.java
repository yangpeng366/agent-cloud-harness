package com.agentcloud.worker;

import com.agentcloud.runtime.TaskRuntimeContext;

/**
 * Worker 执行器接口。
 */
public interface WorkerExecutor {
    WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId);
}
