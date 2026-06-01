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

public class KimiProtocol implements ProviderProtocol {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String providerId() {
        return "kimi";
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
        args.add("--print");
        args.add("--output-format");
        args.add("stream-json");
        ArrayList<String> profileAdjustments = new ArrayList<>();
        if (cwd != null && !cwd.isBlank() && !profileUnsupported(profile, "work_dir_arg")) {
            args.add("--work-dir");
            args.add(cwd);
        } else if (cwd != null && !cwd.isBlank()) {
            profileAdjustments.add("dropped --work-dir");
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
            args.add("--session");
            args.add(resumeId);
        } else if (resumeId != null && !resumeId.isBlank()) {
            profileAdjustments.add("dropped --session");
        }
        args.add("--prompt");
        args.add(prompt);
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
        ParsedKimiOutput parsed = parse(raw != null ? new String(raw, StandardCharsets.UTF_8) : "");
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
        metadata.put("provider_output_parser", "kimi_stream_json");
        if (parsed.sessionId() != null && !parsed.sessionId().isBlank()) {
            metadata.put("provider_session_id", parsed.sessionId());
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

    private ParsedKimiOutput parse(String raw) {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
        String status = "completed";
        String errorText = null;
        if (raw == null || raw.isBlank()) {
            return new ParsedKimiOutput(status, "", null, null);
        }
        for (String line : raw.split("\\R")) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.regionMatches(true, 0, "To resume this session:", 0, "To resume this session:".length())) {
                sessionId = firstNonBlank(sessionId, extractKimiResumeSession(trimmed));
                continue;
            }
            if (trimmed.charAt(0) != '{') {
                appendLine(output, trimmed);
                continue;
            }
            try {
                JsonNode event = MAPPER.readTree(trimmed);
                String role = text(event, "role");
                if ("assistant".equalsIgnoreCase(role) && event.path("content").isArray()) {
                    for (JsonNode block : event.path("content")) {
                        String blockType = text(block, "type");
                        if ("text".equals(blockType)) {
                            appendLine(output, text(block, "text"));
                        } else if ("error".equals(blockType)) {
                            status = "failed";
                            errorText = firstNonBlank(errorText, text(block, "text"), trimmed);
                        }
                    }
                } else if ("error".equalsIgnoreCase(text(event, "type"))) {
                    status = "failed";
                    errorText = firstNonBlank(errorText,
                        text(event, "message"),
                        nestedText(event, "error", "message"),
                        trimmed);
                }
            } catch (Exception ignored) {
                appendLine(output, trimmed);
            }
        }
        return new ParsedKimiOutput(status, output.toString().trim(), errorText, sessionId);
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

    private String extractKimiResumeSession(String line) {
        String trimmed = line == null ? "" : line.trim();
        int marker = trimmed.lastIndexOf("-r ");
        if (marker < 0) {
            marker = trimmed.lastIndexOf("--resume ");
            if (marker < 0) {
                return null;
            }
            return blankToNull(trimmed.substring(marker + "--resume ".length()).trim());
        }
        return blankToNull(trimmed.substring(marker + 3).trim());
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

    private record ParsedKimiOutput(String status, String outputText, String errorText, String sessionId) {
    }
}
