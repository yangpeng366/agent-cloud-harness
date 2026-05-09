package com.agentcloud.runtime;

import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.runtime.context.MountedContextPromptBudgetSupport;
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
        Map<String, Object> latestWorkerMetadata = mergeLatestWorkerMetadata(
            latestToolInvocationMetadata(toolInvocations),
            resolveLatestWorkerMetadata(runtimeContext)
        );
        WorkerRouter.RouteResult routePreview = buildRoutePreview(task, latestWorkerMetadata);

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

        RuntimeFactSet.ExecutionBoundary executionBoundary = buildExecutionBoundary(latestWorkerMetadata, toolInvocations);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tool_invocation_count", toolInvocations.size());
        metadata.put("has_runtime_context", runtimeContext != null);
        metadata.put("has_latest_packet", latestPacket != null);
        metadata.put("has_latest_checkpoint", runtimeContext != null && runtimeContext.latestCheckpoint() != null);
        metadata.put("has_execution_judgment", executionJudgment != null);
        metadata.put("has_completion_judgment", completionJudgment != null);
        metadata.put("has_route_preview", routePreview != null);
        copyDecisionMetadataKey(executionJudgment, metadata, "needs_context_reopen");
        copyDecisionMetadataKey(executionJudgment, metadata, "reopen_candidate_paths");
        copyDecisionMetadataKey(executionJudgment, metadata, "reopen_summary");
        if (!latestWorkerMetadata.isEmpty()) {
            metadata.put("has_latest_worker_metadata", true);
            metadata.putAll(latestWorkerMetadata);
        }
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

    private void copyDecisionMetadataKey(Decision decision, Map<String, Object> target, String key) {
        if (decision == null || target == null || key == null || key.isBlank()) {
            return;
        }
        Map<String, Object> decisionMetadata = decision.metadata();
        if (decisionMetadata == null || decisionMetadata.isEmpty()) {
            return;
        }
        Object value = decisionMetadata.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private Map<String, Object> resolveLatestWorkerMetadata(TaskRuntimeContext runtimeContext) {
        if (runtimeContext == null || runtimeContext.recentArtifacts() == null || runtimeContext.recentArtifacts().isEmpty()) {
            return Map.of();
        }
        for (Artifact artifact : runtimeContext.recentArtifacts()) {
            if (artifact == null || artifact.metadata() == null || artifact.metadata().isEmpty()) {
                continue;
            }
            Map<String, Object> extracted = extractLatestWorkerMetadata(artifact.metadata());
            if (!extracted.isEmpty()) {
                return extracted;
            }
        }
        return Map.of();
    }

    private Map<String, Object> latestToolInvocationMetadata(List<ToolInvocationRecord> toolInvocations) {
        if (toolInvocations == null || toolInvocations.isEmpty()) {
            return Map.of();
        }
        for (ToolInvocationRecord invocation : toolInvocations) {
            if (invocation == null || invocation.metadata() == null || invocation.metadata().isEmpty()) {
                continue;
            }
            Map<String, Object> selected = selectLatestWorkerMetadata(invocation.metadata());
            if (!selected.isEmpty()) {
                if (firstNonBlank(metadataString(selected, "tool_invocation_id"), invocation.id()) != null) {
                    selected.putIfAbsent("tool_invocation_id", invocation.id());
                }
                if (firstNonBlank(metadataString(selected, "execution_id"), invocation.executionId()) != null) {
                    selected.putIfAbsent("execution_id", invocation.executionId());
                }
                if (firstNonBlank(metadataString(selected, "selected_worker"), invocation.workerId()) != null) {
                    selected.putIfAbsent("selected_worker", invocation.workerId());
                }
                if (firstNonBlank(metadataString(selected, "execution_status"), invocation.status()) != null) {
                    selected.putIfAbsent(
                        "execution_status",
                        firstNonBlank(invocation.status(), invocation.success() ? "succeeded" : "failed")
                    );
                }
                if (metadataLong(selected, "duration_ms") == null && invocation.elapsedMs() != null) {
                    selected.put("duration_ms", invocation.elapsedMs().longValue());
                }
                if (firstNonBlank(metadataString(selected, "tool_name"), invocation.toolName()) != null) {
                    selected.putIfAbsent("tool_name", invocation.toolName());
                }
                selected.putIfAbsent("tool_success", invocation.success());
                return selected;
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractLatestWorkerMetadata(Map<String, Object> artifactMetadata) {
        if (artifactMetadata == null || artifactMetadata.isEmpty()) {
            return Map.of();
        }
        Object nested = artifactMetadata.get("latest_worker_metadata");
        if (nested instanceof Map<?, ?> nestedMap) {
            Map<String, Object> nestedMetadata = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : nestedMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    nestedMetadata.put(entry.getKey().toString(), entry.getValue());
                }
            }
            return mergeLatestWorkerMetadata(artifactMetadata, nestedMetadata);
        }
        return selectLatestWorkerMetadata(artifactMetadata);
    }

    private Map<String, Object> mergeLatestWorkerMetadata(Map<String, Object> primarySource,
                                                          Map<String, Object> secondarySource) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Map<String, Object> primary = selectLatestWorkerMetadata(primarySource);
        if (!primary.isEmpty()) {
            merged.putAll(primary);
        }
        Map<String, Object> secondary = selectLatestWorkerMetadata(secondarySource);
        if (!secondary.isEmpty()) {
            merged.putAll(secondary);
        }
        return merged.isEmpty() ? Map.of() : merged;
    }

    private Map<String, Object> selectLatestWorkerMetadata(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        copyMetadataKey(source, selected, "tool_aware_executor");
        copyMetadataKey(source, selected, "tool_execution_mode");
        copyMetadataKey(source, selected, "prompt_rendering_mode");
        copyMetadataKey(source, selected, "mounted_context_mode");
        copyMetadataKey(source, selected, "prompt_mode");
        copyMetadataKey(source, selected, "mounted_context_rendered");
        copyMetadataKey(source, selected, "mounted_render_used");
        copyMetadataKey(source, selected, "mounted_context_injected");
        copyMetadataKey(source, selected, "mounted_context_panel_count");
        copyMetadataKey(source, selected, "mounted_panel_count");
        copyMetadataKey(source, selected, "mounted_context_non_empty_panel_count");
        copyMetadataKey(source, selected, "mounted_non_empty_panel_count");
        copyMetadataKey(source, selected, "mounted_context_selection_trace_count");
        copyMetadataKey(source, selected, MountedContextPromptBudgetSupport.RENDERED_PANEL_COUNT);
        copyMetadataKey(source, selected, MountedContextPromptBudgetSupport.HIDDEN_PANEL_COUNT);
        copyMetadataKey(source, selected, MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT);
        copyMetadataKey(source, selected, MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT);
        copyMetadataKey(source, selected, MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT);
        copyMetadataKey(source, selected, MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT);
        copyMetadataKey(source, selected, MountedContextPromptBudgetSupport.BUDGET_TRUNCATED);
        copyMetadataKey(source, selected, "mounted_pinned_count");
        copyMetadataKey(source, selected, "mounted_active_count");
        copyMetadataKey(source, selected, "mounted_ancestor_count");
        copyMetadataKey(source, selected, "mounted_sibling_count");
        copyMetadataKey(source, selected, "mounted_evidence_count");
        copyMetadataKey(source, selected, "mounted_index_count");
        copyMetadataKey(source, selected, "mounted_archive_count");
        copyMetadataKey(source, selected, "image_input_count");
        copyMetadataKey(source, selected, "image_input_used");
        copyMetadataKey(source, selected, "selected_worker");
        copyMetadataKey(source, selected, "selected_worker_type");
        copyMetadataKey(source, selected, "selected_model_tier");
        copyMetadataKey(source, selected, "execution_role");
        copyMetadataKey(source, selected, "selection_scope");
        copyMetadataKey(source, selected, "why_selected");
        copyMetadataKey(source, selected, "candidate_workers");
        copyMetadataKey(source, selected, "preferred_worker_hint");
        copyMetadataKey(source, selected, "learning_hint_applied");
        copyMetadataKey(source, selected, "fallback_reason");
        copyMetadataKey(source, selected, "route_source");
        copyMetadataKey(source, selected, "provider_id");
        copyMetadataKey(source, selected, "execution_backend");
        copyMetadataKey(source, selected, "execution_id");
        copyMetadataKey(source, selected, "execution_started_at");
        copyMetadataKey(source, selected, "execution_finished_at");
        copyMetadataKey(source, selected, "execution_duration_ms");
        copyMetadataKey(source, selected, "duration_ms");
        copyMetadataKey(source, selected, "execution_status");
        copyMetadataKey(source, selected, "tool_invocation_id");
        copyMetadataKey(source, selected, "tool_invocation_ids");
        copyMetadataKey(source, selected, "provider_session_id");
        copyMetadataKey(source, selected, "provider_thread_id");
        copyMetadataKey(source, selected, "resume_provider_session_id");
        copyMetadataKey(source, selected, "model_mode");
        copyMetadataKey(source, selected, "orchestration_stage");
        copyMetadataKey(source, selected, "planner_worker");
        copyMetadataKey(source, selected, "executor_worker");
        copyMetadataKey(source, selected, "target_worker");
        copyMetadataKey(source, selected, "tool_name");
        copyMetadataKey(source, selected, "tool_success");
        copyMetadataKey(source, selected, "tool_summary");
        copyMetadataKey(source, selected, "tool_plan_reason");
        copyMetadataKey(source, selected, "auto_write_generation_mode");
        copyMetadataKey(source, selected, "auto_write_generation_error");
        copyMetadataKey(source, selected, "output_file_required");
        copyMetadataKey(source, selected, "output_file_path");
        copyMetadataKey(source, selected, "output_file_exists");
        copyMetadataKey(source, selected, "output_file_size");
        copyMetadataKey(source, selected, "output_dir_required");
        copyMetadataKey(source, selected, "output_dir_path");
        copyMetadataKey(source, selected, "output_dir_exists");
        copyMetadataKey(source, selected, "output_dir_entry_count");
        copyMetadataKey(source, selected, "file_backed_artifact");
        copyMetadataKey(source, selected, "directory_backed_artifact");
        copyMetadataKey(source, selected, "evidence_refs");
        copyMetadataKey(source, selected, "unfinished_items");
        copyMetadataKey(source, selected, "needs_context_reopen");
        copyMetadataKey(source, selected, "reopen_candidate_paths");
        copyMetadataKey(source, selected, "reopen_summary");
        copyMetadataKey(source, selected, "grounded_output_present");
        copyMetadataKey(source, selected, "grounding_mode");
        copyMetadataKey(source, selected, "more_declared_rounds_remain");
        copyMetadataKey(source, selected, "current_round_requires_write");
        copyMetadataKey(source, selected, "missing_required_current_round_write");
        copyMetadataKey(source, selected, "current_round_instruction");
        copyMetadataKey(source, selected, "next_round_instruction");
        copyMetadataKey(source, selected, "tool_round_index");
        copyMetadataKey(source, selected, "declared_round_count");
        copyMetadataKey(source, selected, "max_tool_rounds");
        copyMetadataKey(source, selected, "tool_chain_step_count");
        copyMetadataKey(source, selected, "tool_chain_termination_reason");
        copyMetadataKey(source, selected, "tool_chain_trace");
        return selected.isEmpty() ? Map.of() : selected;
    }

    private WorkerRouter.RouteResult buildRoutePreview(Task task, Map<String, Object> latestWorkerMetadata) {
        WorkerRouter.RouteResult routerPreview = workerRouter != null && task != null
            ? workerRouter.selectWorker(task)
            : null;
        String metadataWorker = firstNonBlank(
            metadataString(latestWorkerMetadata, "selected_worker"),
            metadataString(latestWorkerMetadata, "executor_worker")
        );
        String currentWorker = firstNonBlank(
            task != null ? task.assignedWorker() : null,
            routerPreview != null ? routerPreview.selectedWorker() : null
        );
        boolean currentRouteWins = currentWorker != null
            && metadataWorker != null
            && !currentWorker.equalsIgnoreCase(metadataWorker);
        String selectedWorker = currentRouteWins ? currentWorker : firstNonBlank(metadataWorker, currentWorker);
        String routeSource = currentRouteWins
            ? firstNonBlank(
                routerPreview != null ? routerPreview.routeSource() : null,
                metadataString(latestWorkerMetadata, "route_source")
            )
            : firstNonBlank(
                metadataString(latestWorkerMetadata, "route_source"),
                routerPreview != null ? routerPreview.routeSource() : null
            );
        String routeReason = currentRouteWins
            ? firstNonBlank(
                routerPreview != null ? routerPreview.whySelected() : null,
                routerPreview != null ? routerPreview.routeReason() : null,
                metadataString(latestWorkerMetadata, "preassigned_selection_reason"),
                metadataString(latestWorkerMetadata, "why_selected")
            )
            : firstNonBlank(
                metadataString(latestWorkerMetadata, "why_selected"),
                metadataString(latestWorkerMetadata, "preassigned_selection_reason"),
                routerPreview != null ? routerPreview.whySelected() : null,
                routerPreview != null ? routerPreview.routeReason() : null
            );
        String selectedWorkerType = currentRouteWins
            ? firstNonBlank(
                routerPreview != null ? routerPreview.selectedWorkerType() : null,
                metadataString(latestWorkerMetadata, "selected_worker_type")
            )
            : firstNonBlank(
                metadataString(latestWorkerMetadata, "selected_worker_type"),
                routerPreview != null ? routerPreview.selectedWorkerType() : null
            );
        String selectedModelTier = currentRouteWins
            ? firstNonBlank(
                routerPreview != null ? routerPreview.selectedModelTier() : null,
                metadataString(latestWorkerMetadata, "selected_model_tier")
            )
            : firstNonBlank(
                metadataString(latestWorkerMetadata, "selected_model_tier"),
                routerPreview != null ? routerPreview.selectedModelTier() : null
            );
        String selectedExecutionRole = currentRouteWins
            ? firstNonBlank(
                routerPreview != null ? routerPreview.selectedExecutionRole() : null,
                metadataString(latestWorkerMetadata, "execution_role")
            )
            : firstNonBlank(
                metadataString(latestWorkerMetadata, "execution_role"),
                routerPreview != null ? routerPreview.selectedExecutionRole() : null
            );
        String selectionScope = currentRouteWins
            ? firstNonBlank(
                routerPreview != null ? routerPreview.selectionScope() : null,
                resolveSelectionScope(task, selectedExecutionRole),
                metadataString(latestWorkerMetadata, "selection_scope")
            )
            : firstNonBlank(
                metadataString(latestWorkerMetadata, "selection_scope"),
                routerPreview != null ? routerPreview.selectionScope() : null,
                resolveSelectionScope(task, selectedExecutionRole)
            );
        String preferredWorkerHint = currentRouteWins
            ? firstNonBlank(
                routerPreview != null ? routerPreview.preferredWorkerHint() : null,
                metadataString(latestWorkerMetadata, "preferred_worker_hint")
            )
            : firstNonBlank(
                metadataString(latestWorkerMetadata, "preferred_worker_hint"),
                routerPreview != null ? routerPreview.preferredWorkerHint() : null
            );
        boolean learningHintApplied = metadataBoolean(
            latestWorkerMetadata,
            "learning_hint_applied",
            routerPreview != null && routerPreview.learningHintApplied()
        );
        String fallbackReason = currentRouteWins
            ? firstNonBlank(
                routerPreview != null ? routerPreview.fallbackReason() : null,
                metadataString(latestWorkerMetadata, "fallback_reason")
            )
            : firstNonBlank(
                metadataString(latestWorkerMetadata, "fallback_reason"),
                routerPreview != null ? routerPreview.fallbackReason() : null
            );
        List<String> candidateWorkers = currentRouteWins
            ? (routerPreview != null && routerPreview.candidateWorkers() != null ? routerPreview.candidateWorkers() : List.of())
            : metadataStringList(latestWorkerMetadata, "candidate_workers");
        if (candidateWorkers.isEmpty()) {
            candidateWorkers = metadataStringList(latestWorkerMetadata, "candidate_workers");
        }
        if (candidateWorkers.isEmpty() && routerPreview != null && routerPreview.candidateWorkers() != null) {
            candidateWorkers = routerPreview.candidateWorkers();
        }
        boolean hasMetadataRoute = latestWorkerMetadata != null && !latestWorkerMetadata.isEmpty() && (
            selectedWorker != null
                || routeSource != null
                || routeReason != null
                || selectedWorkerType != null
                || selectedModelTier != null
                || selectedExecutionRole != null
                || preferredWorkerHint != null
                || !candidateWorkers.isEmpty()
        );
        if (!hasMetadataRoute) {
            return routerPreview;
        }
        return new WorkerRouter.RouteResult(
            task != null ? task.id() : null,
            selectedWorker,
            routerPreview != null ? routerPreview.fallbackWorkers() : List.of(),
            routeReason,
            routeSource,
            task != null && task.metadata() != null ? stringValue(task.metadata().get("task_type")) : null,
            preferredWorkerHint,
            learningHintApplied,
            candidateWorkers,
            selectedWorkerType,
            selectedModelTier,
            selectedExecutionRole,
            selectionScope,
            routeReason,
            fallbackReason
        );
    }

    private RuntimeFactSet.ExecutionBoundary buildExecutionBoundary(Map<String, Object> latestWorkerMetadata,
                                                                    List<ToolInvocationRecord> toolInvocations) {
        RuntimeFactSet.ExecutionBoundary fromMetadata = buildExecutionBoundaryFromLatestWorkerMetadata(
            latestWorkerMetadata,
            toolInvocations
        );
        return fromMetadata != null ? fromMetadata : buildExecutionBoundaryFromToolInvocations(toolInvocations);
    }

    private RuntimeFactSet.ExecutionBoundary buildExecutionBoundaryFromLatestWorkerMetadata(Map<String, Object> latestWorkerMetadata,
                                                                                            List<ToolInvocationRecord> toolInvocations) {
        if (latestWorkerMetadata == null || latestWorkerMetadata.isEmpty()) {
            return null;
        }
        String executionId = firstNonBlank(
            metadataString(latestWorkerMetadata, "execution_id"),
            metadataString(latestWorkerMetadata, "tool_invocation_id"),
            latestExecutionId(toolInvocations)
        );
        String executionStatus = firstNonBlank(
            metadataString(latestWorkerMetadata, "execution_status"),
            metadataString(latestWorkerMetadata, "tool_chain_termination_reason")
        );
        Long durationMs = firstNonNull(
            metadataLong(latestWorkerMetadata, "execution_duration_ms"),
            metadataLong(latestWorkerMetadata, "duration_ms"),
            toolExecutionDurationMs(toolInvocations)
        );
        List<String> toolInvocationIds = metadataStringList(latestWorkerMetadata, "tool_invocation_ids");
        if (toolInvocationIds.isEmpty()) {
            toolInvocationIds = latestExecutionInvocationIds(toolInvocations);
        }
        Integer toolInvocationCount = firstNonNull(
            metadataInt(latestWorkerMetadata, "tool_chain_step_count"),
            !toolInvocationIds.isEmpty() ? toolInvocationIds.size() : null,
            toolInvocations != null && !toolInvocations.isEmpty() ? toolInvocations.size() : null
        );
        String workerId = firstNonBlank(
            metadataString(latestWorkerMetadata, "selected_worker"),
            metadataString(latestWorkerMetadata, "executor_worker"),
            latestWorkerId(toolInvocations)
        );
        String startedAt = firstNonBlank(
            metadataString(latestWorkerMetadata, "execution_started_at"),
            latestExecutionStartedAt(toolInvocations)
        );
        String finishedAt = firstNonBlank(
            metadataString(latestWorkerMetadata, "execution_finished_at"),
            latestExecutionFinishedAt(toolInvocations)
        );
        if ((executionId == null || executionId.isBlank())
            && (executionStatus == null || executionStatus.isBlank())
            && toolInvocationIds.isEmpty()
            && toolInvocationCount == null) {
            return null;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        copyMetadataKey(latestWorkerMetadata, metadata, "tool_execution_mode");
        copyMetadataKey(latestWorkerMetadata, metadata, "tool_chain_step_count");
        copyMetadataKey(latestWorkerMetadata, metadata, "tool_chain_termination_reason");
        copyMetadataKey(latestWorkerMetadata, metadata, "tool_chain_trace");
        copyMetadataKey(latestWorkerMetadata, metadata, "tool_invocation_ids");
        copyMetadataKey(latestWorkerMetadata, metadata, "tool_scope");
        copyMetadataKey(latestWorkerMetadata, metadata, "evidence_refs");
        copyMetadataKey(latestWorkerMetadata, metadata, "unfinished_items");
        copyMetadataKey(latestWorkerMetadata, metadata, "grounded_output_present");
        copyMetadataKey(latestWorkerMetadata, metadata, "missing_required_current_round_write");
        String latestToolName = firstNonBlank(
            latestToolNameFromTrace(latestWorkerMetadata.get("tool_chain_trace")),
            metadataString(latestWorkerMetadata, "tool_name"),
            latestToolName(toolInvocations)
        );
        if (latestToolName != null) {
            metadata.put("latest_tool_name", latestToolName);
        }

        return new RuntimeFactSet.ExecutionBoundary(
            firstNonBlank(executionId, ""),
            firstNonBlank(executionStatus, ""),
            startedAt,
            finishedAt,
            durationMs,
            firstNonBlank(workerId, ""),
            toolInvocationIds,
            toolInvocationCount,
            firstNonBlank(buildExecutionTraceSummary(latestWorkerMetadata), buildExecutionTraceSummary(toolInvocations)),
            metadata
        );
    }

    private RuntimeFactSet.ExecutionBoundary buildExecutionBoundaryFromToolInvocations(List<ToolInvocationRecord> toolInvocations) {
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
        String traceSummary = buildExecutionTraceSummary(sameExecution);

        Map<String, Object> metadata = new LinkedHashMap<>();
        copyMetadataIfPresent(metadata, latest.metadata(), "tool_execution_mode");
        copyMetadataIfPresent(metadata, latest.metadata(), "tool_chain_step_count");
        copyMetadataIfPresent(metadata, latest.metadata(), "tool_chain_termination_reason");
        copyMetadataIfPresent(metadata, latest.metadata(), "tool_chain_trace");
        copyMetadataIfPresent(metadata, latest.metadata(), "tool_invocation_ids");
        copyMetadataIfPresent(metadata, latest.metadata(), "tool_scope");
        copyMetadataIfPresent(metadata, latest.metadata(), "prompt_rendering_mode");
        copyMetadataIfPresent(metadata, latest.metadata(), "mounted_context_mode");
        copyMetadataIfPresent(metadata, latest.metadata(), "prompt_mode");
        copyMetadataIfPresent(metadata, latest.metadata(), "mounted_context_rendered");
        copyMetadataIfPresent(metadata, latest.metadata(), "mounted_render_used");
        copyMetadataIfPresent(metadata, latest.metadata(), "mounted_context_injected");
        copyMetadataIfPresent(metadata, latest.metadata(), "mounted_context_panel_count");
        copyMetadataIfPresent(metadata, latest.metadata(), "mounted_context_non_empty_panel_count");
        copyMetadataIfPresent(metadata, latest.metadata(), "mounted_context_selection_trace_count");
        copyMetadataIfPresent(metadata, latest.metadata(), MountedContextPromptBudgetSupport.RENDERED_PANEL_COUNT);
        copyMetadataIfPresent(metadata, latest.metadata(), MountedContextPromptBudgetSupport.HIDDEN_PANEL_COUNT);
        copyMetadataIfPresent(metadata, latest.metadata(), MountedContextPromptBudgetSupport.RENDERED_OBJECT_COUNT);
        copyMetadataIfPresent(metadata, latest.metadata(), MountedContextPromptBudgetSupport.HIDDEN_OBJECT_COUNT);
        copyMetadataIfPresent(metadata, latest.metadata(), MountedContextPromptBudgetSupport.RENDERED_SELECTION_TRACE_COUNT);
        copyMetadataIfPresent(metadata, latest.metadata(), MountedContextPromptBudgetSupport.HIDDEN_SELECTION_TRACE_COUNT);
        copyMetadataIfPresent(metadata, latest.metadata(), MountedContextPromptBudgetSupport.BUDGET_TRUNCATED);
        copyMetadataIfPresent(metadata, latest.metadata(), "candidate_workers");
        copyMetadataIfPresent(metadata, latest.metadata(), "evidence_refs");
        copyMetadataIfPresent(metadata, latest.metadata(), "unfinished_items");
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

    private String buildExecutionTraceSummary(Map<String, Object> latestWorkerMetadata) {
        Integer stepCount = metadataInt(latestWorkerMetadata, "tool_chain_step_count");
        String terminationReason = metadataString(latestWorkerMetadata, "tool_chain_termination_reason");
        List<String> toolNames = toolNamesFromTrace(latestWorkerMetadata != null
            ? latestWorkerMetadata.get("tool_chain_trace")
            : null);
        if (stepCount == null && terminationReason == null && toolNames.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (stepCount != null) {
            sb.append(stepCount).append(" step").append(stepCount == 1 ? "" : "s");
        }
        if (terminationReason != null && !terminationReason.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(terminationReason);
        }
        if (!toolNames.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(String.join(" -> ", toolNames));
        }
        return sb.toString();
    }

    private String buildExecutionTraceSummary(List<ToolInvocationRecord> sameExecution) {
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
            + " · " + firstNonBlank(
                stringMetadata(sameExecution.get(0).metadata(), "execution_status"),
                sameExecution.get(0).status(),
                sameExecution.get(0).success() ? "succeeded" : "failed",
                "unknown"
            )
            + " · " + tools;
    }

    private List<String> toolNamesFromTrace(Object traceValue) {
        if (!(traceValue instanceof List<?> rawTrace) || rawTrace.isEmpty()) {
            return List.of();
        }
        List<String> toolNames = new java.util.ArrayList<>();
        for (Object entry : rawTrace) {
            if (entry instanceof Map<?, ?> map) {
                Object toolName = map.get("tool_name");
                if (toolName != null && !toolName.toString().isBlank()) {
                    toolNames.add(toolName.toString());
                }
            }
        }
        return toolNames;
    }

    private String latestToolNameFromTrace(Object traceValue) {
        List<String> toolNames = toolNamesFromTrace(traceValue);
        return toolNames.isEmpty() ? null : toolNames.get(toolNames.size() - 1);
    }

    private List<String> latestExecutionInvocationIds(List<ToolInvocationRecord> toolInvocations) {
        if (toolInvocations == null || toolInvocations.isEmpty()) {
            return List.of();
        }
        String latestExecutionId = latestExecutionId(toolInvocations);
        if (latestExecutionId != null && !latestExecutionId.isBlank()) {
            List<String> sameExecutionIds = toolInvocations.stream()
                .filter(record -> record != null && latestExecutionId.equals(firstNonBlank(record.executionId())))
                .map(ToolInvocationRecord::id)
                .filter(id -> id != null && !id.isBlank())
                .toList();
            if (!sameExecutionIds.isEmpty()) {
                return sameExecutionIds;
            }
        }
        return toolInvocations.stream()
            .map(ToolInvocationRecord::id)
            .filter(id -> id != null && !id.isBlank())
            .toList();
    }

    private String latestToolName(List<ToolInvocationRecord> toolInvocations) {
        if (toolInvocations == null || toolInvocations.isEmpty()) {
            return null;
        }
        return toolInvocations.get(0).toolName();
    }

    private String latestExecutionId(List<ToolInvocationRecord> toolInvocations) {
        if (toolInvocations == null || toolInvocations.isEmpty()) {
            return null;
        }
        return firstNonBlank(toolInvocations.get(0).executionId());
    }

    private String latestWorkerId(List<ToolInvocationRecord> toolInvocations) {
        if (toolInvocations == null || toolInvocations.isEmpty()) {
            return null;
        }
        return firstNonBlank(toolInvocations.get(0).workerId());
    }

    private Long toolExecutionDurationMs(List<ToolInvocationRecord> toolInvocations) {
        if (toolInvocations == null || toolInvocations.isEmpty()) {
            return null;
        }
        return toolInvocations.stream()
            .map(ToolInvocationRecord::elapsedMs)
            .filter(value -> value != null && value > 0)
            .mapToLong(Integer::longValue)
            .sum();
    }

    private String latestExecutionStartedAt(List<ToolInvocationRecord> toolInvocations) {
        if (toolInvocations == null || toolInvocations.isEmpty()) {
            return null;
        }
        return toolInvocations.stream()
            .map(ToolInvocationRecord::createdAt)
            .filter(java.util.Objects::nonNull)
            .min(java.time.Instant::compareTo)
            .map(java.time.Instant::toString)
            .orElse(null);
    }

    private String latestExecutionFinishedAt(List<ToolInvocationRecord> toolInvocations) {
        if (toolInvocations == null || toolInvocations.isEmpty()) {
            return null;
        }
        return toolInvocations.stream()
            .map(ToolInvocationRecord::createdAt)
            .filter(java.util.Objects::nonNull)
            .max(java.time.Instant::compareTo)
            .map(java.time.Instant::toString)
            .orElse(null);
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

    private String metadataString(Map<String, Object> metadata, String key) {
        return stringMetadata(metadata, key);
    }

    private Integer metadataInt(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long metadataLong(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> metadataStringList(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return List.of();
        }
        Object value = metadata.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
            .filter(item -> item != null && !item.toString().isBlank())
            .map(Object::toString)
            .toList();
    }

    private boolean metadataBoolean(Map<String, Object> metadata, String key, boolean defaultValue) {
        if (metadata == null || key == null || key.isBlank()) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.toString());
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

    private String resolveSelectionScope(Task task, String executionRole) {
        if (task != null && isOrchestrated(task)) {
            String stage = metadataString(task.metadata(), "orchestration_stage");
            if (stage != null && stage.toLowerCase().startsWith("execution")) {
                return "executor";
            }
            if ("completed".equalsIgnoreCase(stage)) {
                return "evaluator";
            }
            return "planner";
        }
        String normalizedRole = firstNonBlank(executionRole, "executor");
        if ("planner".equalsIgnoreCase(normalizedRole) || normalizedRole.toLowerCase().contains("planner")) {
            return "planner";
        }
        return "executor";
    }

    private boolean isOrchestrated(Task task) {
        return "orchestrated".equalsIgnoreCase(metadataString(task != null ? task.metadata() : null, "model_mode"));
    }

    private void copyMetadataKey(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
