package com.agentcloud.model.openai;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelCard(
    String id,
    String object,
    Long created,
    String ownedBy
) {}
