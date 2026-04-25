package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Worker(
    String workerId,
    String workerType,     // native-tool | codex | kimi | hermes | other
    List<String> capabilities,
    List<String> toolCapabilities,
    List<String> toolScope,
    Map<String, Boolean> dependencies,  // api_key -> true/false
    Map<String, Object> metadata,
    boolean suggestOnly,
    boolean ready
) {
    public Worker {
        if (workerId == null) workerId = "";
        if (workerType == null || workerType.isBlank()) workerType = "other";
        if (capabilities == null) capabilities = List.of();
        if (toolCapabilities == null) toolCapabilities = List.of();
        if (toolScope == null) toolScope = List.of();
        if (dependencies == null) dependencies = Map.of();
        if (metadata == null) metadata = Map.of();
    }
}
