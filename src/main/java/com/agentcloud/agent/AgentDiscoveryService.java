package com.agentcloud.agent;

import java.util.List;

public interface AgentDiscoveryService {
    AgentProviderStatus detect(AgentProvider provider);

    List<AgentProviderStatus> detectAll();
}
