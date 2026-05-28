package com.agentcloud.tool;

import com.agentcloud.model.Worker;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Worker 工具调用边界策略。
 */
public class ToolPolicy {
    private static final int DEFAULT_COMMAND_TIMEOUT_MS = 15_000;
    private static final int MAX_COMMAND_TIMEOUT_MS = 120_000;
    private static final int DEFAULT_COMMAND_OUTPUT_CHARS = 16_000;
    private static final int MAX_COMMAND_OUTPUT_CHARS = 64_000;
    private static final Set<String> ALLOWED_GIT_SUBCOMMANDS = Set.of(
        "status", "diff", "show", "log", "branch", "rev-parse", "ls-files", "grep"
    );
    private static final List<String> DANGEROUS_COMMAND_SNIPPETS = List.of(
        " rm -rf",
        " rm -fr",
        "remove-item",
        " del ",
        " erase ",
        " rmdir ",
        " rd /s",
        " format ",
        " mkfs",
        " shutdown",
        " reboot",
        " poweroff",
        " halt",
        " restart-computer",
        " stop-computer",
        " diskpart",
        " reg delete",
        " sc delete",
        " git reset --hard",
        " git clean -fd",
        " git clean -xdf",
        " git checkout --",
        " git restore ",
        " git stash clear",
        " git stash drop"
    );

    public void ensureToolAllowed(Worker worker, String toolName) {
        if (worker == null) {
            throw new IllegalArgumentException("worker not found");
        }
        if (worker.suggestOnly()) {
            throw new IllegalArgumentException("worker is suggest-only and cannot invoke tools");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("tool_name is required");
        }
        if (worker.toolCapabilities() == null || !worker.toolCapabilities().contains(toolName)) {
            throw new IllegalArgumentException("tool not allowed for worker: " + toolName);
        }
    }

    public Path resolveAllowedPath(Worker worker, String rawPath, boolean writeMode) {
        return resolveAllowedPath(worker, rawPath, writeMode, Map.of());
    }

    public Path resolveAllowedPath(Worker worker, String rawPath, boolean writeMode, Map<String, Object> taskMetadata) {
        if (worker == null) {
            throw new IllegalArgumentException("worker not found");
        }
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        if (worker.toolScope() == null || worker.toolScope().isEmpty()) {
            throw new IllegalArgumentException("worker has no tool scope");
        }

        Path candidate = Path.of(rawPath);
        if (!candidate.isAbsolute()) {
            candidate = Path.of(worker.toolScope().get(0)).resolve(candidate);
        }
        candidate = candidate.toAbsolutePath().normalize();

        for (String scopeValue : allowedScopes(worker, taskMetadata)) {
            if (scopeValue == null || scopeValue.isBlank()) {
                continue;
            }
            Path scope = Path.of(scopeValue).toAbsolutePath().normalize();
            if (candidate.startsWith(scope)) {
                return candidate;
            }
        }

        throw new IllegalArgumentException(
            (writeMode ? "write path outside allowed scope: " : "read path outside allowed scope: ") + candidate
        );
    }

    public Path resolveWorkingDirectory(Worker worker, Map<String, Object> arguments) {
        return resolveWorkingDirectory(worker, arguments, Map.of());
    }

    public Path resolveWorkingDirectory(Worker worker, Map<String, Object> arguments, Map<String, Object> taskMetadata) {
        Object raw = arguments == null ? null : arguments.get("cwd");
        String requested = raw == null || raw.toString().isBlank()
            ? scopeRoot(worker, taskMetadata)
            : raw.toString();
        return resolveAllowedPath(worker, requested, false, taskMetadata);
    }

    public int resolveCommandTimeoutMs(Map<String, Object> arguments) {
        return boundedPositiveInt(arguments, "timeout_ms", DEFAULT_COMMAND_TIMEOUT_MS, 1_000, MAX_COMMAND_TIMEOUT_MS);
    }

    public int resolveCommandMaxOutputChars(Map<String, Object> arguments) {
        return boundedPositiveInt(arguments, "max_output_chars", DEFAULT_COMMAND_OUTPUT_CHARS, 200, MAX_COMMAND_OUTPUT_CHARS);
    }

    public void ensureCommandTextAllowed(String toolName, String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        String normalized = " " + command
            .toLowerCase(Locale.ROOT)
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            + " ";
        for (String snippet : DANGEROUS_COMMAND_SNIPPETS) {
            if (normalized.contains(snippet)) {
                throw new IllegalArgumentException("dangerous command rejected for tool: " + toolName);
            }
        }
    }

    public void ensureGitArgsAllowed(List<String> args) {
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("git args are required");
        }
        String subcommand = args.get(0) == null ? "" : args.get(0).trim().toLowerCase(Locale.ROOT);
        if (subcommand.isBlank()) {
            throw new IllegalArgumentException("git subcommand is required");
        }
        if (!ALLOWED_GIT_SUBCOMMANDS.contains(subcommand)) {
            throw new IllegalArgumentException("git subcommand not allowed: " + subcommand);
        }
    }

    private List<String> allowedScopes(Worker worker, Map<String, Object> taskMetadata) {
        java.util.ArrayList<String> scopes = new java.util.ArrayList<>();
        if (worker != null && worker.toolScope() != null) {
            scopes.addAll(worker.toolScope());
        }
        addMetadataScope(scopes, taskMetadata, "workspace_root");
        addMetadataScope(scopes, taskMetadata, "workspace");
        addMetadataScope(scopes, taskMetadata, "working_directory");
        addMetadataScope(scopes, taskMetadata, "cwd");
        addMetadataScope(scopes, taskMetadata, "repo_path");
        addMetadataScope(scopes, taskMetadata, "workspace_roots");
        addMetadataScope(scopes, taskMetadata, "workspaces");
        return scopes.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
    }

    private void addMetadataScope(List<String> scopes, Map<String, Object> metadata, String key) {
        if (scopes == null || metadata == null || key == null) {
            return;
        }
        Object value = metadata.get(key);
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && !item.toString().isBlank()) {
                    scopes.add(item.toString());
                }
            }
            return;
        }
        if (value != null && !value.toString().isBlank()) {
            scopes.add(value.toString());
        }
    }

    private String scopeRoot(Worker worker, Map<String, Object> taskMetadata) {
        List<String> scopes = allowedScopes(worker, taskMetadata);
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("worker has no tool scope");
        }
        return scopes.get(0);
    }

    private int boundedPositiveInt(Map<String, Object> arguments,
                                   String key,
                                   int defaultValue,
                                   int minValue,
                                   int maxValue) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            parsed = Integer.parseInt(value.toString());
        }
        if (parsed < minValue) {
            return minValue;
        }
        return Math.min(parsed, maxValue);
    }
}
