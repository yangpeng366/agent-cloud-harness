package com.agentcloud.agent;

import java.util.LinkedHashMap;
import java.util.Map;

public interface AgentProvider {
    AgentProviderDescriptor descriptor();

    AgentProviderStatus detect();

    default AgentProviderStatus refreshStatus() {
        return detect();
    }

    default AgentProviderStatus dispatchPreflight() {
        AgentProviderStatus status = refreshStatus();
        if (status == null) {
            return null;
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(status.metadata());
        metadata.putIfAbsent("dispatch_preflight_mode", "passive_status");
        metadata.putIfAbsent("dispatch_preflight_note", "provider did not implement active dispatch probe");
        return new AgentProviderStatus(
            status.providerId(),
            status.installed(),
            status.version(),
            status.authStatus(),
            status.ready(),
            status.readinessReason(),
            status.checkedAt(),
            Map.copyOf(metadata)
        );
    }
}
