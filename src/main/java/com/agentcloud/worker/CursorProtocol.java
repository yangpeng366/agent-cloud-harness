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

public class CursorProtocol implements ProviderProtocol {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String providerId() {
        return "cursor";
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
        ArrayList<String> args = new ArrayList<>();
        args.add("chat");
        args.add("-p");
        args.add(prompt);
        args.add("--output-format");
        args.add("stream-json");
        ArrayList<String> profileAdjustments = new ArrayList<>();
        if (!profileUnsupported(profile, "yolo")) {
            args.add("--yolo");
        } else {
            profileAdjustments.add("dropped --yolo");
        }
        if (cwd != null && !cwd.isBlank() && !profileUnsupported(profile, "workspace_arg")) {
            args.add("--workspace");
            args.add(cwd);
        } else if (cwd != null && !cwd.isBlank()) {
            profileAdjustments.add("dropped --workspace");
        }
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank() && !profileUnsupported(profile, "model")) {
            args.add("--model");
            args.add(model);
        } else if (model != null && !model.isBlank()) {
            profileAdjustments.add("dropped --model");
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank() && !profileUnsupported(profile, "resume")) {
            args.add("--resume");
            args.add(resumeId);
        } else if (resumeId != null && !resumeId.isBlank()) {
            profileAdjustments.add("dropped --resume");
        }
        LocalCliProviderConfig.LaunchSpec launchSpec = config.launchSpec();
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
        ParsedCursorOutput parsed = parse(raw != null ? new String(raw, StandardCharsets.UTF_8) : "");
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
        metadata.put("provider_output_parser", "cursor_stream_json");
        if (parsed.sessionId() != null && !parsed.sessionId().isBlank()) {
            metadata.put("provider_session_id", parsed.sessionId());
        }
        if (parsed.errorText() != null && !parsed.errorText().isBlank()) {
            metadata.put("provider_error", parsed.errorText());
        }
        return new WorkerExecutionResult(
            summarize(parsed.outputText(), parsed.errorText(), parsed.status()),
            parsed.outputText(),
            false,
            "",
            "",
            "",
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

    private ParsedCursorOutput parse(String raw) {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
        String status = "completed";
        String errorText = null;
        if (raw == null || raw.isBlank()) {
            return new ParsedCursorOutput(status, "", null, null);
        }
        for (String line : raw.split("\\R")) {
            String normalized = normalizeCursorLine(line);
            if (normalized.isBlank() || normalized.charAt(0) != '{') {
                appendLine(output, normalized);
                continue;
            }
            try {
                JsonNode event = MAPPER.readTree(normalized);
                String type = text(event, "type");
                if ("assistant".equals(type)) {
                    JsonNode message = event.path("message");
                    if (message.isObject() && message.path("content").isArray()) {
                        for (JsonNode block : message.path("content")) {
                            if ("text".equals(text(block, "type"))) {
                                appendLine(output, text(block, "text"));
                            }
                        }
                    }
                } else if ("result".equals(type)) {
                    appendLine(output, text(event, "result"));
                    if (event.path("is_error").asBoolean(false) || "error".equalsIgnoreCase(text(event, "subtype"))) {
                        status = "failed";
                        errorText = firstNonBlank(errorText, text(event, "result"), text(event, "message"));
                    }
                } else if ("system".equals(type) && "error".equalsIgnoreCase(text(event, "subtype"))) {
                    status = "failed";
                    errorText = firstNonBlank(errorText, text(event, "message"), normalized);
                }
                sessionId = firstNonBlank(sessionId, text(event, "session_id"), text(event, "sessionId"));
            } catch (Exception ignored) {
                appendLine(output, normalized);
            }
        }
        return new ParsedCursorOutput(status, output.toString().trim(), errorText, sessionId);
    }

    private String normalizeCursorLine(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.regionMatches(true, 0, "stdout:", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        if (trimmed.regionMatches(true, 0, "stderr:", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
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

    private String text(JsonNode node, String field) {
        return node == null || field == null ? null : blankToNull(node.path(field).asText(""));
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

    private record ParsedCursorOutput(String status, String outputText, String errorText, String sessionId) {
    }
}
