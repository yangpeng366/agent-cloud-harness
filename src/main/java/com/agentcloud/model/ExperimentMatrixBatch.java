package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExperimentMatrixBatch(
    String experimentName,
    List<String> requestedCaseKeys,
    List<String> requestedModes,
    Integer createdRunCount,
    List<Task> tasks
) {
    public ExperimentMatrixBatch {
        if (experimentName == null) experimentName = "";
        if (requestedCaseKeys == null) requestedCaseKeys = List.of();
        if (requestedModes == null) requestedModes = List.of();
        if (createdRunCount == null || createdRunCount < 0) createdRunCount = 0;
        if (tasks == null) tasks = List.of();
    }
}
