package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BaselineTaskCase(
    String caseKey,
    String title,
    String taskType,
    String taskLengthBucket,
    String intent,
    String goal,
    Map<String, Object> metadata
) {
    public BaselineTaskCase {
        if (caseKey == null) caseKey = "";
        if (title == null) title = "";
        if (taskType == null || taskType.isBlank()) taskType = "coding";
        if (taskLengthBucket == null || taskLengthBucket.isBlank()) taskLengthBucket = "unspecified";
        if (intent == null) intent = "";
        if (goal == null) goal = "";
        if (metadata == null) metadata = Map.of();
    }
}
