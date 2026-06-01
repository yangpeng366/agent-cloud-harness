package com.agentcloud.worker;

import com.agentcloud.agent.providers.CliCapabilityProfile;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class OpenCodeProtocol implements ProviderProtocol {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String providerId() {
        return "opencode";
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
        LocalCliProviderConfig.LaunchSpec launchSpec = config.launchSpec();
        String resolvedExecutable = resolveOpenCodeExecutable(launchSpec.executableTarget());
        ArrayList<String> args = new ArrayList<>();
        args.add("run");
        args.add("--format");
        args.add("json");
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank()) {
            args.add("--model");
            args.add(model);
        }
        String systemPrompt = systemPrompt(context);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            args.add("--prompt");
            args.add(systemPrompt);
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank()) {
            args.add("--session");
            args.add(resumeId);
        }
        args.add(prompt);
        LocalCliProviderConfig.LaunchSpec resolvedLaunchSpec = launchSpec.withExecutableTarget(resolvedExecutable);
        return new ProviderCliPlan(
            resolvedLaunchSpec.command(args),
            truncate(prompt, 240),
            model,
            null,
            Map.of("OPENCODE_PERMISSION", "{\"*\":\"allow\"}"),
            resolvedLaunchSpec.configuredBinary(),
            resolvedLaunchSpec.executableTarget(),
            resolvedLaunchSpec.launchMode(),
            profile,
            List.of()
        );
    }

    @Override
    public WorkerExecutionResult parseOutput(byte[] raw,
                                             ProviderCliPlan plan,
                                             long durationMs,
                                             Map<String, Object> baseMetadata) {
        ParsedOpenCodeOutput parsed = parse(raw != null ? new String(raw, StandardCharsets.UTF_8) : "");
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
        metadata.put("provider_output_parser", "opencode_json");
        if (parsed.sessionId() != null && !parsed.sessionId().isBlank()) {
            metadata.put("provider_session_id", parsed.sessionId());
        }

        return new WorkerExecutionResult(
            summarize(parsed.outputText(), parsed.errorText(), parsed.status()),
            parsed.outputText(),
            false,
            "",
            "",
            parsed.sessionId(),
            "medium",
            parsed.status(),
            List.of(),
            parsed.errorText() == null || parsed.errorText().isBlank() ? List.of() : List.of(parsed.errorText()),
            0,
            durationMs,
            Map.copyOf(metadata),
            "failed".equals(parsed.status()) ? ExecutionOutcome.FAILED : ExecutionOutcome.COMPLETED
        );
    }

    private ParsedOpenCodeOutput parse(String raw) {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
        String status = "completed";
        String errorText = null;
        if (raw == null || raw.isBlank()) {
            return new ParsedOpenCodeOutput(status, "", null, null);
        }
        for (String line : raw.split("\\R")) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank() || trimmed.charAt(0) != '{') {
                appendLine(output, trimmed);
                continue;
            }
            try {
                JsonNode event = MAPPER.readTree(trimmed);
                String type = text(event, "type");
                JsonNode part = event.path("part");
                sessionId = firstNonBlank(sessionId, text(event, "sessionID"), text(part, "sessionID"));
                if ("text".equals(type)) {
                    appendRaw(output, text(part, "text"));
                } else if ("error".equals(type)) {
                    status = "failed";
                    errorText = firstNonBlank(errorText,
                        nestedText(event, "error", "data", "message"),
                        text(event.path("error"), "name"),
                        trimmed);
                }
            } catch (Exception ignored) {
                appendLine(output, trimmed);
            }
        }
        return new ParsedOpenCodeOutput(status, output.toString().trim(), errorText, sessionId);
    }

    private String configuredModel(LocalCliProviderConfig.ResolvedConfig config, TaskRuntimeContext context) {
        if (context != null && context.task() != null) {
            String taskModel = ProviderTaskPromptBuilder.metadataString(context.task().metadata(), "provider_model");
            if (taskModel != null && !taskModel.isBlank()) {
                return taskModel;
            }
        }
        return config.model().value();
    }

    private String systemPrompt(TaskRuntimeContext context) {
        String metadataPrompt = ProviderTaskPromptBuilder.metadataString(
            context == null || context.task() == null ? null : context.task().metadata(),
            "system_prompt"
        );
        if (metadataPrompt != null && !metadataPrompt.isBlank()) {
            return metadataPrompt;
        }
        return ProviderTaskPromptBuilder.defaultSystemPrompt("OpenCode");
    }

    private String resumeId(TaskRuntimeContext context) {
        if (context == null || context.task() == null) {
            return null;
        }
        String recoveryStage = ProviderTaskPromptBuilder.metadataString(context.task().metadata(), "recovery_stage");
        if ("same_worker_retry_scheduled".equalsIgnoreCase(recoveryStage)
            || "auto_handoff_scheduled".equalsIgnoreCase(recoveryStage)) {
            return null;
        }
        return firstNonBlank(
            ProviderTaskPromptBuilder.metadataString(context.task().metadata(), "provider_session_id"),
            ProviderTaskPromptBuilder.metadataString(context.task().metadata(), "provider_thread_id"),
            ProviderTaskPromptBuilder.metadataString(context.task().metadata(), "resume_provider_session_id")
        );
    }

    private String summarize(String outputText, String errorText, String status) {
        String base = firstNonBlank(outputText, errorText, status);
        if (base == null) {
            return "";
        }
        String normalized = base.replaceAll("\\s+", " ").trim();
        return normalized.length() > 240 ? normalized.substring(0, 240) + "..." : normalized;
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "...";
    }

    private void appendLine(StringBuilder target, String text) {
        if (target == null || text == null || text.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append('\n');
        }
        target.append(text.trim());
    }

    private void appendRaw(StringBuilder target, String text) {
        if (target == null || text == null || text.isBlank()) {
            return;
        }
        target.append(text);
    }

    private String text(JsonNode node, String field) {
        return node == null || field == null ? null : blankToNull(node.path(field).asText(""));
    }

    private String nestedText(JsonNode node, String... path) {
        JsonNode current = node;
        for (String step : path) {
            if (current == null || step == null) {
                return null;
            }
            current = current.path(step);
        }
        return current == null ? null : blankToNull(current.asText(""));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String resolveOpenCodeExecutable(String configuredBinary) {
        if (!isWindowsHost()) {
            return configuredBinary;
        }
        Path binaryPath = directPath(configuredBinary);
        if (binaryPath != null) {
            String nativePath = resolveOpenCodeNativeFromShim(binaryPath);
            return nativePath != null ? nativePath : configuredBinary;
        }
        Path located = locateOnPath(configuredBinary);
        if (located == null) {
            return configuredBinary;
        }
        String nativePath = resolveOpenCodeNativeFromShim(located);
        return nativePath != null ? nativePath : configuredBinary;
    }

    private String resolveOpenCodeNativeFromShim(Path shimPath) {
        if (shimPath == null) {
            return null;
        }
        String fileName = shimPath.getFileName() == null ? "" : shimPath.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".cmd")) {
            return null;
        }
        Path prefix = shimPath.getParent();
        if (prefix == null) {
            return null;
        }
        for (String packageName : openCodeWindowsPackageCandidates()) {
            Path candidate = prefix
                .resolve("node_modules")
                .resolve("opencode-ai")
                .resolve("node_modules")
                .resolve(packageName)
                .resolve("bin")
                .resolve("opencode.exe");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    private List<String> openCodeWindowsPackageCandidates() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("arm")) {
            return List.of("opencode-windows-arm64", "opencode-windows-x64", "opencode-windows-x64-baseline");
        }
        return List.of("opencode-windows-x64", "opencode-windows-x64-baseline", "opencode-windows-arm64");
    }

    private boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private Path directPath(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        if (toolName.contains("/") || toolName.contains("\\")) {
            try {
                return Paths.get(toolName).toAbsolutePath().normalize();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Path locateOnPath(String toolName) {
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
            Path dir;
            try {
                dir = Paths.get(unquote(entry));
            } catch (Exception ignored) {
                continue;
            }
            for (String candidate : candidateExecutableNames(toolName)) {
                Path path = dir.resolve(candidate);
                if (Files.isRegularFile(path)) {
                    return path.toAbsolutePath().normalize();
                }
            }
        }
        return null;
    }

    private Set<String> candidateExecutableNames(String toolName) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (toolName == null || toolName.isBlank()) {
            return names;
        }
        if (!isWindowsHost()) {
            names.add(toolName);
            return names;
        }
        if (toolName.contains(".")) {
            names.add(toolName);
            return names;
        }
        for (String extension : List.of(".exe", ".cmd", ".bat", ".com", ".ps1")) {
            names.add(toolName + extension);
        }
        names.add(toolName);
        return names;
    }

    private String unquote(String value) {
        if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record ParsedOpenCodeOutput(String status, String outputText, String errorText, String sessionId) {
    }
}
