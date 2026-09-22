package com.agentcloud.worker;

import com.agentcloud.agent.providers.CliCapabilityProfile;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Kimi CLI protocol。
 * 命令：kimi --print --output-format stream-json [--work-dir cwd] [--model m] [--session resumeId] --prompt &lt;prompt&gt;
 */
public class KimiProtocol extends AbstractStreamJsonProtocol {

    @Override
    public String providerId() { return "kimi"; }

    @Override
    protected List<String> buildArgs(LocalCliProviderConfig.ResolvedConfig config,
                                     TaskRuntimeContext context, String cwd,
                                     CliCapabilityProfile profile,
                                     String prompt, String model,
                                     List<String> profileAdjustments) {
        ArrayList<String> args = new ArrayList<>();
        args.add("--print");
        args.add("--output-format"); args.add("stream-json");
        if (cwd != null && !cwd.isBlank() && !profileUnsupported(profile, "work_dir_arg")) {
            args.add("--work-dir"); args.add(cwd);
        } else if (cwd != null && !cwd.isBlank()) {
            profileAdjustments.add("dropped --work-dir");
        }
        if (model != null && !model.isBlank() && !profileUnsupported(profile, "model")) {
            args.add("--model"); args.add(model);
        } else if (model != null && !model.isBlank()) {
            profileAdjustments.add("dropped --model");
        }
        String rid = resumeId(context);
        if (rid != null && !rid.isBlank() && !profileUnsupported(profile, "resume")) {
            args.add("--session"); args.add(rid);
        } else if (rid != null && !rid.isBlank()) {
            profileAdjustments.add("dropped --session");
        }
        args.add("--prompt"); args.add(prompt);
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
            if (trimmed.isBlank()) continue;
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
                        nestedText(event, "error", "message"), trimmed);
                }
            } catch (Exception ignored) {
                appendLine(output, trimmed);
            }
        }
        return new ParsedStreamOutput(status, output.toString().trim(), errorText, sessionId);
    }

    private String extractKimiResumeSession(String line) {
        String trimmed = line == null ? "" : line.trim();
        int marker = trimmed.lastIndexOf("-r ");
        if (marker < 0) {
            marker = trimmed.lastIndexOf("--resume ");
            if (marker < 0) return null;
            return blankToNull(trimmed.substring(marker + "--resume ".length()).trim());
        }
        return blankToNull(trimmed.substring(marker + 3).trim());
    }
}
