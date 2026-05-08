package com.agentcloud.engine;

import com.agentcloud.model.Artifact;
import com.agentcloud.model.Checkpoint;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.CheckpointDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsolidationServiceProtocolTest {

    @TempDir
    Path tempDir;

    @Test
    void checkpointRefinedPacketUsesMachineReadableContinuitySchema() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("checkpoint-protocol.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            CheckpointDao checkpointDao = db.jdbi().onDemand(CheckpointDao.class);

            Session session = Session.create("session_cp_1", "checkpoint protocol session", "active");
            sessionDao.insert(session);

            Task task = new Task(
                "task_cp_1",
                session.id(),
                null,
                "stabilize checkpoint packet",
                "paused",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                "Checkpoint summary draft is ready.",
                "Freeze the checkpoint continuity schema.",
                "Persist refined packet fields.",
                "codex",
                "packet",
                "Need one pause-time continuity sample.",
                Map.of(
                    "task_type", "coding",
                    "prompt_mode", "mounted_context_shadow",
                    "open_questions", List.of("Should checkpoint reuse resume packet field names?"),
                    "blockers", List.of("Need one pause-time continuity sample.")
                )
            );
            taskDao.insert(task);

            decisionDao.insert(new Decision(
                "dec_cp_1",
                session.id(),
                task.id(),
                Instant.now(),
                "execution_judgment",
                "Planner recorded the protocol boundary.",
                "Should checkpoint reuse resume packet field names?",
                "high",
                null,
                Map.of(
                    "open_question", "Should checkpoint reuse resume packet field names?",
                    "next_step", "Persist refined packet fields."
                )
            ));
            artifactDao.insert(new Artifact(
                "art_cp_1",
                session.id(),
                task.id(),
                Instant.now(),
                "worker_output",
                "Checkpoint protocol brief",
                null,
                null,
                "Machine-readable continuity fields are ready to persist.",
                Map.of()
            ));
            eventDao.insert(new Event(
                "evt_cp_1",
                session.id(),
                task.id(),
                Instant.now(),
                "node_packet",
                "control_node",
                null,
                "Generating packet before transition",
                Map.of("control_node", "packet")
            ));

            ConsolidationService service = new ConsolidationService(
                decisionDao,
                artifactDao,
                eventDao,
                checkpointDao,
                taskDao
            );

            Checkpoint checkpoint = service.consolidate(task, "pause_before");
            Checkpoint persisted = checkpointDao.findById(checkpoint.id()).orElseThrow();
            Map<String, Object> refinedPacket = persisted.refinedPacket();

            assertEquals("checkpoint_refined_packet", refinedPacket.get("packet_type"));
            assertEquals("1.0", refinedPacket.get("packet_version"));
            assertEquals(Boolean.TRUE, refinedPacket.get("machine_readable_first"));
            assertEquals("Freeze the checkpoint continuity schema.", refinedPacket.get("current_objective"));
            assertEquals("paused", refinedPacket.get("current_status"));
            assertEquals("packet", refinedPacket.get("current_node"));
            assertEquals("codex", refinedPacket.get("assigned_worker"));
            assertEquals("Checkpoint summary draft is ready.", refinedPacket.get("latest_summary"));
            assertEquals("Persist refined packet fields.", refinedPacket.get("next_step"));
            assertEquals("pause_before", refinedPacket.get("trigger"));
            assertEquals("mounted_context_shadow", refinedPacket.get("prompt_rendering_mode"));
            assertEquals("mounted_context_shadow", refinedPacket.get("mounted_context_mode"));
            assertEquals("mounted_context_shadow", refinedPacket.get("prompt_mode"));

            Map<?, ?> taskIdentity = assertInstanceOf(Map.class, refinedPacket.get("task_identity"));
            assertEquals("task_cp_1", taskIdentity.get("task_id"));
            assertEquals("session_cp_1", taskIdentity.get("session_id"));
            assertEquals("coding", taskIdentity.get("task_type"));

            List<?> blockers = assertInstanceOf(List.class, refinedPacket.get("blockers"));
            assertTrue(blockers.contains("Need one pause-time continuity sample."));
            assertTrue(blockers.contains("task_paused"));

            List<?> openQuestions = assertInstanceOf(List.class, refinedPacket.get("open_questions"));
            assertTrue(openQuestions.contains("Should checkpoint reuse resume packet field names?"));

            List<?> keyArtifacts = assertInstanceOf(List.class, refinedPacket.get("key_artifacts"));
            assertTrue(keyArtifacts.contains("Checkpoint protocol brief: Machine-readable continuity fields are ready to persist."));

            List<?> recentArtifacts = assertInstanceOf(List.class, refinedPacket.get("recent_artifacts"));
            Map<?, ?> recentArtifact = assertInstanceOf(Map.class, recentArtifacts.get(0));
            assertEquals("worker_output", recentArtifact.get("artifact_type"));
            assertEquals("Checkpoint protocol brief", recentArtifact.get("title"));

            List<?> recentDecisions = assertInstanceOf(List.class, refinedPacket.get("recent_decisions"));
            Map<?, ?> recentDecision = assertInstanceOf(Map.class, recentDecisions.get(0));
            assertEquals("execution_judgment", recentDecision.get("decision_type"));
            assertEquals("Planner recorded the protocol boundary.", recentDecision.get("summary"));
        }
    }
}
