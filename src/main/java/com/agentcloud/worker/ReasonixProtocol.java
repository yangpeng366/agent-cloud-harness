package com.agentcloud.worker;

import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.agent.providers.CliCapabilityProfile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReasonixProtocol implements ProviderProtocol {

    @Override
    public String providerId() {
        return "reasonix";
    }

    @Override
    public ProviderStatus detect(LocalCliProviderConfig.ResolvedConfig config) {
        String binary = config.launchSpec().configuredBinary();
        if (binary == null || binary.isBlank()) {
            return ProviderStatus.notReady();
        }
        return new ProviderStatus(true, null, Map.of());
    }

    @Override
    public ProviderCliPlan buildPlan(LocalCliProviderConfig.ResolvedConfig config,
                                    TaskRuntimeContext context,
                                    String cwd,
                                    CliCapabilityProfile profile) {
        String prompt = ProviderTaskPromptBuilder.build(context);
        String configuredModel = configuredModel(config, context);
        ArrayList<String> args = new ArrayList<>();
        args.add("run");
        args.add("--no-config");
        args.add("--no-proxy");

        // Add MCP filesystem access for task workspace directories
        List<String> workspacePaths = collectWorkspacePaths(context);
        for (String workspacePath : workspacePaths) {
            args.add("--mcp");
            args.add("filesystem:" + workspacePath);
        }

        args.add(prompt);
        return new ProviderCliPlan(
            config.launchSpec().command(args),
            truncate(prompt, 240),
            configuredModel,
            null,
            Map.of(),
            config.launchSpec().configuredBinary(),
            config.launchSpec().executableTarget(),
            config.launchSpec().launchMode(),
            profile,
            List.of()
        );
    }

    @Override
    public WorkerExecutionResult parseOutput(byte[] raw,
                                           ProviderCliPlan plan,
                                           long durationMs,
                                           Map<String, Object> baseMetadata) {
        String outputText = "";
        if (raw != null && raw.length > 0) {
            outputText = parseReasonixOutput(new String(raw, StandardCharsets.UTF_8));
        }

        String summary = summarize(outputText);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
        metadata.put("provider_output_parser", "reasonix_text");

        return new WorkerExecutionResult(
            summary,
            outputText,
            false,
            "",
            "",
            "",
            "medium",
            "completed",
            List.of(),
            List.of(),
            0,
            durationMs,
            Map.copyOf(metadata),
            ExecutionOutcome.COMPLETED
        );
    }

    private String parseReasonixOutput(String raw) {
        if (raw == null) return "";
        StringBuilder output = new StringBuilder();
        String[] lines = raw.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            if (trimmed.startsWith("⌘") || trimmed.startsWith("—")) continue;
            if (trimmed.startsWith("[skills]")) continue;
            if (trimmed.startsWith("- turns:")) continue;
            if (!output.isEmpty()) {
                output.append('\n');
            }
            output.append(trimmed);
        }
        return output.toString();
    }

    private String configuredModel(LocalCliProviderConfig.ResolvedConfig config, TaskRuntimeContext context) {
        String taskModel = context == null || context.task() == null ? null :
            ProviderTaskPromptBuilder.metadataString(context.task().metadata(), "provider_model");
        if (taskModel != null && !taskModel.isBlank()) {
            return taskModel;
        }
        return config.model().value();
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "...";
    }

    private List<String> collectWorkspacePaths(TaskRuntimeContext context) {
        if (context == null || context.task() == null) return List.of();
        Map<String, Object> metadata = context.task().metadata();
        if (metadata == null || metadata.isEmpty()) return List.of();

        List<String> paths = new ArrayList<>();
        for (String key : new String[]{"cwd", "workspace", "workspace_root", "working_directory",
                "repo_path", "workspace_roots", "workspaces", "workspace_paths"}) {
            Object raw = metadata.get(key);
            if (raw == null) continue;
            if (raw instanceof Iterable<?> iterable) {
                for (Object item : iterable) addPath(paths, item);
            } else if (raw.getClass().isArray() && raw instanceof Object[] array) {
                for (Object item : array) addPath(paths, item);
            } else {
                addPath(paths, raw);
            }
        }
        String goal = context.task().goal();
        if (goal != null) {
            for (String line : goal.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("workspace:") || trimmed.startsWith("Workspace:")) {
                    addPath(paths, trimmed.substring(trimmed.indexOf(':') + 1));
                }
            }
        }
        return dedupePaths(paths);
    }

    private void addPath(List<String> paths, Object raw) {
        if (raw == null) return;
        String path = raw.toString().trim();
        if (path.isEmpty() || path.startsWith("{") || path.startsWith("[")) return;
        if (path.matches("^[a-zA-Z]:\\\\.*")) paths.add(path);
    }

    private List<String> dedupePaths(List<String> paths) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(paths));
    }

    private String summarize(String outputText) {
        if (outputText == null || outputText.isBlank()) {
            return "";
        }
        String normalized = outputText.replaceAll("\\s+", " ").trim();
        return normalized.length() > 240 ? normalized.substring(0, 240) + "..." : normalized;
    }
}