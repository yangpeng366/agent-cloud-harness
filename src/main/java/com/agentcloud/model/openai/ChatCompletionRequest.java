package com.agentcloud.model.openai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionRequest(
    String model,
    List<ChatMessage> messages,
    Boolean stream,
    Map<String, Object> metadata
) {}
