package com.agentcloud.model.openai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionChunkResponse(
    String id,
    String object,
    Long created,
    String model,
    List<Choice> choices,
    ChatCompletionResponse.AgentCloudExtension agentcloud
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Choice(
        Integer index,
        Delta delta,
        String finishReason
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(
        String role,
        String content
    ) {}
}
