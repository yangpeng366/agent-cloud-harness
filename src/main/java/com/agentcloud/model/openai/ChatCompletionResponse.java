package com.agentcloud.model.openai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionResponse(
    String id,
    String object,
    Long created,
    String model,
    List<Choice> choices,
    Usage usage,
    AgentCloudExtension agentcloud
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Choice(
        Integer index,
        ChatMessage message,
        String finishReason
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Usage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentCloudExtension(
        String sessionId,
        String taskId,
        String taskStatus,
        String controlNode,
        String replyType,
        String replySource,
        String liveFlowPath,
        String packetPath
    ) {}
}
