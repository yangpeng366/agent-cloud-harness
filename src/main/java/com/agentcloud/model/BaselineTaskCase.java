package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BaselineTaskCase(
    String caseKey,
    String title,
    String taskType,
    String taskLengthBucket,
    String intent,
    String goal,
    List<String> workspacePreconditions,
    List<String> acceptanceCriteria,
    List<String> expectedArtifacts,
    String recoveryPolicy,
    Map<String, Object> metadata
) {
    public BaselineTaskCase {
        if (caseKey == null) caseKey = "";
        if (title == null) title = "";
        if (taskType == null || taskType.isBlank()) taskType = "coding";
        if (taskLengthBucket == null || taskLengthBucket.isBlank()) taskLengthBucket = "unspecified";
        if (intent == null) intent = "";
        if (goal == null) goal = "";
        if (workspacePreconditions == null) workspacePreconditions = List.of();
        if (acceptanceCriteria == null) acceptanceCriteria = List.of();
        if (expectedArtifacts == null) expectedArtifacts = List.of();
        if (recoveryPolicy == null || recoveryPolicy.isBlank()) recoveryPolicy = "manual_review";
        if (metadata == null) metadata = Map.of();
    }
}
