package com.agentcloud.worker;

import com.agentcloud.agent.providers.CliCapabilityProfile;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deveco provider protocol（opencode 的壳）。
 *
 * <p>命令形态：
 * <pre>
 * deveco run --skip-agreement --format json [--dir &lt;cwd&gt;] [-m &lt;model&gt;] [-s &lt;session&gt;] &lt;message&gt;
 * </pre>
 *
 * <p>输出为 opencode 事件流（逐行 JSON）：
 * {@code type:text} 文本在 {@code part.text}，{@code type:step_finish} 携带
 * {@code part.reason}/{@code part.tokens}/{@code part.cost}，session 从 {@code sessionID}
 * 或 {@code part.sessionID} 抽取。与 {@link OpenCodeProtocol} 同源。
 */
public class DevecoProtocol implements ProviderProtocol {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String providerId() {
        return "deveco";
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
        ArrayList<String> args = new ArrayList<>();
        args.add("run");
        args.add("--skip-agreement");
        args.add("--format");
        args.add("json");
        if (cwd != null && !cwd.isBlank()) {
            args.add("--dir");
            args.add(cwd);
        }
        ArrayList<String> profileAdjustments = new ArrayList<>();
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank() && !profileUnsupported(profile, "model")) {
            args.add("-m");
            args.add(model);
        } else if (model != null && !model.isBlank()) {
            profileAdjustments.add("dropped -m");
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank() && !profileUnsupported(profile, "resume")) {
            args.add("-s");
            args.add(resumeId);
        } else if (resumeId != null && !resumeId.isBlank()) {
            profileAdjustments.add("dropped -s");
        }
        // message 作为最后位置参数
        args.add(prompt);
        return new ProviderCliPlan(
            launchSpec.command(args),
            truncate(prompt, 240),
            model,
            null,
            Map.of(),
            launchSpec.configuredBinary(),
            launchSpec.executableTarget(),
            launchSpec.launchMode(),
            profile,
            List.copyOf(profileAdjustments)
        );
    }

    @Override
    public WorkerExecutionResult parseOutput(byte[] raw,
                                              ProviderCliPlan plan,
                                              long durationMs,
                                              Map<String, Object> baseMetadata) {
        ParsedDevecoOutput parsed = parse(raw != null ? new String(raw, StandardCharsets.UTF_8) : "");
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
        metadata.put("provider_output_parser", "deveco_opencode_json");
        if (parsed.sessionId() != null && !parsed.sessionId().isBlank()) {
            metadata.put("provider_session_id", parsed.sessionId());
        }
        if (parsed.totalTokens() != null) {
            metadata.put("provider_total_tokens", parsed.totalTokens());
        }
        if (parsed.inputTokens() != null) {
            metadata.put("provider_input_tokens", parsed.inputTokens());
        }
        if (parsed.outputTokens() != null) {
            metadata.put("provider_output_tokens", parsed.outputTokens());
        }
        if (parsed.cost() != null) {
            metadata.put("provider_cost", parsed.cost());
        }
        if (parsed.stopReason() != null && !parsed.stopReason().isBlank()) {
            metadata.put("provider_stop_reason", parsed.stopReason());
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

    private ParsedDevecoOutput parse(String raw) {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
        String status = "completed";
        String errorText = null;
        String stopReason = null;
        Long totalTokens = null;
        Long inputTokens = null;
        Long outputTokens = null;
        Double cost = null;
        if (raw == null || raw.isBlank()) {
            return new ParsedDevecoOutput(status, "", null, null, null, null, null, null, null, null);
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
                } else if ("step_finish".equals(type)) {
                    stopReason = firstNonBlank(stopReason, text(part, "reason"));
                    JsonNode tokens = part.path("tokens");
                    if (tokens.isObject()) {
                        totalTokens = firstNonNull(totalTokens, longValue(tokens, "total"));
                        inputTokens = firstNonNull(inputTokens, longValue(tokens, "input"));
                        outputTokens = firstNonNull(outputTokens, longValue(tokens, "output"));
                    }
                    Double partCost = doubleValue(part, "cost");
                    if (partCost != null) {
                        cost = partCost;
                    }
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
        return new ParsedDevecoOutput(status, output.toString().trim(), errorText, sessionId,
            stopReason, totalTokens, inputTokens, outputTokens, cost, null);
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

    private boolean profileUnsupported(CliCapabilityProfile profile, String capability) {
        return profile != null && profile.explicitlyUnsupported(capability);
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

    private Long longValue(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            String text = value.asText("");
            return text.isBlank() ? null : Long.parseLong(text.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double doubleValue(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            String text = value.asText("");
            return text.isBlank() ? null : Double.parseDouble(text.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long firstNonNull(Long current, Long candidate) {
        return current != null ? current : candidate;
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

    private record ParsedDevecoOutput(String status, String outputText, String errorText, String sessionId,
                                      String stopReason, Long totalTokens, Long inputTokens, Long outputTokens,
                                      Double cost, Object ignored) {
    }
}
