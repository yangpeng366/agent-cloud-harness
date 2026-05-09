package com.agentcloud.engine;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.judgment.JudgmentContext;
import com.agentcloud.judgment.JudgmentService;
import com.agentcloud.judgment.model.CompletionDecision;
import com.agentcloud.judgment.model.ExecutionDecision;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.ActiveContextBuilder;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.CheckpointDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ResumePacketDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.worker.WorkerExecutionResult;
import com.agentcloud.worker.WorkerExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlNodeGraphOrchestrationFlowTest {

    @TempDir
    Path tempDir;

    @Test
    void orchestratedTaskRunsPlannerThenExecutorInSingleEnter() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("orchestration-flow.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_1", "demo orchestration", "active"));

            WorkerRouter router = new WorkerRouter(new WorkerRegistry());
            ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
                new ActiveContextBuilder.DefaultActiveContextPolicy(),
                new ActiveContextBuilder.DefaultRetentionPolicy(),
                new ActiveContextBuilder.DefaultExclusionPolicy()
            );
            TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
                eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
            );

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, packetDao, router, null, null,
                new FakeWorkerExecutor(), runtimeContextBuilder, new FakeJudgmentService(),
                artifactDao, decisionDao, null
            );

            Task task = new Task(
                "task_1",
                "session_1",
                null,
                "run orchestrated loop",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "ship a validated result",
                null,
                null,
                "intake",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "prove planner to executor runtime path",
                    "model_mode", "orchestrated",
                    "orchestration_stage", "plan_pending",
                    "prompt_rendering_mode", "mounted_context_primary"
                ))
            );
            taskDao.insert(task);

            Task finalTask = graph.enter(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();
            List<Artifact> artifacts = artifactDao.listBySessionAndTask(task.sessionId(), task.id(), 10);
            List<Decision> decisions = decisionDao.listBySessionAndTask(task.sessionId(), task.id(), 10);

            assertEquals("done", finalTask.status());
            assertEquals("done", persisted.status());
            assertEquals("end", persisted.controlNode());
            assertEquals("kimi", persisted.assignedWorker());
            assertEquals("completed", persisted.metadata().get("orchestration_stage"));
            assertEquals("codex", persisted.metadata().get("planner_worker"));
            assertEquals("kimi", persisted.metadata().get("executor_worker"));
            assertEquals("kimi", persisted.metadata().get("target_worker"));

            assertEquals(2, artifacts.size());
            assertTrue(artifacts.stream().anyMatch(a -> "strong".equals(metadataString(a.metadata(), "selected_model_tier"))));
            assertTrue(artifacts.stream().anyMatch(a -> "small".equals(metadataString(a.metadata(), "selected_model_tier"))));
            assertTrue(artifacts.stream().anyMatch(a -> "plan_pending".equals(metadataString(a.metadata(), "orchestration_stage"))));
            assertTrue(artifacts.stream().anyMatch(a -> "execution_pending".equals(metadataString(a.metadata(), "orchestration_stage"))));

            assertEquals(4, decisions.size());
            assertTrue(decisions.stream().anyMatch(d ->
                "execution_judgment".equals(d.decisionType())
                    && "handoff".equals(metadataString(d.metadata(), "action"))
                    && "kimi".equals(metadataString(d.metadata(), "target_worker"))
            ));
            assertTrue(decisions.stream().anyMatch(d ->
                "completion_judgment".equals(d.decisionType())
                    && "done:high".equals(metadataString(d.metadata(), "evaluation_result"))
            ));
            assertTrue(decisions.stream().anyMatch(d ->
                "completion_judgment".equals(d.decisionType())
                    && "strong_evaluator".equals(metadataString(d.metadata(), "evaluator_role"))
                    && "strong".equals(metadataString(d.metadata(), "evaluator_model_tier"))
                    && "true".equalsIgnoreCase(metadataString(d.metadata(), "orchestration_closed_loop_observed"))
            ));
            assertTrue(decisions.stream().anyMatch(d ->
                "execution_judgment".equals(d.decisionType())
                    && "planner".equals(metadataString(d.metadata(), "selection_scope"))
            ));
            assertTrue(decisions.stream().anyMatch(d ->
                "execution_judgment".equals(d.decisionType())
                    && "mounted_context_primary".equals(metadataString(d.metadata(), "mounted_context_mode"))
                    && "mounted_context_primary".equals(metadataString(d.metadata(), "prompt_mode"))
                    && "true".equalsIgnoreCase(metadataString(d.metadata(), "mounted_context_injected"))
            ));
            assertTrue(decisions.stream().anyMatch(d ->
                "completion_judgment".equals(d.decisionType())
                    && "mounted_context_primary".equals(metadataString(d.metadata(), "mounted_context_mode"))
                    && "mounted_context_primary".equals(metadataString(d.metadata(), "prompt_mode"))
                    && "true".equalsIgnoreCase(metadataString(d.metadata(), "mounted_context_injected"))
            ));
            assertTrue(decisions.stream().anyMatch(d ->
                "execution_judgment".equals(d.decisionType())
                    && d.summary() != null
                    && d.summary().contains("proof=tool:orchestrator_plan_1")
            ));
            assertTrue(decisions.stream().anyMatch(d ->
                "execution_judgment".equals(d.decisionType())
                    && "true".equalsIgnoreCase(metadataString(d.metadata(), "needs_context_reopen"))
                    && metadataString(d.metadata(), "reopen_summary") != null
                    && !metadataStringList(d.metadata(), "reopen_candidate_paths").isEmpty()
            ));
            assertTrue(decisions.stream().anyMatch(d ->
                "completion_judgment".equals(d.decisionType())
                    && d.summary() != null
                    && d.summary().contains("proof=tool:orchestrator_exec_1")
            ));
        }
    }

    @Test
    void continueAutoRunsSchedulerAgainUntilGroundedOutputAppears() throws Exception {
        Path outputFile = tempDir.resolve("auto-continue-result.txt");

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("auto-continue-flow.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_auto_continue", "auto continue flow", "active"));

            WorkerRegistry workerRegistry = new WorkerRegistry();
            workerRegistry.register(new Worker(
                "tool-auto",
                "codex",
                List.of("coding"),
                List.of("read_file", "write_file"),
                List.of(tempDir.toString()),
                Map.of("api_key", true),
                Map.of("model_tier", "strong"),
                false,
                true
            ));
            WorkerRouter router = new WorkerRouter(workerRegistry);
            ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
                new ActiveContextBuilder.DefaultActiveContextPolicy(),
                new ActiveContextBuilder.DefaultRetentionPolicy(),
                new ActiveContextBuilder.DefaultExclusionPolicy()
            );
            TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
                eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
            );

            SequencedWorkerExecutor workerExecutor = new SequencedWorkerExecutor(
                outputFile,
                List.of(
                    new WorkerExecutionResult(
                        "inspected requirements",
                        "looked at the current task state",
                        false,
                        "",
                        "",
                        "write the grounded output file next",
                        "medium",
                        0,
                        10L,
                        Map.ofEntries(
                            Map.entry("tool_aware_executor", true),
                            Map.entry("tool_execution_mode", "multi_tool_round"),
                            Map.entry("tool_chain_step_count", 1),
                            Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
                            Map.entry("output_file_required", true),
                            Map.entry("output_file_path", outputFile.toString()),
                            Map.entry("output_file_exists", false),
                            Map.entry("file_backed_artifact", true),
                            Map.entry("grounded_output_present", false),
                            Map.entry("selected_worker", "tool-auto"),
                            Map.entry("selected_model_tier", "strong"),
                            Map.entry("execution_role", "executor")
                        )
                    ),
                    new WorkerExecutionResult(
                        "wrote grounded output",
                        "the result file is now on disk",
                        true,
                        "auto-continue-result.txt",
                        "Auto-continued grounded output.",
                        "mark the task complete",
                        "high",
                        0,
                        12L,
                        Map.ofEntries(
                            Map.entry("tool_aware_executor", true),
                            Map.entry("tool_execution_mode", "multi_tool_round"),
                            Map.entry("tool_chain_step_count", 1),
                            Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
                            Map.entry("output_file_required", true),
                            Map.entry("output_file_path", outputFile.toString()),
                            Map.entry("output_file_exists", true),
                            Map.entry("output_file_size", 31),
                            Map.entry("file_backed_artifact", true),
                            Map.entry("grounded_output_present", true),
                            Map.entry("selected_worker", "tool-auto"),
                            Map.entry("selected_model_tier", "strong"),
                            Map.entry("execution_role", "executor")
                        )
                    )
                )
            );

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, packetDao, router, null, null,
                workerExecutor, runtimeContextBuilder, new AutoContinueJudgmentService(),
                artifactDao, decisionDao, null
            );

            Task task = new Task(
                "task_auto_continue",
                "session_auto_continue",
                null,
                "auto continue grounded output",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "Produce a grounded result file.",
                null,
                "tool-auto",
                "scheduler",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "Use tool rounds until the grounded output exists.",
                    "output_file", outputFile.toString()
                ))
            );
            taskDao.insert(task);

            Task finalTask = graph.enter(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();
            List<Artifact> artifacts = artifactDao.listBySessionAndTask(task.sessionId(), task.id(), 10);

            assertEquals(2, workerExecutor.callCount());
            assertTrue(Files.exists(outputFile));
            assertEquals("done", finalTask.status());
            assertEquals("done", persisted.status());
            assertEquals("end", persisted.controlNode());
            assertEquals("tool-auto", persisted.assignedWorker());
            assertEquals("1", String.valueOf(persisted.metadata().get("auto_continue_burst_count")));
            assertFalse(artifacts.isEmpty());
            assertTrue(artifacts.stream().anyMatch(artifact ->
                artifact.metadata().get("latest_worker_metadata") instanceof Map<?, ?> latest
                    && "true".equalsIgnoreCase(String.valueOf(latest.get("grounded_output_present")))
            ));
        }
    }

    @Test
    void schedulerPersistsProviderContinuationMetadataIntoTaskAndArtifactTrace() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("provider-continuation-flow.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_provider_continuation", "provider continuation flow", "active"));

            WorkerRegistry workerRegistry = new WorkerRegistry();
            workerRegistry.register(new Worker(
                "codex-app",
                "codex",
                List.of("coding"),
                List.of(),
                List.of(tempDir.toString()),
                Map.of(),
                Map.of("model_tier", "strong", "execution_backend", "provider_app_server"),
                false,
                true
            ));
            WorkerRouter router = new WorkerRouter(workerRegistry);
            ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
                new ActiveContextBuilder.DefaultActiveContextPolicy(),
                new ActiveContextBuilder.DefaultRetentionPolicy(),
                new ActiveContextBuilder.DefaultExclusionPolicy()
            );
            TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
                eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
            );

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, packetDao, router, null, null,
                new ProviderContinuationWorkerExecutor(), runtimeContextBuilder, new DoneJudgmentService(),
                artifactDao, decisionDao, null
            );

            Task task = new Task(
                "task_provider_continuation",
                "session_provider_continuation",
                null,
                "persist provider continuation metadata",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "Verify task metadata persistence for provider-native sessions.",
                null,
                "codex-app",
                "scheduler",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "Persist provider continuation metadata for the next codex round."
                ))
            );
            taskDao.insert(task);

            Task finalTask = graph.enter(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();
            List<Artifact> artifacts = artifactDao.listBySessionAndTask(task.sessionId(), task.id(), 10);

            assertEquals("done", finalTask.status());
            assertEquals("thread-codex-001", persisted.metadata().get("provider_session_id"));
            assertEquals("thread-codex-001", persisted.metadata().get("provider_thread_id"));
            assertEquals("thread-codex-001", persisted.metadata().get("codex_thread_id"));
            assertEquals("provider_app_server", persisted.metadata().get("execution_backend"));
            assertEquals("codex", persisted.metadata().get("provider_id"));
            assertFalse(artifacts.isEmpty());
            assertTrue(artifacts.stream().anyMatch(artifact ->
                artifact.metadata().get("latest_worker_metadata") instanceof Map<?, ?> latest
                    && "thread-codex-001".equals(String.valueOf(latest.get("provider_thread_id")))
                    && "provider_app_server".equals(String.valueOf(latest.get("execution_backend")))
            ));
        }
    }

    @Test
    void continueBuildsFactAwareJudgmentContextForOrchestratedFlow() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("orchestration-judgment-facts.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_fact_aware", "fact aware orchestration", "active"));

            WorkerRouter router = new WorkerRouter(new WorkerRegistry());
            ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
                new ActiveContextBuilder.DefaultActiveContextPolicy(),
                new ActiveContextBuilder.DefaultRetentionPolicy(),
                new ActiveContextBuilder.DefaultExclusionPolicy()
            );
            TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
                eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
            );
            RecordingJudgmentService judgmentService = new RecordingJudgmentService();
            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, packetDao, router, null, null,
                new FakeWorkerExecutor(), runtimeContextBuilder, judgmentService,
                artifactDao, decisionDao, null
            );

            Task task = new Task(
                "task_fact_aware",
                "session_fact_aware",
                null,
                "fact aware orchestration",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "make judgment consume runtime facts",
                null,
                null,
                "intake",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "verify runtime fact set reaches judgment",
                    "model_mode", "orchestrated",
                    "orchestration_stage", "plan_pending"
                ))
            );
            taskDao.insert(task);

            graph.enter(task);

            assertEquals(2, judgmentService.executionContexts.size());

            JudgmentContext plannerContext = judgmentService.contextAt(0);
            assertNotNull(plannerContext.runtimeFactSet());
            assertNotNull(plannerContext.runtimeFactSet().routePreview());
            assertEquals("codex", plannerContext.runtimeFactSet().routePreview().selectedWorker());
            assertEquals("strong", plannerContext.runtimeFactSet().routePreview().selectedModelTier());
            assertEquals("planner", plannerContext.runtimeFactSet().routePreview().selectionScope());
            assertEquals("codex", plannerContext.runtimeFactSet().executionBoundary().workerId());
            assertEquals("12", String.valueOf(plannerContext.runtimeFactSet().executionBoundary().durationMs()));
            assertEquals("capability_match",
                plannerContext.runtimeFactSet().routePreview().routeSource());
            assertTrue(plannerContext.runtimeFactSet().routePreview().whySelected().contains("strong"));

            JudgmentContext executorContext = judgmentService.contextAt(1);
            assertNotNull(executorContext.runtimeFactSet());
            assertNotNull(executorContext.runtimeFactSet().routePreview());
            assertEquals("kimi", executorContext.runtimeFactSet().routePreview().selectedWorker());
            assertEquals("small", executorContext.runtimeFactSet().routePreview().selectedModelTier());
            assertEquals("executor", executorContext.runtimeFactSet().routePreview().selectionScope());
            assertEquals("kimi", executorContext.runtimeFactSet().executionBoundary().workerId());
        }
    }

    @Test
    void continueJudgmentPromptMetadataUsesLatestPacketPromptModeAlias() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("judgment-packet-prompt-mode.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_packet_prompt_mode", "judgment packet prompt mode", "active"));

            WorkerRouter router = new WorkerRouter(new WorkerRegistry());
            ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
                new ActiveContextBuilder.DefaultActiveContextPolicy(),
                new ActiveContextBuilder.DefaultRetentionPolicy(),
                new ActiveContextBuilder.DefaultExclusionPolicy()
            );
            TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
                eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
            );

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, packetDao, router, null, null,
                null, runtimeContextBuilder, new DoneJudgmentService(),
                artifactDao, decisionDao, null
            );

            Task task = new Task(
                "task_packet_prompt_mode",
                "session_packet_prompt_mode",
                null,
                "judgment prompt mode comes from packet alias",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "Complete the task using packet-only prompt mode.",
                null,
                "codex",
                "continue",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "verify judgment prompt metadata follows latest packet alias"
                ))
            );
            taskDao.insert(task);

            packetDao.insert(new ResumePacket(
                UUID.randomUUID().toString(),
                task.sessionId(),
                task.id(),
                Instant.parse("2026-05-06T06:42:00Z"),
                "1.1",
                "review summary",
                null,
                null,
                List.of(),
                "evaluate output",
                Map.of(
                    "prompt_mode", "mounted_context_primary",
                    "next_step", "evaluate output"
                )
            ));

            Task finalTask = graph.enter(task);
            List<Decision> decisions = decisionDao.listBySessionAndTask(task.sessionId(), task.id(), 10);

            assertEquals("done", finalTask.status());
            assertTrue(decisions.stream().anyMatch(d ->
                "execution_judgment".equals(d.decisionType())
                    && "mounted_context_primary".equals(metadataString(d.metadata(), "prompt_mode"))
                    && "mounted_context_primary".equals(metadataString(d.metadata(), "mounted_context_mode"))
                    && "true".equalsIgnoreCase(metadataString(d.metadata(), "mounted_context_rendered"))
                    && "true".equalsIgnoreCase(metadataString(d.metadata(), "mounted_render_used"))
                    && "true".equalsIgnoreCase(metadataString(d.metadata(), "mounted_context_injected"))
            ));
            assertTrue(decisions.stream().anyMatch(d ->
                "completion_judgment".equals(d.decisionType())
                    && "mounted_context_primary".equals(metadataString(d.metadata(), "prompt_mode"))
                    && "mounted_context_primary".equals(metadataString(d.metadata(), "mounted_context_mode"))
                    && "true".equalsIgnoreCase(metadataString(d.metadata(), "mounted_context_rendered"))
                    && "true".equalsIgnoreCase(metadataString(d.metadata(), "mounted_render_used"))
                    && "true".equalsIgnoreCase(metadataString(d.metadata(), "mounted_context_injected"))
            ));
        }
    }

    private static String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> metadataStringList(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return List.of();
        }
        Object value = metadata.get(key);
        if (!(value instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }
        return rawList.stream()
            .filter(java.util.Objects::nonNull)
            .map(Object::toString)
            .filter(item -> !item.isBlank())
            .toList();
    }

    private static final class FakeWorkerExecutor implements WorkerExecutor {
        @Override
        public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
            if ("codex".equals(workerId)) {
                return new WorkerExecutionResult(
                    "Planner brief ready",
                    "Break the task into a compact execution brief for the delegated worker.",
                    true,
                    "Planner Brief",
                    "1. Update the runtime path. 2. Run the test entrypoint. 3. Report final state.",
                    "Implement the delegated runtime change and run verification.",
                    "high",
                    0,
                    12L,
                    Map.ofEntries(
                        Map.entry("parser", "json"),
                        Map.entry("selected_worker", workerId),
                        Map.entry("selected_model_tier", "strong"),
                        Map.entry("execution_role", "planner"),
                        Map.entry("execution_status", "completed"),
                        Map.entry("tool_chain_step_count", 1),
                        Map.entry("tool_chain_termination_reason", "planner_brief_ready"),
                        Map.entry("tool_invocation_ids", List.of("orchestrator_plan_1")),
                        Map.entry("evidence_refs", List.of("tool:read_file:runtime-brief.md"))
                    )
                );
            }
            return new WorkerExecutionResult(
                "Executor completed the delegated step",
                "Implemented the delegated change and verified the expected runtime behavior.",
                true,
                "Execution Result",
                "Runtime path updated and verification completed successfully.",
                "Ready for acceptance.",
                "high",
                0,
                15L,
                Map.ofEntries(
                    Map.entry("parser", "json"),
                    Map.entry("selected_worker", workerId),
                    Map.entry("selected_model_tier", "small"),
                    Map.entry("execution_role", "executor"),
                    Map.entry("execution_status", "completed"),
                    Map.entry("tool_chain_step_count", 1),
                    Map.entry("tool_chain_termination_reason", "executor_step_done"),
                    Map.entry("tool_invocation_ids", List.of("orchestrator_exec_1")),
                    Map.entry("evidence_refs", List.of("tool:patch_file:runtime-path.java"))
                )
            );
        }
    }

    private static final class SequencedWorkerExecutor implements WorkerExecutor {
        private final Path outputFile;
        private final Queue<WorkerExecutionResult> results;
        private int callCount;

        private SequencedWorkerExecutor(Path outputFile, List<WorkerExecutionResult> results) {
            this.outputFile = outputFile;
            this.results = new ArrayDeque<>(results);
        }

        @Override
        public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
            callCount++;
            WorkerExecutionResult result = results.poll();
            if (result == null) {
                throw new AssertionError("unexpected worker execution call " + callCount);
            }
            if (callCount == 2) {
                try {
                    java.nio.file.Files.writeString(outputFile, "Auto-continued grounded output.");
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return result;
        }

        private int callCount() {
            return callCount;
        }
    }

    private static final class FakeJudgmentService implements JudgmentService {
        @Override
        public ExecutionDecision judgeExecution(JudgmentContext context) {
            String stage = metadataString(context.task().metadata(), "orchestration_stage");
            if ("plan_pending".equals(stage)) {
                return new ExecutionDecision(
                    "continue",
                    "planner produced a delegation brief",
                    "Implement the delegated runtime change and run verification.",
                    false,
                    true,
                    false,
                    null
                );
            }
            return new ExecutionDecision(
                "continue",
                "executor finished the delegated work",
                "Mark the task complete.",
                false,
                false,
                null
            );
        }

        @Override
        public CompletionDecision judgeCompletion(JudgmentContext context) {
            String stage = metadataString(context.task().metadata(), "orchestration_stage");
            if ("plan_pending".equals(stage)) {
                return new CompletionDecision(
                    "done",
                    "high",
                    "planner brief is ready",
                    "Delegate execution to a smaller worker."
                );
            }
            return new CompletionDecision(
                "done",
                "high",
                "executor output satisfies the task goal",
                "Mark the task complete."
            );
        }
    }

    private static final class AutoContinueJudgmentService implements JudgmentService {
        @Override
        public ExecutionDecision judgeExecution(JudgmentContext context) {
            boolean grounded = Boolean.parseBoolean(
                String.valueOf(context.latestWorkerMetadata().getOrDefault("grounded_output_present", false))
            );
            if (!grounded) {
                return new ExecutionDecision(
                    "continue",
                    "grounded output is still missing",
                    "Run another tool round to produce the required output.",
                    false,
                    false,
                    null
                );
            }
            return new ExecutionDecision(
                "continue",
                "grounded output exists now",
                "Complete the task.",
                false,
                false,
                null
            );
        }

        @Override
        public CompletionDecision judgeCompletion(JudgmentContext context) {
            boolean grounded = Boolean.parseBoolean(
                String.valueOf(context.latestWorkerMetadata().getOrDefault("grounded_output_present", false))
            );
            if (!grounded) {
                return new CompletionDecision(
                    "partially_done",
                    "medium",
                    "worker has not produced the grounded output yet",
                    "Continue tool execution."
                );
            }
            return new CompletionDecision(
                "done",
                "high",
                "grounded output exists and satisfies the task",
                "Complete the task."
            );
        }
    }

    private static final class ProviderContinuationWorkerExecutor implements WorkerExecutor {
        @Override
        public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
            return new WorkerExecutionResult(
                "Codex thread is ready for the next round",
                "Persist the provider thread id for follow-up execution.",
                true,
                "Codex Continuation",
                "provider thread persisted",
                "resume the same codex thread on the next round",
                "high",
                "completed",
                List.of(),
                List.of(),
                0,
                16L,
                Map.ofEntries(
                    Map.entry("provider_id", "codex"),
                    Map.entry("execution_backend", "provider_app_server"),
                    Map.entry("provider_session_id", "thread-codex-001"),
                    Map.entry("provider_thread_id", "thread-codex-001"),
                    Map.entry("provider_output_parser", "codex_json_rpc"),
                    Map.entry("selected_worker", workerId),
                    Map.entry("selected_model_tier", "strong"),
                    Map.entry("execution_role", "executor")
                )
            );
        }
    }

    private static final class DoneJudgmentService implements JudgmentService {
        @Override
        public ExecutionDecision judgeExecution(JudgmentContext context) {
            return new ExecutionDecision(
                "done",
                "provider continuation metadata has been captured",
                "complete the task",
                false,
                false,
                null
            );
        }

        @Override
        public CompletionDecision judgeCompletion(JudgmentContext context) {
            return new CompletionDecision(
                "done",
                "high",
                "the task produced a reusable provider continuation token",
                "Complete the task."
            );
        }
    }

    private static final class RecordingJudgmentService implements JudgmentService {
        private final List<JudgmentContext> executionContexts = new java.util.ArrayList<>();

        @Override
        public ExecutionDecision judgeExecution(JudgmentContext context) {
            executionContexts.add(context);
            String stage = metadataString(context.task().metadata(), "orchestration_stage");
            if ("plan_pending".equals(stage)) {
                return new ExecutionDecision(
                    "continue",
                    "planner produced a delegation brief",
                    "delegate to executor",
                    false,
                    true,
                    false,
                    null
                );
            }
            return new ExecutionDecision(
                "done",
                "execution complete",
                "finish",
                false,
                false,
                null
            );
        }

        @Override
        public CompletionDecision judgeCompletion(JudgmentContext context) {
            String stage = metadataString(context.task().metadata(), "orchestration_stage");
            if ("plan_pending".equals(stage)) {
                return new CompletionDecision(
                    "done",
                    "high",
                    "planner output accepted as delegation brief",
                    "delegate to executor"
                );
            }
            return new CompletionDecision(
                "done",
                "high",
                "execution output satisfies the goal",
                "complete"
            );
        }

        private JudgmentContext contextAt(int index) {
            return executionContexts.get(index);
        }
    }

}
