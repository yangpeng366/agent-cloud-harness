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
 * Pi provider protocol。
 *
 * <p>命令形态：
 * <pre>
 * pi run [--cwd &lt;cwd&gt;] [--model &lt;model&gt;] [--session &lt;session&gt;] &lt;prompt&gt;
 * </pre>
 *
 * <p>输出为逐行 JSON 事件流：
 * {@code type:agent_start / turn_start / message_update / turn_end / agent_end}。
 * 文本在 {@code content.text}，session 从 {@code session_id} 抽取。
 */
public class PiProtocol implements ProviderProtocol {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String providerId() {
        return "pi";
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
        ArrayList<String> profileAdjustments = new ArrayList<>();
        if (cwd != null && !cwd.isBlank() && !profileUnsupported(profile, "work_dir_arg")) {
            args.add("--cwd");
            args.add(cwd);
        } else if (cwd != null && !cwd.isBlank()) {
            profileAdjustments.add("dropped --cwd");
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
        ParsedPiOutput parsed = parse(raw != null ? new String(raw, StandardCharsets.UTF_8) : "");
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
        metadata.put("provider_output_parser", "pi_event_stream");
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
            "small",
            parsed.status(),
            List.of(),
            parsed.errorText() == null || parsed.errorText().isBlank() ? List.of() : List.of(parsed.errorText()),
            0,
            durationMs,
            Map.copyOf(metadata),
            "failed".equals(parsed.status()) ? ExecutionOutcome.FAILED : ExecutionOutcome.COMPLETED
        );
    }

    private ParsedPiOutput parse(String raw) {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
        String status = "completed";
        String errorText = null;
        if (raw == null || raw.isBlank()) {
            return new ParsedPiOutput(status, output.toString().trim(), null, null);
        }
        for (String line : raw.split("\\R")) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.startsWith("{")) {
                try {
                    JsonNode event = MAPPER.readTree(trimmed);
                    String type = text(event, "type");
                    if (type == null) {
                        appendLine(output, trimmed);
                        continue;
                    }
                    sessionId = firstNonBlank(sessionId, text(event, "session_id"));
                    switch (type) {
                        case "agent_start", "turn_start", "turn_end", "agent_end" -> {}
                        case "message_update", "message_end" -> {
                            JsonNode content = event.path("content");
                            if (content.isArray()) {
                                for (JsonNode block : content) {
                                    String blockType = text(block, "type");
                                    if ("text".equals(blockType)) {
                                        appendLine(output, text(block, "text"));
                                    } else if ("error".equals(blockType)) {
                                        status = "failed";
                                        errorText = firstNonBlank(errorText, text(block, "text"), trimmed);
                                    }
                                }
                            } else {
                                String textContent = text(event, "text");
                                if (textContent != null) {
                                    appendLine(output, textContent);
                                }
                            }
                        }
                        case "tool_execution_start", "tool_execution_end" -> {}
                        default -> appendLine(output, trimmed);
                    }
                } catch (Exception ignored) {
                    appendLine(output, trimmed);
                }
            } else {
                appendLine(output, trimmed);
            }
        }
        return new ParsedPiOutput(status, output.toString().trim(), errorText, sessionId);
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

    private record ParsedPiOutput(String status, String outputText, String errorText, String sessionId) {
    }
}