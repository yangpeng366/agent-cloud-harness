package com.agentcloud.model.openai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelListResponse(
    String object,
    List<ModelCard> data
) {}
