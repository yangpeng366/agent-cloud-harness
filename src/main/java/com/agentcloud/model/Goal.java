package com.agentcloud.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Goal(
    String id,
    String sessionId,
    String parentGoalId,
    String title,
    String status,        // open | paused | closed
    String phase,         // defined | active | completed | abandoned
    String sourceTaskId,
    String activeTaskId,
    String objective,
    Map<String, Object> successCriteria,
    Map<String, Object> constraints,
    Map<String, Object> budget,
    Map<String, Object> progress,
    String outcomeSummary,
    Integer revision,
    Instant openedAt,
    Instant updatedAt,
    Instant closedAt,
    Map<String, Object> metadata
) {
    public Goal {
        if (openedAt == null) openedAt = Instant.now();
        if (updatedAt == null) updatedAt = openedAt;
        if (revision == null) revision = 1;
        if (status == null) status = "open";
        if (phase == null) phase = "defined";
    }

    public static Goal create(String id, String sessionId, String title, String objective) {
        return new Goal(id, sessionId, null, title, "open", "defined", null, null, objective,
            null, null, null, null, null, 1, Instant.now(), Instant.now(), null, null);
    }

    public Goal withStatus(String newStatus) {
        return new Goal(id, sessionId, parentGoalId, title, newStatus, phase, sourceTaskId, activeTaskId,
            objective, successCriteria, constraints, budget, progress, outcomeSummary, revision,
            openedAt, Instant.now(), closedAt, metadata);
    }

    public Goal withPhase(String newPhase) {
        return new Goal(id, sessionId, parentGoalId, title, status, newPhase, sourceTaskId, activeTaskId,
            objective, successCriteria, constraints, budget, progress, outcomeSummary, revision,
            openedAt, Instant.now(), closedAt, metadata);
    }

    public Goal withActiveTaskId(String taskId) {
        return new Goal(id, sessionId, parentGoalId, title, status, phase, sourceTaskId, taskId,
            objective, successCriteria, constraints, budget, progress, outcomeSummary, revision,
            openedAt, Instant.now(), closedAt, metadata);
    }

    public Goal withProgress(Map<String, Object> newProgress) {
        Map<String, Object> copied = newProgress == null ? null : new LinkedHashMap<>(newProgress);
        return new Goal(id, sessionId, parentGoalId, title, status, phase, sourceTaskId, activeTaskId,
            objective, successCriteria, constraints, budget, copied, outcomeSummary, revision,
            openedAt, Instant.now(), closedAt, metadata);
    }

    public Goal withOutcomeSummary(String summary, Instant closedAt) {
        return new Goal(id, sessionId, parentGoalId, title, "closed", "completed", sourceTaskId, activeTaskId,
            objective, successCriteria, constraints, budget, progress, summary, revision,
            openedAt, Instant.now(), closedAt, metadata);
    }

    public Goal withMetadata(Map<String, Object> newMetadata) {
        Map<String, Object> copied = newMetadata == null ? null : new LinkedHashMap<>(newMetadata);
        return new Goal(id, sessionId, parentGoalId, title, status, phase, sourceTaskId, activeTaskId,
            objective, successCriteria, constraints, budget, progress, outcomeSummary, revision,
            openedAt, Instant.now(), closedAt, copied);
    }

    public Goal bumpRevision() {
        return new Goal(id, sessionId, parentGoalId, title, status, phase, sourceTaskId, activeTaskId,
            objective, successCriteria, constraints, budget, progress, outcomeSummary,
            (revision == null ? 1 : revision) + 1, openedAt, Instant.now(), closedAt, metadata);
    }
}