package com.agentcloud.agent.providers;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderDescriptor;
import com.agentcloud.agent.AgentProviderStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class CodexProvider implements AgentProvider {
    @Override
    public AgentProviderDescriptor descriptor() {
        return new AgentProviderDescriptor(
            "codex",
            "Codex",
            "local_cli",
            "pty",
            List.of("chat", "code", "patch", "session"),
            Map.of("model_tier", "strong", "binary", "codex")
        );
    }

    @Override
    public AgentProviderStatus detect() {
        return new AgentProviderStatus(
            "codex",
            false,
            null,
            "unknown",
            false,
            "provider probe not implemented yet",
            Instant.now(),
            Map.of("binary", "codex")
        );
    }
}
