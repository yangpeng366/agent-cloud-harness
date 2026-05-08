package com.agentcloud.engine;

import com.agentcloud.engine.memory.PacketBuilder;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.Decision;
import com.agentcloud.model.HandoffPacketView;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
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

        PacketBuilder packetBuilder = new PacketBuilder(decisionDao, artifactDao, taskDao);
        ConsolidationService consolidationService = new ConsolidationService(
            decisionDao,
            artifactDao,
            eventDao,
            checkpointDao,
            taskDao
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
            null,
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
            null,
            consolidationService,
            null,
            null,
            null,
            null
        );
    }
}
