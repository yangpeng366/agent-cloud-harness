package com.agentcloud.worker;

import com.agentcloud.agent.providers.CliCapabilityProfile;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemini CLI protocol。
 * 命令：gemini -p &lt;prompt&gt; [--yolo] -o stream-json [-m model] [-r resumeId]
 */
public class GeminiProtocol extends AbstractStreamJsonProtocol {

    @Override
    public String providerId() { return "gemini"; }

    @Override
    protected List<String> buildArgs(LocalCliProviderConfig.ResolvedConfig config,
                                     TaskRuntimeContext context, String cwd,
                                     CliCapabilityProfile profile,
                                     String prompt, String model,
                                     List<String> profileAdjustments) {
        ArrayList<String> args = new ArrayList<>();
        args.add("-p"); args.add(prompt);
        if (!profileUnsupported(profile, "yolo")) {
            args.add("--yolo");
        } else {
            profileAdjustments.add("dropped --yolo");
        }
        args.add("-o"); args.add("stream-json");
        if (model != null && !model.isBlank() && !profileUnsupported(profile, "model")) {
            args.add("-m"); args.add(model);
        } else if (model != null && !model.isBlank()) {
            profileAdjustments.add("dropped -m");
        }
        String rid = resumeId(context);
        if (rid != null && !rid.isBlank() && !profileUnsupported(profile, "resume")) {
            args.add("-r"); args.add(rid);
        } else if (rid != null && !rid.isBlank()) {
            profileAdjustments.add("dropped -r");
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
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank() || trimmed.charAt(0) != '{') {
                appendLine(output, trimmed);
                continue;
            }
            try {
                JsonNode event = MAPPER.readTree(trimmed);
                String type = text(event, "type");
                if ("message".equals(type) && "assistant".equalsIgnoreCase(text(event, "role"))) {
                    appendLine(output, text(event, "content"));
                } else if ("error".equals(type)) {
                    status = "failed";
                    errorText = firstNonBlank(errorText, text(event, "message"), trimmed);
                } else if ("result".equals(type) && "error".equalsIgnoreCase(text(event, "status"))) {
                    status = "failed";
                    errorText = firstNonBlank(errorText,
                        nestedText(event, "error", "message"),
                        text(event, "message"), trimmed);
                }
                sessionId = firstNonBlank(sessionId, text(event, "session_id"), text(event, "sessionId"));
            } catch (Exception ignored) {
                appendLine(output, trimmed);
            }
        }
        return new ParsedStreamOutput(status, output.toString().trim(), errorText, sessionId);
    }
}
