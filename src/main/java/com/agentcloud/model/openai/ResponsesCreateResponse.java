package com.agentcloud.model.openai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponsesCreateResponse(
    String id,
    String object,
    Long createdAt,
    String status,
    Long completedAt,
    String model,
    List<OutputItem> output,
    String outputText,
    Usage usage,
    String previousResponseId,
    ChatCompletionResponse.AgentCloudExtension agentcloud
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutputItem(
        String id,
        String type,
        String status,
        String role,
        List<OutputContent> content
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutputContent(
        String type,
        String text,
        List<Object> annotations
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
    ) {}
}
