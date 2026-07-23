package com.agentcloud.agent;

import com.agentcloud.agent.providers.BuiltinAgentProviders;
import com.agentcloud.agent.providers.CodexProvider;
import com.agentcloud.agent.providers.LocalCliAgentProvider;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.engine.router.WorkerRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(providerIds.contains("codebuddy"));
        assertTrue(providerIds.contains("deveco"));
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
        assertNotNull(registry.get("codebuddy"));
        assertNotNull(registry.get("deveco"));
        assertEquals("100", registry.get("codex").metadata().get("selection_priority").toString());
        assertEquals("84", registry.get("deveco").metadata().get("selection_priority").toString());
        assertEquals("provider_native_cli", registry.get("kimi").metadata().get("execution_backend"));
        assertEquals("provider_native_cli", registry.get("hermes").metadata().get("execution_backend"));
        assertEquals("provider_native_cli", registry.get("pi").metadata().get("execution_backend"));
        assertEquals("provider_native_cli", registry.get("kiro").metadata().get("execution_backend"));
        assertEquals("provider_native_cli", registry.get("deveco").metadata().get("execution_backend"));
        assertEquals("native_cli_cwd", registry.get("deveco").metadata().get("workspace_access_mode"));
        assertEquals("resume_if_session_id", registry.get("deveco").metadata().get("recovery_resume_policy"));
        assertEquals(Boolean.TRUE, registry.get("deveco").metadata().get("supports_resume"));
    }

    @Test
    void workerRegistryEnrichesWorkerCapabilityMatrixFields() {
        WorkerRegistry registry = new WorkerRegistry();

        Map<String, Object> codex = registry.get("codex").metadata();
        assertEquals("provider_app_server", codex.get("execution_backend"));
        assertEquals("codex app-server --listen stdio://", codex.get("command_shape"));
        assertEquals("json_rpc", codex.get("input_mode"));
        assertEquals("json_rpc_events", codex.get("output_mode"));
        assertEquals("provider_app_server_events", codex.get("output_contract"));
        assertEquals("fresh_on_recovery", codex.get("recovery_resume_policy"));
        assertEquals(Boolean.TRUE, codex.get("supports_resume"));
        assertEquals(List.of("coding", "reading", "ops"), codex.get("auto_route_task_types"));

        Map<String, Object> kimi = registry.get("kimi").metadata();
        assertEquals("provider_native_cli", kimi.get("execution_backend"));
        assertEquals("kimi --print --output-format stream-json --work-dir <cwd> --prompt <prompt>",
            kimi.get("command_shape"));
        assertEquals("argv_prompt", kimi.get("input_mode"));
        assertEquals("stream_json", kimi.get("output_mode"));
        assertEquals("provider_native_cli_events", kimi.get("output_contract"));
        assertEquals("resume_if_session_id", kimi.get("recovery_resume_policy"));
        assertEquals(Boolean.TRUE, kimi.get("supports_resume"));
        assertEquals(List.of("coding", "research", "browser"), kimi.get("auto_route_task_types"));

        Map<String, Object> gemini = registry.get("gemini").metadata();
        assertEquals(List.of("research", "browser"), gemini.get("auto_route_task_types"));

        assertTrue(registry.get("deepseek").toolCapabilities().contains("read_file"));
        assertTrue(registry.get("deepseek").toolCapabilities().contains("write_file"));
        assertTrue(registry.get("deepseek").toolCapabilities().contains("search_text"));
        assertTrue(registry.get("deepseek").toolCapabilities().contains("patch_file"));
        assertTrue(registry.get("deepseek").toolCapabilities().contains("shell"));
        assertTrue(registry.get("deepseek").metadata().containsKey("host_tool_availability"));
        assertEquals(Boolean.TRUE, registry.get("deepseek").metadata().get("harness_tool_access"));
        assertTrue(Path.of(registry.get("deepseek").toolScope().get(0)).isAbsolute());

        Map<String, Object> toolAware = registry.get("openclaw-native").metadata();
        assertEquals("tool_aware", toolAware.get("execution_backend"));
        assertEquals("harness tool registry", toolAware.get("command_shape"));
        assertEquals("tool_request", toolAware.get("input_mode"));
        assertEquals("tool_result", toolAware.get("output_mode"));
        assertEquals("harness_tool_trace", toolAware.get("output_contract"));
        assertEquals(List.of("browser", "doc", "message", "search", "reading"), toolAware.get("auto_route_task_types"));
    }

    @Test
    void devecoProviderRegisteredWithCorrectBinaryAndEnvVars() {
        AgentProviderRegistry registry = new AgentProviderRegistry();
        BuiltinAgentProviders.defaults().forEach(registry::register);

        AgentProvider provider = registry.list().stream()
            .filter(p -> "deveco".equals(p.descriptor().providerId()))
            .findFirst()
            .orElse(null);
        assertNotNull(provider, "deveco provider must be registered");

        Map<String, Object> descriptorMetadata = provider.descriptor().metadata();
        assertEquals("deveco", descriptorMetadata.get("binary"));
        assertEquals("MULTICA_DEVECO_PATH", descriptorMetadata.get("path_env_var"));
        assertEquals("MULTICA_DEVECO_MODEL", descriptorMetadata.get("model_env_var"));
    }

    @Test
    void workerRegistryEnrichesCodeBuddyCapabilityMatrixFields() {
        WorkerRegistry registry = new WorkerRegistry();

        Map<String, Object> codebuddy = registry.get("codebuddy").metadata();
        assertEquals("provider_native_cli", codebuddy.get("execution_backend"));
        assertEquals("stream_json", codebuddy.get("output_mode"));
        assertEquals("resume_if_session_id", codebuddy.get("recovery_resume_policy"));
        assertEquals(Boolean.TRUE, codebuddy.get("supports_resume"));
        assertEquals("codebuddy -y --print --output-format stream-json --permission-mode bypassPermissions --subagent-permission-mode bypassPermissions --tools default <prompt>",
            codebuddy.get("command_shape"));
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
    void localCliDispatchPreflightRunsActiveProbeAndReportsCommandMetadata() throws Exception {
        Path cli = fakeCli("probe-cli", "echo probe help");
        LocalCliAgentProvider provider = new LocalCliAgentProvider(
            "probe",
            "Probe CLI",
            List.of("chat"),
            Map.of(),
            cli.toString(),
            null,
            null
        );

        AgentProviderStatus status = provider.dispatchPreflight();

        assertTrue(status.ready());
        assertEquals("active_probe", status.metadata().get("dispatch_preflight_mode"));
        assertEquals("cli_help", status.metadata().get("dispatch_preflight_probe_kind"));
        assertEquals(List.of("--help"), status.metadata().get("dispatch_preflight_probe_args"));
        assertEquals(0, status.metadata().get("dispatch_preflight_exit_code"));
        assertTrue(status.metadata().containsKey("dispatch_preflight_command_shape"));
        assertEquals(true, status.metadata().get("cli_profile_evidence_available"));
        assertEquals(false, status.metadata().get("supports_yolo"));
        assertEquals(false, status.metadata().get("supports_model"));
    }

    @Test
    void deepSeekDispatchPreflightValidatesExecSubcommandHelp() throws Exception {
        Path cli = fakeCli("deepseek-cli", "echo deepseek exec help");
        LocalCliAgentProvider provider = new LocalCliAgentProvider(
            "deepseek",
            "DeepSeek CLI",
            List.of("chat", "code"),
            Map.of(),
            cli.toString(),
            null,
            null
        );

        AgentProviderStatus status = provider.dispatchPreflight();

        assertTrue(status.ready());
        assertEquals(List.of("exec", "--help"), status.metadata().get("dispatch_preflight_probe_args"));
        assertTrue(((List<?>) status.metadata().get("dispatch_preflight_command_shape")).contains("exec"));
        assertEquals(true, status.metadata().get("cli_profile_evidence_available"));
    }

    @Test
    void localCliDispatchPreflightUsesConfiguredProbeArgsFromDiscoveryMetadata() throws Exception {
        Path cli = fakeCli("custom-probe-cli", "echo custom probe help");
        LocalCliAgentProvider provider = new LocalCliAgentProvider(
            "custom_agent",
            "Custom Agent",
            List.of("coding"),
            Map.of("dispatch_probe_args", List.of("doctor", "--help")),
            cli.toString(),
            null,
            null
        );

        AgentProviderStatus status = provider.dispatchPreflight();

        assertTrue(status.ready());
        assertEquals(List.of("doctor", "--help"), status.metadata().get("dispatch_preflight_probe_args"));
        assertTrue(((List<?>) status.metadata().get("dispatch_preflight_command_shape")).contains("doctor"));
    }

    @Test
    void localCliDispatchPreflightClassifiesBadArgumentsWithProviderFailureMetadata() throws Exception {
        Path cli = fakeCli("bad-args-cli", "echo error: unknown option %1\r\nexit /b 2", "echo error: unknown option \"$1\"\nexit 2");
        LocalCliAgentProvider provider = new LocalCliAgentProvider(
            "codex",
            "Codex Probe CLI",
            List.of("chat"),
            Map.of(),
            cli.toString(),
            null,
            null
        );

        AgentProviderStatus status = provider.dispatchPreflight();

        assertFalse(status.ready());
        assertTrue(status.readinessReason().contains("command probe failed: exit_code=2"));
        assertEquals("provider_protocol_error", status.metadata().get("provider_failure_class"));
        assertEquals(true, status.metadata().get("provider_retryable"));
        assertTrue(String.valueOf(status.metadata().get("provider_failure_reason")).contains("unknown option"));
        assertEquals(2, status.metadata().get("dispatch_preflight_exit_code"));
        assertTrue(String.valueOf(status.metadata().get("dispatch_preflight_output_preview")).contains("unknown option"));

        AgentProviderRegistry providers = new AgentProviderRegistry().register(provider);
        WorkerRegistry registry = new WorkerRegistry(providers);
        WorkerRegistry.ReadinessCheck readiness = registry.checkReadiness("codex", "dispatch");

        assertFalse(readiness.ready());
        assertEquals("provider_protocol_error", readiness.dispatchPreflightMetadata().get("provider_failure_class"));
        assertTrue(String.valueOf(readiness.dispatchPreflightMetadata().get("provider_failure_reason")).contains("unknown option"));
        assertEquals(true, readiness.dispatchPreflightMetadata().get("provider_retryable"));
        assertEquals("provider_protocol_error", readiness.providerFailureClass());
        assertTrue(readiness.providerFailureReason().contains("unknown option"));
        assertEquals(true, readiness.providerRetryable());
    }

    @Test
    void workerRegistryTemporaryUnavailabilityOverridesProviderReadiness() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path cmdShim = Files.writeString(tempDir.resolve("codex.cmd"), "@echo off\r\necho codex 0.0.1\r\n");
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(new CodexProvider(cmdShim.toString()));
        WorkerRegistry registry = new WorkerRegistry(providers);

        registry.markTemporarilyUnavailable("codex", 60_000L, "thread not found");
        WorkerRegistry.ReadinessCheck readiness = registry.checkReadiness("codex");

        assertFalse(readiness.ready());
        assertFalse(readiness.checks().getOrDefault("runtime_available", true));
        assertTrue(readiness.reason().contains("temporarily unavailable"));
        assertTrue(readiness.reason().contains("thread not found"));
    }

    @Test
    void workerRegistryProviderReadinessAcceptsExpandedNativeCliSupportButStillRequiresProviderReady() {
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(new StaticProvider("hermes", true, false, "binary not found: hermes"));
        WorkerRegistry registry = new WorkerRegistry(providers);

        WorkerRegistry.ReadinessCheck readiness = registry.checkReadiness("hermes");

        assertFalse(readiness.ready());
        assertFalse(readiness.checks().getOrDefault("provider:hermes", true));
        assertTrue(readiness.checks().getOrDefault("executor_backend:provider_native_cli", false));
        assertTrue(readiness.reason().contains("binary not found: hermes"));
    }

    @Test
    void dispatchReadinessRunsProviderPreflightAndMarksWorkerTemporarilyUnavailableOnFailure() {
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(new PreflightProvider("codex", true, false, "provider cannot start fresh turn"));
        WorkerRegistry registry = new WorkerRegistry(providers);

        WorkerRegistry.ReadinessCheck passive = registry.checkReadiness("codex");
        WorkerRegistry.ReadinessCheck dispatch = registry.checkReadiness("codex", "dispatch");
        WorkerRegistry.ReadinessCheck afterFailure = registry.checkReadiness("codex");

        assertTrue(passive.ready());
        assertEquals("passive", passive.mode());
        assertNull(passive.dispatchPreflightReady());

        assertFalse(dispatch.ready());
        assertEquals("dispatch", dispatch.mode());
        assertFalse(dispatch.checks().getOrDefault("dispatch_preflight", true));
        assertFalse(dispatch.dispatchPreflightReady());
        assertEquals("provider cannot start fresh turn", dispatch.dispatchPreflightReason());

        assertFalse(afterFailure.ready());
        assertFalse(afterFailure.checks().getOrDefault("runtime_available", true));
        assertTrue(afterFailure.reason().contains("temporarily unavailable"));
    }

    @Test
    void dispatchPreflightTimingDefaultsAreLongEnoughForBrowserAcceptance() {
        String cacheProperty = "agentcloud.dispatch.preflight.cache_ms";
        String unavailableProperty = "agentcloud.dispatch.preflight.unavailable_ms";
        String previousCache = System.getProperty(cacheProperty);
        String previousUnavailable = System.getProperty(unavailableProperty);
        System.clearProperty(cacheProperty);
        System.clearProperty(unavailableProperty);
        try {
            assertEquals(120_000L, WorkerRegistry.dispatchPreflightCacheMs());
            assertEquals(600_000L, WorkerRegistry.dispatchPreflightUnavailableMs());
        } finally {
            restoreProperty(cacheProperty, previousCache);
            restoreProperty(unavailableProperty, previousUnavailable);
        }
    }

    @Test
    void dispatchPreflightTimingCanBeOverriddenForValidationRuns() {
        String cacheProperty = "agentcloud.dispatch.preflight.cache_ms";
        String unavailableProperty = "agentcloud.dispatch.preflight.unavailable_ms";
        String previousCache = System.getProperty(cacheProperty);
        String previousUnavailable = System.getProperty(unavailableProperty);
        System.setProperty(cacheProperty, "45000");
        System.setProperty(unavailableProperty, "300000");
        try {
            assertEquals(45_000L, WorkerRegistry.dispatchPreflightCacheMs());
            assertEquals(300_000L, WorkerRegistry.dispatchPreflightUnavailableMs());
        } finally {
            restoreProperty(cacheProperty, previousCache);
            restoreProperty(unavailableProperty, previousUnavailable);
        }
    }

    @Test
    void dispatchReadinessProjectsPassiveFallbackProbeMode() {
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(new StaticProvider("codex", true, true, "ready"));
        WorkerRegistry registry = new WorkerRegistry(providers);

        WorkerRegistry.ReadinessCheck dispatch = registry.checkReadiness("codex", "dispatch");

        assertTrue(dispatch.ready());
        assertTrue(dispatch.dispatchPreflightReady());
        assertEquals("passive_status", dispatch.dispatchPreflightMode());
        assertFalse(dispatch.dispatchPreflightActiveProbe());
    }

    @Test
    void dispatchReadinessStrictModeRejectsPassiveFallbackProbe() {
        String property = "agentcloud.dispatch.preflight.require_active_probe";
        String previous = System.getProperty(property);
        System.setProperty(property, "true");
        try {
            AgentProviderRegistry providers = new AgentProviderRegistry()
                .register(new StaticProvider("codex", true, true, "ready"));
            WorkerRegistry registry = new WorkerRegistry(providers);

            WorkerRegistry.ReadinessCheck dispatch = registry.checkReadiness("codex", "dispatch");

            assertFalse(dispatch.ready());
            assertFalse(dispatch.dispatchPreflightReady());
            assertEquals("passive_status", dispatch.dispatchPreflightMode());
            assertFalse(dispatch.dispatchPreflightActiveProbe());
            assertTrue(dispatch.dispatchPreflightReason().contains("active probe required"));
        } finally {
            restoreProperty(property, previous);
        }
    }

    @Test
    void dispatchReadinessStrictModeAllowsActiveProbe() {
        String property = "agentcloud.dispatch.preflight.require_active_probe";
        String previous = System.getProperty(property);
        System.setProperty(property, "true");
        try {
            AgentProviderRegistry providers = new AgentProviderRegistry()
                .register(new PreflightProvider("codex", true, true, "ready"));
            WorkerRegistry registry = new WorkerRegistry(providers);

            WorkerRegistry.ReadinessCheck dispatch = registry.checkReadiness("codex", "dispatch");

            assertTrue(dispatch.ready());
            assertTrue(dispatch.dispatchPreflightReady());
            assertEquals("active_probe", dispatch.dispatchPreflightMode());
            assertTrue(dispatch.dispatchPreflightActiveProbe());
        } finally {
            restoreProperty(property, previous);
        }
    }

    @Test
    void workerRegistryCachesDispatchPreflightAndKeepsCommandMetadata() {
        CountingPreflightProvider provider = new CountingPreflightProvider("codex");
        AgentProviderRegistry providers = new AgentProviderRegistry().register(provider);
        WorkerRegistry registry = new WorkerRegistry(providers);

        WorkerRegistry.ReadinessCheck first = registry.checkReadiness("codex", "dispatch");
        WorkerRegistry.ReadinessCheck second = registry.checkReadiness("codex", "dispatch");

        assertTrue(first.ready());
        assertFalse(first.dispatchPreflightCached());
        assertEquals(List.of("--version"), first.dispatchPreflightMetadata().get("dispatch_preflight_probe_args"));
        assertEquals(true, first.cliProfile().get("cli_profile_evidence_available"));
        assertEquals(false, first.cliProfile().get("supports_yolo"));
        assertTrue(second.ready());
        assertTrue(second.dispatchPreflightCached());
        assertEquals(List.of("--version"), second.dispatchPreflightMetadata().get("dispatch_preflight_probe_args"));
        assertEquals(false, second.cliProfile().get("supports_yolo"));
        assertEquals(1, provider.dispatchCount());
    }

    @Test
    void agentProviderRegistryKeepsCliProfileAcrossPassiveStatusRefresh() {
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(new ProfilePreflightProvider("gemini"));

        AgentProviderStatus dispatch = providers.dispatchPreflight("gemini");
        AgentProviderStatus passive = providers.refresh("gemini");

        assertEquals(true, dispatch.metadata().get("cli_profile_evidence_available"));
        assertEquals(false, dispatch.metadata().get("supports_yolo"));
        assertEquals(true, passive.metadata().get("cli_profile_evidence_available"));
        assertEquals(false, passive.metadata().get("supports_yolo"));
        assertTrue(passive.metadata().containsKey("cli_profile_cached_at"));
        assertEquals(false, providers.cliProfileMetadata("gemini").get("supports_yolo"));
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

    private record PreflightProvider(String providerId,
                                     boolean passiveReady,
                                     boolean dispatchReady,
                                     String dispatchReason) implements AgentProvider {
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
                true,
                "0.0.0-test",
                "ready",
                passiveReady,
                passiveReady ? null : "passive not ready",
                null,
                java.util.Map.of("source", "test")
            );
        }

        @Override
        public AgentProviderStatus dispatchPreflight() {
            return new AgentProviderStatus(
                providerId,
                true,
                "0.0.0-test",
                "ready",
                dispatchReady,
                dispatchReady ? null : dispatchReason,
                null,
                java.util.Map.of(
                    "source", "dispatch_preflight_test",
                    "dispatch_preflight_mode", "active_probe"
                )
            );
        }
    }

    private void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private Path fakeCli(String baseName, String body) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return fakeCli(baseName, body, body);
    }

    private Path fakeCli(String baseName, String windowsBody, String unixBody) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path path = tempDir.resolve(baseName + (windows ? ".cmd" : ".sh"));
        String content = windows
            ? "@echo off\r\n" + windowsBody + "\r\n"
            : "#!/usr/bin/env sh\n" + unixBody + "\n";
        Files.writeString(path, content);
        if (!windows) {
            path.toFile().setExecutable(true);
        }
        return path;
    }

    private static final class CountingPreflightProvider implements AgentProvider {
        private final String providerId;
        private final AtomicInteger dispatchCount = new AtomicInteger();

        private CountingPreflightProvider(String providerId) {
            this.providerId = providerId;
        }

        int dispatchCount() {
            return dispatchCount.get();
        }

        @Override
        public AgentProviderDescriptor descriptor() {
            return new AgentProviderDescriptor(
                providerId,
                providerId,
                "local_cli",
                "process",
                List.of("chat"),
                Map.of()
            );
        }

        @Override
        public AgentProviderStatus detect() {
            return new AgentProviderStatus(
                providerId,
                true,
                "0.0.0-test",
                "ready",
                true,
                null,
                null,
                Map.of("source", "test")
            );
        }

        @Override
        public AgentProviderStatus dispatchPreflight() {
            dispatchCount.incrementAndGet();
            return new AgentProviderStatus(
                providerId,
                true,
                "0.0.0-test",
                "ready",
                true,
                null,
                null,
                Map.of(
                    "source", "dispatch_preflight_test",
                    "dispatch_preflight_mode", "active_probe",
                    "dispatch_preflight_probe_kind", "cli_help",
                    "dispatch_preflight_probe_args", List.of("--version"),
                    "dispatch_preflight_command_shape", List.of("direct", "--version"),
                    "dispatch_preflight_exit_code", 0,
                    "cli_profile_evidence_available", true,
                    "supports_yolo", false
                )
            );
        }
    }

    private static final class ProfilePreflightProvider implements AgentProvider {
        private final String providerId;

        private ProfilePreflightProvider(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public AgentProviderDescriptor descriptor() {
            return new AgentProviderDescriptor(
                providerId,
                providerId,
                "local_cli",
                "process",
                List.of("chat"),
                Map.of()
            );
        }

        @Override
        public AgentProviderStatus detect() {
            return new AgentProviderStatus(
                providerId,
                true,
                "0.0.0-test",
                "ready",
                true,
                null,
                null,
                Map.of("source", "passive_detect")
            );
        }

        @Override
        public AgentProviderStatus dispatchPreflight() {
            return new AgentProviderStatus(
                providerId,
                true,
                "0.0.0-test",
                "ready",
                true,
                null,
                null,
                Map.of(
                    "source", "dispatch_preflight_test",
                    "dispatch_preflight_mode", "active_probe",
                    "cli_profile_evidence_available", true,
                    "supports_yolo", false,
                    "supports_model", true
                )
            );
        }
    }
}
