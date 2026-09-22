package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskRecoveryJob(
    String id,
    String taskId,
    String sessionId,
    String status,
    String mode,
    String recommendedAction,
    String targetWorker,
    String recoveryExecutionMode,
    String failureClass,
    String providerFailureClass,
    String statusUrl,
    Instant acceptedAt,
    Instant startedAt,
    Instant completedAt,
    String errorMessage,
    Map<String, Object> metadata
) {
    public TaskRecoveryJob {
        acceptedAt = acceptedAt != null ? acceptedAt : Instant.now();
        if (metadata != null) {
            LinkedHashMap<String, Object> cleaned = new LinkedHashMap<>();
            metadata.forEach((key, value) -> {
                if (key != null && value != null) {
                    cleaned.put(key, value);
                }
            });
            metadata = Map.copyOf(cleaned);
        } else {
            metadata = Map.of();
        }
    }

    public TaskRecoveryJob withStatus(String status, Instant startedAt, Instant completedAt, String errorMessage) {
        return new TaskRecoveryJob(
            id, taskId, sessionId, status, mode, recommendedAction, targetWorker,
            recoveryExecutionMode, failureClass, providerFailureClass, statusUrl,
            acceptedAt, startedAt, completedAt, errorMessage, metadata
        );
    }
}
