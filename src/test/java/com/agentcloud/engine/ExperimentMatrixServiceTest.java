package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Decision;
import com.agentcloud.model.ExperimentMatrixCreateRequest;
import com.agentcloud.model.ExperimentMatrixSummary;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ExperimentRunDao;
import com.agentcloud.store.ResumePacketDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.store.ToolInvocationDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentMatrixServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesBuiltInThreeByThreeBaselineCases() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("experiment-matrix-cases.db"))) {
            ExperimentMatrixService service = service(db).experimentMatrixService();

            var cases = service.listBaselineCases();

            assertEquals(9, cases.size());
            assertEquals(3, cases.stream().filter(taskCase -> "short".equals(taskCase.taskLengthBucket())).count());
            assertEquals(3, cases.stream().filter(taskCase -> "medium".equals(taskCase.taskLengthBucket())).count());
            assertEquals(3, cases.stream().filter(taskCase -> "long".equals(taskCase.taskLengthBucket())).count());
            assertTrue(cases.stream().allMatch(taskCase -> !taskCase.workspacePreconditions().isEmpty()));
            assertTrue(cases.stream().allMatch(taskCase -> !taskCase.acceptanceCriteria().isEmpty()));
            assertTrue(cases.stream().allMatch(taskCase -> !taskCase.expectedArtifacts().isEmpty()));
            assertTrue(cases.stream().allMatch(taskCase -> !taskCase.recoveryPolicy().isBlank()));
        }
    }

    @Test
    void createsComparableRunsAndSummarizesThemByModeAndCase() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("experiment-matrix-summary.db"))) {
            TestHarness harness = service(db);
            ExperimentMatrixService service = harness.experimentMatrixService();
            TaskService taskService = harness.taskService();

            var batch = service.createBaselineRuns(new ExperimentMatrixCreateRequest(
                "baseline-matrix-a",
                List.of("short-001", "long-001"),
                List.of("strong_only", "small_only", "orchestrated"),
                "high",
                "eval",
                false,
                Map.of("requested_by", "test")
            ));

            assertEquals(6, batch.createdRunCount());
            assertEquals(6, batch.tasks().size());
            assertEquals(List.of("strong_only", "small_only", "orchestrated"), batch.requestedModes());

            Map<String, Task> byCaseAndMode = batch.tasks().stream().collect(Collectors.toMap(
                task -> task.metadata().get("task_case_key") + "::" + task.metadata().get("model_mode"),
                Function.identity()
            ));

            Task strongShort = byCaseAndMode.get("short-001::strong_only");
            Task smallShort = byCaseAndMode.get("short-001::small_only");
            Task orchestratedShort = byCaseAndMode.get("short-001::orchestrated");
            assertNotNull(strongShort);
            assertNotNull(smallShort);
            assertNotNull(orchestratedShort);
            assertEquals("baseline-matrix-a", strongShort.metadata().get("experiment_name"));
            assertEquals("short", strongShort.metadata().get("task_length_bucket"));
            assertEquals("baseline_v1", strongShort.metadata().get("baseline_matrix_source"));
            assertEquals("retry_once_then_human_review", strongShort.metadata().get("baseline_recovery_policy"));
            assertTrue(strongShort.metadata().get("baseline_workspace_preconditions") instanceof List<?>);
            assertTrue(strongShort.metadata().get("baseline_acceptance_criteria") instanceof List<?>);
            assertTrue(strongShort.metadata().get("baseline_expected_artifacts") instanceof List<?>);
            assertTrue(((List<?>) strongShort.metadata().get("baseline_acceptance_criteria")).stream()
                .anyMatch(value -> String.valueOf(value).contains("regression test")));

            harness.artifactDao().insert(new com.agentcloud.model.Artifact(
                IdGenerator.newId("art"),
                strongShort.sessionId(),
                strongShort.id(),
                Instant.now(),
                "worker_artifact",
                "strong single tool",
                null,
                null,
                "single tool strong output",
                Map.ofEntries(
                    Map.entry("route_source", "capability_match"),
                    Map.entry("execution_judgment_action", "done"),
                    Map.entry("completion_judgment_status", "done"),
                    Map.entry("completion_alignment_level", "high"),
                    Map.entry("has_route_evidence", true),
                    Map.entry("has_execution_judgment", true),
                    Map.entry("has_completion_judgment", true),
                    Map.entry("has_closed_loop_evidence_chain", true),
                    Map.entry("prompt_mode", "active_context_only"),
                    Map.entry("mounted_context_rendered", false),
                    Map.entry("mounted_render_used", false),
                    Map.entry("mounted_context_injected", false),
                    Map.entry("mounted_active_count", 0),
                    Map.entry("mounted_evidence_count", 0),
                    Map.entry("mounted_context_rendered_object_count", 0),
                    Map.entry("mounted_context_hidden_object_count", 0),
                    Map.entry("mounted_context_rendered_selection_trace_count", 0),
                    Map.entry("mounted_context_hidden_selection_trace_count", 0),
                    Map.entry("mounted_context_budget_truncated", false),
                    Map.entry("latest_worker_metadata", Map.of(
                        "tool_execution_mode", "single_tool_round",
                        "tool_chain_step_count", 1,
                        "tool_chain_termination_reason", "planner_no_additional_tool"
                    ))
                )
            ));
            harness.artifactDao().insert(new com.agentcloud.model.Artifact(
                IdGenerator.newId("art"),
                smallShort.sessionId(),
                smallShort.id(),
                Instant.now(),
                "worker_artifact",
                "small guarded multi tool",
                null,
                null,
                "multi tool small output",
                Map.ofEntries(
                    Map.entry("route_source", "capability_match"),
                    Map.entry("preferred_worker_hint", "codex"),
                    Map.entry("learning_hint_applied", false),
                    Map.entry("fallback_reason", "hint filtered by model tier"),
                    Map.entry("execution_judgment_action", "escalate"),
                    Map.entry("completion_judgment_status", "misaligned"),
                    Map.entry("completion_alignment_level", "low"),
                    Map.entry("has_route_evidence", true),
                    Map.entry("has_execution_judgment", true),
                    Map.entry("has_completion_judgment", true),
                    Map.entry("has_closed_loop_evidence_chain", true),
                    Map.entry("prompt_mode", "mounted_context_shadow"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", false),
                    Map.entry("mounted_context_panel_count", 5),
                    Map.entry("mounted_active_count", 2),
                    Map.entry("mounted_evidence_count", 1),
                    Map.entry("mounted_context_rendered_object_count", 6),
                    Map.entry("mounted_context_hidden_object_count", 2),
                    Map.entry("mounted_context_rendered_selection_trace_count", 1),
                    Map.entry("mounted_context_hidden_selection_trace_count", 1),
                    Map.entry("mounted_context_budget_truncated", true),
                    Map.entry("latest_worker_metadata", Map.of(
                        "tool_execution_mode", "multi_tool_round",
                        "tool_chain_step_count", 4,
                        "tool_chain_termination_reason", "repeated_tool_guard"
                    ))
                )
            ));
            harness.artifactDao().insert(new com.agentcloud.model.Artifact(
                IdGenerator.newId("art"),
                orchestratedShort.sessionId(),
                orchestratedShort.id(),
                Instant.now(),
                "worker_artifact",
                "orchestrated two-step tool chain",
                null,
                null,
                "multi tool orchestrated output",
                Map.ofEntries(
                    Map.entry("route_source", "learning_memory"),
                    Map.entry("preferred_worker_hint", "kimi"),
                    Map.entry("learning_hint_applied", true),
                    Map.entry("orchestration_closed_loop_observed", true),
                    Map.entry("planner_worker", "codex"),
                    Map.entry("planner_model_tier", "strong"),
                    Map.entry("executor_worker", "kimi"),
                    Map.entry("executor_model_tier", "small"),
                    Map.entry("evaluator_model_tier", "strong"),
                    Map.entry("execution_judgment_action", "checkpoint"),
                    Map.entry("completion_judgment_status", "partially_done"),
                    Map.entry("completion_alignment_level", "medium"),
                    Map.entry("has_route_evidence", true),
                    Map.entry("has_execution_judgment", true),
                    Map.entry("has_completion_judgment", true),
                    Map.entry("has_closed_loop_evidence_chain", true),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("mounted_context_panel_count", 7),
                    Map.entry("mounted_active_count", 4),
                    Map.entry("mounted_evidence_count", 2),
                    Map.entry("mounted_context_rendered_object_count", 9),
                    Map.entry("mounted_context_hidden_object_count", 1),
                    Map.entry("mounted_context_rendered_selection_trace_count", 3),
                    Map.entry("mounted_context_hidden_selection_trace_count", 0),
                    Map.entry("mounted_context_budget_truncated", true),
                    Map.entry("closed_loop_evidence", Map.of(
                        "task_surface_refs", Map.of(
                            "task_id", orchestratedShort.id(),
                            "live_flow_path", "/api/v1/tasks/" + orchestratedShort.id() + "/live_flow",
                            "runtime_context_path", "/api/v1/tasks/" + orchestratedShort.id() + "/runtime_context",
                            "harness_trace_path", "/api/v1/tasks/" + orchestratedShort.id() + "/harness_trace",
                            "judgment_trace_path", "/api/v1/tasks/" + orchestratedShort.id() + "/judgment_trace",
                            "tool_trace_path", "/api/v1/tasks/" + orchestratedShort.id() + "/tool_trace"
                        )
                    )),
                    Map.entry("latest_worker_metadata", Map.of(
                        "tool_execution_mode", "multi_tool_round",
                        "tool_chain_step_count", 2,
                        "tool_chain_termination_reason", "planner_no_additional_tool"
                    ))
                )
            ));

            harness.insertJudgments(strongShort, "done", "done", "high");
            harness.insertJudgments(smallShort, "escalate", "misaligned", "low");
            harness.insertJudgments(orchestratedShort, "checkpoint", "partially_done", "medium");

            taskService.updateTaskState(strongShort.id(), "done", "baseline strong completed");
            taskService.updateTaskState(smallShort.id(), "failed", "baseline small stalled");
            taskService.updateTaskState(orchestratedShort.id(), "waiting_human", "baseline orchestration needs review");

            ExperimentMatrixSummary summary = service.summarizeExperiment("baseline-matrix-a");

            assertEquals(6, summary.totalRuns());
            assertEquals(2, summary.caseComparisons().size());
            assertEquals(3, summary.promptModeSummaries().size());
            assertEquals(1, summary.promptModeSummaries().get("mounted_context_primary").runCount());
            assertEquals(1, summary.promptModeSummaries().get("mounted_context_primary").mountedContextInjectedCount());
            assertEquals(1, summary.promptModeSummaries().get("mounted_context_shadow").mountedRenderUsedCount());
            assertEquals(3, summary.executionJudgmentPromptModeSummaries().size());
            assertEquals(1, summary.executionJudgmentPromptModeSummaries().get("active_context_only").runCount());
            assertEquals(1.0, summary.executionJudgmentPromptModeSummaries()
                .get("mounted_context_shadow").mountedRenderUsedRate());
            assertEquals(3, summary.completionJudgmentPromptModeSummaries().size());
            assertEquals(0, summary.completionJudgmentPromptModeSummaries()
                .get("mounted_context_shadow").mountedContextInjectedCount());
            assertEquals(9.0, summary.completionJudgmentPromptModeSummaries()
                .get("mounted_context_primary").averageMountedContextRenderedObjectCount());

            Map<String, ExperimentMatrixSummary.ModeSummary> modeSummaries = summary.modeSummaries().stream()
                .collect(Collectors.toMap(ExperimentMatrixSummary.ModeSummary::modelMode, Function.identity()));
            assertEquals(2, modeSummaries.get("strong_only").runCount());
            assertEquals(1, modeSummaries.get("strong_only").completedCount());
            assertEquals(1, modeSummaries.get("strong_only").acceptedCount());
            assertEquals(1, modeSummaries.get("strong_only").runsWithRouteData());
            assertEquals(1, modeSummaries.get("strong_only").runsWithExecutionJudgment());
            assertEquals(1, modeSummaries.get("strong_only").runsWithCompletionJudgment());
            assertEquals(1, modeSummaries.get("strong_only").runsWithClosedLoopEvidenceChain());
            assertEquals(2, modeSummaries.get("strong_only").runsWithTaskSurfaceRefs());
            assertEquals(1, modeSummaries.get("strong_only").runsWithJudgmentSurfaceRefs());
            assertEquals(0, modeSummaries.get("strong_only").runsWithToolTraceSurfaceRefs());
            assertEquals(1, modeSummaries.get("strong_only").routeSourceCounts().get("capability_match"));
            assertEquals(1, modeSummaries.get("strong_only").executionActionCounts().get("done"));
            assertEquals(1, modeSummaries.get("strong_only").completionJudgmentStatusCounts().get("done"));
            assertEquals(1, modeSummaries.get("strong_only").completionAlignmentLevelCounts().get("high"));
            assertEquals(0, modeSummaries.get("strong_only").runsWithLearningHint());
            assertEquals(0, modeSummaries.get("strong_only").learningHintAppliedCount());
            assertEquals(0.0, modeSummaries.get("strong_only").learningHintAppliedRate());
            assertEquals(1, modeSummaries.get("strong_only").runsWithPromptModeData());
            assertEquals(1, modeSummaries.get("strong_only").promptModeCounts().get("active_context_only"));
            assertEquals(0, modeSummaries.get("strong_only").runsWithMountedContextRendered());
            assertEquals(0, modeSummaries.get("strong_only").runsWithMountedRenderUsed());
            assertEquals(0, modeSummaries.get("strong_only").runsWithMountedContextInjected());
            assertEquals(0.0, modeSummaries.get("strong_only").mountedContextRenderedRate());
            assertEquals(0.0, modeSummaries.get("strong_only").mountedRenderUsedRate());
            assertEquals(0.0, modeSummaries.get("strong_only").mountedContextInjectedRate());
            assertEquals(0.0, modeSummaries.get("strong_only").averageMountedContextPanelCount());
            assertEquals(0.0, modeSummaries.get("strong_only").averageMountedContextActiveCount());
            assertEquals(0.0, modeSummaries.get("strong_only").averageMountedContextEvidenceCount());
            assertEquals(1, modeSummaries.get("strong_only").runsWithMountedContextBudgetData());
            assertEquals(0, modeSummaries.get("strong_only").runsWithMountedContextBudgetTruncated());
            assertEquals(0.0, modeSummaries.get("strong_only").mountedContextBudgetTruncatedRate());
            assertEquals(0.0, modeSummaries.get("strong_only").averageMountedContextRenderedObjectCount());
            assertEquals(0.0, modeSummaries.get("strong_only").averageMountedContextHiddenObjectCount());
            assertEquals(1, modeSummaries.get("strong_only").runsWithExecutionJudgmentPromptModeData());
            assertEquals(1, modeSummaries.get("strong_only").executionJudgmentPromptModeCounts().get("active_context_only"));
            assertEquals(0, modeSummaries.get("strong_only").runsWithExecutionJudgmentMountedContextRendered());
            assertEquals(0, modeSummaries.get("strong_only").runsWithExecutionJudgmentMountedRenderUsed());
            assertEquals(0, modeSummaries.get("strong_only").runsWithExecutionJudgmentMountedContextInjected());
            assertEquals(0.0, modeSummaries.get("strong_only").executionJudgmentMountedRenderUsedRate());
            assertEquals(0.0, modeSummaries.get("strong_only").averageExecutionJudgmentMountedContextActiveCount());
            assertEquals(0.0, modeSummaries.get("strong_only").averageExecutionJudgmentMountedContextEvidenceCount());
            assertEquals(1, modeSummaries.get("strong_only").runsWithExecutionJudgmentMountedContextBudgetData());
            assertEquals(0, modeSummaries.get("strong_only").runsWithExecutionJudgmentMountedContextBudgetTruncated());
            assertEquals(1, modeSummaries.get("strong_only").runsWithCompletionJudgmentPromptModeData());
            assertEquals(1, modeSummaries.get("strong_only").completionJudgmentPromptModeCounts().get("active_context_only"));
            assertEquals(0, modeSummaries.get("strong_only").runsWithCompletionJudgmentMountedContextRendered());
            assertEquals(0, modeSummaries.get("strong_only").runsWithCompletionJudgmentMountedRenderUsed());
            assertEquals(0, modeSummaries.get("strong_only").runsWithCompletionJudgmentMountedContextInjected());
            assertEquals(0.0, modeSummaries.get("strong_only").completionJudgmentMountedRenderUsedRate());
            assertEquals(0.0, modeSummaries.get("strong_only").averageCompletionJudgmentMountedContextActiveCount());
            assertEquals(0.0, modeSummaries.get("strong_only").averageCompletionJudgmentMountedContextEvidenceCount());
            assertEquals(1, modeSummaries.get("strong_only").runsWithCompletionJudgmentMountedContextBudgetData());
            assertEquals(0, modeSummaries.get("strong_only").runsWithCompletionJudgmentMountedContextBudgetTruncated());
            assertEquals(1, modeSummaries.get("strong_only").runsWithToolChainData());
            assertEquals(1.0, modeSummaries.get("strong_only").averageToolChainStepCount());
            assertEquals(1, modeSummaries.get("strong_only").maxToolChainStepCount());
            assertEquals(1, modeSummaries.get("strong_only").toolExecutionModeCounts().get("single_tool_round"));
            assertEquals(2, modeSummaries.get("small_only").runCount());
            assertEquals(1, modeSummaries.get("small_only").rejectedCount());
            assertEquals(1, modeSummaries.get("small_only").runsWithRouteData());
            assertEquals(1, modeSummaries.get("small_only").runsWithExecutionJudgment());
            assertEquals(1, modeSummaries.get("small_only").runsWithCompletionJudgment());
            assertEquals(1, modeSummaries.get("small_only").runsWithClosedLoopEvidenceChain());
            assertEquals(2, modeSummaries.get("small_only").runsWithTaskSurfaceRefs());
            assertEquals(1, modeSummaries.get("small_only").runsWithJudgmentSurfaceRefs());
            assertEquals(0, modeSummaries.get("small_only").runsWithToolTraceSurfaceRefs());
            assertEquals(1, modeSummaries.get("small_only").routeSourceCounts().get("capability_match"));
            assertEquals(1, modeSummaries.get("small_only").executionActionCounts().get("escalate"));
            assertEquals(1, modeSummaries.get("small_only").completionJudgmentStatusCounts().get("misaligned"));
            assertEquals(1, modeSummaries.get("small_only").completionAlignmentLevelCounts().get("low"));
            assertEquals(1, modeSummaries.get("small_only").runsWithLearningHint());
            assertEquals(0, modeSummaries.get("small_only").learningHintAppliedCount());
            assertEquals(0.0, modeSummaries.get("small_only").learningHintAppliedRate());
            assertEquals(1, modeSummaries.get("small_only").runsWithPromptModeData());
            assertEquals(1, modeSummaries.get("small_only").promptModeCounts().get("mounted_context_shadow"));
            assertEquals(1, modeSummaries.get("small_only").runsWithMountedContextRendered());
            assertEquals(1, modeSummaries.get("small_only").runsWithMountedRenderUsed());
            assertEquals(0, modeSummaries.get("small_only").runsWithMountedContextInjected());
            assertEquals(1.0, modeSummaries.get("small_only").mountedContextRenderedRate());
            assertEquals(1.0, modeSummaries.get("small_only").mountedRenderUsedRate());
            assertEquals(0.0, modeSummaries.get("small_only").mountedContextInjectedRate());
            assertEquals(5.0, modeSummaries.get("small_only").averageMountedContextPanelCount());
            assertEquals(2.0, modeSummaries.get("small_only").averageMountedContextActiveCount());
            assertEquals(1.0, modeSummaries.get("small_only").averageMountedContextEvidenceCount());
            assertEquals(1, modeSummaries.get("small_only").runsWithMountedContextBudgetData());
            assertEquals(1, modeSummaries.get("small_only").runsWithMountedContextBudgetTruncated());
            assertEquals(1.0, modeSummaries.get("small_only").mountedContextBudgetTruncatedRate());
            assertEquals(6.0, modeSummaries.get("small_only").averageMountedContextRenderedObjectCount());
            assertEquals(2.0, modeSummaries.get("small_only").averageMountedContextHiddenObjectCount());
            assertEquals(1.0, modeSummaries.get("small_only").averageMountedContextRenderedSelectionTraceCount());
            assertEquals(1.0, modeSummaries.get("small_only").averageMountedContextHiddenSelectionTraceCount());
            assertEquals(1, modeSummaries.get("small_only").runsWithExecutionJudgmentPromptModeData());
            assertEquals(1, modeSummaries.get("small_only").executionJudgmentPromptModeCounts().get("mounted_context_shadow"));
            assertEquals(1, modeSummaries.get("small_only").runsWithExecutionJudgmentMountedContextRendered());
            assertEquals(1, modeSummaries.get("small_only").runsWithExecutionJudgmentMountedRenderUsed());
            assertEquals(0, modeSummaries.get("small_only").runsWithExecutionJudgmentMountedContextInjected());
            assertEquals(1.0, modeSummaries.get("small_only").executionJudgmentMountedRenderUsedRate());
            assertEquals(2.0, modeSummaries.get("small_only").averageExecutionJudgmentMountedContextActiveCount());
            assertEquals(1.0, modeSummaries.get("small_only").averageExecutionJudgmentMountedContextEvidenceCount());
            assertEquals(1, modeSummaries.get("small_only").runsWithExecutionJudgmentMountedContextBudgetData());
            assertEquals(1, modeSummaries.get("small_only").runsWithExecutionJudgmentMountedContextBudgetTruncated());
            assertEquals(1.0, modeSummaries.get("small_only").executionJudgmentMountedContextBudgetTruncatedRate());
            assertEquals(1, modeSummaries.get("small_only").runsWithCompletionJudgmentPromptModeData());
            assertEquals(1, modeSummaries.get("small_only").completionJudgmentPromptModeCounts().get("mounted_context_shadow"));
            assertEquals(1, modeSummaries.get("small_only").runsWithCompletionJudgmentMountedContextRendered());
            assertEquals(1, modeSummaries.get("small_only").runsWithCompletionJudgmentMountedRenderUsed());
            assertEquals(0, modeSummaries.get("small_only").runsWithCompletionJudgmentMountedContextInjected());
            assertEquals(1.0, modeSummaries.get("small_only").completionJudgmentMountedRenderUsedRate());
            assertEquals(2.0, modeSummaries.get("small_only").averageCompletionJudgmentMountedContextActiveCount());
            assertEquals(1.0, modeSummaries.get("small_only").averageCompletionJudgmentMountedContextEvidenceCount());
            assertEquals(1, modeSummaries.get("small_only").runsWithCompletionJudgmentMountedContextBudgetData());
            assertEquals(1, modeSummaries.get("small_only").runsWithCompletionJudgmentMountedContextBudgetTruncated());
            assertEquals(1.0, modeSummaries.get("small_only").completionJudgmentMountedContextBudgetTruncatedRate());
            assertEquals(1, modeSummaries.get("small_only").runsWithToolChainData());
            assertEquals(4.0, modeSummaries.get("small_only").averageToolChainStepCount());
            assertEquals(4, modeSummaries.get("small_only").maxToolChainStepCount());
            assertEquals(1, modeSummaries.get("small_only").toolChainTerminationReasonCounts().get("repeated_tool_guard"));
            assertEquals(2, modeSummaries.get("orchestrated").runCount());
            assertEquals(1, modeSummaries.get("orchestrated").needsFollowupCount());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithRouteData());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithExecutionJudgment());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithCompletionJudgment());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithClosedLoopEvidenceChain());
            assertEquals(2, modeSummaries.get("orchestrated").runsWithTaskSurfaceRefs());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithJudgmentSurfaceRefs());
            assertEquals(0, modeSummaries.get("orchestrated").runsWithToolTraceSurfaceRefs());
            assertEquals(1, modeSummaries.get("orchestrated").routeSourceCounts().get("learning_memory"));
            assertEquals(1, modeSummaries.get("orchestrated").executionActionCounts().get("checkpoint"));
            assertEquals(1, modeSummaries.get("orchestrated").completionJudgmentStatusCounts().get("partially_done"));
            assertEquals(1, modeSummaries.get("orchestrated").completionAlignmentLevelCounts().get("medium"));
            assertEquals(2, modeSummaries.get("orchestrated").orchestratedRunCount());
            assertEquals(1, modeSummaries.get("orchestrated").orchestrationClosedLoopObservedCount());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithStrongPlannerEvidence());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithSmallExecutorEvidence());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithStrongEvaluatorEvidence());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithStrongSmallStrongLoop());
            assertEquals(1, modeSummaries.get("orchestrated").evaluatorModelTierCounts().get("strong"));
            assertEquals(1, modeSummaries.get("orchestrated").runsWithLearningHint());
            assertEquals(1, modeSummaries.get("orchestrated").learningHintAppliedCount());
            assertEquals(1.0, modeSummaries.get("orchestrated").learningHintAppliedRate());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithPromptModeData());
            assertEquals(1, modeSummaries.get("orchestrated").promptModeCounts().get("mounted_context_primary"));
            assertEquals(1, modeSummaries.get("orchestrated").runsWithMountedContextRendered());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithMountedRenderUsed());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithMountedContextInjected());
            assertEquals(1.0, modeSummaries.get("orchestrated").mountedContextRenderedRate());
            assertEquals(1.0, modeSummaries.get("orchestrated").mountedRenderUsedRate());
            assertEquals(1.0, modeSummaries.get("orchestrated").mountedContextInjectedRate());
            assertEquals(7.0, modeSummaries.get("orchestrated").averageMountedContextPanelCount());
            assertEquals(4.0, modeSummaries.get("orchestrated").averageMountedContextActiveCount());
            assertEquals(2.0, modeSummaries.get("orchestrated").averageMountedContextEvidenceCount());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithMountedContextBudgetData());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithMountedContextBudgetTruncated());
            assertEquals(1.0, modeSummaries.get("orchestrated").mountedContextBudgetTruncatedRate());
            assertEquals(9.0, modeSummaries.get("orchestrated").averageMountedContextRenderedObjectCount());
            assertEquals(1.0, modeSummaries.get("orchestrated").averageMountedContextHiddenObjectCount());
            assertEquals(3.0, modeSummaries.get("orchestrated").averageMountedContextRenderedSelectionTraceCount());
            assertEquals(0.0, modeSummaries.get("orchestrated").averageMountedContextHiddenSelectionTraceCount());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithExecutionJudgmentPromptModeData());
            assertEquals(1, modeSummaries.get("orchestrated").executionJudgmentPromptModeCounts().get("mounted_context_primary"));
            assertEquals(1, modeSummaries.get("orchestrated").runsWithExecutionJudgmentMountedContextRendered());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithExecutionJudgmentMountedRenderUsed());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithExecutionJudgmentMountedContextInjected());
            assertEquals(1.0, modeSummaries.get("orchestrated").executionJudgmentMountedRenderUsedRate());
            assertEquals(4.0, modeSummaries.get("orchestrated").averageExecutionJudgmentMountedContextActiveCount());
            assertEquals(2.0, modeSummaries.get("orchestrated").averageExecutionJudgmentMountedContextEvidenceCount());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithExecutionJudgmentMountedContextBudgetData());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithExecutionJudgmentMountedContextBudgetTruncated());
            assertEquals(1.0, modeSummaries.get("orchestrated").executionJudgmentMountedContextBudgetTruncatedRate());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithCompletionJudgmentPromptModeData());
            assertEquals(1, modeSummaries.get("orchestrated").completionJudgmentPromptModeCounts().get("mounted_context_primary"));
            assertEquals(1, modeSummaries.get("orchestrated").runsWithCompletionJudgmentMountedContextRendered());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithCompletionJudgmentMountedRenderUsed());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithCompletionJudgmentMountedContextInjected());
            assertEquals(1.0, modeSummaries.get("orchestrated").completionJudgmentMountedRenderUsedRate());
            assertEquals(4.0, modeSummaries.get("orchestrated").averageCompletionJudgmentMountedContextActiveCount());
            assertEquals(2.0, modeSummaries.get("orchestrated").averageCompletionJudgmentMountedContextEvidenceCount());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithCompletionJudgmentMountedContextBudgetData());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithCompletionJudgmentMountedContextBudgetTruncated());
            assertEquals(1.0, modeSummaries.get("orchestrated").completionJudgmentMountedContextBudgetTruncatedRate());
            assertEquals(1, modeSummaries.get("orchestrated").runsWithToolChainData());
            assertEquals(2.0, modeSummaries.get("orchestrated").averageToolChainStepCount());
            assertEquals(2, modeSummaries.get("orchestrated").maxToolChainStepCount());
            assertEquals(1, modeSummaries.get("orchestrated").toolExecutionModeCounts().get("multi_tool_round"));
            assertEquals(1, modeSummaries.get("orchestrated").toolChainTerminationReasonCounts().get("planner_no_additional_tool"));

            ExperimentMatrixSummary.CaseComparison shortCase = summary.caseComparisons().stream()
                .filter(caseComparison -> "short-001".equals(caseComparison.taskCaseKey()))
                .findFirst()
                .orElseThrow();
            assertEquals(3, shortCase.runsByMode().size());
            assertEquals("accepted", shortCase.runsByMode().get("strong_only").acceptanceResult());
            assertEquals("rejected", shortCase.runsByMode().get("small_only").acceptanceResult());
            assertEquals("needs_followup", shortCase.runsByMode().get("orchestrated").acceptanceResult());
            assertTrue(shortCase.runsByMode().containsKey("strong_only"));
            assertTrue(shortCase.runsByMode().containsKey("small_only"));
            assertTrue(shortCase.runsByMode().containsKey("orchestrated"));
        }
    }

    private TestHarness service(DatabaseManager db) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
        ToolInvocationDao toolInvocationDao = db.jdbi().onDemand(ToolInvocationDao.class);
        DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
        ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
        ExperimentRunDao experimentRunDao = db.jdbi().onDemand(ExperimentRunDao.class);

        ExperimentRunService experimentRunService = new ExperimentRunService(
            experimentRunDao,
            decisionDao,
            artifactDao,
            eventDao,
            toolInvocationDao
        );

        TaskService taskService = new TaskService(
            taskDao,
            sessionDao,
            eventDao,
            packetDao,
            new WorkerRouter(new WorkerRegistry()),
            null,
            null,
            null,
            new TaskRuntimeContextBuilder(null, null, null, null, null, null, null),
            null,
            null,
            toolInvocationDao,
            null,
            experimentRunService
        );

        return new TestHarness(
            taskService,
            new ExperimentMatrixService(taskService, experimentRunService),
            artifactDao,
            decisionDao
        );
    }

    private record TestHarness(TaskService taskService,
                               ExperimentMatrixService experimentMatrixService,
                               ArtifactDao artifactDao,
                               DecisionDao decisionDao) {
        private void insertJudgments(Task task,
                                     String executionAction,
                                     String completionStatus,
                                     String alignmentLevel) {
            var executionMetadata = new java.util.LinkedHashMap<String, Object>();
            executionMetadata.put("action", executionAction);
            executionMetadata.putAll(judgmentPromptMetadataForTask(task));
            if (task.assignedWorker() != null) {
                executionMetadata.put("selected_worker", task.assignedWorker());
            }
            decisionDao.insert(new Decision(
                IdGenerator.newId("dec"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "execution_judgment",
                "Execution judgment: " + executionAction,
                "seed execution judgment for matrix summary",
                "medium",
                null,
                executionMetadata
            ));
            decisionDao.insert(new Decision(
                IdGenerator.newId("dec"),
                task.sessionId(),
                task.id(),
                Instant.now(),
                "completion_judgment",
                "Completion judgment: " + completionStatus,
                "seed completion judgment for matrix summary",
                "medium",
                null,
                completionMetadata(completionStatus, alignmentLevel, task)
            ));
        }

        private Map<String, Object> completionMetadata(String completionStatus,
                                                       String alignmentLevel,
                                                       Task task) {
            var completionMetadata = new java.util.LinkedHashMap<String, Object>();
            completionMetadata.put("status", completionStatus);
            completionMetadata.put("alignment_level", alignmentLevel);
            completionMetadata.putAll(judgmentPromptMetadataForTask(task));
            return completionMetadata;
        }

        private Map<String, Object> judgmentPromptMetadataForTask(Task task) {
            String mode = task != null && task.metadata() != null
                ? String.valueOf(task.metadata().getOrDefault("model_mode", "orchestrated"))
                : "orchestrated";
            return switch (mode) {
                case "strong_only" -> Map.ofEntries(
                    Map.entry("prompt_mode", "active_context_only"),
                    Map.entry("mounted_context_rendered", false),
                    Map.entry("mounted_render_used", false),
                    Map.entry("mounted_context_injected", false),
                    Map.entry("mounted_active_count", 0),
                    Map.entry("mounted_evidence_count", 0),
                    Map.entry("mounted_context_rendered_object_count", 0),
                    Map.entry("mounted_context_hidden_object_count", 0),
                    Map.entry("mounted_context_rendered_selection_trace_count", 0),
                    Map.entry("mounted_context_hidden_selection_trace_count", 0),
                    Map.entry("mounted_context_budget_truncated", false)
                );
                case "small_only" -> Map.ofEntries(
                    Map.entry("prompt_mode", "mounted_context_shadow"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", false),
                    Map.entry("mounted_context_panel_count", 5),
                    Map.entry("mounted_active_count", 2),
                    Map.entry("mounted_evidence_count", 1),
                    Map.entry("mounted_context_rendered_object_count", 6),
                    Map.entry("mounted_context_hidden_object_count", 2),
                    Map.entry("mounted_context_rendered_selection_trace_count", 1),
                    Map.entry("mounted_context_hidden_selection_trace_count", 1),
                    Map.entry("mounted_context_budget_truncated", true)
                );
                default -> Map.ofEntries(
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("mounted_context_panel_count", 7),
                    Map.entry("mounted_active_count", 4),
                    Map.entry("mounted_evidence_count", 2),
                    Map.entry("mounted_context_rendered_object_count", 9),
                    Map.entry("mounted_context_hidden_object_count", 1),
                    Map.entry("mounted_context_rendered_selection_trace_count", 3),
                    Map.entry("mounted_context_hidden_selection_trace_count", 0),
                    Map.entry("mounted_context_budget_truncated", true)
                );
            };
        }
    }
}
