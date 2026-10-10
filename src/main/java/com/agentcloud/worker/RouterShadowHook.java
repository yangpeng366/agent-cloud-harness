package com.agentcloud.worker;

import com.agentcloud.runtime.TaskRuntimeContext;

import java.util.Map;

/**
 * M1 Router 旁路观察 hook；WorkerExecutorRouter 在构造 envelope.metadata 之前调用。
 * <p>
 * 默认 null = no-op（既有行为不变）。M2 接入时通过
 * {@link WorkerExecutorRouter#setRouterShadowHook(RouterShadowHook)} 注入；
 * JudgmentShadowHookAdapter（judgment.model.shadow 包）会把 RouterShadowService
 * 适配到本接口。详见第 50 号笔记。
 */
public interface RouterShadowHook {
    /**
     * @param context      当前执行上下文（task / packet / events / decisions / artifacts / tools / messages）
     * @param workerId     既有 WorkerRouter 选定的 workerId
     * @param executionId  本轮 execution 唯一 ID（task:worker:startEpochMs）
     * @return 要合并进 envelope.metadata 的字段；返回 null 或空 Map = 不写
     */
    Map<String, Object> preEnvelope(TaskRuntimeContext context, String workerId, String executionId);
}