package com.agentcloud.model.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseCreateRequest(
    String model,
    JsonNode input,
    String instructions,
    String previousResponseId,
    Boolean stream,
    Map<String, Object> metadata
) {}
