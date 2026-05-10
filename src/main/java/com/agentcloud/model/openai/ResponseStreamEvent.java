package com.agentcloud.model.openai;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseStreamEvent(
    String type,
    ResponsesCreateResponse response,
    Integer outputIndex,
    String itemId,
    Integer contentIndex,
    ResponsesCreateResponse.OutputItem item,
    ResponsesCreateResponse.OutputContent part,
    String delta,
    String text
) {}
