package com.agentcloud.worker;

import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.agent.providers.CliCapabilityProfile;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GenericCliProtocol implements ProviderProtocol {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([^}]+)\\s*\\}\\}");

    private final String providerId;
    private final List<String> commandTemplate;
    private final OutputParser outputParser;
    private final Map<String, String> defaultEnvironment;
    private final String launchMode;
    private final boolean prependConfiguredBinary;
    private final String configuredBinaryOverride;

    public GenericCliProtocol(String providerId, List<String> commandTemplate) {
        this(providerId, commandTemplate, OutputParser.TEXT, Map.of(), "direct");
    }

    public GenericCliProtocol(String providerId, List<String> commandTemplate,
                             OutputParser outputParser,
                             Map<String, String> defaultEnvironment,
                             String launchMode) {
        this(providerId, commandTemplate, outputParser, defaultEnvironment, launchMode, true, null);
    }

    public GenericCliProtocol(String providerId, List<String> commandTemplate,
                             OutputParser outputParser,
                             Map<String, String> defaultEnvironment,
                             String launchMode,
                             boolean prependConfiguredBinary,
                             String configuredBinaryOverride) {
        this.providerId = providerId;
        this.commandTemplate = commandTemplate != null ? commandTemplate : List.of();
        this.outputParser = outputParser != null ? outputParser : OutputParser.TEXT;
        this.defaultEnvironment = defaultEnvironment != null ? defaultEnvironment : Map.of();
        this.launchMode = launchMode != null ? launchMode : "direct";
        this.prependConfiguredBinary = prependConfiguredBinary;
        this.configuredBinaryOverride = blankToNull(configuredBinaryOverride);
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public ProviderStatus detect(LocalCliProviderConfig.ResolvedConfig config) {
        String binary = launchSpec(config).configuredBinary();
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
        String model = configuredModel(config, context);

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("prompt", prompt);
        placeholders.put("model", model != null ? model : "");
        placeholders.put("cwd", cwd != null ? cwd : "");

        LocalCliProviderConfig.LaunchSpec launchSpec = launchSpec(config);
        List<String> resolvedCommand = resolveCommandTemplate(launchSpec, placeholders);
        Map<String, String> environment = new LinkedHashMap<>(defaultEnvironment);

        return new ProviderCliPlan(
            resolvedCommand,
            truncate(prompt, 240),
            model,
            null,
            environment,
            launchSpec.configuredBinary(),
            launchSpec.executableTarget(),
            launchMode,
            profile,
            List.of()
        );
    }

    private List<String> resolveCommandTemplate(LocalCliProviderConfig.LaunchSpec launchSpec,
                                               Map<String, String> placeholders) {
        List<String> resolved = new ArrayList<>();
        for (String part : commandTemplate) {
            resolved.add(resolvePlaceholders(part, placeholders));
        }
        if (!prependConfiguredBinary) {
            return List.copyOf(resolved);
        }
        return launchSpec.command(resolved);
    }

    private LocalCliProviderConfig.LaunchSpec launchSpec(LocalCliProviderConfig.ResolvedConfig config) {
        if (configuredBinaryOverride != null) {
            return LocalCliProviderConfig.LaunchSpec.resolve(configuredBinaryOverride);
        }
        return config.launchSpec();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String resolvePlaceholders(String template, Map<String, String> placeholders) {
        if (template == null) return "";
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = placeholders.getOrDefault(key, "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @Override
    public WorkerExecutionResult parseOutput(byte[] raw,
                                           ProviderCliPlan plan,
                                           long durationMs,
                                           Map<String, Object> baseMetadata) {
        String rawOutput = raw != null ? new String(raw, StandardCharsets.UTF_8) : "";
        ParsedOutput parsed = outputParser.parse(rawOutput);

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
        metadata.put("provider_output_parser", outputParser.name());
        if (parsed.metadata != null) {
            metadata.putAll(parsed.metadata);
        }

        return new WorkerExecutionResult(
            parsed.summary,
            parsed.outputText,
            false,
            "",
            "",
            "",
            "medium",
            parsed.status,
            List.of(),
            List.of(),
            0,
            durationMs,
            Map.copyOf(metadata),
            parsed.outcome
        );
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

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "...";
    }

    public enum OutputParser {
        TEXT {
            @Override
            ParsedOutput parse(String raw) {
                String text = raw != null ? raw.trim() : "";
                String summary = text.length() > 240 ? text.substring(0, 240) + "..." : text;
                return new ParsedOutput("completed", summary, text, ExecutionOutcome.COMPLETED, null);
            }
        },
        JSON {
            @Override
            ParsedOutput parse(String raw) {
                try {
                    JsonNode node = MAPPER.readTree(raw);
                    String text = text(node, "content", "text", "response");
                    String summary = text.length() > 240 ? text.substring(0, 240) + "..." : text;
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("json_parsed", true);
                    return new ParsedOutput("completed", summary, text, ExecutionOutcome.COMPLETED, metadata);
                } catch (Exception e) {
                    return TEXT.parse(raw);
                }
            }

            private String text(JsonNode node, String... fields) {
                for (String field : fields) {
                    JsonNode value = node.path(field);
                    if (!value.isMissingNode() && !value.isNull()) {
                        return value.asText();
                    }
                }
                return node.asText();
            }
        },
        LINES {
            @Override
            ParsedOutput parse(String raw) {
                if (raw == null) return TEXT.parse("");
                StringBuilder output = new StringBuilder();
                String status = "completed";
                String errorText = null;
                for (String line : raw.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.isBlank()) continue;
                    if (trimmed.startsWith("ERROR:") || trimmed.startsWith("[ERROR]")) {
                        status = "failed";
                        errorText = trimmed;
                    }
                    if (output.length() > 0) output.append('\n');
                    output.append(trimmed);
                }
                String text = output.toString();
                String summary = text.length() > 240 ? text.substring(0, 240) + "..." : text;
                return new ParsedOutput(status, summary, text,
                    "failed".equals(status) ? ExecutionOutcome.FAILED : ExecutionOutcome.COMPLETED, null);
            }
        };

        abstract ParsedOutput parse(String raw);
    }

    private record ParsedOutput(String status, String summary, String outputText,
                               ExecutionOutcome outcome, Map<String, Object> metadata) {}
}
