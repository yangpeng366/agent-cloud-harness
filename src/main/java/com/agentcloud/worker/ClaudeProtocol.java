package com.agentcloud.worker;

import com.agentcloud.agent.providers.CliCapabilityProfile;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude Code CLI protocol。
 * 命令：claude -p --output-format stream-json --input-format stream-json --verbose
 *        --strict-mcp-config --permission-mode bypassPermissions [--model m] [--resume id]
 * stdin：stream-json user payload
 */
public class ClaudeProtocol extends AbstractStreamJsonProtocol {

    @Override
    public String providerId() { return "claude"; }

    @Override
    protected List<String> buildArgs(LocalCliProviderConfig.ResolvedConfig config,
                                     TaskRuntimeContext context, String cwd,
                                     CliCapabilityProfile profile,
                                     String prompt, String model,
                                     List<String> profileAdjustments) {
        ArrayList<String> args = new ArrayList<>();
        args.add("-p");
        args.add("--output-format"); args.add("stream-json");
        args.add("--input-format");  args.add("stream-json");
        args.add("--verbose");
        args.add("--strict-mcp-config");
        args.add("--permission-mode"); args.add("bypassPermissions");
        if (model != null && !model.isBlank() && !profileUnsupported(profile, "model")) {
            args.add("--model"); args.add(model);
        } else if (model != null && !model.isBlank()) {
            profileAdjustments.add("dropped --model");
        }
        String rid = resumeId(context);
        if (rid != null && !rid.isBlank() && !profileUnsupported(profile, "resume")) {
            args.add("--resume"); args.add(rid);
        } else if (rid != null && !rid.isBlank()) {
            profileAdjustments.add("dropped --resume");
        }
        return args;
    }

    @Override
    protected String buildStdinPrompt(String prompt) {
        try {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "user");
            payload.put("message", Map.of(
                "role", "user",
                "content", List.of(Map.of("type", "text", "text", prompt))
            ));
            return MAPPER.writeValueAsString(payload) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("failed to build claude stdin payload", e);
        }
    }

    @Override
    protected ParsedStreamOutput parseStream(String raw) {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
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
                if ("assistant".equals(type)) {
                    JsonNode message = event.path("message");
                    if (message.isObject() && message.path("content").isArray()) {
                        for (JsonNode block : message.path("content")) {
                            String blockType = text(block, "type");
                            if ("text".equals(blockType) || "thinking".equals(blockType)) {
                                appendLine(output, text(block, "text"));
                            }
                        }
                    }
                } else if ("result".equals(type)) {
                    appendLine(output, text(event, "result"));
                    if (event.path("is_error").asBoolean(false)) {
                        status = "failed";
                        errorText = firstNonBlank(errorText, text(event, "result"), text(event, "message"));
                    }
                } else if ("system".equals(type)) {
                    String subtype = text(event, "subtype");
                    if ("error".equalsIgnoreCase(subtype)) {
                        status = "failed";
                        errorText = firstNonBlank(errorText, text(event, "message"), trimmed);
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
