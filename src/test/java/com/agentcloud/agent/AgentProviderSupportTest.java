package com.agentcloud.agent;

import com.agentcloud.agent.providers.BuiltinAgentProviders;
import com.agentcloud.agent.providers.CodexProvider;
import com.agentcloud.engine.router.WorkerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProviderSupportTest {

    @Test
    void builtinProvidersIncludeCommonCliAgentsFromMulticaCatalog() {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);

        List<String> providerIds = registry.list().stream()
            .map(provider -> provider.descriptor().providerId())
            .toList();

        assertTrue(providerIds.contains("openclaw"));
        assertTrue(providerIds.contains("codex"));
        assertTrue(providerIds.contains("cursor"));
        assertTrue(providerIds.contains("claude"));
        assertTrue(providerIds.contains("copilot"));
        assertTrue(providerIds.contains("opencode"));
        assertTrue(providerIds.contains("gemini"));
        assertTrue(providerIds.contains("kimi"));
        assertTrue(providerIds.contains("kiro"));
    }

    @Test
    void codexProviderReportsConfiguredBinaryWhenProbeFails() {
        CodexProvider provider = new CodexProvider("definitely-missing-codex-binary-for-test");

        AgentProviderStatus status = provider.detect();

        assertEquals("codex", status.providerId());
        assertFalse(status.installed());
        assertFalse(status.ready());
        assertEquals("unknown", status.authStatus());
        assertEquals("definitely-missing-codex-binary-for-test", status.metadata().get("configured_binary"));
        assertEquals("default", status.metadata().get("binary_source"));
        assertTrue(status.readinessReason().contains("binary not found"));
    }

    @Test
    void providerResolverNormalizesCommonWorkerAliases() {
        assertEquals("cursor", AgentProviderResolver.providerIdForWorker("cursor-agent", "cursor-agent"));
        assertEquals("kiro", AgentProviderResolver.providerIdForWorker("kiro-cli", "kiro-cli"));
        assertEquals("claude", AgentProviderResolver.providerIdForWorker("claude-code", "cli"));
        assertEquals("copilot", AgentProviderResolver.providerIdForWorker("github-copilot", "copilot"));
        assertEquals("openclaw", AgentProviderResolver.providerIdForWorker("openclaw-native", "native-tool"));
    }

    @Test
    void workerRegistryPreloadsExpandedAgentWorkersWithoutDroppingCodex() {
        WorkerRegistry registry = new WorkerRegistry();

        assertNotNull(registry.get("codex"));
        assertNotNull(registry.get("cursor"));
        assertNotNull(registry.get("claude"));
        assertNotNull(registry.get("copilot"));
        assertNotNull(registry.get("opencode"));
        assertNotNull(registry.get("gemini"));
        assertNotNull(registry.get("hermes"));
        assertNotNull(registry.get("pi"));
        assertNotNull(registry.get("kiro"));
        assertEquals("100", registry.get("codex").metadata().get("selection_priority").toString());
    }
}
