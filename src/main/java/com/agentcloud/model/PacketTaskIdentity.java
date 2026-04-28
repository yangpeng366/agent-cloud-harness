package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PacketTaskIdentity(
    @JsonProperty("task_id") String taskId,
    @JsonProperty("session_id") String sessionId,
    @JsonProperty("parent_task_id") String parentTaskId,
    String title,
    @JsonProperty("task_type") String taskType
) {}
