package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PacketArtifactRef(
    @JsonProperty("artifact_type") String artifactType,
    String title,
    String summary,
    @JsonProperty("created_at") String createdAt
) {}
