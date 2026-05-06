package com.agentcloud.agent;

public interface AgentProvider {
    AgentProviderDescriptor descriptor();

    AgentProviderStatus detect();

    default AgentProviderStatus refreshStatus() {
        return detect();
    }
}
