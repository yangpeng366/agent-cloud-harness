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
                Map.of(
                    "route_source", "capability_match",
                    "execution_judgment_action", "done",
                    "completion_judgment_status", "done",
                    "completion_alignment_level", "high",
                    "has_route_evidence", true,
                    "has_execution_judgment", true,
                    "has_completion_judgment", true,
                    "has_closed_loop_evidence_chain", true,
                    "latest_worker_metadata", Map.of(
                        "tool_execution_mode", "single_tool_round",
                        "tool_chain_step_count", 1,
                        "tool_chain_termination_reason", "planner_no_additional_tool"
                    )
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
                    Map.entry("execution_judgment_action", "checkpoint"),
                    Map.entry("completion_judgment_status", "partially_done"),
                    Map.entry("completion_alignment_level", "medium"),
                    Map.entry("has_route_evidence", true),
                    Map.entry("has_execution_judgment", true),
                    Map.entry("has_completion_judgment", true),
                    Map.entry("has_closed_loop_evidence_chain", true),
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
            assertEquals(1, modeSummaries.get("orchestrated").runsWithLearningHint());
            assertEquals(1, modeSummaries.get("orchestrated").learningHintAppliedCount());
            assertEquals(1.0, modeSummaries.get("orchestrated").learningHintAppliedRate());
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
                Map.of(
                    "status", completionStatus,
                    "alignment_level", alignmentLevel
                )
            ));
        }
    }
}
