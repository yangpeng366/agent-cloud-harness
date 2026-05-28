package com.agentcloud.engine;

import com.agentcloud.model.BaselineTaskCase;
import com.agentcloud.model.ExperimentMatrixBatch;
import com.agentcloud.model.ExperimentMatrixCreateRequest;
import com.agentcloud.model.ExperimentMatrixSummary;
import com.agentcloud.model.ExperimentRunRecord;
import com.agentcloud.model.ExperimentRunSummary;
import com.agentcloud.model.Task;
import com.agentcloud.model.TaskCreateRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 提供最小 baseline matrix 的固定任务集、批量建 run 与结果汇总。
 */
public class ExperimentMatrixService {
    private static final List<String> SUPPORTED_MODES = List.of("strong_only", "small_only", "orchestrated");
    private static final List<BaselineTaskCase> BASELINE_CASES = List.of(
        baselineCase(
            "short-001",
            "Fix a small regression in task routing and add one assertion",
            "short",
            "Locate a compact routing regression and describe the smallest safe fix.",
            "Produce a concise fix plan and the exact assertion that should be added."
        ),
        baselineCase(
            "short-002",
            "Explain a failing single-path test and propose the minimal code change",
            "short",
            "Inspect one failing path and identify the direct cause.",
            "Return a short explanation plus the smallest code-level fix."
        ),
        baselineCase(
            "short-003",
            "Refactor one helper into a reusable method without changing behavior",
            "short",
            "Reduce duplication around a small helper while preserving runtime behavior.",
            "Provide the refactor outline and the unchanged behavior guarantees."
        ),
        baselineCase(
            "medium-001",
            "Add one diagnostics endpoint and cover it with a regression test",
            "medium",
            "Implement a focused endpoint addition with enough verification to prevent regressions.",
            "Deliver the endpoint contract, expected data shape, and required test coverage."
        ),
        baselineCase(
            "medium-002",
            "Trace worker selection metadata across one execution lifecycle",
            "medium",
            "Follow the runtime metadata path from routing to output artifacts.",
            "Explain where the trace fields should be written and how they should be verified."
        ),
        baselineCase(
            "medium-003",
            "Harden pause and resume behavior with one packet-oriented regression test",
            "medium",
            "Check continuity behavior around packet generation and resume handoff.",
            "Describe the bug surface, the safe fix, and the regression test."
        ),
        baselineCase(
            "long-001",
            "Complete a multi-step orchestration improvement across routing, execution, and validation",
            "long",
            "Plan and sequence a strong-planner to small-executor runtime change that spans multiple components.",
            "Produce a phased implementation brief with execution order, risk points, and validation checkpoints."
        ),
        baselineCase(
            "long-002",
            "Design an experiment comparison pipeline for strong, small, and orchestrated modes",
            "long",
            "Define how the same task should be replayed in three modes and compared on stable metrics.",
            "Return the run plan, the comparison fields, and the expected output format."
        ),
        baselineCase(
            "long-003",
            "Stabilize resume and handoff continuity for an interrupted long-running task",
            "long",
            "Handle interruption, recovery, and worker transfer without losing execution context.",
            "Provide the recovery steps, handoff boundaries, and the criteria for successful continuation."
        )
    );

    private final TaskService taskService;
    private final ExperimentRunService experimentRunService;

    public ExperimentMatrixService(TaskService taskService, ExperimentRunService experimentRunService) {
        this.taskService = taskService;
        this.experimentRunService = experimentRunService;
    }

    public List<BaselineTaskCase> listBaselineCases() {
        return BASELINE_CASES;
    }

    public List<String> supportedModes() {
        return SUPPORTED_MODES;
    }

    public ExperimentMatrixBatch createBaselineRuns(ExperimentMatrixCreateRequest request) {
        String experimentName = firstNonBlank(
            request != null ? request.experimentName() : null,
            IdGenerator.newId("experiment")
        );
        List<String> modes = resolveModes(request != null ? request.modes() : null);
        List<BaselineTaskCase> taskCases = resolveCases(request != null ? request.caseKeys() : null);
        boolean autoStart = request != null && Boolean.TRUE.equals(request.autoStart());
        String priority = request != null ? request.priority() : "high";
        String source = request != null ? request.source() : "eval";
        Map<String, Object> extraMetadata = request != null ? request.metadata() : Map.of();

        List<Task> created = new ArrayList<>();
        for (BaselineTaskCase taskCase : taskCases) {
            for (String mode : modes) {
                LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
                metadata.putAll(taskCase.metadata());
                metadata.putAll(extraMetadata);
                metadata.put("experiment_name", experimentName);
                metadata.put("task_case_key", taskCase.caseKey());
                metadata.put("task_length_bucket", taskCase.taskLengthBucket());
                metadata.put("model_mode", mode);
                metadata.put("baseline_matrix_source", "baseline_v1");
                metadata.put("baseline_case_title", taskCase.title());
                metadata.put("baseline_case_version", "v1");
                metadata.put("baseline_workspace_preconditions", taskCase.workspacePreconditions());
                metadata.put("baseline_acceptance_criteria", taskCase.acceptanceCriteria());
                metadata.put("baseline_expected_artifacts", taskCase.expectedArtifacts());
                metadata.put("baseline_recovery_policy", taskCase.recoveryPolicy());

                Task createdTask = taskService.createTask(new TaskCreateRequest(
                    taskCase.title() + " [" + mode + "]",
                    taskCase.taskType(),
                    source,
                    priority,
                    taskCase.intent(),
                    taskCase.goal(),
                    null,
                    null,
                    metadata,
                    autoStart
                ));
                created.add(createdTask);
            }
        }
        return new ExperimentMatrixBatch(
            experimentName,
            taskCases.stream().map(BaselineTaskCase::caseKey).toList(),
            modes,
            created.size(),
            created
        );
    }

