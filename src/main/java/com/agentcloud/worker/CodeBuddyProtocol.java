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
 * CodeBuddy provider protocol（Claude Code 风格 CLI）。
 *
 * <p>命令形态：
 * <pre>
 * codebuddy -y --print --output-format stream-json \
 *   --permission-mode bypassPermissions \
 *   --subagent-permission-mode bypassPermissions \
 *   --tools default [--model &lt;m&gt;] [-r &lt;id&gt;] &lt;prompt&gt;
 * </pre>
 *
 * <p>输出为 Claude Code 同款 stream-json 事件流，解析目标与 {@link ClaudeProtocol} 1:1 对齐：
 * {@code type:system(subtype:init)} 含 {@code session_id}/{@code model}，
 * {@code type:assistant} 含 {@code message.content[].text}，
 * {@code type:result} 含 {@code result}/{@code is_error}/{@code usage}。
 */
public class CodeBuddyProtocol implements ProviderProtocol {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String providerId() {
        return "codebuddy";
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
        args.add("-y");
        args.add("--print");
        args.add("--output-format");
        args.add("stream-json");
        args.add("--permission-mode");
        args.add("bypassPermissions");
        args.add("--subagent-permission-mode");
        args.add("bypassPermissions");
        args.add("--tools");
        args.add("default");
        ArrayList<String> profileAdjustments = new ArrayList<>();
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank() && !profileUnsupported(profile, "model")) {
            args.add("--model");
            args.add(model);
        } else if (model != null && !model.isBlank()) {
            profileAdjustments.add("dropped --model");
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank() && !profileUnsupported(profile, "resume")) {
            args.add("-r");
            args.add(resumeId);
        } else if (resumeId != null && !resumeId.isBlank()) {
            profileAdjustments.add("dropped -r");
        }
        // prompt 作为最后位置参数（argv 交付）
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
        ParsedCodeBuddyOutput parsed = parse(raw != null ? new String(raw, StandardCharsets.UTF_8) : "");
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
        metadata.put("provider_output_parser", "codebuddy_stream_json");
        if (parsed.sessionId() != null && !parsed.sessionId().isBlank()) {
            metadata.put("provider_session_id", parsed.sessionId());
        }
        if (parsed.activeModel() != null && !parsed.activeModel().isBlank()) {
            metadata.put("provider_active_model", parsed.activeModel());
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

    private ParsedCodeBuddyOutput parse(String raw) {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
        String activeModel = null;
        String status = "completed";
        String errorText = null;
        if (raw == null || raw.isBlank()) {
            return new ParsedCodeBuddyOutput(status, "", null, null, null);
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
                if ("system".equals(type)) {
                    String subtype = text(event, "subtype");
                    if ("init".equalsIgnoreCase(subtype)) {
                        // system init 事件携带 session_id 与 model
                        sessionId = firstNonBlank(sessionId, text(event, "session_id"), text(event, "sessionId"));
                        activeModel = firstNonBlank(activeModel, text(event, "model"));
                    } else if ("error".equalsIgnoreCase(subtype)) {
                        status = "failed";
                        errorText = firstNonBlank(errorText, text(event, "message"), trimmed);
                    }
                } else if ("assistant".equals(type)) {
                    JsonNode message = event.path("message");
                    if (message.isObject() && message.path("content").isArray()) {
                        for (JsonNode block : message.path("content")) {
                            String blockType = text(block, "type");
                            if ("text".equals(blockType) || "thinking".equals(blockType)) {
                                appendLine(output, text(block, "text"));
                            }
                        }
                    }
                    // assistant 事件同样可能携带 session_id / model
                    sessionId = firstNonBlank(sessionId, text(event, "session_id"), text(event, "sessionId"));
                    activeModel = firstNonBlank(activeModel, text(message, "model"));
                } else if ("result".equals(type)) {
                    appendLine(output, text(event, "result"));
                    if (event.path("is_error").asBoolean(false)) {
                        status = "failed";
                        errorText = firstNonBlank(errorText, text(event, "result"), text(event, "message"));
                    }
                }
                sessionId = firstNonBlank(sessionId, text(event, "session_id"), text(event, "sessionId"));
            } catch (Exception ignored) {
                appendLine(output, trimmed);
            }
        }
        return new ParsedCodeBuddyOutput(status, output.toString().trim(), errorText, sessionId, activeModel);
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

    private record ParsedCodeBuddyOutput(String status, String outputText, String errorText,
                                          String sessionId, String activeModel) {
    }
}
