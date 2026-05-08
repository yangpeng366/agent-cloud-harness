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

        RuntimeFactSet.ExecutionBoundary executionBoundary = buildExecutionBoundary(toolInvocations);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tool_invocation_count", toolInvocations.size());
        metadata.put("has_runtime_context", runtimeContext != null);
        metadata.put("has_latest_packet", latestPacket != null);
        metadata.put("has_latest_checkpoint", runtimeContext != null && runtimeContext.latestCheckpoint() != null);
        metadata.put("has_execution_judgment", executionJudgment != null);
        metadata.put("has_completion_judgment", completionJudgment != null);
        metadata.put("has_execution_boundary", executionBoundary != null);
        if (executionBoundary != null) {
            metadata.put("execution_id", executionBoundary.executionId());
            metadata.put("execution_status", executionBoundary.executionStatus());
            metadata.put("execution_duration_ms", executionBoundary.durationMs());
            metadata.put("execution_tool_invocation_count", executionBoundary.toolInvocationCount());
            metadata.put("execution_trace_summary", executionBoundary.traceSummary());
        }

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
            executionBoundary,
            routePreview,
            metadata
        );
    }

    private RuntimeFactSet.ExecutionBoundary buildExecutionBoundary(List<ToolInvocationRecord> toolInvocations) {
        if (toolInvocations == null || toolInvocations.isEmpty()) {
            return null;
        }
        ToolInvocationRecord latest = toolInvocations.get(0);
        String executionId = firstNonBlank(latest.executionId());
        if (executionId == null) {
            return null;
        }
        List<ToolInvocationRecord> sameExecution = toolInvocations.stream()
            .filter(record -> record != null && executionId.equals(firstNonBlank(record.executionId())))
            .toList();
        List<String> toolInvocationIds = sameExecution.stream()
            .map(ToolInvocationRecord::id)
            .filter(id -> id != null && !id.isBlank())
            .toList();
        long durationMs = sameExecution.stream()
            .map(ToolInvocationRecord::elapsedMs)
            .filter(value -> value != null && value > 0)
            .mapToLong(Integer::longValue)
            .sum();
        String executionStatus = firstNonBlank(
            stringMetadata(latest.metadata(), "execution_status"),
            latest.status(),
            latest.success() ? "succeeded" : "failed"
        );
        String workerId = firstNonBlank(latest.workerId());
        String startedAt = sameExecution.stream()
            .map(ToolInvocationRecord::createdAt)
            .filter(java.util.Objects::nonNull)
            .min(java.time.Instant::compareTo)
            .map(java.time.Instant::toString)
            .orElse(null);
        String finishedAt = sameExecution.stream()
            .map(ToolInvocationRecord::createdAt)
            .filter(java.util.Objects::nonNull)
            .max(java.time.Instant::compareTo)
            .map(java.time.Instant::toString)
            .orElse(null);
        String traceSummary = buildExecutionTraceSummary(executionStatus, sameExecution);

        Map<String, Object> metadata = new LinkedHashMap<>();
        copyMetadataIfPresent(metadata, latest.metadata(), "tool_execution_mode");
        copyMetadataIfPresent(metadata, latest.metadata(), "tool_chain_step_count");
        copyMetadataIfPresent(metadata, latest.metadata(), "tool_chain_termination_reason");
        copyMetadataIfPresent(metadata, latest.metadata(), "tool_chain_trace");
        copyMetadataIfPresent(metadata, latest.metadata(), "tool_invocation_ids");
        copyMetadataIfPresent(metadata, latest.metadata(), "tool_scope");
        metadata.put("latest_tool_name", latest.toolName());

        return new RuntimeFactSet.ExecutionBoundary(
            executionId,
            executionStatus,
            startedAt,
            finishedAt,
            durationMs > 0 ? durationMs : null,
            workerId,
            toolInvocationIds,
            sameExecution.size(),
            traceSummary,
            metadata
        );
    }

    private String buildExecutionTraceSummary(String executionStatus, List<ToolInvocationRecord> sameExecution) {
        if (sameExecution == null || sameExecution.isEmpty()) {
            return null;
        }
        String tools = sameExecution.stream()
            .map(ToolInvocationRecord::toolName)
            .filter(name -> name != null && !name.isBlank())
            .distinct()
            .reduce((left, right) -> left + " -> " + right)
            .orElse("tool_execution");
        return sameExecution.size() + " tool call" + (sameExecution.size() == 1 ? "" : "s")
            + " · " + firstNonBlank(executionStatus, "unknown")
            + " · " + tools;
    }

    private void copyMetadataIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (target == null || source == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private String stringMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        return stringValue(metadata.get(key));
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
