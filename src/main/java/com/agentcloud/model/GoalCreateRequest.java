package com.agentcloud.model;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GoalCreateRequest(
    String sessionId,
    String title,
    String objective,
    String parentGoalId,
    String sourceTaskId,
    Map<String, Object> successCriteria,
    Map<String, Object> constraints,
    Map<String, Object> budget,
    Map<String, Object> metadata
) {}