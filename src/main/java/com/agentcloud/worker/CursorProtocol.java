package com.agentcloud.worker;

import com.agentcloud.agent.providers.CliCapabilityProfile;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Cursor CLI protocol。
 * 命令：cursor chat -p &lt;prompt&gt; --output-format stream-json [--yolo] [--workspace cwd] [--model m] [--resume id]
 */
public class CursorProtocol extends AbstractStreamJsonProtocol {

    @Override
    public String providerId() { return "cursor"; }

    @Override
    protected List<String> buildArgs(LocalCliProviderConfig.ResolvedConfig config,
                                     TaskRuntimeContext context, String cwd,
                                     CliCapabilityProfile profile,
                                     String prompt, String model,
                                     List<String> profileAdjustments) {
        ArrayList<String> args = new ArrayList<>();
        args.add("chat");
        args.add("-p"); args.add(prompt);
        args.add("--output-format"); args.add("stream-json");
        if (!profileUnsupported(profile, "yolo")) {
            args.add("--yolo");
        } else {
            profileAdjustments.add("dropped --yolo");
        }
        if (cwd != null && !cwd.isBlank() && !profileUnsupported(profile, "workspace_arg")) {
            args.add("--workspace"); args.add(cwd);
        } else if (cwd != null && !cwd.isBlank()) {
            profileAdjustments.add("dropped --workspace");
        }
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
    protected ParsedStreamOutput parseStream(String raw) {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
        String status = "completed";
        String errorText = null;
        if (raw == null || raw.isBlank()) {
            return new ParsedStreamOutput(status, "", null, null);
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
        return new ParsedStreamOutput(status, output.toString().trim(), errorText, sessionId);
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
}
