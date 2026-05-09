package com.agentcloud.agent;

import com.agentcloud.agent.providers.BuiltinAgentProviders;
import com.agentcloud.agent.providers.CodexProvider;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.engine.router.WorkerRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProviderSupportTest {

    @TempDir
    Path tempDir;

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
        assertTrue(providerIds.contains("deepseek"));
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
    void launchSpecPrefersWindowsCmdWrapperWhenDirectShimExists() throws Exception {
        Path bareShim = Files.writeString(tempDir.resolve("codex"), "# shim\n");
        Path cmdShim = Files.writeString(tempDir.resolve("codex.cmd"), "@echo off\r\necho codex\r\n");

        LocalCliProviderConfig.LaunchSpec launchSpec = new LocalCliProviderConfig(
            "codex",
            bareShim.toString(),
            "X",
            "Y"
        ).resolve().launchSpec();

        assertEquals(cmdShim.toString(), launchSpec.executableTarget());
        assertEquals("cmd_file", launchSpec.launchMode());
        assertEquals("cmd.exe", launchSpec.commandPrefix().get(0));
    }

    @Test
    void providerResolverNormalizesCommonWorkerAliases() {
        assertEquals("cursor", AgentProviderResolver.providerIdForWorker("cursor-agent", "cursor-agent"));
        assertEquals("kiro", AgentProviderResolver.providerIdForWorker("kiro-cli", "kiro-cli"));
        assertEquals("claude", AgentProviderResolver.providerIdForWorker("claude-code", "cli"));
        assertEquals("copilot", AgentProviderResolver.providerIdForWorker("github-copilot", "copilot"));
        assertEquals("deepseek", AgentProviderResolver.providerIdForWorker("deepseek-tui", "deepseek-cli"));
        assertEquals("openclaw", AgentProviderResolver.providerIdForWorker("openclaw-native", "native-tool"));
    }

    @Test
    void workerRegistryPreloadsExpandedAgentWorkersWithoutDroppingCodex() {
        WorkerRegistry registry = new WorkerRegistry();

        assertNotNull(registry.get("codex"));
        assertNotNull(registry.get("cursor"));
        assertNotNull(registry.get("claude"));
        assertNotNull(registry.get("copilot"));
        assertNotNull(registry.get("deepseek"));
        assertNotNull(registry.get("opencode"));
        assertNotNull(registry.get("gemini"));
        assertNotNull(registry.get("hermes"));
        assertNotNull(registry.get("pi"));
        assertNotNull(registry.get("kiro"));
        assertEquals("100", registry.get("codex").metadata().get("selection_priority").toString());
        assertEquals("provider_native_cli", registry.get("kimi").metadata().get("execution_backend"));
        assertEquals("provider_native_cli", registry.get("hermes").metadata().get("execution_backend"));
        assertEquals("provider_native_cli", registry.get("pi").metadata().get("execution_backend"));
        assertEquals("provider_native_cli", registry.get("kiro").metadata().get("execution_backend"));
    }

    @Test
    void workerRegistryProviderReadinessTracksCodexDetectStatus() {
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(new CodexProvider("definitely-missing-codex-binary-for-test"));
        WorkerRegistry registry = new WorkerRegistry(providers);

        WorkerRegistry.ReadinessCheck readiness = registry.checkReadiness("codex");

        assertFalse(readiness.ready());
        assertFalse(readiness.checks().getOrDefault("provider:codex", true));
        assertTrue(readiness.reason().contains("binary not found"));
    }

    @Test
    void workerRegistryProviderReadinessAllowsCodexWhenResolvedWrapperIsLaunchable() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path cmdShim = Files.writeString(tempDir.resolve("codex.cmd"), "@echo off\r\necho codex 0.0.1\r\n");
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(new CodexProvider(cmdShim.toString()));
        WorkerRegistry registry = new WorkerRegistry(providers);

        WorkerRegistry.ReadinessCheck readiness = registry.checkReadiness("codex");

        assertTrue(readiness.ready());
        assertTrue(readiness.checks().getOrDefault("provider:codex", false));
        assertEquals("ready", readiness.reason());
    }

    @Test
    void workerRegistryProviderReadinessRejectsUnsupportedBuiltinProviderBackendEvenWhenProviderIsReady() {
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(new StaticProvider("hermes", true, true, "ready"));
        WorkerRegistry registry = new WorkerRegistry(providers);

        WorkerRegistry.ReadinessCheck readiness = registry.checkReadiness("hermes");

        assertFalse(readiness.ready());
        assertTrue(readiness.checks().getOrDefault("provider:hermes", false));
        assertFalse(readiness.checks().getOrDefault("executor_backend:provider_native_cli", true));
        assertTrue(readiness.reason().contains("executor backend not supported"));
        assertTrue(readiness.reason().contains("provider=hermes"));
    }

    private record StaticProvider(String providerId,
                                  boolean installed,
                                  boolean ready,
                                  String reason) implements AgentProvider {
        @Override
        public AgentProviderDescriptor descriptor() {
            return new AgentProviderDescriptor(
                providerId,
                providerId,
                "local_cli",
                "process",
                List.of("chat"),
                java.util.Map.of()
            );
        }

        @Override
        public AgentProviderStatus detect() {
            return new AgentProviderStatus(
                providerId,
                installed,
                "0.0.0-test",
                "ready",
                ready,
                reason,
                null,
                java.util.Map.of("source", "test")
            );
        }
    }
}
