package com.agentcloud.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PacketDecisionRef(
    @JsonProperty("decision_type") String decisionType,
    String summary,
    String rationale,
    @JsonProperty("created_at") String createdAt
) {}
