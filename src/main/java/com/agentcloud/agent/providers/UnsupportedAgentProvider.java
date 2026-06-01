package com.agentcloud.agent.providers;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderDescriptor;
import com.agentcloud.agent.AgentProviderStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读观测型 provider：已被配置发现，但当前 harness 还没有对应执行器。
 */
public class UnsupportedAgentProvider implements AgentProvider {
    private final AgentProviderDescriptor descriptor;
    private final String reason;

    public UnsupportedAgentProvider(String providerId,
                                    String displayName,
                                    List<String> capabilities,
                                    Map<String, Object> metadata,
                                    String reason) {
        LinkedHashMap<String, Object> descriptorMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            descriptorMetadata.putAll(metadata);
        }
        descriptorMetadata.put("provider_discovery_supported", false);
        descriptorMetadata.put("unsupported_backend", true);
        descriptorMetadata.put("ready_for_dispatch", false);
        this.reason = reason == null || reason.isBlank()
            ? "provider protocol is not supported by current harness"
            : reason;
        descriptorMetadata.put("provider_discovery_unsupported_reason", this.reason);
        this.descriptor = new AgentProviderDescriptor(
            providerId,
            displayName,
            "unsupported",
            "unsupported",
            capabilities,
            Map.copyOf(descriptorMetadata)
        );
    }

    @Override
    public AgentProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AgentProviderStatus detect() {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(descriptor.metadata());
        metadata.put("provider_discovery_supported", false);
        metadata.put("unsupported_backend", true);
        metadata.put("ready_for_dispatch", false);
        return new AgentProviderStatus(
            descriptor.providerId(),
            false,
            null,
            "unsupported",
            false,
            reason,
            Instant.now(),
            Map.copyOf(metadata)
        );
    }
}
