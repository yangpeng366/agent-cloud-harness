package com.agentcloud.agent.providers;

import com.agentcloud.tool.HostToolAvailability;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 本地 CLI provider 的共享配置解析，避免探测和执行链路使用不同配置来源。
 */
public final class LocalCliProviderConfig {
    private final String providerId;
    private final String defaultBinary;
    private final String pathEnvVar;
    private final String modelEnvVar;
    private final String pathProperty;
    private final String modelProperty;

    public LocalCliProviderConfig(String providerId,
                                  String defaultBinary,
                                  String pathEnvVar,
                                  String modelEnvVar) {
        this.providerId = providerId == null ? "" : providerId;
        this.defaultBinary = defaultBinary == null || defaultBinary.isBlank() ? this.providerId : defaultBinary;
        this.pathEnvVar = blankToNull(pathEnvVar);
        this.modelEnvVar = blankToNull(modelEnvVar);
        this.pathProperty = this.providerId.isBlank() ? null : "agentcloud.providers." + this.providerId + ".path";
        this.modelProperty = this.providerId.isBlank() ? null : "agentcloud.providers." + this.providerId + ".model";
    }

    public ResolvedConfig resolve() {
        ConfigValue binary = resolveConfig(pathProperty, pathEnvVar, defaultBinary);
        ConfigValue model = resolveConfig(modelProperty, modelEnvVar, null);
        return new ResolvedConfig(
            providerId,
            defaultBinary,
            pathEnvVar,
            modelEnvVar,
            pathProperty,
            modelProperty,
            binary,
            model
        );
    }

