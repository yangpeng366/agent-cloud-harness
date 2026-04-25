package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * 运行中沉淀的可强化经验记忆。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LearningMemory(
    String id,
    String sessionId,
    String taskId,
    Instant createdAt,
    String memoryType,
    String state,
    String hintKey,
    String summary,
    Double confidenceScore,
    Integer reinforcementCount,
    Map<String, Object> evidence,
    Map<String, Object> metadata
) {
    public LearningMemory {
        if (createdAt == null) createdAt = Instant.now();
        if (memoryType == null) memoryType = "completion_pattern";
        if (state == null) state = "candidate";
        if (hintKey == null) hintKey = "";
        if (summary == null) summary = "";
        if (confidenceScore == null) confidenceScore = 0.5d;
        if (reinforcementCount == null || reinforcementCount < 1) reinforcementCount = 1;
    }

    public LearningMemory withReinforcement(Double newConfidenceScore, Integer newCount,
                                            Map<String, Object> newEvidence, Map<String, Object> newMetadata) {
        return new LearningMemory(
            id, sessionId, taskId, createdAt, memoryType,
            nextState(newCount != null ? newCount : reinforcementCount),
            hintKey, summary,
            newConfidenceScore != null ? newConfidenceScore : confidenceScore,
            newCount != null ? newCount : reinforcementCount,
            newEvidence != null ? newEvidence : evidence,
            newMetadata != null ? newMetadata : metadata
        );
    }

    private static String nextState(int count) {
        if (count >= 5) return "stable_hint";
        if (count >= 3) return "reinforced";
        return "candidate";
    }
}
