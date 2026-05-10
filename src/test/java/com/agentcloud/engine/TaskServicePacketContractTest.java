package com.agentcloud.engine;

import com.agentcloud.engine.memory.PacketBuilder;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.judgment.JudgmentContext;
import com.agentcloud.judgment.JudgmentService;
import com.agentcloud.judgment.model.CompletionDecision;
import com.agentcloud.judgment.model.ExecutionDecision;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.HandoffPacketView;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.model.TaskControlResult;
import com.agentcloud.runtime.ActiveContextBuilder;
import com.agentcloud.runtime.RuntimeFactSetAssembler;
import com.agentcloud.runtime.RuntimeFactSurfaceExporter;
import com.agentcloud.runtime.TaskRuntimeContextBuilder;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.CheckpointDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ResumePacketDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServicePacketContractTest {

    @TempDir
    Path tempDir;

    @Test
    void refreshResumePacketPersistsTypedContinuityFields() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-service-resume-packet.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            TaskService service = service(db);

            Session session = Session.create("session_packet_1", "resume packet session", "active");
            sessionDao.insert(session);

            Task task = new Task(
                "task_packet_1",
                session.id(),
                null,
                "solidify resume packet contract",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                "Planner summary is ready.",
                "Finalize a stable resume packet contract.",
                "Continue executor validation.",
                "codex",
                "continue",
                "Need one persisted protocol sample.",
                Map.of(
                    "task_type", "coding",
                    "prompt_mode", "mounted_context_primary",
                    "open_questions", List.of("Should the packet preserve legacy summaries?"),
                    "blockers", List.of("Need one persisted protocol sample.")
                )
            );
            taskDao.insert(task);
            decisionDao.insert(new Decision(
                "dec_packet_1",
                session.id(),
                task.id(),
                Instant.now(),
                "execution_judgment",
                "Planner kept the protocol machine-readable first.",
                "Should the packet preserve legacy summaries?",
                "medium",
                null,
                Map.of("open_question", "Should the packet preserve legacy summaries?")
            ));
            artifactDao.insert(new Artifact(
                "art_packet_1",
                session.id(),
                task.id(),
                Instant.now(),
                "worker_output",
                "Planner protocol brief",
                null,
                null,
                "Typed packet fields are now aligned.",
                Map.of("selected_model_tier", "strong")
            ));

            service.refreshResumePacket(task.id());
            ResumePacket persisted = service.getLatestPacket(task.id());

            assertNotNull(persisted);
            assertEquals("1.1", persisted.packetVersion());
            assertEquals("task_packet_1", persisted.taskIdentity().taskId());
            assertEquals("session_packet_1", persisted.taskIdentity().sessionId());
            assertEquals("coding", persisted.taskIdentity().taskType());
            assertEquals("Finalize a stable resume packet contract.", persisted.currentObjective());
            assertEquals("active", persisted.currentStatus());
            assertEquals("continue", persisted.currentNode());
            assertEquals("codex", persisted.assignedWorker());
            assertEquals("Planner summary is ready.", persisted.latestSummary());
            assertEquals("Continue executor validation.", persisted.nextStep());
            assertTrue(persisted.blockers().contains("Need one persisted protocol sample."));
            assertTrue(persisted.openQuestions().contains("Should the packet preserve legacy summaries?"));
            assertEquals(1, persisted.recentArtifacts().size());
            assertEquals("Planner protocol brief", persisted.recentArtifacts().get(0).title());
            assertEquals(1, persisted.recentDecisions().size());
            assertEquals("execution_judgment", persisted.recentDecisions().get(0).decisionType());
            assertEquals("Continue executor validation.", persisted.payload().get("next_step"));
            assertEquals(Boolean.TRUE, persisted.payload().get("machine_readable_first"));
            assertEquals("mounted_context_primary", persisted.payload().get("prompt_rendering_mode"));
            assertEquals("mounted_context_primary", persisted.payload().get("mounted_context_mode"));
            assertEquals("mounted_context_primary", persisted.payload().get("prompt_mode"));
            assertEquals(Boolean.TRUE, persisted.machineReadableFirst());
        }
    }

    @Test
    void handoffPacketPreviewExposesTypedMinimalProtocol() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-service-handoff-packet.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            TaskService service = service(db);

            Session session = Session.create("session_packet_2", "handoff packet session", "active");
            sessionDao.insert(session);

            Task task = new Task(
                "task_packet_2",
                session.id(),
                null,
                "complete orchestrated execution",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                "Planner phase is done and executor should continue.",
                "Ship the task through executor continuation.",
                "Apply the final executor patch.",
                "codex",
                "handoff",
                "Need executor continuation.",
                Map.of(
                    "task_type", "coding",
                    "model_mode", "orchestrated",
                    "orchestration_stage", "execution_pending",
                    "prompt_mode", "mounted_context_shadow",
                    "planner_worker", "codex",
                    "executor_worker", "kimi",
                    "open_questions", List.of("Should executor keep the current file layout?")
                )
            );
            taskDao.insert(task);
            taskDao.insert(new Task(
                "task_packet_2_done",
                session.id(),
                task.id(),
                "prepare executor brief",
                "done",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                "Executor brief prepared.",
                null,
                null,
                "codex",
                "end",
                null,
                Map.of()
            ));
            taskDao.insert(new Task(
                "task_packet_2_pending",
                session.id(),
                task.id(),
                "apply executor patch",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                null,
                "Apply the final executor patch.",
                "kimi",
                "scheduler",
                null,
                Map.of()
            ));
            decisionDao.insert(new Decision(
                "dec_packet_2",
                session.id(),
                task.id(),
                Instant.now(),
                "completion_judgment",
                "Planner output is ready for executor handoff.",
                "The remaining work is execution-heavy.",
                "medium",
                null,
                Map.of()
            ));
            artifactDao.insert(new Artifact(
                "art_packet_2",
                session.id(),
                task.id(),
                Instant.now(),
                "worker_output",
                "Planner delegation brief",
                null,
                null,
                "Executor can continue from this brief.",
                Map.of("selected_model_tier", "strong")
            ));

            HandoffPacketView view = service.getHandoffPacket(task.id(), "kimi");

            assertEquals("task_packet_2", view.taskId());
            assertEquals("codex", view.fromWorker());
            assertEquals("kimi", view.toWorker());
            assertEquals("1.0", view.handoffPacket().packetVersion());
            assertEquals(Boolean.TRUE, view.handoffPacket().machineReadableFirst());
            assertEquals("task_packet_2", view.handoffPacket().taskIdentity().taskId());
            assertEquals("Ship the task through executor continuation.", view.handoffPacket().currentObjective());
            assertEquals("active", view.handoffPacket().currentStatus());
            assertEquals("handoff", view.handoffPacket().currentNode());
            assertTrue(view.handoffPacket().whyHandoff().contains("delegated execution"));
            assertTrue(view.handoffPacket().whatDone().stream().anyMatch(item -> item.contains("Executor brief prepared")));
            assertTrue(view.handoffPacket().whatRemaining().stream().anyMatch(item -> item.contains("Apply the final executor patch")));
            assertTrue(view.handoffPacket().cautions().stream().anyMatch(item -> item.contains("Need executor continuation")));
            assertEquals("Apply the final executor patch.", view.handoffPacket().resumeHint());
            assertEquals("orchestrated", view.handoffPacket().metadata().get("model_mode"));
            assertEquals("execution_pending", view.handoffPacket().metadata().get("orchestration_stage"));
            assertEquals("mounted_context_shadow", view.handoffPacket().metadata().get("prompt_rendering_mode"));
            assertEquals("mounted_context_shadow", view.handoffPacket().metadata().get("mounted_context_mode"));
            assertEquals("mounted_context_shadow", view.handoffPacket().metadata().get("prompt_mode"));
            Map<?, ?> runtimeFacts = assertInstanceOf(Map.class, view.handoffPacket().metadata().get("runtime_facts"));
            assertEquals("task_packet_2", runtimeFacts.get("task_id"));
            assertEquals("Apply the final executor patch.", runtimeFacts.get("recommended_next_step"));
            Map<?, ?> routePreview = assertInstanceOf(Map.class, runtimeFacts.get("route_preview"));
            assertEquals("codex", routePreview.get("selected_worker"));
            Map<?, ?> runtimeCognitionSurface =
                assertInstanceOf(Map.class, view.handoffPacket().metadata().get("runtime_cognition_surface"));
            Map<?, ?> routeSurface = assertInstanceOf(Map.class, runtimeCognitionSurface.get("route"));
            assertEquals("codex", routeSurface.get("selected_worker"));
            Map<?, ?> executionSurface = assertInstanceOf(Map.class, runtimeCognitionSurface.get("execution"));
            assertEquals("mounted_context_shadow", executionSurface.get("prompt_mode"));
        }
    }

    @Test
    void pauseTaskPersistsResumePacketAndPauseCheckpoint() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-service-pause-packet.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);
            TaskService service = serviceWithControlGraph(db);

            Session session = Session.create("session_packet_3", "pause packet session", "active");
            sessionDao.insert(session);

            Task task = new Task(
                "task_packet_3",
                session.id(),
                null,
                "persist pause packet",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                "Pause boundary is ready.",
                "Persist the packet before pause.",
                "Resume after review.",
                "codex",
                "continue",
                null,
                Map.of(
                    "task_type", "coding",
                    "prompt_mode", "mounted_context_primary",
                    "open_questions", List.of("Should pause emit a stored packet?")
                )
            );
            taskDao.insert(task);
            decisionDao.insert(new Decision(
                "dec_packet_3",
                session.id(),
                task.id(),
                Instant.now(),
                "execution_judgment",
                "Pause packet should be durable.",
                "Should pause emit a stored packet?",
                "high",
                null,
                Map.of("open_question", "Should pause emit a stored packet?")
            ));
            artifactDao.insert(new Artifact(
                "art_packet_3",
                session.id(),
                task.id(),
                Instant.now(),
                "worker_output",
                "Pause protocol brief",
                null,
                null,
                "Packet must be queryable after pause.",
                Map.of()
            ));

            var result = service.pauseTask(task.id(), "needs review");
            ResumePacket persistedPacket = packetDao.getLatestByTask(session.id(), task.id()).orElseThrow();
            Checkpoint persistedCheckpoint = checkpointDao.listByTask(task.id(), 10).stream()
                .findFirst()
                .orElseThrow();

            assertEquals("paused", result.state());
            assertEquals("scheduler", result.controlNode());
            assertEquals("codex", result.assignedWorker());
            assertTrue(result.packetRefreshed());
            assertEquals(persistedPacket.id(), result.resumePacketId());

            assertEquals("task_packet_3", persistedPacket.taskIdentity().taskId());
            assertEquals("paused", persistedPacket.currentStatus());
            assertEquals("packet", persistedPacket.currentNode());
            assertEquals("Persist the packet before pause.", persistedPacket.currentObjective());
            assertEquals("Resume after review.", persistedPacket.nextStep());
            assertTrue(persistedPacket.blockers().contains("needs review"));
            assertTrue(persistedPacket.blockers().contains("task_paused"));
            assertTrue(persistedPacket.openQuestions().contains("Should pause emit a stored packet?"));
            assertEquals("mounted_context_primary", persistedPacket.payload().get("prompt_rendering_mode"));
            assertEquals("mounted_context_primary", persistedPacket.payload().get("mounted_context_mode"));
            assertEquals("mounted_context_primary", persistedPacket.payload().get("prompt_mode"));

            assertEquals("pause_before", persistedCheckpoint.checkpointType());
            @SuppressWarnings("unchecked")
            List<String> keyArtifacts = (List<String>) persistedCheckpoint.refinedPacket().get("key_artifacts");
            assertTrue(keyArtifacts.contains("Pause protocol brief: Packet must be queryable after pause."));
        }
    }

    @Test
    void handoffTaskProjectsSharedRuntimeFactSurfaceIntoControlActionEvent() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-service-handoff-event.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            TaskService service = serviceWithControlGraph(db);

            Session session = Session.create("session_packet_4", "handoff event session", "active");
            sessionDao.insert(session);

            Task task = new Task(
                "task_packet_4",
                session.id(),
                null,
                "project handoff continuity event",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                "Planner phase is complete.",
                "Ship handoff continuity through lifecycle events.",
                "Apply the final executor patch.",
                "codex",
                "continue",
                "Need executor continuation.",
                Map.of(
                    "task_type", "coding",
                    "model_mode", "orchestrated",
                    "orchestration_stage", "execution_pending",
                    "prompt_mode", "mounted_context_shadow",
                    "planner_worker", "codex",
                    "executor_worker", "kimi",
                    "route_source", "preassigned",
                    "candidate_workers", List.of("codex", "kimi")
                )
            );
            taskDao.insert(task);
            taskDao.insert(new Task(
                "task_packet_4_done",
                session.id(),
                task.id(),
                "prepare executor brief",
                "done",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                "Executor brief prepared.",
                null,
                null,
                "codex",
                "end",
                null,
                Map.of()
            ));
            taskDao.insert(new Task(
                "task_packet_4_pending",
                session.id(),
                task.id(),
                "apply executor patch",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                null,
                "Apply the final executor patch.",
                "kimi",
                "scheduler",
                null,
                Map.of()
            ));
            decisionDao.insert(new Decision(
                "dec_packet_4",
                session.id(),
                task.id(),
                Instant.now(),
                "completion_judgment",
                "Planner output is ready for executor handoff.",
                "The remaining work is execution-heavy.",
                "medium",
                null,
                Map.of()
            ));
            artifactDao.insert(new Artifact(
                "art_packet_4",
                session.id(),
                task.id(),
                Instant.now(),
                "worker_output",
                "Planner delegation brief",
                null,
                null,
                "Executor can continue from this brief.",
                Map.of("selected_model_tier", "strong")
            ));

            service.handoffTask(task.id(), "kimi");

            Event handoffEvent = eventDao.listBySessionAndTask(session.id(), task.id(), 20).stream()
                .filter(event -> "task_control_action".equals(event.eventType()))
                .filter(event -> "handoff".equals(String.valueOf(event.payload().get("action"))))
                .findFirst()
                .orElseThrow();

            assertEquals("kimi", handoffEvent.payload().get("assigned_worker"));
            assertEquals("codex", handoffEvent.payload().get("previous_worker"));
            assertEquals("kimi", handoffEvent.payload().get("target_worker"));
            assertEquals("mounted_context_shadow", handoffEvent.payload().get("prompt_mode"));
            @SuppressWarnings("unchecked")
            Map<String, Object> runtimeFacts = (Map<String, Object>) handoffEvent.payload().get("runtime_facts");
            assertEquals("task_packet_4", runtimeFacts.get("task_id"));
            assertEquals("Apply the final executor patch.", runtimeFacts.get("recommended_next_step"));
            @SuppressWarnings("unchecked")
            Map<String, Object> runtimeSurface = (Map<String, Object>) handoffEvent.payload().get("runtime_cognition_surface");
            @SuppressWarnings("unchecked")
            Map<String, Object> routeSurface = (Map<String, Object>) runtimeSurface.get("route");
            @SuppressWarnings("unchecked")
            Map<String, Object> executionSurface = (Map<String, Object>) runtimeSurface.get("execution");
            assertEquals("codex", routeSurface.get("selected_worker"));
            assertEquals("preassigned", routeSurface.get("route_source"));
            assertEquals("mounted_context_shadow", executionSurface.get("prompt_mode"));
        }
    }

    @Test
    void continueTaskPersistsArchiveRetrievalCheckpointWhenJudgmentRequestsIt() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-service-archive-retrieval-checkpoint.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);

            Session session = Session.create("session_packet_5", "archive retrieval session", "active");
            sessionDao.insert(session);

            Task task = new Task(
                "task_packet_5",
                session.id(),
                null,
                "persist archive retrieval checkpoint",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                "Evidence is incomplete.",
                "Persist explicit archive retrieval lifecycle checkpoints.",
                "Reopen archived tool evidence before next round.",
                "codex",
                "continue",
                null,
                Map.of(
                    "task_type", "coding",
                    "prompt_mode", "mounted_context_primary",
                    "open_questions", List.of("Which archived tool trace should be reopened?")
                )
            );
            taskDao.insert(task);
            artifactDao.insert(new Artifact(
                "art_packet_5",
                session.id(),
                task.id(),
                Instant.now(),
                "worker_output",
                "Current evidence snapshot",
                null,
                null,
                "The worker needs archived tool traces to continue safely.",
                Map.ofEntries(
                    Map.entry("selected_worker", "codex"),
                    Map.entry("selected_model_tier", "strong"),
                    Map.entry("execution_role", "executor"),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("tool_invocation_ids", List.of("tool_archive_1")),
                    Map.entry("evidence_refs", List.of("tool:read_file:archive-notes.txt")),
                    Map.entry("unfinished_items", List.of("reopen archived tool evidence")),
                    Map.entry("route_source", "preassigned")
                )
            ));

            TaskService service = serviceWithArchiveRetrievalControlGraph(db);

            TaskControlResult result = service.continueTask(task.id());
            ResumePacket persistedPacket = packetDao.getLatestByTask(session.id(), task.id()).orElseThrow();
            Checkpoint persistedCheckpoint = checkpointDao.listByTask(task.id(), 10).stream()
                .findFirst()
                .orElseThrow();

            assertEquals("active", result.state());
            assertEquals("scheduler", result.controlNode());
            assertEquals("archive_retrieval_before", persistedCheckpoint.checkpointType());
            assertEquals("packet", persistedPacket.currentNode());
            assertEquals("active", persistedPacket.currentStatus());
            assertEquals("Reopen archived tool evidence before next round.", persistedPacket.nextStep());
            assertNotNull(persistedCheckpoint.refinedPacket().get("runtime_cognition_surface"));
            @SuppressWarnings("unchecked")
            Map<String, Object> runtimeSurface =
                (Map<String, Object>) persistedCheckpoint.refinedPacket().get("runtime_cognition_surface");
            assertNotNull(runtimeSurface);
            @SuppressWarnings("unchecked")
            Map<String, Object> executionJudgment =
                (Map<String, Object>) runtimeSurface.get("execution_judgment");
            assertNotNull(executionJudgment);
            assertEquals(Boolean.TRUE, executionJudgment.get("needs_archive_retrieval"));
            assertEquals(Boolean.TRUE, executionJudgment.get("evidence_gap_detected"));
            assertEquals(Boolean.TRUE, executionJudgment.get("needs_external_fact_refresh"));
        }
    }

    @Test
    void continueTaskPersistsExternalFactRefreshCheckpointWhenJudgmentRequestsIt() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("task-service-external-fact-refresh-checkpoint.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            Session session = Session.create("session_packet_6", "external fact refresh session", "active");
            sessionDao.insert(session);

            Task task = new Task(
                "task_packet_6",
                session.id(),
                null,
                "persist external fact refresh checkpoint",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                "Local evidence is bounded but outdated.",
                "Persist explicit external fact refresh lifecycle checkpoints.",
                "Refresh external facts before next round.",
                "codex",
                "continue",
                null,
                Map.of(
                    "task_type", "research",
                    "prompt_mode", "mounted_context_primary",
                    "open_questions", List.of("Which external fact source should be refreshed?")
                )
            );
            taskDao.insert(task);
            artifactDao.insert(new Artifact(
                "art_packet_6",
                session.id(),
                task.id(),
                Instant.now(),
                "worker_output",
                "Bounded local evidence snapshot",
                null,
                null,
                "The worker needs newer external facts before it can continue safely.",
                Map.ofEntries(
                    Map.entry("selected_worker", "codex"),
                    Map.entry("selected_model_tier", "strong"),
                    Map.entry("execution_role", "executor"),
                    Map.entry("prompt_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_mode", "mounted_context_primary"),
                    Map.entry("mounted_context_rendered", true),
                    Map.entry("mounted_render_used", true),
                    Map.entry("mounted_context_injected", true),
                    Map.entry("tool_invocation_ids", List.of("tool_refresh_1")),
                    Map.entry("evidence_refs", List.of("tool:search_text:cached-facts.txt")),
                    Map.entry("unfinished_items", List.of("refresh external facts before answer")),
                    Map.entry("route_source", "preassigned")
                )
            ));

            TaskService service = serviceWithExternalFactRefreshControlGraph(db);

            TaskControlResult result = service.continueTask(task.id());
            ResumePacket persistedPacket = packetDao.getLatestByTask(session.id(), task.id()).orElseThrow();
            Checkpoint persistedCheckpoint = checkpointDao.listByTask(task.id(), 10).stream()
                .findFirst()
                .orElseThrow();

            assertEquals("active", result.state());
            assertEquals("scheduler", result.controlNode());
            assertEquals("external_fact_refresh_before", persistedCheckpoint.checkpointType());
            assertEquals("packet", persistedPacket.currentNode());
            assertEquals("active", persistedPacket.currentStatus());
            assertEquals("Refresh external facts before next round.", persistedPacket.nextStep());
            @SuppressWarnings("unchecked")
            Map<String, Object> runtimeSurface =
                (Map<String, Object>) persistedCheckpoint.refinedPacket().get("runtime_cognition_surface");
            assertNotNull(runtimeSurface);
            @SuppressWarnings("unchecked")
            Map<String, Object> executionJudgment =
                (Map<String, Object>) runtimeSurface.get("execution_judgment");
            assertNotNull(executionJudgment);
            assertEquals(Boolean.TRUE, executionJudgment.get("needs_external_fact_refresh"));
            assertEquals(Boolean.FALSE, executionJudgment.get("needs_archive_retrieval"));
        }
    }

    private TaskService service(DatabaseManager db) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
        DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
        ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);

        return new TaskService(
            taskDao,
            sessionDao,
            eventDao,
            packetDao,
            new WorkerRouter(new WorkerRegistry()),
            new PacketBuilder(decisionDao, artifactDao, taskDao),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    private TaskService serviceWithControlGraph(DatabaseManager db) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
        DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
        ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
        CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);
        ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
            new ActiveContextBuilder.DefaultActiveContextPolicy(),
            new ActiveContextBuilder.DefaultRetentionPolicy(),
            new ActiveContextBuilder.DefaultExclusionPolicy()
        );
        TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
            eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
        );
        RuntimeFactSetAssembler runtimeFactSetAssembler = new RuntimeFactSetAssembler(runtimeContextBuilder, null, null);
        RuntimeFactSurfaceExporter runtimeFactSurfaceExporter = new RuntimeFactSurfaceExporter();
        PacketBuilder packetBuilder = new PacketBuilder(
            decisionDao, artifactDao, taskDao, packetDao, runtimeFactSetAssembler, runtimeFactSurfaceExporter
        );
        ConsolidationService consolidationService = new ConsolidationService(
            decisionDao,
            artifactDao,
            eventDao,
            checkpointDao,
            taskDao,
            packetDao,
            runtimeFactSetAssembler,
            runtimeFactSurfaceExporter
        );
        ControlNodeGraph graph = new ControlNodeGraph(
            taskDao,
            eventDao,
            sessionDao,
            packetDao,
            new WorkerRouter(new WorkerRegistry()),
            packetBuilder,
            consolidationService,
            null,
            runtimeContextBuilder,
            null,
            artifactDao,
            decisionDao,
            null
        );

        return new TaskService(
            taskDao,
            sessionDao,
            eventDao,
            packetDao,
            new WorkerRouter(new WorkerRegistry()),
            packetBuilder,
            graph,
            null,
            runtimeContextBuilder,
            consolidationService,
            null,
            null,
            null,
            null
        );
    }

    private TaskService serviceWithArchiveRetrievalControlGraph(DatabaseManager db) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
        DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
        ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
        CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);
        ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
            new ActiveContextBuilder.DefaultActiveContextPolicy(),
            new ActiveContextBuilder.DefaultRetentionPolicy(),
            new ActiveContextBuilder.DefaultExclusionPolicy()
        );
        TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
            eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
        );
        RuntimeFactSetAssembler runtimeFactSetAssembler = new RuntimeFactSetAssembler(runtimeContextBuilder, null, null);
        RuntimeFactSurfaceExporter runtimeFactSurfaceExporter = new RuntimeFactSurfaceExporter();
        PacketBuilder packetBuilder = new PacketBuilder(
            decisionDao, artifactDao, taskDao, packetDao, runtimeFactSetAssembler, runtimeFactSurfaceExporter
        );
        ConsolidationService consolidationService = new ConsolidationService(
            decisionDao,
            artifactDao,
            eventDao,
            checkpointDao,
            taskDao,
            packetDao,
            runtimeFactSetAssembler,
            runtimeFactSurfaceExporter
        );
        ControlNodeGraph graph = new ControlNodeGraph(
            taskDao,
            eventDao,
            sessionDao,
            packetDao,
            new WorkerRouter(new WorkerRegistry()),
            packetBuilder,
            consolidationService,
            null,
            runtimeContextBuilder,
            new ArchiveRetrievalJudgmentService(),
            artifactDao,
            decisionDao,
            null
        );

        return new TaskService(
            taskDao,
            sessionDao,
            eventDao,
            packetDao,
            new WorkerRouter(new WorkerRegistry()),
            packetBuilder,
            graph,
            null,
            runtimeContextBuilder,
            consolidationService,
            null,
            null,
            null,
            null
        );
    }

    private TaskService serviceWithExternalFactRefreshControlGraph(DatabaseManager db) {
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        EventDao eventDao = db.jdbi().onDemand(EventDao.class);
        ResumePacketDao packetDao = db.jdbi().onDemand(ResumePacketDao.class);
        DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
        ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
        CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);
        ActiveContextBuilder activeContextBuilder = new ActiveContextBuilder(
            new ActiveContextBuilder.DefaultActiveContextPolicy(),
            new ActiveContextBuilder.DefaultRetentionPolicy(),
            new ActiveContextBuilder.DefaultExclusionPolicy()
        );
        TaskRuntimeContextBuilder runtimeContextBuilder = new TaskRuntimeContextBuilder(
            eventDao, decisionDao, artifactDao, packetDao, checkpointDao, activeContextBuilder, null
        );
        RuntimeFactSetAssembler runtimeFactSetAssembler = new RuntimeFactSetAssembler(runtimeContextBuilder, null, null);
        RuntimeFactSurfaceExporter runtimeFactSurfaceExporter = new RuntimeFactSurfaceExporter();
        PacketBuilder packetBuilder = new PacketBuilder(
            decisionDao, artifactDao, taskDao, packetDao, runtimeFactSetAssembler, runtimeFactSurfaceExporter
        );
        ConsolidationService consolidationService = new ConsolidationService(
            decisionDao,
            artifactDao,
            eventDao,
            checkpointDao,
            taskDao,
            packetDao,
            runtimeFactSetAssembler,
            runtimeFactSurfaceExporter
        );
        ControlNodeGraph graph = new ControlNodeGraph(
            taskDao,
            eventDao,
            sessionDao,
            packetDao,
            new WorkerRouter(new WorkerRegistry()),
            packetBuilder,
            consolidationService,
            null,
            runtimeContextBuilder,
            new ExternalFactRefreshJudgmentService(),
            artifactDao,
            decisionDao,
            null
        );

        return new TaskService(
            taskDao,
            sessionDao,
            eventDao,
            packetDao,
            new WorkerRouter(new WorkerRegistry()),
            packetBuilder,
            graph,
            null,
            runtimeContextBuilder,
            consolidationService,
            null,
            null,
            null,
            null
        );
    }

    private static final class ArchiveRetrievalJudgmentService implements JudgmentService {
        @Override
        public ExecutionDecision judgeExecution(JudgmentContext context) {
            return new ExecutionDecision(
                "continue",
                "current evidence is insufficient without archived tool traces",
                "Reopen archived tool evidence before next round.",
                false,
                true,
                true,
                true,
                true,
                false,
                null
            );
        }

        @Override
        public CompletionDecision judgeCompletion(JudgmentContext context) {
            return new CompletionDecision(
                "partially_done",
                "medium",
                "more archived evidence is required before completion",
                "Reopen archived tool evidence before next round."
            );
        }
    }

    private static final class ExternalFactRefreshJudgmentService implements JudgmentService {
        @Override
        public ExecutionDecision judgeExecution(JudgmentContext context) {
            return new ExecutionDecision(
                "continue",
                "current local evidence is stale without an external refresh",
                "Refresh external facts before next round.",
                false,
                false,
                true,
                false,
                true,
                false,
                null
            );
        }

        @Override
        public CompletionDecision judgeCompletion(JudgmentContext context) {
            return new CompletionDecision(
                "partially_done",
                "medium",
                "newer external evidence is required before completion",
                "Refresh external facts before next round."
            );
        }
    }
}
