package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * 基线实验 run 的统一落盘记录。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExperimentRunRecord(
    String id,
    String sessionId,
    String taskId,
    String experimentName,
    String taskCaseKey,
    String taskTitle,
    String taskType,
    String taskLengthBucket,
    String modelMode,
    Integer totalSteps,
    String completionStatus,
    String acceptanceResult,
    Double totalCost,
    Double strongModelCostRatio,
    Integer handoffCount,
    Integer resumeCount,
    Integer humanGateCount,
    String failureReason,
    Boolean recoverySuccess,
    String finalArtifactQualityNote,
    Instant createdAt,
    Instant updatedAt,
    Map<String, Object> metadata
) {
    public ExperimentRunRecord {
        if (id == null) id = "";
        if (sessionId == null) sessionId = "";
        if (taskId == null) taskId = "";
        if (taskTitle == null) taskTitle = "";
        if (taskLengthBucket == null || taskLengthBucket.isBlank()) taskLengthBucket = "unspecified";
        if (modelMode == null || modelMode.isBlank()) modelMode = "orchestrated";
        if (totalSteps == null || totalSteps < 0) totalSteps = 0;
        if (completionStatus == null || completionStatus.isBlank()) completionStatus = "active";
        if (acceptanceResult == null || acceptanceResult.isBlank()) acceptanceResult = "not_evaluated";
        if (totalCost == null || totalCost < 0) totalCost = 0.0;
        if (strongModelCostRatio == null || strongModelCostRatio < 0) strongModelCostRatio = 0.0;
        if (handoffCount == null || handoffCount < 0) handoffCount = 0;
        if (resumeCount == null || resumeCount < 0) resumeCount = 0;
        if (humanGateCount == null || humanGateCount < 0) humanGateCount = 0;
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
        if (metadata == null) metadata = Map.of();
    }
}
