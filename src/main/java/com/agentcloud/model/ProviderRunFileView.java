package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderRunFileView(
    String taskId,
    String kind,
    String path,
    Long sizeBytes,
    Integer limitBytes,
    Boolean truncated,
    String content
) {}
