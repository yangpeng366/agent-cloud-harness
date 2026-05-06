package com.agentcloud.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentProviderRegistry {
    private final Map<String, AgentProvider> providers = new LinkedHashMap<>();

    public AgentProviderRegistry register(AgentProvider provider) {
        if (provider == null) {
            return this;
        }
        providers.put(provider.descriptor().providerId(), provider);
        return this;
    }

    public AgentProvider get(String providerId) {
        return providers.get(providerId);
    }

    public List<AgentProvider> list() {
        return new ArrayList<>(providers.values());
    }

    public List<AgentProviderStatus> listStatuses() {
        return providers.values().stream()
            .map(AgentProvider::detect)
            .toList();
    }

    public AgentProviderStatus refresh(String providerId) {
        AgentProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("provider not found");
        }
        return provider.refreshStatus();
    }
}
