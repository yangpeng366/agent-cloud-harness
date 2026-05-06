package com.agentcloud.agent.providers;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderDescriptor;
import com.agentcloud.agent.AgentProviderStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class OpenClawProvider implements AgentProvider {
    @Override
    public AgentProviderDescriptor descriptor() {
        return new AgentProviderDescriptor(
            "openclaw",
            "OpenClaw",
            "embedded",
            "inproc",
            List.of("chat", "tool", "session", "orchestration"),
            Map.of("model_tier", "orchestrator")
        );
    }

    @Override
    public AgentProviderStatus detect() {
        return new AgentProviderStatus(
            "openclaw",
            true,
            "runtime",
            "ok",
            true,
            null,
            Instant.now(),
            Map.of("source", "embedded_runtime")
        );
    }
}
