package com.agentcloud.engine.memory;

import com.agentcloud.model.*;
import com.agentcloud.store.*;

import java.util.*;
import java.util.stream.Collectors;

public class ContextReconstructor {
    private final TaskDao taskDao;
    private final DecisionDao decisionDao;
    private final ArtifactDao artifactDao;
    private final EventDao eventDao;
    private final RelationDao relationDao;

    public ContextReconstructor(TaskDao taskDao, DecisionDao decisionDao, ArtifactDao artifactDao,
                                EventDao eventDao, RelationDao relationDao) {
        this.taskDao = taskDao;
        this.decisionDao = decisionDao;
        this.artifactDao = artifactDao;
        this.eventDao = eventDao;
        this.relationDao = relationDao;
    }

    public ReconstructedContext reconstruct(Session session, String agentId, String roleId) {
        // Step 1: 活跃对象识别
        Task rawActiveTask = session.currentTaskId() != null
            ? taskDao.findById(session.currentTaskId()).orElse(null)
            : null;

        if (rawActiveTask == null) {
            List<Task> activeTasks = taskDao.listActiveBySession(session.id());
            if (!activeTasks.isEmpty()) rawActiveTask = activeTasks.get(0);
        }
        final Task activeTask = rawActiveTask;

        List<Task> allTasks = taskDao.listBySession(session.id());
        List<Decision> recentDecisions = activeTask != null
            ? decisionDao.listBySessionAndTask(session.id(), activeTask.id(), 10)
            : decisionDao.listBySession(session.id(), 10);
        List<Artifact> recentArtifacts = activeTask != null
            ? artifactDao.listBySessionAndTask(session.id(), activeTask.id(), 10)
            : artifactDao.listBySession(session.id(), 10);
        List<Event> recentEvents = activeTask != null
            ? eventDao.listBySessionAndTask(session.id(), activeTask.id(), 20)
            : eventDao.listBySession(session.id(), 20);

        // Step 2: 关键关系聚合
        List<Relation> relations = new ArrayList<>();
        if (activeTask != null) {
            relations.addAll(relationDao.listBySource("task", activeTask.id()));
            relations.addAll(relationDao.listByTarget("task", activeTask.id()));
        }

        // Step 3: 当前任务态重建
        TaskState taskState = activeTask != null ? new TaskState(
            activeTask.id(), activeTask.title(), activeTask.status(),
            activeTask.goal(), activeTask.nextStep(),
            allTasks.stream().filter(t -> activeTask.id().equals(t.parentTaskId())).map(Task::title).toList(),
            List.of(), // blockers
            List.of()  // satisfied deps
        ) : null;

        // Step 4: 决策链恢复
        List<String> decisionChain = recentDecisions.stream()
            .map(d -> d.summary() + (d.rationale() != null ? " (" + d.rationale() + ")" : ""))
            .toList();

        // Step 5: Shared / Local Context
        SharedContext shared = new SharedContext(
            session.id(), session.title(),
            allTasks.stream().filter(t -> !"done".equals(t.status())).map(Task::title).toList(),
            recentDecisions.stream().map(Decision::summary).toList(),
            List.of()
        );

        LocalContext local = new LocalContext(
            agentId, roleId,
            activeTask != null ? activeTask.title() : null,
            recentArtifacts.stream().map(Artifact::title).toList(),
            recentDecisions.stream().map(Decision::summary).toList(),
            activeTask != null ? activeTask.nextStep() : null
        );

        return new ReconstructedContext(session.id(), taskState, decisionChain, shared, local, relations, recentEvents);
    }

    public record TaskState(String taskId, String title, String status, String goal, String nextStep,
                            List<String> subTasks, List<String> blockers, List<String> satisfiedDependencies) {}
    public record SharedContext(String sessionId, String sessionTitle, List<String> activeTasks,
                                List<String> keyDecisions, List<String> sharedConstraints) {}
    public record LocalContext(String agentId, String roleId, String currentTask, List<String> visibleArtifacts,
                               List<String> visibleDecisions, String nextActionHint) {}
    public record ReconstructedContext(String sessionId, TaskState activeTaskState, List<String> decisionChain,
                                       SharedContext sharedContext, LocalContext localContext,
                                       List<Relation> relations, List<Event> recentEvents) {}
}
