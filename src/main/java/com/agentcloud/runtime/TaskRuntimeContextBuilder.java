package com.agentcloud.runtime;

import com.agentcloud.engine.LearningMemoryService;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.Task;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.CheckpointDao;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ResumePacketDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * 从 DAO 查询组装 TaskRuntimeContext。
 */
public class TaskRuntimeContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(TaskRuntimeContextBuilder.class);
    private final EventDao eventDao;
    private final DecisionDao decisionDao;
    private final ArtifactDao artifactDao;
    private final ResumePacketDao packetDao;
    private final CheckpointDao checkpointDao;
    private final ActiveContextBuilder activeContextBuilder;
    private final LearningMemoryService learningMemoryService;

    public TaskRuntimeContextBuilder(EventDao eventDao, DecisionDao decisionDao,
                                     ArtifactDao artifactDao, ResumePacketDao packetDao, CheckpointDao checkpointDao,
                                     ActiveContextBuilder activeContextBuilder,
                                     LearningMemoryService learningMemoryService) {
        this.eventDao = eventDao;
        this.decisionDao = decisionDao;
        this.artifactDao = artifactDao;
        this.packetDao = packetDao;
        this.checkpointDao = checkpointDao;
        this.activeContextBuilder = activeContextBuilder;
        this.learningMemoryService = learningMemoryService;
    }

    public TaskRuntimeContext build(Task task) {
        String sessionId = task.sessionId();
        String taskId = task.id();
        long startedAt = System.currentTimeMillis();

        log.info("[RuntimeContext] build start task={} session={}", taskId, sessionId);

        log.info("[RuntimeContext] query events task={}", taskId);
        List<Event> events = safeList(() -> eventDao.listBySessionAndTask(sessionId, taskId, 20));
        log.info("[RuntimeContext] events loaded task={} count={}", taskId, events.size());

        log.info("[RuntimeContext] query decisions task={}", taskId);
        List<Decision> decisions = safeList(() -> decisionDao.listBySessionAndTask(sessionId, taskId, 20));
        log.info("[RuntimeContext] decisions loaded task={} count={}", taskId, decisions.size());

        log.info("[RuntimeContext] query artifacts task={}", taskId);
        List<Artifact> artifacts = safeList(() -> artifactDao.listBySessionAndTask(sessionId, taskId, 20));
        log.info("[RuntimeContext] artifacts loaded task={} count={}", taskId, artifacts.size());

        log.info("[RuntimeContext] query latest packet task={}", taskId);
        ResumePacket packet = packetDao.getLatestByTask(sessionId, taskId).orElse(null);
        log.info("[RuntimeContext] latest packet loaded task={} present={}", taskId, packet != null);

        log.info("[RuntimeContext] query latest checkpoint task={}", taskId);
        var checkpoints = safeList(() -> checkpointDao.listByTask(taskId, 1));
        var latestCheckpoint = checkpoints.isEmpty() ? null : checkpoints.get(0);
        log.info("[RuntimeContext] latest checkpoint loaded task={} present={}", taskId, latestCheckpoint != null);

        String taskType = task.metadata() != null && task.metadata().get("task_type") != null
            ? task.metadata().get("task_type").toString() : "general";
        log.info("[RuntimeContext] query learned hints task={} taskType={}", taskId, taskType);
        List<String> learnedHints = learningMemoryService != null
            ? learningMemoryService.contextRetentionHints(taskType)
            : List.of();
        log.info("[RuntimeContext] learned hints loaded task={} count={}", taskId, learnedHints.size());

        log.info("[RuntimeContext] build active context task={}", taskId);
        ActiveContext activeContext = activeContextBuilder.build(task, packet, latestCheckpoint, events, decisions, artifacts, learnedHints);
        log.info("[RuntimeContext] build done task={} durationMs={}", taskId, System.currentTimeMillis() - startedAt);

        return new TaskRuntimeContext(task, packet, latestCheckpoint, events, decisions, artifacts, activeContext);
    }

    @FunctionalInterface
    private interface DaoCall<T> {
        List<T> call();
    }

    private <T> List<T> safeList(DaoCall<T> call) {
        try {
            List<T> result = call.call();
            return result != null ? result : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to query runtime context list, returning empty", e);
            return Collections.emptyList();
        }
    }
}
