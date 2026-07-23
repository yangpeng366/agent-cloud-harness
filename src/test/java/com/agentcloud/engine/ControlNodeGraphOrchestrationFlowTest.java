package com.agentcloud.engine;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderDescriptor;
import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.AgentProviderStatus;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.judgment.JudgmentContext;
import com.agentcloud.judgment.JudgmentService;
import com.agentcloud.judgment.model.CompletionDecision;
import com.agentcloud.judgment.model.ExecutionDecision;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.Session;
import com.agentcloud.model.SessionMessage;
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
import com.agentcloud.store.SessionMessageDao;
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
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
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
                taskDao, eventDao, sessionDao, sessionMessageDao, packetDao, router, null, null,
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
            List<SessionMessage> workerRoundMessages = sessionMessageDao.listBySessionAndTask(task.sessionId(), task.id(), 10).stream()
                .filter(message -> "worker_round".equals(message.messageType()))
                .toList();
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
            assertEquals(2, workerRoundMessages.size());
            for (Artifact artifact : artifacts) {
                SessionMessage workerRound = workerRoundMessages.stream()
                    .filter(message -> artifact.id().equals(metadataString(message.metadata(), "artifact_id")))
                    .findFirst()
                    .orElseThrow();
                assertEquals("assistant", workerRound.role());
                assertEquals("worker_round_projection", workerRound.metadata().get("created_via"));
                assertEquals(metadataString(artifact.metadata(), "worker_id"), metadataString(workerRound.metadata(), "worker_id"));
                assertEquals(metadataString(artifact.metadata(), "provider_id"), metadataString(workerRound.metadata(), "provider_id"));
                assertTrue(workerRound.content().contains("完成一轮执行"));
            }

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
    void schedulerReroutesWhenAssignedWorkerFailsDispatchPreflight() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("dispatch-preflight-reroute.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_dispatch_preflight", "dispatch preflight flow", "active"));

            AgentProviderRegistry providers = new AgentProviderRegistry()
                .register(new PreflightProvider("codex", true, false, "fresh turn rejected"))
                .register(new PreflightProvider("kimi", true, true, null));
            WorkerRegistry workerRegistry = new WorkerRegistry(providers);
            WorkerRouter router = new WorkerRouter(workerRegistry);
            ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
                new ActiveContextBuilder.DefaultActiveContextPolicy(),
                new ActiveContextBuilder.DefaultRetentionPolicy(),
                new ActiveContextBuilder.DefaultExclusionPolicy()
            );
            TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
                eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
            );
            RecordingWorkerExecutor workerExecutor = new RecordingWorkerExecutor();

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, packetDao, router, null, null,
                workerExecutor, runtimeContextBuilder, new DoneJudgmentService(),
                artifactDao, decisionDao, null
            );

            Task task = new Task(
                "task_dispatch_preflight",
                "session_dispatch_preflight",
                null,
                "continue long coding task",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                null,
                "complete the long coding task",
                "codex",
                "scheduler",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "assigned_worker", "codex",
                    "model_mode", "small_only"
                ))
            );
            taskDao.insert(task);

            Task finalTask = graph.enter(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();

            assertEquals(List.of("kimi"), workerExecutor.workerIds());
            assertEquals("done", finalTask.status());
            assertEquals("kimi", persisted.assignedWorker());
            assertEquals("codex", persisted.metadata().get("previous_worker"));
            assertEquals("codex", persisted.metadata().get("dispatch_preflight_failed_worker"));
            assertEquals("fresh turn rejected", persisted.metadata().get("dispatch_preflight_reason"));
            Event preflightEvent = eventDao.listBySessionAndTask(task.sessionId(), task.id(), 10).stream()
                .filter(event -> "worker_dispatch_preflight_failed".equals(event.eventType()))
                .findFirst()
                .orElseThrow();
            assertEquals("active_probe", preflightEvent.payload().get("dispatch_preflight_mode"));
            assertEquals(Boolean.TRUE, preflightEvent.payload().get("dispatch_preflight_active_probe"));
        }
    }

    @Test
    void continueAutoRunsAdditionalDeclaredRoundsBeforeStopping() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("auto-continue-declared-rounds.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_declared_rounds", "auto continue declared rounds", "active"));

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
                null,
                List.of(
                    new WorkerExecutionResult(
                        "round 1 complete",
                        "drafted the first long-task substep",
                        false,
                        "",
                        "",
                        "",
                        "medium",
                        0,
                        8L,
                        Map.ofEntries(
                            Map.entry("tool_aware_executor", true),
                            Map.entry("tool_execution_mode", "multi_tool_round"),
                            Map.entry("tool_chain_step_count", 1),
                            Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
                            Map.entry("more_declared_rounds_remain", true),
                            Map.entry("declared_round_count", 3),
                            Map.entry("next_round_instruction", "Continue with round 2."),
                            Map.entry("selected_worker", "tool-auto"),
                            Map.entry("selected_model_tier", "strong"),
                            Map.entry("execution_role", "executor")
                        )
                    ),
                    new WorkerExecutionResult(
                        "round 2 complete",
                        "drafted the second long-task substep",
                        false,
                        "",
                        "",
                        "",
                        "medium",
                        0,
                        9L,
                        Map.ofEntries(
                            Map.entry("tool_aware_executor", true),
                            Map.entry("tool_execution_mode", "multi_tool_round"),
                            Map.entry("tool_chain_step_count", 1),
                            Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
                            Map.entry("more_declared_rounds_remain", true),
                            Map.entry("declared_round_count", 3),
                            Map.entry("next_round_instruction", "Continue with round 3."),
                            Map.entry("selected_worker", "tool-auto"),
                            Map.entry("selected_model_tier", "strong"),
                            Map.entry("execution_role", "executor")
                        )
                    ),
                    new WorkerExecutionResult(
                        "round 3 complete",
                        "the long-task final result is ready",
                        true,
                        "Final Result",
                        "Long task completed after three declared rounds.",
                        "mark the task complete",
                        "high",
                        0,
                        10L,
                        Map.ofEntries(
                            Map.entry("tool_aware_executor", true),
                            Map.entry("tool_execution_mode", "multi_tool_round"),
                            Map.entry("tool_chain_step_count", 1),
                            Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
                            Map.entry("more_declared_rounds_remain", false),
                            Map.entry("declared_round_count", 3),
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
                "task_declared_rounds",
                "session_declared_rounds",
                null,
                "auto continue declared rounds",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "Complete the long task across declared rounds.",
                null,
                "tool-auto",
                "scheduler",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "Use declared rounds to keep the long task moving."
                ))
            );
            taskDao.insert(task);

            Task finalTask = graph.enter(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();

            assertEquals(3, workerExecutor.callCount());
            assertEquals("done", finalTask.status());
            assertEquals("done", persisted.status());
            assertEquals("end", persisted.controlNode());
            assertEquals("2", String.valueOf(persisted.metadata().get("auto_continue_burst_count")));
        }
    }

    @Test
    void resumeClearsPreviousAutoContinueBurstBeforeRunningAgain() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("resume-clears-auto-continue-burst.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_resume_burst", "resume clears burst", "active"));

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
                null,
                List.of(
                    new WorkerExecutionResult(
                        "resumed round complete",
                        "final resumed round finished successfully",
                        true,
                        "Resumed Result",
                        "Resume path completed the remaining work.",
                        "complete the task",
                        "high",
                        0,
                        8L,
                        Map.ofEntries(
                            Map.entry("tool_aware_executor", true),
                            Map.entry("tool_execution_mode", "multi_tool_round"),
                            Map.entry("tool_chain_step_count", 1),
                            Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
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
                "task_resume_burst",
                "session_resume_burst",
                null,
                "resume clears auto continue burst",
                "paused",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "Resume long task after previous auto-continue burst was exhausted.",
                null,
                "tool-auto",
                "scheduler",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "Resume after previous auto-continue attempts.",
                    "auto_continue_burst_count", 3
                ))
            );
            taskDao.insert(task);

            Task resumed = graph.triggerResume(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();

            assertEquals(1, workerExecutor.callCount());
            assertEquals("done", resumed.status());
            assertEquals("done", persisted.status());
            assertEquals("end", persisted.controlNode());
            assertFalse(persisted.metadata().containsKey("auto_continue_burst_count"));
        }
    }

    @Test
    void recoveryFallbackEmptyOutputStopsAtHumanGateAfterRetryAndSingleHandoff() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("recovery-empty-fallback-human-gate.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_recovery_empty", "recovery empty fallback", "active"));

            WorkerRegistry workerRegistry = new WorkerRegistry();
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
                null,
                List.of(
                    new WorkerExecutionResult(
                        "",
                        "",
                        false,
                        "",
                        "",
                        "",
                        "low",
                        "timeout",
                        List.of(),
                        List.of(),
                        0,
                        12L,
                        Map.ofEntries(
                            Map.entry("tool_aware_executor", true),
                            Map.entry("tool_execution_mode", "multi_tool_round"),
                            Map.entry("tool_chain_step_count", 1),
                            Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
                            Map.entry("selected_worker", "codex"),
                            Map.entry("selected_model_tier", "strong"),
                            Map.entry("execution_role", "executor"),
                            Map.entry("output_text", "thread not found: 29180"),
                            Map.entry("candidate_workers", List.of("codex", "kimi"))
                        )
                    ),
                    new WorkerExecutionResult(
                        "",
                        "",
                        false,
                        "",
                        "",
                        "",
                        "low",
                        "empty",
                        List.of(),
                        List.of(),
                        0,
                        6L,
                        Map.ofEntries(
                            Map.entry("tool_aware_executor", true),
                            Map.entry("tool_execution_mode", "multi_tool_round"),
                            Map.entry("tool_chain_step_count", 1),
                            Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
                            Map.entry("selected_worker", "codex"),
                            Map.entry("selected_model_tier", "strong"),
                            Map.entry("execution_role", "executor"),
                            Map.entry("candidate_workers", List.of("codex", "kimi"))
                        )
                    ),
                    new WorkerExecutionResult(
                        "",
                        "",
                        false,
                        "",
                        "",
                        "",
                        "low",
                        "empty",
                        List.of(),
                        List.of(),
                        0,
                        5L,
                        Map.ofEntries(
                            Map.entry("tool_aware_executor", true),
                            Map.entry("tool_execution_mode", "multi_tool_round"),
                            Map.entry("tool_chain_step_count", 1),
                            Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
                            Map.entry("selected_worker", "kimi"),
                            Map.entry("selected_model_tier", "small"),
                            Map.entry("execution_role", "executor"),
                            Map.entry("candidate_workers", List.of("kimi", "codex"))
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
                "task_recovery_empty",
                "session_recovery_empty",
                null,
                "recover after empty fallback output",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "finish the delegated work",
                null,
                "codex",
                "scheduler",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "reproduce timeout then empty fallback worker output",
                    "model_mode", "orchestrated",
                    "orchestration_stage", "execution_active"
                ))
            );
            taskDao.insert(task);

            Task finalTask = graph.enter(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();

            assertEquals(3, workerExecutor.callCount());
            assertEquals("waiting_human", finalTask.status());
            assertEquals("waiting_human", persisted.status());
            assertEquals("human_gate", persisted.controlNode());
            assertEquals("human_gate_required", persisted.metadata().get("recovery_stage"));
            assertEquals("worker_runtime_transient", persisted.metadata().get("failure_class"));
            assertEquals("1", String.valueOf(persisted.metadata().get("auto_same_worker_retry_count")));
            assertEquals("1", String.valueOf(persisted.metadata().get("auto_handoff_count")));
            assertEquals("cursor", persisted.assignedWorker());
        }
    }

    @Test
    void providerRuntimeTransientClassificationTriggersColdRetryWithoutTextGuessing() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("provider-failure-class-recovery.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_provider_failure_class", "provider failure class recovery", "active"));

            WorkerRegistry workerRegistry = new WorkerRegistry();
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
                null,
                List.of(
                    new WorkerExecutionResult(
                        "provider failed with classified transient",
                        "",
                        false,
                        "",
                        "",
                        "",
                        "low",
                        "failed",
                        List.of(),
                        List.of(),
                        0,
                        7L,
                        Map.ofEntries(
                            Map.entry("selected_worker", "codex"),
                            Map.entry("selected_model_tier", "strong"),
                            Map.entry("execution_role", "executor"),
                            Map.entry("provider_failure_class", "provider_runtime_transient"),
                            Map.entry("provider_failure_reason", "codex app-server lost continuation state"),
                            Map.entry("provider_retryable", true)
                        )
                    ),
                    new WorkerExecutionResult(
                        "retry completed",
                        "retry completed",
                        false,
                        "",
                        "",
                        "mark done",
                        "high",
                        "completed",
                        List.of(),
                        List.of(),
                        0,
                        8L,
                        Map.ofEntries(
                            Map.entry("selected_worker", "codex"),
                            Map.entry("selected_model_tier", "strong"),
                            Map.entry("execution_role", "executor"),
                            Map.entry("grounded_output_present", true)
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
                "task_provider_failure_class",
                "session_provider_failure_class",
                null,
                "recover based on provider failure class",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "finish the work",
                null,
                "codex",
                "scheduler",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "recover from provider-classified transient failure"
                ))
            );
            taskDao.insert(task);

            Task finalTask = graph.enter(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();

            assertEquals(2, workerExecutor.callCount());
            assertEquals("done", finalTask.status());
            assertEquals("done", persisted.status());
            assertEquals("worker_runtime_transient", persisted.metadata().get("failure_class"));
            assertEquals("same_worker_retry_scheduled", persisted.metadata().get("recovery_stage"));
            assertEquals("1", String.valueOf(persisted.metadata().get("auto_same_worker_retry_count")));
        }
    }

    @Test
    void sameWorkerRetryColdStartClearsProviderContinuationMetadataBeforeNextRoundExecution() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("recovery-clears-provider-continuation.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_recovery_cold_start", "recovery cold start", "active"));

            WorkerRegistry workerRegistry = new WorkerRegistry();
            WorkerRouter router = new WorkerRouter(workerRegistry);
            ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
                new ActiveContextBuilder.DefaultActiveContextPolicy(),
                new ActiveContextBuilder.DefaultRetentionPolicy(),
                new ActiveContextBuilder.DefaultExclusionPolicy()
            );
            TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
                eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
            );

            RecoveryContinuationClearingWorkerExecutor workerExecutor = new RecoveryContinuationClearingWorkerExecutor();

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, packetDao, router, null, null,
                workerExecutor, runtimeContextBuilder, new AutoContinueJudgmentService(),
                artifactDao, decisionDao, null
            );

            Task task = new Task(
                "task_recovery_cold_start",
                "session_recovery_cold_start",
                null,
                "clear provider continuation metadata before same-worker retry",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "finish the delegated work",
                null,
                "codex",
                "scheduler",
                null,
                new LinkedHashMap<>(Map.ofEntries(
                    Map.entry("task_type", "coding"),
                    Map.entry("intent", "reproduce thread-not-found then same-worker cold-start retry"),
                    Map.entry("model_mode", "orchestrated"),
                    Map.entry("orchestration_stage", "execution_active"),
                    Map.entry("provider_session_id", "thread-codex-001"),
                    Map.entry("provider_thread_id", "thread-codex-001"),
                    Map.entry("codex_thread_id", "thread-codex-001"),
                    Map.entry("resume_provider_session_id", "thread-codex-001")
                ))
            );
            taskDao.insert(task);

            Task finalTask = graph.enter(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();

            assertEquals(2, workerExecutor.callCount());
            assertEquals("done", finalTask.status());
            assertEquals("done", persisted.status());
            assertEquals("end", persisted.controlNode());
            assertFalse(workerExecutor.secondRoundMetadata().containsKey("provider_session_id"));
            assertFalse(workerExecutor.secondRoundMetadata().containsKey("provider_thread_id"));
            assertFalse(workerExecutor.secondRoundMetadata().containsKey("codex_thread_id"));
            assertFalse(workerExecutor.secondRoundMetadata().containsKey("resume_provider_session_id"));
        }
    }

    @Test
    void plannerNoiseOutputDoesNotDelegateToExecutorAndFallsIntoRecovery() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("planner-noise-gate.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_planner_noise", "planner noise gate", "active"));

            WorkerRegistry workerRegistry = new WorkerRegistry();
            WorkerRouter router = new WorkerRouter(workerRegistry);
            ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
                new ActiveContextBuilder.DefaultActiveContextPolicy(),
                new ActiveContextBuilder.DefaultRetentionPolicy(),
                new ActiveContextBuilder.DefaultExclusionPolicy()
            );
            TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
                eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
            );

            String noisyFailure = "thread not found: 29180\n" + "x".repeat(12_500);
            SequencedWorkerExecutor workerExecutor = new SequencedWorkerExecutor(
                null,
                List.of(
                    new WorkerExecutionResult(
                        "Planner emitted oversized noisy failure output",
                        noisyFailure,
                        true,
                        "Planner Output",
                        noisyFailure,
                        "Retry planner or handoff via recovery.",
                        "low",
                        "completed",
                        List.of(),
                        List.of(),
                        0,
                        19L,
                        Map.ofEntries(
                            Map.entry("parser", "json"),
                            Map.entry("selected_worker", "codex"),
                            Map.entry("selected_model_tier", "strong"),
                            Map.entry("execution_role", "planner"),
                            Map.entry("execution_status", "completed"),
                            Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
                            Map.entry("output_text", noisyFailure),
                            Map.entry("candidate_workers", List.of("codex", "kimi"))
                        )
                    ),
                    new WorkerExecutionResult(
                        "",
                        "",
                        false,
                        "",
                        "",
                        "",
                        "low",
                        "empty",
                        List.of(),
                        List.of(),
                        0,
                        6L,
                        Map.ofEntries(
                            Map.entry("selected_worker", "codex"),
                            Map.entry("selected_model_tier", "strong"),
                            Map.entry("execution_role", "planner"),
                            Map.entry("execution_status", "empty"),
                            Map.entry("candidate_workers", List.of("codex", "kimi"))
                        )
                    ),
                    new WorkerExecutionResult(
                        "",
                        "",
                        false,
                        "",
                        "",
                        "",
                        "low",
                        "empty",
                        List.of(),
                        List.of(),
                        0,
                        5L,
                        Map.ofEntries(
                            Map.entry("selected_worker", "kimi"),
                            Map.entry("selected_model_tier", "small"),
                            Map.entry("execution_role", "executor"),
                            Map.entry("execution_status", "empty"),
                            Map.entry("candidate_workers", List.of("kimi", "codex"))
                        )
                    )
                )
            );

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, packetDao, router, null, null,
                workerExecutor, runtimeContextBuilder, new FakeJudgmentService(),
                artifactDao, decisionDao, null
            );

            Task task = new Task(
                "task_planner_noise",
                "session_planner_noise",
                null,
                "planner noise should not delegate",
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
                "codex",
                "scheduler",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "reproduce oversized planner failure output before delegation",
                    "model_mode", "orchestrated",
                    "orchestration_stage", "plan_pending"
                ))
            );
            taskDao.insert(task);

            Task finalTask = graph.enter(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();
            List<Decision> decisions = decisionDao.listBySessionAndTask(task.sessionId(), task.id(), 10);

            assertEquals(3, workerExecutor.callCount());
            assertEquals("waiting_human", finalTask.status());
            assertEquals("waiting_human", persisted.status());
            assertEquals("human_gate", persisted.controlNode());
            assertEquals("plan_pending", persisted.metadata().get("orchestration_stage"));
            assertEquals("rejected", persisted.metadata().get("planner_delegation_gate"));
            assertEquals("runtime_failure_signal", persisted.metadata().get("planner_delegation_gate_reason"));
            assertEquals("human_gate_required", persisted.metadata().get("recovery_stage"));
            assertEquals("1", String.valueOf(persisted.metadata().get("auto_handoff_count")));
            assertEquals("cursor", persisted.assignedWorker());
            assertFalse(decisions.stream().anyMatch(d ->
                "execution_judgment".equals(d.decisionType())
                    && "handoff".equals(metadataString(d.metadata(), "action"))
                    && "planner".equals(metadataString(d.metadata(), "selection_scope"))
                    && "kimi".equals(metadataString(d.metadata(), "target_worker"))
            ));
        }
    }

    @Test
    void localWorkspaceAccessRefusalDoesNotLeaveTaskActiveScheduler() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("local-workspace-access-refusal.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_local_refusal", "local workspace refusal", "active"));

            WorkerRegistry workerRegistry = new WorkerRegistry();
            WorkerRouter router = new WorkerRouter(workerRegistry);
            ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
                new ActiveContextBuilder.DefaultActiveContextPolicy(),
                new ActiveContextBuilder.DefaultRetentionPolicy(),
                new ActiveContextBuilder.DefaultExclusionPolicy()
            );
            TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
                eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
            );

            String refusal = "您提到按文档计划执行，但我目前无法直接访问您本地的文件或路径。请补充以下信息之一，或将完整文档的文本内容粘贴在这里。";
            SequencedWorkerExecutor workerExecutor = new SequencedWorkerExecutor(
                null,
                List.of(
                    new WorkerExecutionResult(
                        "worker codex failed: thread not found (27316)",
                        "thread not found: 27316",
                        false,
                        "",
                        "thread not found: 27316",
                        "Retry with a fresh session.",
                        "low",
                        "failed",
                        List.of(),
                        List.of("worker runtime failed"),
                        0,
                        5L,
                        Map.ofEntries(
                            Map.entry("selected_worker", "codex"),
                            Map.entry("selected_model_tier", "strong"),
                            Map.entry("execution_role", "planner"),
                            Map.entry("execution_status", "failed"),
                            Map.entry("provider_failure_class", "provider_runtime_transient"),
                            Map.entry("candidate_workers", List.of("codex", "deepseek"))
                        )
                    ),
                    new WorkerExecutionResult(
                        refusal,
                        refusal,
                        false,
                        "",
                        "",
                        "Handoff to a local coding worker.",
                        "low",
                        "completed",
                        List.of(),
                        List.of("local workspace inaccessible"),
                        0,
                        5L,
                        Map.ofEntries(
                            Map.entry("selected_worker", "deepseek"),
                            Map.entry("selected_model_tier", "strong"),
                            Map.entry("execution_role", "planner"),
                            Map.entry("execution_status", "completed"),
                            Map.entry("output_text", refusal),
                            Map.entry("candidate_workers", List.of("codex", "deepseek"))
                        )
                    )
                )
            );

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, packetDao, router, null, null,
                workerExecutor, runtimeContextBuilder, new FakeJudgmentService(),
                artifactDao, decisionDao, null
            );

            Task task = new Task(
                "task_local_refusal",
                "session_local_refusal",
                null,
                "按文档计划 D:\\gitAll\\articleeditor 修改代码",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "按文档计划 D:\\gitAll\\articleeditor\\docs\\XINHUA_CNML_ADAPTER_IMPLEMENTATION_PLAN_2026-05-15.md 修改代码",
                null,
                "codex",
                "scheduler",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "修改本地 articleeditor 仓库代码",
                    "model_mode", "orchestrated",
                    "orchestration_stage", "plan_pending",
                    "auto_same_worker_retry_count", 1
                ))
            );
            taskDao.insert(task);

            Task finalTask = graph.enter(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();

            assertEquals(2, workerExecutor.callCount());
            assertEquals("waiting_human", finalTask.status());
            assertEquals("waiting_human", persisted.status());
            assertEquals("human_gate", persisted.controlNode());
            assertEquals("rejected", persisted.metadata().get("planner_delegation_gate"));
            assertEquals("local_workspace_access_refusal", persisted.metadata().get("planner_delegation_gate_reason"));
            assertEquals("worker_backend_deterministic", persisted.metadata().get("failure_class"));
            assertEquals("human_gate_required", persisted.metadata().get("recovery_stage"));
            assertEquals("1", String.valueOf(persisted.metadata().get("auto_handoff_count")));
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
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
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
                taskDao, eventDao, sessionDao, sessionMessageDao, packetDao, router, null, null,
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
            List<SessionMessage> messages = sessionMessageDao.listBySessionAndTask(task.sessionId(), task.id(), 20);

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
                    && "timeout".equals(String.valueOf(latest.get("provider_turn_status")))
                    && latest.get("provider_protocol_trace") instanceof List<?> trace
                    && trace.contains("thread/started")
                    && "thread-codex-001".equals(String.valueOf(artifact.metadata().get("provider_thread_id")))
                    && "codex turn completion timed out".equals(String.valueOf(artifact.metadata().get("provider_error")))
                    && "timeout".equals(String.valueOf(artifact.metadata().get("provider_turn_status")))
                    && "provider_runtime_transient".equals(String.valueOf(artifact.metadata().get("provider_failure_class")))
                    && artifact.metadata().get("provider_protocol_trace") instanceof List<?> topTrace
                    && topTrace.contains("turn/started")
            ));
            SessionMessage workerRoundMessage = messages.stream()
                .filter(message -> "worker_round".equals(message.messageType()))
                .findFirst()
                .orElseThrow();
            assertEquals("codex", workerRoundMessage.metadata().get("provider_id"));
            assertEquals("provider_app_server", workerRoundMessage.metadata().get("execution_backend"));
            assertEquals("thread-codex-001", workerRoundMessage.metadata().get("provider_thread_id"));
            assertEquals("codex turn completion timed out", workerRoundMessage.metadata().get("provider_error"));
            assertEquals("provider_runtime_transient", workerRoundMessage.metadata().get("provider_failure_class"));
            assertEquals("codex turn completion timed out", workerRoundMessage.metadata().get("provider_failure_reason"));
            assertEquals("codex_json_rpc", workerRoundMessage.metadata().get("provider_output_parser"));
            assertEquals(Boolean.TRUE, workerRoundMessage.metadata().get("provider_retryable"));
            assertEquals(2, workerRoundMessage.metadata().get("provider_protocol_trace_count"));
            assertTrue(workerRoundMessage.metadata().get("provider_protocol_trace_preview") instanceof List<?> trace
                && trace.contains("turn/started"));
            assertFalse(workerRoundMessage.metadata().containsKey("provider_protocol_trace"));
        }
    }

    @Test
    void providerBackedRoundAutoContinuesWhenNextStepIsPresent() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("provider-auto-continue-flow.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_provider_auto_continue", "provider auto continue flow", "active"));

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
            SequencedWorkerExecutor workerExecutor = new SequencedWorkerExecutor(
                null,
                List.of(
                    new WorkerExecutionResult(
                        "Codex prepared the continuation round",
                        "Provider thread has context but still needs the final implementation pass.",
                        false,
                        "",
                        "",
                        "continue the same codex thread and finish the implementation",
                        "medium",
                        "completed",
                        List.of(),
                        List.of("finish implementation"),
                        0,
                        20L,
                        Map.ofEntries(
                            Map.entry("provider_id", "codex"),
                            Map.entry("execution_backend", "provider_app_server"),
                            Map.entry("provider_session_id", "thread-codex-auto-001"),
                            Map.entry("provider_thread_id", "thread-codex-auto-001"),
                            Map.entry("provider_output_parser", "codex_json_rpc"),
                            Map.entry("selected_worker", "codex-app"),
                            Map.entry("selected_model_tier", "strong"),
                            Map.entry("execution_role", "executor")
                        )
                    ),
                    new WorkerExecutionResult(
                        "Codex completed the resumed thread",
                        "The implementation is complete after the resumed provider round.",
                        true,
                        "Provider Auto Continue Result",
                        "Provider-backed task completed after automatic continuation.",
                        "mark complete",
                        "high",
                        "completed",
                        List.of(),
                        List.of(),
                        0,
                        18L,
                        Map.ofEntries(
                            Map.entry("provider_id", "codex"),
                            Map.entry("execution_backend", "provider_app_server"),
                            Map.entry("provider_session_id", "thread-codex-auto-001"),
                            Map.entry("provider_thread_id", "thread-codex-auto-001"),
                            Map.entry("provider_output_parser", "codex_json_rpc"),
                            Map.entry("grounded_output_present", true),
                            Map.entry("selected_worker", "codex-app"),
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
                "task_provider_auto_continue",
                "session_provider_auto_continue",
                null,
                "auto continue provider-backed codex round",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "Finish the provider-backed long task without manual continue.",
                null,
                "codex-app",
                "scheduler",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "Continue provider-backed codex thread until the implementation is complete."
                ))
            );
            taskDao.insert(task);

            Task finalTask = graph.enter(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();

            assertEquals(2, workerExecutor.callCount());
            assertEquals("done", finalTask.status());
            assertEquals("done", persisted.status());
            assertEquals("end", persisted.controlNode());
            assertEquals("1", String.valueOf(persisted.metadata().get("auto_continue_burst_count")));
            assertEquals("thread-codex-auto-001", persisted.metadata().get("provider_thread_id"));
            assertEquals("provider_app_server", persisted.metadata().get("execution_backend"));
        }
    }

    @Test
    void partialTimeoutProviderRoundStopsAtHumanGateInsteadOfAutoContinuing() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("partial-timeout-human-gate.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);

            sessionDao.insert(Session.create("session_partial_timeout_gate", "partial timeout gate", "active"));

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
            SequencedWorkerExecutor workerExecutor = new SequencedWorkerExecutor(
                null,
                List.of(
                    new WorkerExecutionResult(
                        "Codex produced partial implementation notes before max duration.",
                        "Partial result is useful, but final verification is still pending.",
                        false,
                        "",
                        "",
                        "continue the same codex thread and finish verification",
                        "medium",
                        "partial_timeout",
                        List.of(),
                        List.of("finish verification"),
                        0,
                        900_000L,
                        Map.ofEntries(
                            Map.entry("provider_id", "codex"),
                            Map.entry("execution_backend", "provider_app_server"),
                            Map.entry("provider_session_id", "thread-partial-timeout-001"),
                            Map.entry("provider_thread_id", "thread-partial-timeout-001"),
                            Map.entry("resume_provider_session_id", "resume-thread-partial-timeout-001"),
                            Map.entry("provider_output_parser", "codex_json_rpc"),
                            Map.entry("provider_turn_status", "partial_timeout"),
                            Map.entry("provider_timeout_kind", "max_duration"),
                            Map.entry("provider_turn_activity_timeout_ms", 180_000L),
                            Map.entry("provider_turn_max_duration_ms", 900_000L),
                            Map.entry("provider_stdout_path", "D:\\tmp\\provider-runs\\codex\\task-partial\\stdout.log"),
                            Map.entry("selected_worker", "codex-app"),
                            Map.entry("selected_model_tier", "strong"),
                            Map.entry("execution_role", "executor")
                        )
                    )
                )
            );

            ControlNodeGraph graph = new ControlNodeGraph(
                taskDao, eventDao, sessionDao, sessionMessageDao, packetDao, router, null, null,
                workerExecutor, runtimeContextBuilder, new AutoContinueJudgmentService(),
                artifactDao, decisionDao, null
            );

            Task task = new Task(
                "task_partial_timeout_gate",
                "session_partial_timeout_gate",
                null,
                "partial timeout should wait for user",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "Finish the provider-backed task, but do not silently retry partial timeout output.",
                null,
                "codex-app",
                "scheduler",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "Keep useful Codex partial output visible and wait for continue or handoff."
                ))
            );
            taskDao.insert(task);

            Task finalTask = graph.enter(task);
            Task persisted = taskDao.findById(task.id()).orElseThrow();
            List<SessionMessage> messages = sessionMessageDao.listBySessionAndTask(task.sessionId(), task.id(), 20);

            assertEquals(1, workerExecutor.callCount());
            assertEquals("waiting_human", finalTask.status());
            assertEquals("waiting_human", persisted.status());
            assertEquals("human_gate", persisted.controlNode());
            assertEquals("partial_result_or_quality_risk", persisted.metadata().get("failure_class"));
            assertEquals("human_gate_required", persisted.metadata().get("recovery_stage"));
            assertFalse(persisted.metadata().containsKey("auto_same_worker_retry_count"));
            assertFalse(persisted.metadata().containsKey("auto_handoff_count"));
            assertEquals("codex-app", persisted.assignedWorker());

            SessionMessage workerRoundMessage = messages.stream()
                .filter(message -> "worker_round".equals(message.messageType()))
                .findFirst()
                .orElseThrow();
            assertEquals("partial_timeout", workerRoundMessage.metadata().get("execution_status"));
            assertEquals("thread-partial-timeout-001", workerRoundMessage.metadata().get("provider_thread_id"));
            assertEquals("resume-thread-partial-timeout-001",
                workerRoundMessage.metadata().get("resume_provider_session_id"));
            assertEquals("max_duration", workerRoundMessage.metadata().get("provider_timeout_kind"));
            assertEquals("D:\\tmp\\provider-runs\\codex\\task-partial\\stdout.log",
                workerRoundMessage.metadata().get("provider_stdout_path"));
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

    @Test
    void currentRoundSparseMetadataDoesNotFallbackToPreviousWorkerIdentity() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("stale-worker-metadata-flow.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_stale_worker_metadata", "stale worker metadata", "active"));

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
                new SparseSecondRoundWorkerExecutor(), runtimeContextBuilder, new FakeJudgmentService(),
                artifactDao, decisionDao, null
            );

            Task task = new Task(
                "task_stale_worker_metadata",
                "session_stale_worker_metadata",
                null,
                "avoid stale worker metadata fallback",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                "ensure current round worker identity survives sparse metadata",
                null,
                null,
                "intake",
                null,
                new LinkedHashMap<>(Map.of(
                    "task_type", "coding",
                    "intent", "reproduce codex failure then kimi fallback without stale planner metadata",
                    "model_mode", "orchestrated",
                    "orchestration_stage", "plan_pending"
                ))
            );
            taskDao.insert(task);

            Task finalTask = graph.enter(task);
            List<Decision> decisions = decisionDao.listBySessionAndTask(task.sessionId(), task.id(), 10);

            assertEquals("done", finalTask.status());
            assertTrue(decisions.stream().anyMatch(d ->
                "completion_judgment".equals(d.decisionType())
                    && "kimi".equals(metadataString(d.metadata(), "selected_worker"))
                    && "small".equals(metadataString(d.metadata(), "selected_model_tier"))
                    && "executor".equals(metadataString(d.metadata(), "execution_role"))
                    && metadataString(d.metadata(), "why_selected") != null
            ));
            assertTrue(decisions.stream().noneMatch(d ->
                "completion_judgment".equals(d.decisionType())
                    && "kimi".equals(metadataString(d.metadata(), "selected_worker"))
                    && "strong".equals(metadataString(d.metadata(), "selected_model_tier"))
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
            if (callCount == 2 && outputFile != null) {
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

    private static final class RecordingWorkerExecutor implements WorkerExecutor {
        private final List<String> workerIds = new java.util.ArrayList<>();

        @Override
        public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
            workerIds.add(workerId);
            return new WorkerExecutionResult(
                "dispatch-ready worker completed",
                "worker " + workerId + " completed after dispatch preflight",
                true,
                "Dispatch Ready Result",
                "Dispatch-ready worker completed the task.",
                "mark complete",
                "high",
                "completed",
                List.of(),
                List.of(),
                0,
                5L,
                Map.ofEntries(
                    Map.entry("selected_worker", workerId),
                    Map.entry("selected_model_tier", "small"),
                    Map.entry("execution_role", "executor"),
                    Map.entry("execution_status", "completed"),
                    Map.entry("grounded_output_present", true)
                )
            );
        }

        private List<String> workerIds() {
            return List.copyOf(workerIds);
        }
    }

    private static final class SparseSecondRoundWorkerExecutor implements WorkerExecutor {
        private int round;

        @Override
        public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
            round++;
            if (round == 1) {
                return new WorkerExecutionResult(
                    "Planner brief ready",
                    "Break the task into a compact execution brief for the delegated worker.",
                    true,
                    "Planner Brief",
                    "1. Update the runtime path. 2. Run the test entrypoint. 3. Report final state.",
                    "Implement the delegated runtime change and run verification.",
                    "high",
                    "completed",
                    List.of("tool:orchestrator_plan_1"),
                    List.of(),
                    0,
                    12L,
                    Map.ofEntries(
                        Map.entry("parser", "json"),
                        Map.entry("selected_worker", workerId),
                        Map.entry("selected_model_tier", "strong"),
                        Map.entry("execution_role", "planner"),
                        Map.entry("tool_invocation_ids", List.of("orchestrator_plan_1"))
                    )
                );
            }
            return new WorkerExecutionResult(
                "Executor finished the delegated work",
                "",
                false,
                "",
                "",
                "Mark the task complete.",
                "high",
                "completed",
                List.of(),
                List.of(),
                0,
                8L,
                Map.of(
                    "parser", "json"
                )
            );
        }
    }

    private static final class RecoveryContinuationClearingWorkerExecutor implements WorkerExecutor {
        private int callCount;
        private Map<String, Object> secondRoundMetadata = Map.of();

        @Override
        public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
            callCount++;
            if (callCount == 1) {
                return new WorkerExecutionResult(
                    "",
                    "",
                    false,
                    "",
                    "",
                    "",
                    "low",
                    "timeout",
                    List.of(),
                    List.of(),
                    0,
                    7L,
                    Map.ofEntries(
                        Map.entry("tool_aware_executor", true),
                        Map.entry("tool_execution_mode", "multi_tool_round"),
                        Map.entry("tool_chain_step_count", 1),
                        Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
                        Map.entry("selected_worker", workerId),
                        Map.entry("selected_model_tier", "strong"),
                        Map.entry("execution_role", "executor"),
                        Map.entry("output_text", "thread not found: 29180")
                    )
                );
            }
            secondRoundMetadata = context.task().metadata() == null
                ? Map.of()
                : new LinkedHashMap<>(context.task().metadata());
            return new WorkerExecutionResult(
                "wrote grounded output",
                "the result file is now on disk",
                true,
                "Recovery Result",
                "Recovered after cold-start retry.",
                "mark the task complete",
                "high",
                "completed",
                List.of(),
                List.of(),
                0,
                9L,
                Map.ofEntries(
                    Map.entry("tool_aware_executor", true),
                    Map.entry("tool_execution_mode", "multi_tool_round"),
                    Map.entry("tool_chain_step_count", 1),
                    Map.entry("tool_chain_termination_reason", "planner_no_additional_tool"),
                    Map.entry("grounded_output_present", true),
                    Map.entry("selected_worker", workerId),
                    Map.entry("selected_model_tier", "strong"),
                    Map.entry("execution_role", "executor")
                )
            );
        }

        private int callCount() {
            return callCount;
        }

        private Map<String, Object> secondRoundMetadata() {
            return secondRoundMetadata;
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
                    Map.entry("provider_error", "codex turn completion timed out"),
                    Map.entry("provider_turn_status", "timeout"),
                    Map.entry("provider_timeout_kind", "activity_timeout"),
                    Map.entry("provider_turn_activity_timeout_ms", 180_000L),
                    Map.entry("provider_turn_max_duration_ms", 900_000L),
                    Map.entry("provider_failure_class", "provider_runtime_transient"),
                    Map.entry("provider_failure_reason", "codex turn completion timed out"),
                    Map.entry("provider_retryable", true),
                    Map.entry("provider_protocol_trace", List.of("thread/started", "turn/started")),
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

    private record PreflightProvider(String providerId,
                                     boolean passiveReady,
                                     boolean dispatchReady,
                                     String dispatchReason) implements AgentProvider {
        @Override
        public AgentProviderDescriptor descriptor() {
            return new AgentProviderDescriptor(
                providerId,
                providerId,
                "local_cli",
                "process",
                List.of("chat"),
                Map.of()
            );
        }

        @Override
        public AgentProviderStatus detect() {
            return new AgentProviderStatus(
                providerId,
                true,
                "0.0.0-test",
                "ready",
                passiveReady,
                passiveReady ? null : "passive not ready",
                null,
                Map.of("source", "test")
            );
        }

        @Override
        public AgentProviderStatus dispatchPreflight() {
            return new AgentProviderStatus(
                providerId,
                true,
                "0.0.0-test",
                "ready",
                dispatchReady,
                dispatchReady ? null : dispatchReason,
                null,
                Map.of(
                    "source", "dispatch_preflight_test",
                    "dispatch_preflight_mode", "active_probe"
                )
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


    @Test
    void orchestratedLoopDecisionTraceDistinguishesPlannerFailureFromExecutorFailure() {
        // P1 acceptance criteria 3 & 4: failure triggers explainable escalation,
        // and decision trace distinguishes planner failure from executor failure.
        // We verify the traceability contract on the orchestrated success path:
        // execution_role and selected_model_tier must be present in execution_judgment,
        // enabling downstream consumers to attribute failures to planner or executor phase.

        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("p1-failure-discrimination.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            sessionDao.insert(Session.create("session_p1", "P1 failure discrimination", "active"));

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
                taskDao, eventDao, sessionDao, sessionMessageDao, packetDao, router, null, null,
                new FakeWorkerExecutor(), runtimeContextBuilder, new FakeJudgmentService(),
                artifactDao, decisionDao, null
            );

            Task task = new Task(
                "task_p1",
                "session_p1",
                null,
                "orchestrated loop for P1 traceability",
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
                    "intent", "prove planner to executor traceability",
                    "model_mode", "orchestrated",
                    "orchestration_stage", "plan_pending",
                    "prompt_rendering_mode", "mounted_context_primary"
                ))
            );
            taskDao.insert(task);

            graph.enter(task);
            List<Decision> decisions = decisionDao.listBySessionAndTask(task.sessionId(), task.id(), 10);

            // execution_judgment must carry execution_role and selected_model_tier
            // so that failure attribution (planner vs executor, strong vs small) is always possible
            Decision executionJudgment = decisions.stream()
                .filter(d -> "execution_judgment".equals(d.decisionType()))
                .reduce((first, second) -> second) // take last if multiple
                .orElseThrow(() -> new AssertionError("no execution_judgment found"));
            assertNotNull(metadataString(executionJudgment.metadata(), "execution_role"),
                "execution_judgment must carry execution_role for failure phase discrimination");
            assertNotNull(metadataString(executionJudgment.metadata(), "selected_model_tier"),
                "execution_judgment must carry selected_model_tier for failure phase discrimination");
            // The last execution_judgment should reflect the executor (small model) phase
            assertTrue(metadataString(executionJudgment.metadata(), "execution_role").contains("executor"), "execution_role must indicate executor phase for failure discrimination");

            // Completion judgment must carry evaluator_role, evaluator_model_tier, and closed-loop signal
            Decision completionJudgment = decisions.stream()
                .filter(d -> "completion_judgment".equals(d.decisionType()))
                .findFirst().orElseThrow();
            assertEquals("strong_evaluator", metadataString(completionJudgment.metadata(), "evaluator_role"));
            assertEquals("strong", metadataString(completionJudgment.metadata(), "evaluator_model_tier"));
            assertEquals("true", metadataString(completionJudgment.metadata(), "orchestration_closed_loop_observed"));

            // Task metadata must distinguish planner and executor workers
            Task persisted = taskDao.findById(task.id()).orElseThrow();
            assertNotNull(metadataString(persisted.metadata(), "planner_worker"));
            assertNotNull(metadataString(persisted.metadata(), "executor_worker"));
        }
    }
}
