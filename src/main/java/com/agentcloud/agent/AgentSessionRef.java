package com.agentcloud.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Provider 侧会话引用。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentSessionRef(
    String providerId,
    String sessionId,
    String externalSessionId,
    Map<String, Object> metadata
) {
    public AgentSessionRef {
        if (providerId == null) providerId = "";
        if (sessionId == null) sessionId = "";
        if (externalSessionId == null) externalSessionId = "";
        if (metadata == null) metadata = Map.of();
    }
}
