package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExperimentMatrixCreateRequest(
    String experimentName,
    List<String> caseKeys,
    List<String> modes,
    String priority,
    String source,
    Boolean autoStart,
    Map<String, Object> metadata
) {
    public ExperimentMatrixCreateRequest {
        if (caseKeys == null) caseKeys = List.of();
        if (modes == null) modes = List.of();
        if (priority == null || priority.isBlank()) priority = "high";
        if (source == null || source.isBlank()) source = "eval";
        if (autoStart == null) autoStart = false;
        if (metadata == null) metadata = Map.of();
    }
}
