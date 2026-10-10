package com.agentcloud.judgment.model.shadow;

import com.agentcloud.model.Task;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.worker.RouterShadowHook;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 RouterShadowService 适配到 WorkerExecutorRouter.RouterShadowHook。
 * <p>
 * runId  = context.task().sessionId()
 * turnId = executionId（task:worker:startEpochMs 形式）
 * taskId = context.task().id()
 * <p>
 * normalizedInput 由 Task 模型字段启发式填充：
 * difficulty  = priority 高/中/低 → 0.80 / 0.50 / 0.20
 * side_effect_risk = 0.10（保守默认，M4 Calibration 再调）
 * 其余字段默认 false
 */
public class RouterShadowHookAdapter implements RouterShadowHook {

    private final RouterShadowService service;

    public RouterShadowHookAdapter(RouterShadowService service) {
        if (service == null) throw new IllegalArgumentException("service required");
        this.service = service;
    }

    @Override
    public Map<String, Object> preEnvelope(TaskRuntimeContext context, String workerId, String executionId) {
        if (context == null || context.task() == null) {
            return Map.of();
        }
        Task task = context.task();
        String runId = task.sessionId() == null ? "" : task.sessionId();
        String turnId = executionId == null ? "" : executionId;
        String taskId = task.id() == null ? "" : task.id();

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("difficulty", priorityToDifficulty(task.priority()));
        input.put("side_effect_risk", 0.10);
        input.put("needs_browser", false);
        input.put("budget_exhausted", false);
        input.put("human_requested", false);
        input.put("high_stakes", false);

        JudgmentDecision decision = service.observe(runId, turnId, taskId, workerId, input);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("routing_decision_id", decision.judgmentId());
        meta.put("routing_tier", decision.action());
        meta.put("routing_scores", decision.scores());
        meta.put("routing_degraded", decision.degraded());
        meta.put("routing_provider", decision.modelRef());
        return meta;
    }

    private static double priorityToDifficulty(String priority) {
        if (priority == null) return 0.50;
        return switch (priority.toLowerCase()) {
            case "high" -> 0.80;
            case "medium" -> 0.50;
            case "low" -> 0.20;
            default -> 0.50;
        };
    }
}