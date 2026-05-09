package com.agentcloud.agent.providers;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderDescriptor;
import com.agentcloud.agent.AgentProviderStatus;
import com.agentcloud.tool.HostToolAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 复用本地 CLI 型 agent provider 的探测逻辑。
 */
public class LocalCliAgentProvider implements AgentProvider {
    private static final Logger log = LoggerFactory.getLogger(LocalCliAgentProvider.class);
    private static final long VERSION_PROBE_TIMEOUT_MS = 1200L;
    private static final int VERSION_PREVIEW_LIMIT = 200;

    private final AgentProviderDescriptor descriptor;
    private final String providerId;
    private final String defaultBinary;
    private final String pathEnvVar;
    private final String modelEnvVar;
    private final LocalCliProviderConfig cliConfig;

    public LocalCliAgentProvider(String providerId,
                                 String displayName,
                                 List<String> capabilities,
                                 Map<String, Object> metadata,
                                 String defaultBinary,
                                 String pathEnvVar,
                                 String modelEnvVar) {
        this(providerId, displayName, "local_cli", "pty", capabilities, metadata,
            defaultBinary, pathEnvVar, modelEnvVar);
    }

    public LocalCliAgentProvider(String providerId,
                                 String displayName,
                                 String providerType,
                                 String transport,
                                 List<String> capabilities,
                                 Map<String, Object> metadata,
                                 String defaultBinary,
                                 String pathEnvVar,
                                 String modelEnvVar) {
        this.providerId = providerId == null ? "" : providerId;
        this.defaultBinary = defaultBinary == null || defaultBinary.isBlank() ? this.providerId : defaultBinary;
        this.pathEnvVar = blankToNull(pathEnvVar);
        this.modelEnvVar = blankToNull(modelEnvVar);
        this.cliConfig = new LocalCliProviderConfig(this.providerId, this.defaultBinary, this.pathEnvVar, this.modelEnvVar);
        this.descriptor = new AgentProviderDescriptor(
            this.providerId,
            displayName,
            providerType,
            transport,
            capabilities,
            descriptorMetadata(metadata)
        );
    }

    @Override
    public AgentProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AgentProviderStatus detect() {
        LocalCliProviderConfig.ResolvedConfig resolved = cliConfig.resolve();
        LocalCliProviderConfig.LaunchSpec launchSpec = resolved.launchSpec();
        LocalCliProviderConfig.ConfigValue binary = resolved.binary();
        boolean installed = launchSpec.available();
        String version = installed ? probeVersion(launchSpec) : null;

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(resolved.metadata());
        if (version != null) {
            metadata.put("version_probe", "cli");
        }

        return new AgentProviderStatus(
            providerId,
            installed,
            version,
            "unknown",
            installed,
            installed ? null : "binary not found: " + binary.value(),
            Instant.now(),
            Map.copyOf(metadata)
        );
    }

    private Map<String, Object> descriptorMetadata(Map<String, Object> metadata) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (metadata != null) {
            merged.putAll(metadata);
        }
        merged.putAll(cliConfig.resolve().metadata());
        merged.put("probe_mode", "local_cli");
        return Map.copyOf(merged);
    }

    public LocalCliProviderConfig cliConfig() {
        return cliConfig;
    }

    private String probeVersion(LocalCliProviderConfig.LaunchSpec launchSpec) {
        for (List<String> args : List.of(
            List.of("--version"),
            List.of("version"),
            List.of("-v")
        )) {
            String version = runVersionProbe(launchSpec.command(args));
            if (version != null) {
                return version;
            }
        }
        return null;
    }

    private String runVersionProbe(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            if (!process.waitFor(VERSION_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (output.isBlank()) {
                return null;
            }
            String firstLine = output.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse(null);
            return truncate(firstLine, VERSION_PREVIEW_LIMIT);
        } catch (IOException e) {
            log.debug("Version probe failed for provider={} command={} reason={}", providerId, command, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "...";
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (target != null && key != null && value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