    private ConfigValue resolveConfig(String propertyKey, String envKey, String fallbackValue) {
        String propertyValue = blankToNull(propertyKey == null ? null : System.getProperty(propertyKey));
        if (propertyValue != null) {
            return new ConfigValue(propertyValue, "system_property");
        }
        String envValue = blankToNull(envKey == null ? null : System.getenv(envKey));
        if (envValue != null) {
            return new ConfigValue(envValue, "environment");
        }
        return new ConfigValue(fallbackValue, "default");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record ResolvedConfig(String providerId,
                                 String defaultBinary,
                                 String pathEnvVar,
                                 String modelEnvVar,
                                 String pathProperty,
                                 String modelProperty,
                                 ConfigValue binary,
                                 ConfigValue model) {
        public ResolvedConfig {
            if (providerId == null) providerId = "";
            if (defaultBinary == null || defaultBinary.isBlank()) defaultBinary = providerId;
            if (binary == null) binary = new ConfigValue(defaultBinary, "default");
            if (model == null) model = new ConfigValue("", "default");
        }

        public LaunchSpec launchSpec() {
            return LaunchSpec.resolve(binary.value());
        }

        public Map<String, Object> metadata() {
            LaunchSpec launchSpec = launchSpec();
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("binary", defaultBinary);
            metadata.put("configured_binary", binary.value());
            metadata.put("binary_source", binary.source());
            putIfNotBlank(metadata, "launch_target", launchSpec.executableTarget());
            putIfNotBlank(metadata, "launch_mode", launchSpec.launchMode());
            metadata.put("launch_available", launchSpec.available());
            putIfNotBlank(metadata, "path_env_var", pathEnvVar);
            putIfNotBlank(metadata, "path_property", pathProperty);
            putIfNotBlank(metadata, "configured_model", model.value());
            putIfNotBlank(metadata, "model_source", model.source());
            putIfNotBlank(metadata, "model_env_var", modelEnvVar);
            putIfNotBlank(metadata, "model_property", modelProperty);
            return Map.copyOf(metadata);
        }

        private void putIfNotBlank(Map<String, Object> target, String key, String value) {
            if (target != null && key != null && value != null && !value.isBlank()) {
                target.put(key, value);
            }
        }
    }

    public record ConfigValue(String value, String source) {
        public ConfigValue {
            if (value == null || value.isBlank()) value = "";
            if (source == null || source.isBlank()) source = "default";
        }
    }

    public record LaunchSpec(String configuredBinary,
                             String executableTarget,
                             String launchMode,
                             List<String> commandPrefix,
                             boolean available) {
        public LaunchSpec {
            if (configuredBinary == null) configuredBinary = "";
            if (executableTarget == null || executableTarget.isBlank()) executableTarget = configuredBinary;
            if (launchMode == null || launchMode.isBlank()) launchMode = "direct";
            if (commandPrefix == null) commandPrefix = List.of();
        }

        public List<String> command(List<String> args) {
            ArrayList<String> command = new ArrayList<>(commandPrefix);
            if (args != null && !args.isEmpty()) {
                command.addAll(args);
            }
            return List.copyOf(command);
        }

        public LaunchSpec withExecutableTarget(String target) {
            String normalizedTarget = blankToNull(target);
            if (normalizedTarget == null) {
                return this;
            }
            return launchSpecForResolvedTarget(configuredBinary, normalizedTarget);
        }

        public static LaunchSpec resolve(String configuredBinary) {
            String normalizedConfiguredBinary = blankToNull(configuredBinary);
            if (normalizedConfiguredBinary == null) {
                return new LaunchSpec("", "", "direct", List.of(), false);
            }
            Path resolvedPath = resolveBinaryPath(normalizedConfiguredBinary);
            if (!HostToolAvailability.isWindowsHost()) {
                String target = resolvedPath != null
                    ? resolvedPath.toAbsolutePath().normalize().toString()
                    : normalizedConfiguredBinary;
                return new LaunchSpec(
                    normalizedConfiguredBinary,
                    target,
                    "direct",
                    List.of(target),
                    resolvedPath != null || HostToolAvailability.isToolAvailable(normalizedConfiguredBinary)
                );
            }

            if (resolvedPath == null) {
                return new LaunchSpec(
                    normalizedConfiguredBinary,
                    normalizedConfiguredBinary,
                    "direct",
                    List.of(normalizedConfiguredBinary),
                    false
                );
            }

            Path preferredTarget = preferWindowsLaunchTarget(resolvedPath);
            return launchSpecForResolvedTarget(
                normalizedConfiguredBinary,
                preferredTarget.toAbsolutePath().normalize().toString()
            );
        }

        private static Path resolveBinaryPath(String configuredBinary) {
            Path directPath = directPath(configuredBinary);
            if (directPath != null) {
                if (Files.isRegularFile(directPath)) {
                    return directPath;
                }
                if (HostToolAvailability.isWindowsHost()) {
                    Path companion = locateWindowsCompanion(directPath);
                    if (companion != null) {
                        return companion;
                    }
                }
                return null;
            }
            return locateOnPath(configuredBinary);
        }

        private static Path preferWindowsLaunchTarget(Path resolvedPath) {
            if (resolvedPath == null || !HostToolAvailability.isWindowsHost()) {
                return resolvedPath;
            }
            Path companion = locateWindowsCompanion(resolvedPath);
            return companion != null ? companion : resolvedPath;
        }

        private static Path locateWindowsCompanion(Path path) {
            if (path == null) {
                return null;
            }
            String extension = extension(path.getFileName() == null ? "" : path.getFileName().toString());
            if (!extension.isBlank()) {
                return null;
            }
            Path parent = path.getParent();
            String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
            if (parent == null || fileName.isBlank()) {
                return null;
            }
            for (String candidateExtension : windowsExecutableExtensions()) {
                Path candidate = parent.resolve(fileName + candidateExtension);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().normalize();
                }
            }
            return null;
        }

        private static Path directPath(String toolName) {
            if (toolName == null || toolName.isBlank()) {
                return null;
            }
            if (!toolName.contains("/") && !toolName.contains("\\")) {
                return null;
            }
            try {
                return Paths.get(toolName).toAbsolutePath().normalize();
            } catch (InvalidPathException ignored) {
                return null;
            }
        }

        private static Path locateOnPath(String toolName) {
            if (toolName == null || toolName.isBlank()) {
                return null;
            }
            String pathValue = System.getenv("PATH");
            if (pathValue == null || pathValue.isBlank()) {
                return null;
            }
            for (String rawEntry : pathValue.split(File.pathSeparator)) {
                String entry = rawEntry == null ? "" : rawEntry.trim();
                if (entry.isBlank()) {
                    continue;
                }
                Path directory;
                try {
                    directory = Paths.get(unquote(entry));
                } catch (Exception ignored) {
                    continue;
                }
                for (String candidate : candidateExecutableNames(toolName)) {
                    Path path = directory.resolve(candidate);
                    if (Files.isRegularFile(path)) {
                        return path.toAbsolutePath().normalize();
                    }
                }
            }
            return null;
        }

        private static List<String> candidateExecutableNames(String toolName) {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            if (!HostToolAvailability.isWindowsHost()) {
                names.add(toolName);
                return List.copyOf(names);
            }
            String normalized = blankToNull(toolName);
            if (normalized == null) {
                return List.of();
            }
            if (normalized.contains(".")) {
                names.add(normalized);
                return List.copyOf(names);
            }
            for (String extension : windowsExecutableExtensions()) {
                names.add(normalized + extension);
            }
            names.add(normalized);
            return List.copyOf(names);
        }

        private static List<String> windowsExecutableExtensions() {
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            ordered.add(".exe");
            ordered.add(".cmd");
            ordered.add(".bat");
            ordered.add(".com");
            ordered.add(".ps1");
            String pathExt = System.getenv("PATHEXT");
            if (pathExt != null && !pathExt.isBlank()) {
                for (String raw : pathExt.split(";")) {
                    String extension = blankToNull(raw);
                    if (extension == null) {
                        continue;
                    }
                    String normalized = extension.startsWith(".")
                        ? extension.toLowerCase(Locale.ROOT)
                        : ("." + extension).toLowerCase(Locale.ROOT);
                    ordered.add(normalized);
                }
            }
            return List.copyOf(ordered);
        }

        private static String extension(Path path) {
            return path == null || path.getFileName() == null
                ? ""
                : extension(path.getFileName().toString());
        }

        private static String extension(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            int index = value.lastIndexOf('.');
            if (index < 0 || index == value.length() - 1) {
                return "";
            }
            return value.substring(index).toLowerCase(Locale.ROOT);
        }

        private static LaunchSpec launchSpecForResolvedTarget(String configuredBinary, String resolvedTarget) {
            String normalizedTarget = blankToNull(resolvedTarget);
            if (normalizedTarget == null) {
                return new LaunchSpec(configuredBinary, configuredBinary, "direct", List.of(configuredBinary), false);
            }
            String extension = extension(normalizedTarget);
            if (".ps1".equals(extension)) {
                String powershellBinary = powershellBinary();
                return new LaunchSpec(
                    configuredBinary,
                    normalizedTarget,
                    "powershell_file",
                    List.of(
                        powershellBinary,
                        "-NoLogo",
                        "-NonInteractive",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-File",
                        normalizedTarget
                    ),
                    HostToolAvailability.isToolAvailable(powershellBinary)
                );
            }
            if (".cmd".equals(extension) || ".bat".equals(extension)) {
                String cmdBinary = cmdBinary();
                return new LaunchSpec(
                    configuredBinary,
                    normalizedTarget,
                    "cmd_file",
                    List.of(cmdBinary, "/c", normalizedTarget),
                    HostToolAvailability.isToolAvailable(cmdBinary)
                );
            }
            return new LaunchSpec(
                configuredBinary,
                normalizedTarget,
                "direct",
                List.of(normalizedTarget),
                Files.isRegularFile(Path.of(normalizedTarget))
            );
        }

        private static String powershellBinary() {
            if (HostToolAvailability.isToolAvailable("powershell.exe")) {
                return "powershell.exe";
            }
            if (HostToolAvailability.isToolAvailable("powershell")) {
                return "powershell";
            }
            return "powershell.exe";
        }

        private static String cmdBinary() {
            if (HostToolAvailability.isToolAvailable("cmd.exe")) {
                return "cmd.exe";
            }
            if (HostToolAvailability.isToolAvailable("cmd")) {
                return "cmd";
            }
            return "cmd.exe";
        }

        private static String unquote(String value) {
            if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
