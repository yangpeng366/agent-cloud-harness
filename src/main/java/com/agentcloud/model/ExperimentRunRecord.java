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
    Integer inputTokens,
    Integer outputTokens,
    Integer cachedTokens,
    String usageSource,
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
        if (inputTokens == null || inputTokens < 0) inputTokens = 0;
        if (outputTokens == null || outputTokens < 0) outputTokens = 0;
        if (cachedTokens == null || cachedTokens < 0) cachedTokens = 0;
        if (usageSource == null || usageSource.isBlank()) usageSource = "unavailable";
        if (handoffCount == null || handoffCount < 0) handoffCount = 0;
        if (resumeCount == null || resumeCount < 0) resumeCount = 0;
        if (humanGateCount == null || humanGateCount < 0) humanGateCount = 0;
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
        if (metadata == null) metadata = Map.of();
    }

    /**
     * 向后兼容构造器（B 向 Step1 加 token 列之前的位置参数调用点）。
     * token 相关字段置 0 / unavailable，与「未采集」语义一致，不伪造数值。
     */
    public ExperimentRunRecord(String id,
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
                               Map<String, Object> metadata) {
        this(id, sessionId, taskId, experimentName, taskCaseKey, taskTitle, taskType, taskLengthBucket,
            modelMode, totalSteps, completionStatus, acceptanceResult, totalCost, strongModelCostRatio,
            0, 0, 0, "unavailable",
            handoffCount, resumeCount, humanGateCount, failureReason, recoverySuccess,
            finalArtifactQualityNote, createdAt, updatedAt, metadata);
    }
}
