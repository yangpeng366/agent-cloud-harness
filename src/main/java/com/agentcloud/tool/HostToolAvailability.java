package com.agentcloud.tool;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 宿主机工具可用性探测。
 */
public final class HostToolAvailability {
    private static final List<String> COMMAND_TOOL_CAPABILITIES = List.of("git", "shell", "powershell", "cmd");
    private static final Set<String> DEFAULT_WINDOWS_EXTENSIONS = Set.of(".exe", ".cmd", ".bat", ".com");

    private HostToolAvailability() {
    }

    public static boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isCommandToolCapability(String toolCapability) {
        String normalized = normalizeToolCapability(toolCapability);
        return normalized != null && COMMAND_TOOL_CAPABILITIES.contains(normalized);
    }

    public static boolean isToolCapabilityAvailable(String toolCapability) {
        String normalized = normalizeToolCapability(toolCapability);
        if (normalized == null) {
            return false;
        }
        return switch (normalized) {
            case "git" -> isToolAvailable("git");
            case "shell" -> isWindowsHost()
                ? isToolAvailable("cmd.exe") || isToolAvailable("cmd")
                : isToolAvailable("/bin/sh");
            case "powershell" -> isWindowsHost()
                && (isToolAvailable("powershell.exe") || isToolAvailable("powershell"));
            case "cmd" -> isWindowsHost()
                && (isToolAvailable("cmd.exe") || isToolAvailable("cmd"));
            default -> true;
        };
    }

    public static String unavailableReason(String toolCapability) {
        String normalized = normalizeToolCapability(toolCapability);
        if (normalized == null || !COMMAND_TOOL_CAPABILITIES.contains(normalized)) {
            return null;
        }
        if (!isWindowsHost() && ("powershell".equals(normalized) || "cmd".equals(normalized))) {
            return normalized + " is only available on Windows hosts";
        }
        return isToolCapabilityAvailable(normalized)
            ? null
            : normalized + " is not available on this host";
    }

    public static List<String> supportedCommandToolCapabilities() {
        ArrayList<String> toolCapabilities = new ArrayList<>();
        for (String toolCapability : COMMAND_TOOL_CAPABILITIES) {
            if (unavailableReason(toolCapability) == null) {
                toolCapabilities.add(toolCapability);
            }
        }
        return List.copyOf(toolCapabilities);
    }

    public static Map<String, Boolean> readinessChecks(Iterable<String> toolCapabilities) {
        LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        if (toolCapabilities == null) {
            return Map.of();
        }
        for (String toolCapability : toolCapabilities) {
            String normalized = normalizeToolCapability(toolCapability);
            if (normalized == null || !COMMAND_TOOL_CAPABILITIES.contains(normalized)) {
                continue;
            }
            checks.put("tool:" + normalized, isToolCapabilityAvailable(normalized));
        }
        return checks.isEmpty() ? Map.of() : Map.copyOf(checks);
    }

    public static Map<String, Boolean> declaredToolAvailability(Iterable<String> toolCapabilities) {
        LinkedHashMap<String, Boolean> availability = new LinkedHashMap<>();
        if (toolCapabilities == null) {
            return Map.of();
        }
        for (String toolCapability : toolCapabilities) {
            String normalized = normalizeToolCapability(toolCapability);
            if (normalized == null || !COMMAND_TOOL_CAPABILITIES.contains(normalized)) {
                continue;
            }
            availability.put(normalized, isToolCapabilityAvailable(normalized));
        }
        return availability.isEmpty() ? Map.of() : Map.copyOf(availability);
    }

    public static boolean isToolAvailable(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }

        Path directPath = toDirectPath(toolName);
        if (directPath != null) {
            return isRunnableFile(directPath);
        }

        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return false;
        }

        for (String rawEntry : pathValue.split(File.pathSeparator)) {
            String pathEntry = rawEntry == null ? "" : rawEntry.trim();
            if (pathEntry.isBlank()) {
                continue;
            }

            Path directory;
            try {
                directory = Path.of(unquote(pathEntry));
            } catch (InvalidPathException ignored) {
                continue;
            }
            for (String candidateName : candidateExecutableNames(toolName)) {
                if (isRunnableFile(directory.resolve(candidateName))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalizeToolCapability(String toolCapability) {
        if (toolCapability == null || toolCapability.isBlank()) {
            return null;
        }
        return toolCapability.trim().toLowerCase(Locale.ROOT);
    }

    private static Path toDirectPath(String toolName) {
        if (toolName.contains("/") || toolName.contains("\\")) {
            try {
                return Path.of(toolName);
            } catch (InvalidPathException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Iterable<String> candidateExecutableNames(String toolName) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(toolName);
        if (!isWindowsHost() || toolName.contains(".")) {
            return candidates;
        }

        String pathExtValue = System.getenv("PATHEXT");
        Set<String> extensions = pathExtValue == null || pathExtValue.isBlank()
            ? DEFAULT_WINDOWS_EXTENSIONS
            : parsePathExt(pathExtValue);
        for (String extension : extensions) {
            candidates.add(toolName + extension);
        }
        return candidates;
    }

    private static Set<String> parsePathExt(String pathExtValue) {
        LinkedHashSet<String> extensions = new LinkedHashSet<>();
        for (String rawExtension : pathExtValue.split(";")) {
            String extension = rawExtension == null ? "" : rawExtension.trim();
            if (extension.isBlank()) {
                continue;
            }
            extensions.add(extension.toLowerCase(Locale.ROOT));
        }
        return extensions.isEmpty() ? DEFAULT_WINDOWS_EXTENSIONS : extensions;
    }

    private static boolean isRunnableFile(Path candidate) {
        if (!Files.isRegularFile(candidate)) {
            return false;
        }
        return isWindowsHost() || Files.isExecutable(candidate);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
