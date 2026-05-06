package com.agentcloud.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentProviderStatus(
    String providerId,
    boolean installed,
    String version,
    String authStatus,
    boolean ready,
    String readinessReason,
    Instant checkedAt,
    Map<String, Object> metadata
) {
    public AgentProviderStatus {
        if (providerId == null) providerId = "";
        if (authStatus == null || authStatus.isBlank()) authStatus = "unknown";
        if (checkedAt == null) checkedAt = Instant.now();
        if (metadata == null) metadata = Map.of();
    }
}
