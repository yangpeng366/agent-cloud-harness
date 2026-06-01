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
        },
        STREAM_JSON {
            @Override
            ParsedOutput parse(String raw) {
                if (raw == null || raw.isBlank()) {
                    return TEXT.parse("");
                }
                StringBuilder output = new StringBuilder();
                String status = "completed";
                String errorText = null;
                int eventCount = 0;
                int parsedEventCount = 0;
                for (String line : raw.split("\\R")) {
                    String trimmed = line == null ? "" : line.trim();
                    if (trimmed.isBlank()) {
                        continue;
                    }
                    eventCount++;
                    if (!trimmed.startsWith("{")) {
                        appendLine(output, trimmed);
                        continue;
                    }
                    try {
                        JsonNode event = MAPPER.readTree(trimmed);
                        parsedEventCount++;
                        String type = text(event, "type");
                        String extracted = extractStreamText(event, type);
                        appendLine(output, extracted);
                        if (isErrorEvent(event, type)) {
                            status = "failed";
                            errorText = firstNonBlank(errorText, extracted, text(event, "message"), text(event, "error"), trimmed);
                        }
                    } catch (Exception ignored) {
                        appendLine(output, trimmed);
                    }
                }
                String text = output.toString().trim();
                String summary = summarize(text.isBlank() ? errorText : text);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("stream_json_event_count", eventCount);
                metadata.put("stream_json_parsed_event_count", parsedEventCount);
                if (errorText != null && !errorText.isBlank()) {
                    metadata.put("stream_json_error_text", errorText);
                }
                return new ParsedOutput(status, summary, text,
                    "failed".equals(status) ? ExecutionOutcome.FAILED : ExecutionOutcome.COMPLETED, metadata);
            }
        };

        abstract ParsedOutput parse(String raw);
    }

    private static String extractStreamText(JsonNode event, String type) {
        String direct = firstNonBlank(
            text(event, "content"),
            text(event, "text"),
            text(event, "delta"),
            text(event, "response"),
            text(event, "result"),
            text(event, "message")
        );
        if (direct != null) {
            return direct;
        }
        JsonNode message = event.path("message");
        if (message.isTextual()) {
            return message.asText();
        }
        if (message.isObject()) {
            String nested = firstNonBlank(text(message, "content"), text(message, "text"));
            if (nested != null) {
                return nested;
            }
            JsonNode content = message.path("content");
            if (content.isArray()) {
                StringBuilder blocks = new StringBuilder();
                for (JsonNode block : content) {
                    appendLine(blocks, firstNonBlank(text(block, "text"), text(block, "content")));
                }
                return blocks.toString();
            }
        }
        if ("error".equalsIgnoreCase(type)) {
            return firstNonBlank(text(event, "error"), text(event, "reason"));
        }
        return null;
    }

    private static boolean isErrorEvent(JsonNode event, String type) {
        String status = text(event, "status");
        String subtype = text(event, "subtype");
        return "error".equalsIgnoreCase(type)
            || "failed".equalsIgnoreCase(type)
            || "error".equalsIgnoreCase(status)
            || "failed".equalsIgnoreCase(status)
            || "error".equalsIgnoreCase(subtype)
            || event.path("is_error").asBoolean(false);
    }

    private static void appendLine(StringBuilder target, String text) {
        if (target == null || text == null || text.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append('\n');
        }
        target.append(text.trim());
    }

    private static String summarize(String value) {
        String text = value != null ? value.trim() : "";
        return text.length() > 240 ? text.substring(0, 240) + "..." : text;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            String text = value.asText();
            return text.isBlank() ? null : text;
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
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

    private record ParsedOutput(String status, String summary, String outputText,
                               ExecutionOutcome outcome, Map<String, Object> metadata) {}
}
