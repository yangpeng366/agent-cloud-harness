package com.agentcloud.agent;

import java.util.List;

public class SimpleAgentDiscoveryService implements AgentDiscoveryService {
    private final AgentProviderRegistry registry;

    public SimpleAgentDiscoveryService(AgentProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AgentProviderStatus detect(AgentProvider provider) {
        return provider.detect();
    }

    @Override
    public List<AgentProviderStatus> detectAll() {
        return registry.listStatuses();
    }
}
