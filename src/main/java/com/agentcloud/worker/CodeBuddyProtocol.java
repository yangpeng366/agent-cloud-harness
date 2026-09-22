package com.agentcloud.worker;

import com.agentcloud.agent.providers.CliCapabilityProfile;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * CodeBuddy protocol（Claude Code 风格 CLI）。
 * 输出为 Claude Code 同款 stream-json 事件流。
 */
public class CodeBuddyProtocol extends AbstractStreamJsonProtocol {

    @Override
    public String providerId() { return "codebuddy"; }

    @Override
    protected List<String> buildArgs(LocalCliProviderConfig.ResolvedConfig config,
                                     TaskRuntimeContext context, String cwd,
                                     CliCapabilityProfile profile,
                                     String prompt, String model,
                                     List<String> profileAdjustments) {
        ArrayList<String> args = new ArrayList<>();
        args.add("-y");
        args.add("--print");
        args.add("--output-format"); args.add("stream-json");
        args.add("--permission-mode"); args.add("bypassPermissions");
        args.add("--subagent-permission-mode"); args.add("bypassPermissions");
        args.add("--tools"); args.add("default");
        if (model != null && !model.isBlank() && !profileUnsupported(profile, "model")) {
            args.add("--model"); args.add(model);
        } else if (model != null && !model.isBlank()) {
            profileAdjustments.add("dropped --model");
        }
        String rid = resumeId(context);
        if (rid != null && !rid.isBlank() && !profileUnsupported(profile, "resume")) {
            args.add("-r"); args.add(rid);
        } else if (rid != null && !rid.isBlank()) {
            profileAdjustments.add("dropped -r");
        }
        args.add(prompt);
        return args;
    }

    @Override
    protected ParsedStreamOutput parseStream(String raw) {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
        String activeModel = null;
        String status = "completed";
        String errorText = null;
        if (raw == null || raw.isBlank()) {
            return new ParsedStreamOutput(status, "", null, null);
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
        return new ParsedStreamOutput(status, output.toString().trim(), errorText, sessionId);
    }
}
