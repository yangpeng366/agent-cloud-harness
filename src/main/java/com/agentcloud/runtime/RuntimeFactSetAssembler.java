package com.agentcloud.runtime;

import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Decision;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.runtime.model.RuntimeFactSet;
import com.agentcloud.store.ToolInvocationDao;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组装 phase-1 最小 RuntimeFactSet。
 */
public class RuntimeFactSetAssembler {
    private final TaskRuntimeContextBuilder runtimeContextBuilder;
    private final ToolInvocationDao toolInvocationDao;
    private final WorkerRouter workerRouter;

    public RuntimeFactSetAssembler(TaskRuntimeContextBuilder runtimeContextBuilder,
                                   ToolInvocationDao toolInvocationDao,
                                   WorkerRouter workerRouter) {
        this.runtimeContextBuilder = runtimeContextBuilder;
        this.toolInvocationDao = toolInvocationDao;
        this.workerRouter = workerRouter;
    }

    public RuntimeFactSet assemble(Task task, int limit) {
        if (task == null) {
            return RuntimeFactSet.empty(null);
        }

        TaskRuntimeContext runtimeContext = runtimeContextBuilder != null ? runtimeContextBuilder.build(task) : null;
        ResumePacket latestPacket = runtimeContext != null ? runtimeContext.latestPacket() : null;
        Decision executionJudgment = latestDecision(runtimeContext, "execution_judgment");
        Decision completionJudgment = latestDecision(runtimeContext, "completion_judgment");
        List<ToolInvocationRecord> toolInvocations = toolInvocationDao != null
            ? toolInvocationDao.listByTask(task.id(), boundedLimit(limit))
            : List.of();
        WorkerRouter.RouteResult routePreview = workerRouter != null ? workerRouter.selectWorker(task) : null;

        String latestOutput = runtimeContext == null || runtimeContext.recentArtifacts().isEmpty()
            ? ""
            : firstNonBlank(
                runtimeContext.recentArtifacts().get(0).summary(),
                runtimeContext.recentArtifacts().get(0).title()
            );
        String recommendedAction = executionJudgment != null && executionJudgment.metadata() != null
            ? stringValue(executionJudgment.metadata().get("action"))
            : null;
        String recommendedNextStep = firstNonBlank(
            executionJudgment != null && executionJudgment.metadata() != null
                ? stringValue(executionJudgment.metadata().get("next_step"))
                : null,
            completionJudgment != null && completionJudgment.metadata() != null
                ? stringValue(completionJudgment.metadata().get("suggested_next_action"))
                : null,
            task.nextStep()
        );

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tool_invocation_count", toolInvocations.size());
        metadata.put("has_runtime_context", runtimeContext != null);
        metadata.put("has_latest_packet", latestPacket != null);
        metadata.put("has_latest_checkpoint", runtimeContext != null && runtimeContext.latestCheckpoint() != null);
        metadata.put("has_execution_judgment", executionJudgment != null);
        metadata.put("has_completion_judgment", completionJudgment != null);

        return new RuntimeFactSet(
            task.id(),
            task.sessionId(),
            task.status(),
            task.controlNode(),
            task.assignedWorker(),
            latestOutput,
            recommendedAction,
            recommendedNextStep,
            runtimeContext,
            latestPacket,
            runtimeContext != null ? runtimeContext.latestCheckpoint() : null,
            executionJudgment,
            completionJudgment,
            toolInvocations,
            routePreview,
            metadata
        );
    }

    private Decision latestDecision(TaskRuntimeContext runtimeContext, String decisionType) {
        if (runtimeContext == null || runtimeContext.recentDecisions() == null) {
            return null;
        }
        return runtimeContext.recentDecisions().stream()
            .filter(decision -> decision != null && decisionType.equals(decision.decisionType()))
            .findFirst()
            .orElse(null);
    }

    private int boundedLimit(int limit) {
        if (limit <= 0) return 10;
        return Math.min(limit, 100);
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
