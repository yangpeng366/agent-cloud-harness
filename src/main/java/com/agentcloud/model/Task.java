package com.agentcloud.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Task(
    String id,
    String sessionId,
    String parentTaskId,
    String title,
    String status,        // active | paused | waiting | done | failed
    String priority,      // low | medium | high
    Instant createdAt,
    Instant updatedAt,
    Instant startedAt,
    Instant completedAt,
    String ownerRole,
    String summary,
    String goal,
    String nextStep,
    String assignedWorker,
    String controlNode,   // intake | scheduler | continue | packet | human_gate | handoff | end
    String waitingReason,
    Map<String, Object> metadata
) {
    public Task {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    public static Task create(String id, String sessionId, String title, String status, String priority) {
        return new Task(id, sessionId, null, title, status, priority, Instant.now(), Instant.now(),
            null, null, null, null, null, null, null, null, null, null);
    }

    public Task withStatus(String newStatus) {
        return new Task(id, sessionId, parentTaskId, title, newStatus, priority,
            createdAt, Instant.now(), startedAt, completedAt, ownerRole, summary, goal, nextStep,
            assignedWorker, controlNode, waitingReason, metadata);
    }

    public Task withAssignedWorker(String workerId) {
        return new Task(id, sessionId, parentTaskId, title, status, priority,
            createdAt, Instant.now(), startedAt, completedAt, ownerRole, summary, goal, nextStep,
            workerId, controlNode, waitingReason, metadata);
    }

    public Task withSummary(String newSummary) {
        return new Task(id, sessionId, parentTaskId, title, status, priority,
            createdAt, Instant.now(), startedAt, completedAt, ownerRole, newSummary, goal, nextStep,
            assignedWorker, controlNode, waitingReason, metadata);
    }

    public Task withNextStep(String newNextStep) {
        return new Task(id, sessionId, parentTaskId, title, status, priority,
            createdAt, Instant.now(), startedAt, completedAt, ownerRole, summary, goal, newNextStep,
            assignedWorker, controlNode, waitingReason, metadata);
    }

    public Task withControlNode(String node) {
        return new Task(id, sessionId, parentTaskId, title, status, priority,
            createdAt, Instant.now(), startedAt, completedAt, ownerRole, summary, goal, nextStep,
            assignedWorker, node, waitingReason, metadata);
    }

    public Task withMetadata(Map<String, Object> newMetadata) {
        Map<String, Object> copied = newMetadata == null ? null : new LinkedHashMap<>(newMetadata);
        return new Task(id, sessionId, parentTaskId, title, status, priority,
            createdAt, Instant.now(), startedAt, completedAt, ownerRole, summary, goal, nextStep,
            assignedWorker, controlNode, waitingReason, copied);
    }

    public Task withWaitingReason(String reason) {
        return new Task(id, sessionId, parentTaskId, title, status, priority,
            createdAt, Instant.now(), startedAt, completedAt, ownerRole, summary, goal, nextStep,
            assignedWorker, controlNode, reason, metadata);
    }

    public Task withCompletedAt(Instant newCompletedAt) {
        return new Task(id, sessionId, parentTaskId, title, status, priority,
            createdAt, Instant.now(), startedAt, newCompletedAt, ownerRole, summary, goal, nextStep,
            assignedWorker, controlNode, waitingReason, metadata);
    }
}
