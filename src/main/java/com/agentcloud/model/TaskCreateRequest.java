package com.agentcloud.model;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskCreateRequest(
    String title,
    String taskType,    // coding | reading | browser | research | ops | continuation | other
    String source,      // user | schedule | resume | external_event
    String priority,    // low | medium | high
    String intent,
    String goal,
    String parentTaskId,
    String sessionId,
    Map<String, Object> metadata,
    Boolean autoStart
) {}