    public ExperimentMatrixSummary summarizeExperiment(String experimentName) {
        String normalizedExperimentName = blankToNull(experimentName);
        if (normalizedExperimentName == null) {
            throw new IllegalArgumentException("experiment_name is required");
        }
        List<ExperimentRunRecord> runs = experimentRunService.listRuns(normalizedExperimentName, null, null, null, 200);
        ExperimentRunSummary rolloutSummary = experimentRunService.summarizeRuns(
            normalizedExperimentName,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );

        List<ExperimentMatrixSummary.ModeSummary> modeSummaries = new ArrayList<>();
        for (String mode : SUPPORTED_MODES) {
            List<ExperimentRunRecord> runsByMode = runs.stream()
                .filter(run -> mode.equals(run.modelMode()))
                .toList();
            int runCount = runsByMode.size();
            int completedCount = (int) runsByMode.stream()
                .filter(run -> "done".equalsIgnoreCase(run.completionStatus()))
                .count();
            int acceptedCount = (int) runsByMode.stream()
                .filter(run -> "accepted".equalsIgnoreCase(run.acceptanceResult()))
                .count();
            int rejectedCount = (int) runsByMode.stream()
                .filter(run -> "rejected".equalsIgnoreCase(run.acceptanceResult()))
                .count();
            int needsFollowupCount = (int) runsByMode.stream()
                .filter(run -> "needs_followup".equalsIgnoreCase(run.acceptanceResult()))
                .count();
            double totalCost = roundToThree(runsByMode.stream()
                .map(ExperimentRunRecord::totalCost)
                .filter(value -> value != null)
                .mapToDouble(Double::doubleValue)
                .sum());
            int totalHandoffs = runsByMode.stream()
                .map(ExperimentRunRecord::handoffCount)
                .filter(value -> value != null)
                .mapToInt(Integer::intValue)
                .sum();
            int totalResumes = runsByMode.stream()
                .map(ExperimentRunRecord::resumeCount)
                .filter(value -> value != null)
                .mapToInt(Integer::intValue)
                .sum();
            int totalHumanGates = runsByMode.stream()
                .map(ExperimentRunRecord::humanGateCount)
                .filter(value -> value != null)
                .mapToInt(Integer::intValue)
                .sum();
            int runsWithRouteData = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(metadata -> Boolean.TRUE.equals(metadataBoolean(metadata, "has_route_evidence")))
                .count();
            int runsWithExecutionJudgment = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(metadata -> Boolean.TRUE.equals(metadataBoolean(metadata, "has_execution_judgment")))
                .count();
            int runsWithCompletionJudgment = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(metadata -> Boolean.TRUE.equals(metadataBoolean(metadata, "has_completion_judgment")))
                .count();
            int runsWithClosedLoopEvidenceChain = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(metadata -> Boolean.TRUE.equals(metadataBoolean(metadata, "has_closed_loop_evidence_chain")))
                .count();
            int runsWithTaskSurfaceRefs = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(this::hasTaskSurfaceRefs)
                .count();
            int runsWithJudgmentSurfaceRefs = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(this::hasJudgmentSurfaceRefs)
                .count();
            int runsWithToolTraceSurfaceRefs = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(this::hasToolTraceSurfaceRefs)
                .count();
            int runsWithLearningHint = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataString(metadata, "preferred_worker_hint"))
                .filter(value -> value != null && !value.isBlank())
                .count();
            int learningHintAppliedCount = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataBoolean(metadata, "learning_hint_applied"))
                .filter(Boolean.TRUE::equals)
                .count();
            double learningHintAppliedRate = runsWithLearningHint == 0
                ? 0.0
                : roundToThree((double) learningHintAppliedCount / runsWithLearningHint);
            int runsWithPromptModeData = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataString(metadata, "prompt_mode"))
                .filter(value -> value != null && !value.isBlank())
                .count();
            int runsWithMountedContextRendered = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataBoolean(metadata, "mounted_context_rendered"))
                .filter(Boolean.TRUE::equals)
                .count();
            int runsWithMountedRenderUsed = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataBoolean(metadata, "mounted_render_used"))
                .filter(Boolean.TRUE::equals)
                .count();
            int runsWithMountedContextInjected = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataBoolean(metadata, "mounted_context_injected"))
                .filter(Boolean.TRUE::equals)
                .count();
            List<Integer> observedMountedContextPanelCounts = runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> firstPositiveInt(
                    metadataInt(metadata, "mounted_context_panel_count"),
                    metadataInt(metadata, "mounted_panel_count")
                ))
                .filter(value -> value != null && value >= 0)
                .toList();
            double mountedContextRenderedRate = runsWithPromptModeData == 0
                ? 0.0
                : roundToThree((double) runsWithMountedContextRendered / runsWithPromptModeData);
            double mountedRenderUsedRate = runsWithPromptModeData == 0
                ? 0.0
                : roundToThree((double) runsWithMountedRenderUsed / runsWithPromptModeData);
            double mountedContextInjectedRate = runsWithPromptModeData == 0
                ? 0.0
                : roundToThree((double) runsWithMountedContextInjected / runsWithPromptModeData);
            double averageMountedContextPanelCount = observedMountedContextPanelCounts.isEmpty()
                ? 0.0
                : roundToThree(observedMountedContextPanelCounts.stream()
                    .mapToInt(Integer::intValue)
                    .average()
                    .orElse(0.0));
            List<Integer> observedMountedContextActiveCounts = metadataInts(
                runsByMode,
                "mounted_active_count"
            );
            List<Integer> observedMountedContextEvidenceCounts = metadataInts(
                runsByMode,
                "mounted_evidence_count"
            );
            double averageMountedContextActiveCount = averageIntList(observedMountedContextActiveCounts);
            double averageMountedContextEvidenceCount = averageIntList(observedMountedContextEvidenceCounts);
            List<Integer> observedMountedContextRenderedObjectCounts = metadataInts(
                runsByMode,
                "mounted_context_rendered_object_count"
            );
            List<Integer> observedMountedContextHiddenObjectCounts = metadataInts(
                runsByMode,
                "mounted_context_hidden_object_count"
            );
            List<Integer> observedMountedContextRenderedSelectionTraceCounts = metadataInts(
                runsByMode,
                "mounted_context_rendered_selection_trace_count"
            );
            List<Integer> observedMountedContextHiddenSelectionTraceCounts = metadataInts(
                runsByMode,
                "mounted_context_hidden_selection_trace_count"
            );
            int runsWithMountedContextBudgetData = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(metadata -> hasAnyMetadataKey(
                    metadata,
                    "mounted_context_rendered_object_count",
                    "mounted_context_hidden_object_count",
                    "mounted_context_rendered_selection_trace_count",
                    "mounted_context_hidden_selection_trace_count",
                    "mounted_context_budget_truncated"
                ))
                .count();
            int runsWithMountedContextBudgetTruncated = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataBoolean(metadata, "mounted_context_budget_truncated"))
                .filter(Boolean.TRUE::equals)
                .count();
            double mountedContextBudgetTruncatedRate = runsWithMountedContextBudgetData == 0
                ? 0.0
                : roundToThree((double) runsWithMountedContextBudgetTruncated / runsWithMountedContextBudgetData);
            double averageMountedContextRenderedObjectCount = averageIntList(observedMountedContextRenderedObjectCounts);
            double averageMountedContextHiddenObjectCount = averageIntList(observedMountedContextHiddenObjectCounts);
            double averageMountedContextRenderedSelectionTraceCount = averageIntList(
                observedMountedContextRenderedSelectionTraceCounts
            );
            double averageMountedContextHiddenSelectionTraceCount = averageIntList(
                observedMountedContextHiddenSelectionTraceCounts
            );
            int runsWithExecutionJudgmentPromptModeData = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataString(metadata, "execution_judgment_prompt_mode"))
                .filter(value -> value != null && !value.isBlank())
                .count();
            int runsWithExecutionJudgmentMountedContextRendered = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataBoolean(metadata, "execution_judgment_mounted_context_rendered"))
                .filter(Boolean.TRUE::equals)
                .count();
            int runsWithExecutionJudgmentMountedRenderUsed = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataBoolean(metadata, "execution_judgment_mounted_render_used"))
                .filter(Boolean.TRUE::equals)
                .count();
            int runsWithExecutionJudgmentMountedContextInjected = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataBoolean(metadata, "execution_judgment_mounted_context_injected"))
                .filter(Boolean.TRUE::equals)
                .count();
            double executionJudgmentMountedContextRenderedRate = runsWithExecutionJudgmentPromptModeData == 0
                ? 0.0
                : roundToThree((double) runsWithExecutionJudgmentMountedContextRendered
                    / runsWithExecutionJudgmentPromptModeData);
            double executionJudgmentMountedRenderUsedRate = runsWithExecutionJudgmentPromptModeData == 0
                ? 0.0
                : roundToThree((double) runsWithExecutionJudgmentMountedRenderUsed
                    / runsWithExecutionJudgmentPromptModeData);
            double executionJudgmentMountedContextInjectedRate = runsWithExecutionJudgmentPromptModeData == 0
                ? 0.0
                : roundToThree((double) runsWithExecutionJudgmentMountedContextInjected
                    / runsWithExecutionJudgmentPromptModeData);
            List<Integer> observedExecutionJudgmentMountedContextActiveCounts = metadataInts(
                runsByMode,
                "execution_judgment_mounted_active_count"
            );
            List<Integer> observedExecutionJudgmentMountedContextEvidenceCounts = metadataInts(
                runsByMode,
                "execution_judgment_mounted_evidence_count"
            );
            double averageExecutionJudgmentMountedContextActiveCount = averageIntList(
                observedExecutionJudgmentMountedContextActiveCounts
            );
            double averageExecutionJudgmentMountedContextEvidenceCount = averageIntList(
                observedExecutionJudgmentMountedContextEvidenceCounts
            );
            List<Integer> observedExecutionJudgmentMountedContextRenderedObjectCounts = metadataInts(
                runsByMode,
                "execution_judgment_mounted_context_rendered_object_count"
            );
            List<Integer> observedExecutionJudgmentMountedContextHiddenObjectCounts = metadataInts(
                runsByMode,
                "execution_judgment_mounted_context_hidden_object_count"
            );
            List<Integer> observedExecutionJudgmentMountedContextRenderedSelectionTraceCounts = metadataInts(
                runsByMode,
                "execution_judgment_mounted_context_rendered_selection_trace_count"
            );
            List<Integer> observedExecutionJudgmentMountedContextHiddenSelectionTraceCounts = metadataInts(
                runsByMode,
                "execution_judgment_mounted_context_hidden_selection_trace_count"
            );
            int runsWithExecutionJudgmentMountedContextBudgetData = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(metadata -> hasAnyMetadataKey(
                    metadata,
                    "execution_judgment_mounted_context_rendered_object_count",
                    "execution_judgment_mounted_context_hidden_object_count",
                    "execution_judgment_mounted_context_rendered_selection_trace_count",
                    "execution_judgment_mounted_context_hidden_selection_trace_count",
                    "execution_judgment_mounted_context_budget_truncated"
                ))
                .count();
            int runsWithExecutionJudgmentMountedContextBudgetTruncated = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataBoolean(metadata, "execution_judgment_mounted_context_budget_truncated"))
                .filter(Boolean.TRUE::equals)
                .count();
            double executionJudgmentMountedContextBudgetTruncatedRate =
                runsWithExecutionJudgmentMountedContextBudgetData == 0
                    ? 0.0
                    : roundToThree((double) runsWithExecutionJudgmentMountedContextBudgetTruncated
                        / runsWithExecutionJudgmentMountedContextBudgetData);
            double averageExecutionJudgmentMountedContextRenderedObjectCount = averageIntList(
                observedExecutionJudgmentMountedContextRenderedObjectCounts
            );
            double averageExecutionJudgmentMountedContextHiddenObjectCount = averageIntList(
                observedExecutionJudgmentMountedContextHiddenObjectCounts
            );
            double averageExecutionJudgmentMountedContextRenderedSelectionTraceCount = averageIntList(
                observedExecutionJudgmentMountedContextRenderedSelectionTraceCounts
            );
            double averageExecutionJudgmentMountedContextHiddenSelectionTraceCount = averageIntList(
                observedExecutionJudgmentMountedContextHiddenSelectionTraceCounts
            );
            int runsWithCompletionJudgmentPromptModeData = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataString(metadata, "completion_judgment_prompt_mode"))
                .filter(value -> value != null && !value.isBlank())
                .count();
            int runsWithCompletionJudgmentMountedContextRendered = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataBoolean(metadata, "completion_judgment_mounted_context_rendered"))
                .filter(Boolean.TRUE::equals)
                .count();
            int runsWithCompletionJudgmentMountedRenderUsed = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataBoolean(metadata, "completion_judgment_mounted_render_used"))
                .filter(Boolean.TRUE::equals)
                .count();
            int runsWithCompletionJudgmentMountedContextInjected = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataBoolean(metadata, "completion_judgment_mounted_context_injected"))
                .filter(Boolean.TRUE::equals)
                .count();
            double completionJudgmentMountedContextRenderedRate = runsWithCompletionJudgmentPromptModeData == 0
                ? 0.0
                : roundToThree((double) runsWithCompletionJudgmentMountedContextRendered
                    / runsWithCompletionJudgmentPromptModeData);
            double completionJudgmentMountedRenderUsedRate = runsWithCompletionJudgmentPromptModeData == 0
                ? 0.0
                : roundToThree((double) runsWithCompletionJudgmentMountedRenderUsed
                    / runsWithCompletionJudgmentPromptModeData);
            double completionJudgmentMountedContextInjectedRate = runsWithCompletionJudgmentPromptModeData == 0
                ? 0.0
                : roundToThree((double) runsWithCompletionJudgmentMountedContextInjected
                    / runsWithCompletionJudgmentPromptModeData);
            List<Integer> observedCompletionJudgmentMountedContextActiveCounts = metadataInts(
                runsByMode,
                "completion_judgment_mounted_active_count"
            );
            List<Integer> observedCompletionJudgmentMountedContextEvidenceCounts = metadataInts(
                runsByMode,
                "completion_judgment_mounted_evidence_count"
            );
            double averageCompletionJudgmentMountedContextActiveCount = averageIntList(
                observedCompletionJudgmentMountedContextActiveCounts
            );
            double averageCompletionJudgmentMountedContextEvidenceCount = averageIntList(
                observedCompletionJudgmentMountedContextEvidenceCounts
            );
            List<Integer> observedCompletionJudgmentMountedContextRenderedObjectCounts = metadataInts(
                runsByMode,
                "completion_judgment_mounted_context_rendered_object_count"
            );
            List<Integer> observedCompletionJudgmentMountedContextHiddenObjectCounts = metadataInts(
                runsByMode,
                "completion_judgment_mounted_context_hidden_object_count"
            );
            List<Integer> observedCompletionJudgmentMountedContextRenderedSelectionTraceCounts = metadataInts(
                runsByMode,
                "completion_judgment_mounted_context_rendered_selection_trace_count"
            );
            List<Integer> observedCompletionJudgmentMountedContextHiddenSelectionTraceCounts = metadataInts(
                runsByMode,
                "completion_judgment_mounted_context_hidden_selection_trace_count"
            );
            int runsWithCompletionJudgmentMountedContextBudgetData = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(metadata -> hasAnyMetadataKey(
                    metadata,
                    "completion_judgment_mounted_context_rendered_object_count",
                    "completion_judgment_mounted_context_hidden_object_count",
                    "completion_judgment_mounted_context_rendered_selection_trace_count",
                    "completion_judgment_mounted_context_hidden_selection_trace_count",
                    "completion_judgment_mounted_context_budget_truncated"
                ))
                .count();
            int runsWithCompletionJudgmentMountedContextBudgetTruncated = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataBoolean(metadata, "completion_judgment_mounted_context_budget_truncated"))
                .filter(Boolean.TRUE::equals)
                .count();
            double completionJudgmentMountedContextBudgetTruncatedRate =
                runsWithCompletionJudgmentMountedContextBudgetData == 0
                    ? 0.0
                    : roundToThree((double) runsWithCompletionJudgmentMountedContextBudgetTruncated
                        / runsWithCompletionJudgmentMountedContextBudgetData);
            double averageCompletionJudgmentMountedContextRenderedObjectCount = averageIntList(
                observedCompletionJudgmentMountedContextRenderedObjectCounts
            );
            double averageCompletionJudgmentMountedContextHiddenObjectCount = averageIntList(
                observedCompletionJudgmentMountedContextHiddenObjectCounts
            );
            double averageCompletionJudgmentMountedContextRenderedSelectionTraceCount = averageIntList(
                observedCompletionJudgmentMountedContextRenderedSelectionTraceCounts
            );
            double averageCompletionJudgmentMountedContextHiddenSelectionTraceCount = averageIntList(
                observedCompletionJudgmentMountedContextHiddenSelectionTraceCounts
            );
            Map<String, Integer> promptModeCounts = countMetadataValues(runsByMode, "prompt_mode");
            Map<String, Integer> executionJudgmentPromptModeCounts = countMetadataValues(
                runsByMode, "execution_judgment_prompt_mode"
            );
            Map<String, Integer> completionJudgmentPromptModeCounts = countMetadataValues(
                runsByMode, "completion_judgment_prompt_mode"
            );
            List<Integer> observedToolChainSteps = runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .map(metadata -> metadataInt(metadata, "tool_chain_step_count"))
                .filter(value -> value != null && value >= 0)
                .toList();
            int runsWithToolChainData = observedToolChainSteps.size();
            double averageToolChainStepCount = runsWithToolChainData == 0
                ? 0.0
                : roundToThree(observedToolChainSteps.stream().mapToInt(Integer::intValue).average().orElse(0.0));
            int maxToolChainStepCount = observedToolChainSteps.stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
            Map<String, Integer> routeSourceCounts = countMetadataValues(runsByMode, "route_source");
            Map<String, Integer> executionActionCounts = countMetadataValues(runsByMode, "execution_judgment_action");
            Map<String, Integer> completionJudgmentStatusCounts = countMetadataValues(
                runsByMode, "completion_judgment_status"
            );
            Map<String, Integer> completionAlignmentLevelCounts = countMetadataValues(
                runsByMode, "completion_alignment_level"
            );
            Map<String, Integer> toolExecutionModeCounts = countMetadataValues(runsByMode, "tool_execution_mode");
            Map<String, Integer> toolChainTerminationReasonCounts = countMetadataValues(
                runsByMode, "tool_chain_termination_reason"
            );
            double averageCost = runCount == 0 ? 0.0 : roundToThree(totalCost / runCount);
            double completionRate = runCount == 0 ? 0.0 : roundToThree((double) completedCount / runCount);
            double acceptanceRate = runCount == 0 ? 0.0 : roundToThree((double) acceptedCount / runCount);
            int orchestratedRunCount = (int) runsByMode.stream()
                .filter(run -> "orchestrated".equalsIgnoreCase(run.modelMode()))
                .count();
            int orchestrationClosedLoopObservedCount = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(metadata -> Boolean.TRUE.equals(metadataBoolean(metadata, "orchestration_closed_loop_observed")))
                .count();
            int runsWithStrongPlannerEvidence = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(this::hasStrongPlannerEvidence)
                .count();
            int runsWithSmallExecutorEvidence = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(this::hasSmallExecutorEvidence)
                .count();
            int runsWithStrongEvaluatorEvidence = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(this::hasStrongEvaluatorEvidence)
                .count();
            int runsWithStrongSmallStrongLoop = (int) runsByMode.stream()
                .map(ExperimentRunRecord::metadata)
                .filter(metadata -> hasStrongPlannerEvidence(metadata)
                    && hasSmallExecutorEvidence(metadata)
                    && hasStrongEvaluatorEvidence(metadata))
                .count();
            Map<String, Integer> evaluatorModelTierCounts = countMetadataValues(runsByMode, "evaluator_model_tier");
            modeSummaries.add(new ExperimentMatrixSummary.ModeSummary(
                mode,
                runCount,
                completedCount,
                acceptedCount,
                rejectedCount,
                needsFollowupCount,
                totalCost,
                averageCost,
                totalHandoffs,
                totalResumes,
                totalHumanGates,
                completionRate,
                acceptanceRate,
                orchestrationClosedLoopObservedCount,
                orchestratedRunCount,
                runsWithStrongPlannerEvidence,
                runsWithSmallExecutorEvidence,
                runsWithStrongEvaluatorEvidence,
                runsWithStrongSmallStrongLoop,
                evaluatorModelTierCounts,
                runsWithRouteData,
                runsWithExecutionJudgment,
                runsWithCompletionJudgment,
                runsWithClosedLoopEvidenceChain,
                runsWithTaskSurfaceRefs,
                runsWithJudgmentSurfaceRefs,
                runsWithToolTraceSurfaceRefs,
                runsWithLearningHint,
                learningHintAppliedCount,
                learningHintAppliedRate,
                runsWithPromptModeData,
                promptModeCounts,
                runsWithMountedContextRendered,
                runsWithMountedRenderUsed,
                runsWithMountedContextInjected,
                mountedContextRenderedRate,
                mountedRenderUsedRate,
                mountedContextInjectedRate,
                averageMountedContextPanelCount,
                averageMountedContextActiveCount,
                averageMountedContextEvidenceCount,
                runsWithMountedContextBudgetData,
                runsWithMountedContextBudgetTruncated,
                mountedContextBudgetTruncatedRate,
                averageMountedContextRenderedObjectCount,
                averageMountedContextHiddenObjectCount,
                averageMountedContextRenderedSelectionTraceCount,
                averageMountedContextHiddenSelectionTraceCount,
                runsWithExecutionJudgmentPromptModeData,
                executionJudgmentPromptModeCounts,
                runsWithExecutionJudgmentMountedContextRendered,
                runsWithExecutionJudgmentMountedRenderUsed,
                runsWithExecutionJudgmentMountedContextInjected,
                executionJudgmentMountedContextRenderedRate,
                executionJudgmentMountedRenderUsedRate,
                executionJudgmentMountedContextInjectedRate,
                averageExecutionJudgmentMountedContextActiveCount,
                averageExecutionJudgmentMountedContextEvidenceCount,
                runsWithExecutionJudgmentMountedContextBudgetData,
                runsWithExecutionJudgmentMountedContextBudgetTruncated,
                executionJudgmentMountedContextBudgetTruncatedRate,
                averageExecutionJudgmentMountedContextRenderedObjectCount,
                averageExecutionJudgmentMountedContextHiddenObjectCount,
                averageExecutionJudgmentMountedContextRenderedSelectionTraceCount,
                averageExecutionJudgmentMountedContextHiddenSelectionTraceCount,
                runsWithCompletionJudgmentPromptModeData,
                completionJudgmentPromptModeCounts,
                runsWithCompletionJudgmentMountedContextRendered,
                runsWithCompletionJudgmentMountedRenderUsed,
                runsWithCompletionJudgmentMountedContextInjected,
                completionJudgmentMountedContextRenderedRate,
                completionJudgmentMountedRenderUsedRate,
                completionJudgmentMountedContextInjectedRate,
                averageCompletionJudgmentMountedContextActiveCount,
                averageCompletionJudgmentMountedContextEvidenceCount,
                runsWithCompletionJudgmentMountedContextBudgetData,
                runsWithCompletionJudgmentMountedContextBudgetTruncated,
                completionJudgmentMountedContextBudgetTruncatedRate,
                averageCompletionJudgmentMountedContextRenderedObjectCount,
                averageCompletionJudgmentMountedContextHiddenObjectCount,
                averageCompletionJudgmentMountedContextRenderedSelectionTraceCount,
                averageCompletionJudgmentMountedContextHiddenSelectionTraceCount,
                routeSourceCounts,
                executionActionCounts,
                completionJudgmentStatusCounts,
                completionAlignmentLevelCounts,
                runsWithToolChainData,
                averageToolChainStepCount,
                maxToolChainStepCount,
                toolExecutionModeCounts,
                toolChainTerminationReasonCounts
            ));
        }

        Map<String, List<ExperimentRunRecord>> runsByCaseKey = runs.stream()
            .collect(Collectors.groupingBy(
                ExperimentRunRecord::taskCaseKey,
                LinkedHashMap::new,
                Collectors.toList()
            ));

        Map<String, Integer> baselineOrder = new LinkedHashMap<>();
        for (int i = 0; i < BASELINE_CASES.size(); i++) {
            baselineOrder.put(BASELINE_CASES.get(i).caseKey(), i);
        }

        List<ExperimentMatrixSummary.CaseComparison> caseComparisons = runsByCaseKey.entrySet().stream()
            .sorted((left, right) -> Integer.compare(
                baselineOrder.getOrDefault(left.getKey(), Integer.MAX_VALUE),
                baselineOrder.getOrDefault(right.getKey(), Integer.MAX_VALUE)
            ))
            .map(entry -> {
                List<ExperimentRunRecord> caseRuns = entry.getValue();
                ExperimentRunRecord sample = caseRuns.get(0);
                LinkedHashMap<String, ExperimentRunRecord> runsByMode = new LinkedHashMap<>();
                for (String mode : SUPPORTED_MODES) {
                    caseRuns.stream()
                        .filter(run -> mode.equals(run.modelMode()))
                        .findFirst()
                        .ifPresent(run -> runsByMode.put(mode, run));
                }
                caseRuns.stream()
                    .filter(run -> !runsByMode.containsKey(run.modelMode()))
                    .forEach(run -> runsByMode.put(run.modelMode(), run));
                return new ExperimentMatrixSummary.CaseComparison(
                    entry.getKey(),
                    firstNonBlank(sample.taskTitle(), sample.taskId()),
                    firstNonBlank(sample.taskLengthBucket(), "unspecified"),
                    runsByMode
                );
            })
            .toList();

        return new ExperimentMatrixSummary(
            normalizedExperimentName,
            runs.size(),
            SUPPORTED_MODES,
            modeSummaries,
            caseComparisons,
            rolloutSummary.promptModeSummaries(),
            rolloutSummary.executionJudgmentPromptModeSummaries(),
            rolloutSummary.completionJudgmentPromptModeSummaries()
        );
    }

    private List<String> resolveModes(List<String> requestedModes) {
        if (requestedModes == null || requestedModes.isEmpty()) {
            return SUPPORTED_MODES;
        }
        LinkedHashMap<String, String> deduped = new LinkedHashMap<>();
        for (String requestedMode : requestedModes) {
            String normalized = normalizeMode(requestedMode);
            if (normalized == null) {
                throw new IllegalArgumentException("unsupported model mode: " + requestedMode);
            }
            deduped.put(normalized, normalized);
        }
        return deduped.values().stream().toList();
    }

    private List<BaselineTaskCase> resolveCases(List<String> requestedCaseKeys) {
        if (requestedCaseKeys == null || requestedCaseKeys.isEmpty()) {
            return BASELINE_CASES;
        }
        Map<String, BaselineTaskCase> indexed = BASELINE_CASES.stream()
            .collect(Collectors.toMap(BaselineTaskCase::caseKey, taskCase -> taskCase, (left, right) -> left, LinkedHashMap::new));
        List<BaselineTaskCase> selected = new ArrayList<>();
        for (String caseKey : requestedCaseKeys) {
            BaselineTaskCase taskCase = indexed.get(caseKey);
            if (taskCase == null) {
                throw new IllegalArgumentException("unknown baseline case: " + caseKey);
            }
            selected.add(taskCase);
        }
        return selected;
    }

    private String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        return SUPPORTED_MODES.contains(normalized) ? normalized : null;
    }

    private static BaselineTaskCase baselineCase(String caseKey,
                                                 String title,
                                                 String lengthBucket,
                                                 String intent,
                                                 String goal) {
        List<String> workspacePreconditions = switch (lengthBucket) {
            case "short" -> List.of(
                "Use the current repository worktree as the only source of truth.",
                "Limit the proposed change to one focused code path and its nearest regression test."
            );
            case "medium" -> List.of(
                "Use the current repository worktree as the only source of truth.",
                "Assume a clean temporary SQLite database is available for service or handler tests.",
                "Keep the implementation within the existing HttpServer/Jdbi/record architecture."
            );
            case "long" -> List.of(
                "Use the current repository worktree as the only source of truth.",
                "Plan across routing, execution, judgment, packet, and UI evidence surfaces when relevant.",
                "Preserve existing dirty worktree changes that are unrelated to the case."
            );
            default -> List.of("Use the current repository worktree as the only source of truth.");
        };
        List<String> acceptanceCriteria = switch (lengthBucket) {
            case "short" -> List.of(
                "The answer identifies the exact file or contract surface to change.",
                "The proposed assertion is concrete enough to become a regression test.",
                "No broad architecture or unrelated behavior change is introduced."
            );
            case "medium" -> List.of(
                "The endpoint, trace, or packet contract is described with stable field names.",
                "At least one regression test or probe path is named.",
                "The result explains how to verify the behavior from persisted or HTTP-visible evidence."
            );
            case "long" -> List.of(
                "The work is split into ordered phases with explicit handoff and recovery boundaries.",
                "The comparison or continuity evidence can be read from experiment_run, live_flow, packet, or tool trace surfaces.",
                "The final output states what remains manual and what can be accepted automatically."
            );
            default -> List.of("The result has an explicit, verifiable acceptance condition.");
        };
        List<String> expectedArtifacts = switch (lengthBucket) {
            case "short" -> List.of("fix_plan", "regression_assertion");
            case "medium" -> List.of("contract_summary", "verification_path", "test_plan");
            case "long" -> List.of("phased_plan", "risk_register", "acceptance_gate");
            default -> List.of("evaluation_result");
        };
        String recoveryPolicy = switch (lengthBucket) {
            case "short" -> "retry_once_then_human_review";
            case "medium" -> "retry_once_or_auto_handoff_if_provider_transient";
            case "long" -> "allow_pause_resume_and_handoff_with_packet_before_human_gate";
            default -> "manual_review";
        };
        return new BaselineTaskCase(
            caseKey,
            title,
            "coding",
            lengthBucket,
            intent,
            goal,
            workspacePreconditions,
            acceptanceCriteria,
            expectedArtifacts,
            recoveryPolicy,
            Map.of(
                "task_pack", "baseline_matrix_v1",
                "length_bucket", lengthBucket
            )
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private Integer metadataInt(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean metadataBoolean(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            String normalized = text.trim();
            if ("true".equalsIgnoreCase(normalized)) {
                return true;
            }
            if ("false".equalsIgnoreCase(normalized)) {
                return false;
            }
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return null;
    }

    private Map<String, Integer> countMetadataValues(List<ExperimentRunRecord> runs, String key) {
        if (runs == null || runs.isEmpty() || key == null || key.isBlank()) {
            return Map.of();
        }
        return runs.stream()
            .map(ExperimentRunRecord::metadata)
            .map(metadata -> metadataString(metadata, key))
            .filter(value -> value != null && !value.isBlank())
            .collect(Collectors.groupingBy(
                value -> value,
                LinkedHashMap::new,
                Collectors.summingInt(value -> 1)
            ))
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private List<Integer> metadataInts(List<ExperimentRunRecord> runs, String key) {
        if (runs == null || runs.isEmpty() || key == null || key.isBlank()) {
            return List.of();
        }
        return runs.stream()
            .map(ExperimentRunRecord::metadata)
            .map(metadata -> metadataInt(metadata, key))
            .filter(value -> value != null && value >= 0)
            .toList();
    }

    private boolean hasAnyMetadataKey(Map<String, Object> metadata, String... keys) {
        if (metadata == null || metadata.isEmpty() || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (key != null && !key.isBlank() && metadata.containsKey(key) && metadata.get(key) != null) {
                return true;
            }
        }
        return false;
    }

    private double averageIntList(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        return roundToThree(values.stream().mapToInt(Integer::intValue).average().orElse(0.0));
    }

    private Integer firstPositiveInt(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null && value >= 0) {
                return value;
            }
        }
        return null;
    }

    private boolean hasTaskSurfaceRefs(Map<String, Object> metadata) {
        Map<String, Object> refs = taskSurfaceRefs(metadata);
        return metadataString(refs, "live_flow_path") != null
            && metadataString(refs, "harness_trace_path") != null;
    }

    private boolean hasJudgmentSurfaceRefs(Map<String, Object> metadata) {
        return metadataString(taskSurfaceRefs(metadata), "judgment_trace_path") != null;
    }

    private boolean hasToolTraceSurfaceRefs(Map<String, Object> metadata) {
        return metadataString(taskSurfaceRefs(metadata), "tool_trace_path") != null;
    }

    private boolean hasStrongPlannerEvidence(Map<String, Object> metadata) {
        String plannerWorker = firstNonBlank(
            metadataString(metadata, "planner_worker"),
            metadataString(metadata, "planning_worker")
        );
        String plannerTier = firstNonBlank(
            metadataString(metadata, "planner_model_tier"),
            metadataString(metadata, "planning_model_tier")
        );
        String orchestrationStage = metadataString(metadata, "orchestration_stage");
        String selectedTier = metadataString(metadata, "selected_model_tier");
        return "strong".equalsIgnoreCase(plannerTier)
            || (plannerWorker != null && !plannerWorker.isBlank()
            && !"small".equalsIgnoreCase(plannerTier))
            || (orchestrationStage != null
            && orchestrationStage.toLowerCase().startsWith("plan")
            && "strong".equalsIgnoreCase(selectedTier));
    }

    private boolean hasSmallExecutorEvidence(Map<String, Object> metadata) {
        String executorWorker = firstNonBlank(
            metadataString(metadata, "executor_worker"),
            metadataString(metadata, "execution_worker")
        );
        String executorTier = firstNonBlank(
            metadataString(metadata, "executor_model_tier"),
            metadataString(metadata, "execution_model_tier")
        );
        String selectedTier = metadataString(metadata, "selected_model_tier");
        String executionRole = metadataString(metadata, "execution_role");
        return "small".equalsIgnoreCase(executorTier)
            || (executorWorker != null && !executorWorker.isBlank()
            && !"strong".equalsIgnoreCase(executorTier))
            || ("small".equalsIgnoreCase(selectedTier)
            && (executionRole == null || !"planner".equalsIgnoreCase(executionRole)));
    }

    private boolean hasStrongEvaluatorEvidence(Map<String, Object> metadata) {
        return "strong".equalsIgnoreCase(metadataString(metadata, "evaluator_model_tier"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> taskSurfaceRefs(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Object rawEvidence = metadata.get("closed_loop_evidence");
        if (!(rawEvidence instanceof Map<?, ?> evidenceMap)) {
            return Map.of();
        }
        Object rawRefs = evidenceMap.get("task_surface_refs");
        if (!(rawRefs instanceof Map<?, ?> refsMap)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : refsMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                normalized.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return normalized;
    }

    private double roundToThree(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
