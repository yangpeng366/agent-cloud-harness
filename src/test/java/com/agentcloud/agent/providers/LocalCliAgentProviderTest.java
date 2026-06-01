package com.agentcloud.agent.providers;

import com.agentcloud.agent.AgentProviderStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCliAgentProviderTest {

    @Test
    void descriptorMergesCliProbeMetadata() {
        LocalCliAgentProvider provider = new LocalCliAgentProvider(
            "missing_agent",
            "Missing Agent",
            List.of("coding"),
            Map.of("model_tier", "small"),
            "definitely-missing-agent-cli",
            "MISSING_AGENT_PATH",
            "MISSING_AGENT_MODEL"
        );

        Map<String, Object> metadata = provider.descriptor().metadata();

        assertEquals("local_cli", metadata.get("probe_mode"));
        assertEquals("small", metadata.get("model_tier"));
        assertEquals("definitely-missing-agent-cli", metadata.get("configured_binary"));
        assertEquals("agentcloud.providers.missing_agent.path", metadata.get("path_property"));
        assertEquals("agentcloud.providers.missing_agent.model", metadata.get("model_property"));
        assertEquals("default", metadata.get("binary_source"));
    }

    @Test
    void detectReportsMissingBinaryWithoutMarkingReady() {
        LocalCliAgentProvider provider = new LocalCliAgentProvider(
            "missing_agent",
            "Missing Agent",
            List.of("coding"),
            Map.of(),
            "definitely-missing-agent-cli",
            null,
            null
        );

        AgentProviderStatus status = provider.detect();

        assertEquals("missing_agent", status.providerId());
        assertFalse(status.installed());
        assertFalse(status.ready());
        assertTrue(status.readinessReason().contains("binary not found: definitely-missing-agent-cli"));
        assertEquals("definitely-missing-agent-cli", status.metadata().get("configured_binary"));
        assertEquals("default", status.metadata().get("binary_source"));
    }

    @Test
    void dispatchPreflightForMissingBinaryStillReportsProbeCommandShape() {
        LocalCliAgentProvider provider = new LocalCliAgentProvider(
            "deepseek",
            "DeepSeek",
            List.of("coding"),
            Map.of(),
            "definitely-missing-deepseek-cli",
            null,
            null
        );

        AgentProviderStatus status = provider.dispatchPreflight();

        assertFalse(status.ready());
        assertEquals("active_probe", status.metadata().get("dispatch_preflight_mode"));
        assertEquals("cli_help", status.metadata().get("dispatch_preflight_probe_kind"));
        assertEquals(List.of("exec", "--help"), status.metadata().get("dispatch_preflight_probe_args"));
        assertTrue(String.valueOf(status.metadata().get("dispatch_preflight_command_shape")).contains("exec"));
        assertTrue(status.readinessReason().contains("binary not found: definitely-missing-deepseek-cli"));
    }
}
