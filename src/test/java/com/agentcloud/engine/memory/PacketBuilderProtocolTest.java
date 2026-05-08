package com.agentcloud.engine.memory;

import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.HandoffPacket;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketBuilderProtocolTest {

    @TempDir
    Path tempDir;

    @Test
    void resumePacketIncludesMachineReadableContinuityFields() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("resume-packet-protocol.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);

            Session session = Session.create("session_1", "protocol session", "active");
            sessionDao.insert(session);

            Task task = new Task(
                "task_1",
                session.id(),
                null,
                "stabilize packet protocol",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                "Planner prepared a stable draft.",
                "Produce a stable packet contract.",
                "Finish executor handoff.",
                "codex",
                "continue",
                null,
                Map.of(
                    "task_type", "coding",
                    "prompt_mode", "mounted_context_primary",
                    "open_questions", List.of("Should the packet expose task_type?"),
                    "blockers", List.of("Need a stable comparison target.")
                )
            );
            taskDao.insert(task);

            decisionDao.insert(new Decision(
                "dec_1",
                session.id(),
                task.id(),
                Instant.now(),
                "execution_judgment",
                "Planner chose to preserve machine-readable packet fields.",
                "Open question remains around backward compatibility?",
                "medium",
                null,
                Map.of("open_question", "Should we keep legacy summary fields?")
            ));
            artifactDao.insert(new Artifact(
                "art_1",
                session.id(),
                task.id(),
                Instant.now(),
                "worker_output",
                "Planner brief",
                null,
                null,
                "Delegation brief ready for executor.",
                Map.of("selected_model_tier", "strong")
            ));

            PacketBuilder builder = new PacketBuilder(decisionDao, artifactDao, taskDao);
            ResumePacket packet = builder.buildResumePacket(task, session);

            assertEquals("1.1", packet.packetVersion());
            assertEquals("task_1", packet.taskIdentity().taskId());
            assertEquals("session_1", packet.taskIdentity().sessionId());
            assertEquals("coding", packet.taskIdentity().taskType());
            assertEquals("Produce a stable packet contract.", packet.currentObjective());
            assertEquals("active", packet.currentStatus());
            assertEquals("continue", packet.currentNode());
            assertEquals("codex", packet.assignedWorker());
            assertEquals("Planner prepared a stable draft.", packet.latestSummary());
            assertEquals("Finish executor handoff.", packet.nextStep());
            assertTrue(packet.blockers().contains("Need a stable comparison target."));
            assertTrue(packet.openQuestions().contains("Should the packet expose task_type?"));
            assertEquals(1, packet.recentArtifacts().size());
            assertEquals("Planner brief", packet.recentArtifacts().get(0).title());
            assertEquals(1, packet.recentDecisions().size());
            assertEquals("execution_judgment", packet.recentDecisions().get(0).decisionType());
            assertEquals("Finish executor handoff.", packet.payload().get("next_step"));
            assertEquals(Boolean.TRUE, packet.payload().get("machine_readable_first"));
            assertEquals("mounted_context_primary", packet.payload().get("prompt_rendering_mode"));
            assertEquals("mounted_context_primary", packet.payload().get("mounted_context_mode"));
            assertEquals("mounted_context_primary", packet.payload().get("prompt_mode"));
            assertEquals(Boolean.TRUE, packet.machineReadableFirst());
        }
    }

    @Test
    void handoffPacketIncludesTypedMinimalProtocolFields() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("handoff-packet-protocol.db"))) {
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            DecisionDao decisionDao = db.jdbi().onDemand(DecisionDao.class);
            ArtifactDao artifactDao = db.jdbi().onDemand(ArtifactDao.class);

            Session session = Session.create("session_2", "handoff session", "active");
            sessionDao.insert(session);

            Task task = new Task(
                "task_2",
                session.id(),
                null,
                "finish long-running task",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                "Planner phase is done; executor should continue.",
                "Ship the long-running task with a smaller executor.",
                "Implement the final execution steps.",
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
                    "open_questions", List.of("Should executor keep current file layout?")
                )
            );
            taskDao.insert(task);
            taskDao.insert(new Task(
                "task_2_child_done",
                session.id(),
                task.id(),
                "prepare plan",
                "done",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                "Planning completed.",
                null,
                null,
                "codex",
                "end",
                null,
                Map.of()
            ));
            taskDao.insert(new Task(
                "task_2_child_pending",
                session.id(),
                task.id(),
                "execute final patch",
                "active",
                "high",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                null,
                "Run the final file updates.",
                "kimi",
                "scheduler",
                null,
                Map.of()
            ));

            decisionDao.insert(new Decision(
                "dec_2",
                session.id(),
                task.id(),
                Instant.now(),
                "completion_judgment",
                "Planner judged the task ready for executor handoff.",
                "The remaining work is execution heavy.",
                "medium",
                null,
                Map.of()
            ));
            artifactDao.insert(new Artifact(
                "art_2",
                session.id(),
                task.id(),
                Instant.now(),
                "worker_output",
                "Planner artifact",
                null,
                null,
                "Implementation outline prepared.",
                Map.of("selected_model_tier", "strong")
            ));

            PacketBuilder builder = new PacketBuilder(decisionDao, artifactDao, taskDao);
            HandoffPacket packet = builder.buildHandoffPacket(task, session, "codex", "kimi");

            assertEquals("1.0", packet.packetVersion());
            assertEquals(Boolean.TRUE, packet.machineReadableFirst());
            assertEquals("task_2", packet.taskIdentity().taskId());
            assertEquals("codex", packet.fromWorker());
            assertEquals("kimi", packet.toWorker());
            assertEquals("Ship the long-running task with a smaller executor.", packet.currentObjective());
            assertEquals("active", packet.currentStatus());
            assertEquals("handoff", packet.currentNode());
            assertTrue(packet.whyHandoff().contains("delegated execution"));
            assertTrue(packet.whatDone().stream().anyMatch(item -> item.contains("Planning completed")));
            assertTrue(packet.whatRemaining().stream().anyMatch(item -> item.contains("Implement the final execution steps")));
            assertTrue(packet.cautions().stream().anyMatch(item -> item.contains("Need executor continuation")));
            assertEquals("Implement the final execution steps.", packet.resumeHint());
            assertEquals("orchestrated", packet.metadata().get("model_mode"));
            assertEquals("mounted_context_shadow", packet.metadata().get("prompt_rendering_mode"));
            assertEquals("mounted_context_shadow", packet.metadata().get("mounted_context_mode"));
            assertEquals("mounted_context_shadow", packet.metadata().get("prompt_mode"));
        }
    }
}
