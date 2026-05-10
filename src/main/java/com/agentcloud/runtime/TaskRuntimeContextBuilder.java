package com.agentcloud.runtime;

import com.agentcloud.engine.LearningMemoryService;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Decision;
import com.agentcloud.model.Event;
import com.agentcloud.model.ResumePacket;
import com.agentcloud.model.SessionMessage;
import com.agentcloud.model.Task;
import com.agentcloud.model.ToolInvocationRecord;
import com.agentcloud.runtime.context.ContextViewBuilder;
import com.agentcloud.runtime.context.MountedContextView;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.CheckpointDao;
import com.agentcloud.store.DecisionDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.ResumePacketDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.ToolInvocationDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 从 DAO 查询组装 TaskRuntimeContext。
 */
public class TaskRuntimeContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(TaskRuntimeContextBuilder.class);
    private static final int TASK_MESSAGE_LIMIT = 12;
    private static final int SESSION_MESSAGE_LIMIT = 8;
    private final EventDao eventDao;
    private final DecisionDao decisionDao;
    private final ArtifactDao artifactDao;
    private final ResumePacketDao packetDao;
    private final CheckpointDao checkpointDao;
    private final ToolInvocationDao toolInvocationDao;
    private final SessionMessageDao sessionMessageDao;
    private final ActiveContextBuilder activeContextBuilder;
    private final LearningMemoryService learningMemoryService;
    private final ContextViewBuilder contextViewBuilder;

    public TaskRuntimeContextBuilder(EventDao eventDao, DecisionDao decisionDao,
                                     ArtifactDao artifactDao, ResumePacketDao packetDao, CheckpointDao checkpointDao,
                                     ActiveContextBuilder activeContextBuilder,
                                     LearningMemoryService learningMemoryService) {
        this(eventDao, decisionDao, artifactDao, packetDao, checkpointDao, null, null,
            activeContextBuilder, learningMemoryService, null);
    }

    public TaskRuntimeContextBuilder(EventDao eventDao, DecisionDao decisionDao,
                                     ArtifactDao artifactDao, ResumePacketDao packetDao, CheckpointDao checkpointDao,
                                     SessionMessageDao sessionMessageDao,
                                     ActiveContextBuilder activeContextBuilder,
                                     LearningMemoryService learningMemoryService) {
        this(eventDao, decisionDao, artifactDao, packetDao, checkpointDao, null, sessionMessageDao,
            activeContextBuilder, learningMemoryService, null);
    }

    public TaskRuntimeContextBuilder(EventDao eventDao, DecisionDao decisionDao,
                                     ArtifactDao artifactDao, ResumePacketDao packetDao, CheckpointDao checkpointDao,
                                     ToolInvocationDao toolInvocationDao,
                                     SessionMessageDao sessionMessageDao,
                                     ActiveContextBuilder activeContextBuilder,
                                     LearningMemoryService learningMemoryService) {
        this(eventDao, decisionDao, artifactDao, packetDao, checkpointDao, toolInvocationDao, sessionMessageDao,
            activeContextBuilder, learningMemoryService, null);
    }

    public TaskRuntimeContextBuilder(EventDao eventDao, DecisionDao decisionDao,
                                     ArtifactDao artifactDao, ResumePacketDao packetDao, CheckpointDao checkpointDao,
                                     SessionMessageDao sessionMessageDao,
                                     ActiveContextBuilder activeContextBuilder,
                                     LearningMemoryService learningMemoryService,
                                     ContextViewBuilder contextViewBuilder) {
        this(eventDao, decisionDao, artifactDao, packetDao, checkpointDao, null, sessionMessageDao,
            activeContextBuilder, learningMemoryService, contextViewBuilder);
    }

    public TaskRuntimeContextBuilder(EventDao eventDao, DecisionDao decisionDao,
                                     ArtifactDao artifactDao, ResumePacketDao packetDao, CheckpointDao checkpointDao,
                                     ToolInvocationDao toolInvocationDao,
                                     SessionMessageDao sessionMessageDao,
                                     ActiveContextBuilder activeContextBuilder,
                                     LearningMemoryService learningMemoryService,
                                     ContextViewBuilder contextViewBuilder) {
        this.eventDao = eventDao;
        this.decisionDao = decisionDao;
        this.artifactDao = artifactDao;
        this.packetDao = packetDao;
        this.checkpointDao = checkpointDao;
        this.toolInvocationDao = toolInvocationDao;
        this.sessionMessageDao = sessionMessageDao;
        this.activeContextBuilder = activeContextBuilder;
        this.learningMemoryService = learningMemoryService;
        this.contextViewBuilder = contextViewBuilder != null ? contextViewBuilder : new ContextViewBuilder();
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

        log.info("[RuntimeContext] query tool invocations task={}", taskId);
        List<ToolInvocationRecord> toolInvocations = toolInvocationDao == null
            ? List.of()
            : safeList(() -> toolInvocationDao.listBySessionAndTask(sessionId, taskId, 12));
        log.info("[RuntimeContext] tool invocations loaded task={} count={}", taskId, toolInvocations.size());

        log.info("[RuntimeContext] query recent task messages task={}", taskId);
        List<SessionMessage> taskMessages = sessionMessageDao == null
            ? List.of()
            : safeList(() -> sessionMessageDao.listBySessionAndTask(sessionId, taskId, TASK_MESSAGE_LIMIT));
        List<SessionMessage> sessionMessages = sessionMessageDao == null
            ? List.of()
            : safeList(() -> sessionMessageDao.listBySession(sessionId, SESSION_MESSAGE_LIMIT));
        List<SessionMessage> messages = mergeContinuityMessages(taskMessages, sessionMessages, taskId);
        log.info("[RuntimeContext] task messages loaded task={} direct={} merged={}", taskId, taskMessages.size(), messages.size());

        log.info("[RuntimeContext] query latest packet task={}", taskId);
        ResumePacket packet = packetDao == null ? null : packetDao.getLatestByTask(sessionId, taskId).orElse(null);
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
        ActiveContext activeContext = activeContextBuilder == null
            ? null
            : activeContextBuilder.build(task, packet, latestCheckpoint, events, decisions, artifacts, learnedHints);
        log.info("[RuntimeContext] build done task={} durationMs={}", taskId, System.currentTimeMillis() - startedAt);

        TaskRuntimeContext baseContext = new TaskRuntimeContext(
            task, packet, latestCheckpoint, events, decisions, artifacts, toolInvocations, messages, activeContext
        );
        MountedContextView mountedContextView = contextViewBuilder.build(baseContext);
        return new TaskRuntimeContext(
            task, packet, latestCheckpoint, events, decisions, artifacts, toolInvocations, messages, activeContext, mountedContextView
        );
    }

    @FunctionalInterface
    private interface DaoCall<T> {
        List<T> call();
    }

    private <T> List<T> safeList(DaoCall<T> call) {
        if (call == null) {
            return Collections.emptyList();
        }
        try {
            List<T> result = call.call();
            return result != null ? result : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to query runtime context list, returning empty", e);
            return Collections.emptyList();
        }
    }

    private <T> List<T> chronological(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> copied = new ArrayList<>(values);
        Collections.reverse(copied);
        return copied;
    }

    private List<SessionMessage> mergeContinuityMessages(List<SessionMessage> taskMessages,
                                                         List<SessionMessage> sessionMessages,
                                                         String taskId) {
        LinkedHashMap<String, SessionMessage> merged = new LinkedHashMap<>();
        for (SessionMessage message : chronological(taskMessages)) {
            if (message == null || message.id() == null) {
                continue;
            }
            merged.put(message.id(), withContinuityScope(message, "task"));
        }
        for (SessionMessage message : chronological(sessionMessages)) {
            if (message == null || message.id() == null || merged.containsKey(message.id())) {
                continue;
            }
            if (message.taskId() != null && !message.taskId().isBlank()) {
                continue;
            }
            merged.put(message.id(), withContinuityScope(message, "session"));
        }
        return List.copyOf(merged.values());
    }

    private SessionMessage withContinuityScope(SessionMessage message, String scope) {
        if (message == null || scope == null || scope.isBlank()) {
            return message;
        }
        LinkedHashMap<String, Object> metadata = message.metadata() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(message.metadata());
        metadata.putIfAbsent("continuity_scope", scope);
        return new SessionMessage(
            message.id(),
            message.sessionId(),
            message.taskId(),
            message.role(),
            message.messageType(),
            message.content(),
            message.createdAt(),
            metadata
        );
    }
}
