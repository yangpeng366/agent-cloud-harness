package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionMessageCreateRequest(
    String role,
    String messageType,
    String content,
    String taskId,
    Map<String, Object> metadata
) {}
