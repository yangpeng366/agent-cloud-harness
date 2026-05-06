package com.agentcloud.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentProviderDescriptor(
    String providerId,
    String displayName,
    String providerType,
    String transport,
    List<String> capabilities,
    Map<String, Object> metadata
) {
    public AgentProviderDescriptor {
        if (providerId == null) providerId = "";
        if (displayName == null || displayName.isBlank()) displayName = providerId;
        if (providerType == null || providerType.isBlank()) providerType = "local_cli";
        if (transport == null || transport.isBlank()) transport = "process";
        if (capabilities == null) capabilities = List.of();
        if (metadata == null) metadata = Map.of();
    }
}
