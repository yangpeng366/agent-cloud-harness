package com.agentcloud.engine;

import com.agentcloud.model.*;
import com.agentcloud.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Consolidation Layer - 离线巩固层
 * 负责：Reactivation / Selection / Compression / Abstraction / Integration
 */
public class ConsolidationService {
    private static final Logger log = LoggerFactory.getLogger(ConsolidationService.class);
    private final DecisionDao decisionDao;
    private final ArtifactDao artifactDao;
    private final EventDao eventDao;
    private final CheckpointDao checkpointDao;
    private final TaskDao taskDao;

    public ConsolidationService(DecisionDao decisionDao, ArtifactDao artifactDao, EventDao eventDao,
                                CheckpointDao checkpointDao, TaskDao taskDao) {
        this.decisionDao = decisionDao;
        this.artifactDao = artifactDao;
        this.eventDao = eventDao;
        this.checkpointDao = checkpointDao;
        this.taskDao = taskDao;
    }

    public Checkpoint consolidate(Task task, String triggerType) {
        log.info("[Consolidation] task={} trigger={}", task.id(), triggerType);

        // Step 1: Reactivation - 收集最近轨迹
        List<Decision> decisions = decisionDao.listBySessionAndTask(task.sessionId(), task.id(), 20);
        List<Artifact> artifacts = artifactDao.listBySessionAndTask(task.id(), task.id(), 20);
        List<Event> events = eventDao.listBySessionAndTask(task.sessionId(), task.id(), 50);

        // Step 2: Selection - 筛出高价值项
        List<String> keyDecisions = decisions.stream()
            .filter(d -> d.impactLevel() != null && List.of("high", "critical").contains(d.impactLevel()))
            .map(Decision::summary)
            .toList();

        List<String> keyArtifacts = artifacts.stream()
            .map(Artifact::title)
            .filter(t -> t != null)
            .toList();

        // Step 3: Compression - 合并重复/低价值噪声
        String summary = String.format("Task [%s] consolidated. %d decisions, %d artifacts, %d events reviewed.",
            task.title(), decisions.size(), artifacts.size(), events.size());

        // Step 4: Abstraction - 提取结构化关系
        List<Map<String, String>> newRelations = new ArrayList<>();
        for (Decision d : decisions) {
            Map<String, String> rel = new HashMap<>();
            rel.put("type", "decision");
            rel.put("summary", d.summary());
            rel.put("impact", d.impactLevel());
            newRelations.add(rel);
        }

        // Step 5: Integration - 输出 refined packet + world model delta
        Map<String, Object> refinedPacket = new HashMap<>();
        refinedPacket.put("task_id", task.id());
        refinedPacket.put("trigger", triggerType);
        refinedPacket.put("key_decisions", keyDecisions);
        refinedPacket.put("key_artifacts", keyArtifacts);
        refinedPacket.put("consolidated_at", Instant.now().toString());

        Map<String, Object> worldModelDelta = new HashMap<>();
        worldModelDelta.put("new_relations", newRelations);
        worldModelDelta.put("stale_items", List.of());

        Checkpoint cp = new Checkpoint(
            IdGenerator.newId("cp"), task.sessionId(), task.id(), Instant.now(),
            triggerType, summary, refinedPacket, worldModelDelta, Map.of()
        );
        checkpointDao.insert(cp);

        log.info("[Consolidation] checkpoint={} created for task={}", cp.id(), task.id());
        return cp;
    }

    public java.util.List<com.agentcloud.model.Checkpoint> listByTask(String taskId, int limit) {
        return checkpointDao.listByTask(taskId, limit);
    }
}
