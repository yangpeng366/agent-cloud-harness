package com.agentcloud.engine.memory;

import com.agentcloud.model.*;
import com.agentcloud.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PacketBuilder {
    private static final Logger log = LoggerFactory.getLogger(PacketBuilder.class);
    private final DecisionDao decisionDao;
    private final ArtifactDao artifactDao;
    private final TaskDao taskDao;

    public PacketBuilder(DecisionDao decisionDao, ArtifactDao artifactDao, TaskDao taskDao) {
        this.decisionDao = decisionDao;
        this.artifactDao = artifactDao;
        this.taskDao = taskDao;
    }

    public ResumePacket buildResumePacket(Task task, Session session) {
        List<Decision> decisions = decisionDao.listBySessionAndTask(session.id(), task.id(), 10);
        List<Artifact> artifacts = artifactDao.listBySessionAndTask(session.id(), task.id(), 10);

        String decisionSummary = decisions.stream()
            .map(d -> "[" + d.createdAt() + "] " + d.summary())
            .collect(Collectors.joining("\n"));

        String artifactSummary = artifacts.stream()
            .map(a -> a.artifactType() + ": " + a.title())
            .collect(Collectors.joining("\n"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("session_id", session.id());
        payload.put("session_title", session.title());
        payload.put("active_goal", task.goal());
        payload.put("task_status", task.status());
        payload.put("recent_decisions", decisions.stream().map(Decision::summary).toList());
        payload.put("relevant_artifacts", artifacts.stream().map(Artifact::title).toList());
        payload.put("blockers", List.of());
        payload.put("key_constraints", List.of());

        String nextStep = task.nextStep() != null ? task.nextStep() : "continue from current task";

        return new ResumePacket(
            java.util.UUID.randomUUID().toString(),
            session.id(), task.id(), Instant.now(), "1.0",
            task.summary(), decisionSummary, artifactSummary,
            List.of(), nextStep, payload
        );
    }

    public Map<String, Object> buildHandoffPacket(Task task, Session session, String fromWorker, String toWorker) {
        List<Decision> decisions = decisionDao.listBySessionAndTask(session.id(), task.id(), 10);
        List<Artifact> artifacts = artifactDao.listBySessionAndTask(session.id(), task.id(), 10);
        List<Task> subTasks = taskDao.listBySession(session.id()).stream()
            .filter(t -> task.id().equals(t.parentTaskId()))
            .toList();

        Map<String, Object> packet = new HashMap<>();
        packet.put("session_id", session.id());
        packet.put("handoff_from_agent", fromWorker);
        packet.put("handoff_to_agent", toWorker);
        packet.put("active_goal", task.goal());
        packet.put("handoff_task", task.title());
        packet.put("completed_work", subTasks.stream().filter(t -> "done".equals(t.status())).map(Task::title).toList());
        packet.put("pending_work", subTasks.stream().filter(t -> !"done".equals(t.status())).map(Task::title).toList());
        packet.put("blockers", List.of());
        packet.put("relevant_decisions", decisions.stream().map(Decision::summary).toList());
        packet.put("required_artifacts", artifacts.stream().map(Artifact::title).toList());
        packet.put("shared_constraints", List.of());
        packet.put("expected_output", task.goal());
        packet.put("priority", task.priority());

        log.info("Handoff packet built for task={} from={} to={}", task.id(), fromWorker, toWorker);
        return packet;
    }
}
