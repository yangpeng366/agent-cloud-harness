package com.agentcloud.agent.providers;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderDescriptor;
import com.agentcloud.agent.AgentProviderStatus;
import com.agentcloud.runtime.TextDecoding;
import com.agentcloud.worker.ProviderFailureClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
    private final List<String> configuredDispatchProbeArgs;

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
        this.configuredDispatchProbeArgs = configuredDispatchProbeArgs(metadata);
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

    @Override
    public AgentProviderStatus dispatchPreflight() {
        LocalCliProviderConfig.ResolvedConfig resolved = cliConfig.resolve();
        LocalCliProviderConfig.LaunchSpec launchSpec = resolved.launchSpec();
        LocalCliProviderConfig.ConfigValue binary = resolved.binary();
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(resolved.metadata());
        List<String> probeArgs = dispatchProbeArgs(providerId, binary.value());
        metadata.put("dispatch_preflight_mode", "active_probe");
        metadata.put("dispatch_preflight_probe_kind", "cli_help");
        metadata.put("dispatch_preflight_probe_args", probeArgs);
        metadata.put("dispatch_preflight_command_shape", commandShape(launchSpec, probeArgs));

        if (!launchSpec.available()) {
            return new AgentProviderStatus(
                providerId,
                false,
                null,
                "unknown",
                false,
                "binary not found: " + binary.value(),
                Instant.now(),
                Map.copyOf(metadata)
            );
        }

        CommandProbeResult probe = runCommandProbe(launchSpec.command(probeArgs));
        metadata.put("dispatch_preflight_exit_code", probe.exitCode());
        if (probe.outputPreview() != null && !probe.outputPreview().isBlank()) {
            metadata.put("dispatch_preflight_output_preview", probe.outputPreview());
        }
        CliCapabilityProfile profile = CliCapabilityProfile.fromHelpOutput(providerId, probe.output());
        metadata.putAll(profile.metadata());

        boolean ready = probe.exitCode() == 0;
        String failureReason = ready ? null : commandProbeFailureReason(probe);
        if (!ready) {
            ProviderFailureClassifier.Classification classification =
                ProviderFailureClassifier.classify("failed", failureReason);
            if (classification != null) {
                metadata.put("provider_failure_class", classification.failureClass());
                metadata.put("provider_failure_reason", classification.reason());
                metadata.put("provider_retryable", classification.retryable());
            }
        }
        return new AgentProviderStatus(
            providerId,
            true,
            null,
            "unknown",
            ready,
            failureReason,
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
            String output = TextDecoding.decodeExternalProcessOutput(process.getInputStream().readAllBytes()).trim();
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

    private CommandProbeResult runCommandProbe(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            if (!process.waitFor(VERSION_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new CommandProbeResult(-1, "probe timed out", "probe timed out");
            }
            String output = TextDecoding.decodeExternalProcessOutput(process.getInputStream().readAllBytes()).trim();
            return new CommandProbeResult(
                process.exitValue(),
                truncate(firstNonBlankLine(output), VERSION_PREVIEW_LIMIT),
                output
            );
        } catch (IOException e) {
            log.debug("Dispatch command probe failed for provider={} command={} reason={}",
                providerId, command, e.getMessage());
            return new CommandProbeResult(-1, e.getMessage(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandProbeResult(-1, "probe interrupted", "probe interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private List<String> dispatchProbeArgs(String providerId, String configuredBinary) {
        if (!configuredDispatchProbeArgs.isEmpty()) {
            return configuredDispatchProbeArgs;
        }
        return switch ((providerId == null ? "" : providerId).toLowerCase()) {
            case "cursor" -> List.of("chat", "--help");
            case "opencode" -> List.of("run", "--help");
            case "deepseek" -> List.of("exec", "--help");
            case "reasonix" -> List.of("run", "--help");
            case "trae" -> List.of("chat", "--help");
            case "codebuddy" -> List.of("--help");
            case "hermes" -> List.of("--help");
            case "pi" -> List.of("--help");
            case "kiro" -> List.of("--help");
            case "codex" -> List.of("--version");
            default -> List.of("--help");
        };
    }

    private List<String> configuredDispatchProbeArgs(Map<String, Object> metadata) {
        if (metadata == null) {
            return List.of();
        }
        Object value = metadata.get("dispatch_probe_args");
        if (value == null) {
            value = metadata.get("dispatch_preflight_probe_args");
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .map(item -> item == null ? "" : String.valueOf(item).trim())
                .filter(item -> !item.isBlank())
                .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.trim());
        }
        return List.of();
    }

    private String commandProbeFailureReason(CommandProbeResult probe) {
        if (probe == null) {
            return "command probe failed";
        }
        String output = probe.outputPreview();
        if (output == null || output.isBlank()) {
            return "command probe failed: exit_code=" + probe.exitCode();
        }
        return "command probe failed: exit_code=" + probe.exitCode() + " output=" + output;
    }

    private List<String> commandShape(LocalCliProviderConfig.LaunchSpec launchSpec, List<String> probeArgs) {
        if (launchSpec == null) {
            return List.of();
        }
        java.util.ArrayList<String> shape = new java.util.ArrayList<>();
        shape.add(launchSpec.launchMode());
        shape.addAll(probeArgs == null ? List.of() : probeArgs);
        return List.copyOf(shape);
    }

    private String firstNonBlankLine(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        return output.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .findFirst()
            .orElse(null);
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

    private record CommandProbeResult(int exitCode, String outputPreview, String output) {}
}
