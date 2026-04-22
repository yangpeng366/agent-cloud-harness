package com.agentcloud.model;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Worker(
    String workerId,
    String workerType,     // native-tool | codex | kimi | hermes | other
    List<String> capabilities,
    Map<String, Boolean> dependencies,  // api_key -> true/false
    Map<String, Object> metadata,
    boolean ready
) {}
