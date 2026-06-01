package com.agentcloud.worker;

import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.agent.providers.CliCapabilityProfile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek protocol — delegates to {@code reasonix run --model deepseek-v4-flash}
 * so that deepseek tasks get full filesystem/tool access through Reasonix's MCP infrastructure.
 */
public class DeepSeekProtocol implements ProviderProtocol {

    @Override
    public String providerId() {
        return "deepseek";
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
        String model = config.model() != null && !config.model().value().isBlank()
            ? config.model().value()
            : "deepseek-v4-flash";

        ArrayList<String> args = new ArrayList<>();
        args.add("run");
        args.add("--no-config");
        args.add("--no-proxy");
        args.add("--model");
        args.add(model);
        args.add(prompt);

        LocalCliProviderConfig.LaunchSpec launchSpec = new LocalCliProviderConfig(
            "reasonix",
            "reasonix",
            "MULTICA_REASONIX_PATH",
            "MULTICA_REASONIX_MODEL"
        ).resolve().launchSpec();

        // Use Reasonix CLI binary for execution to get tool access.
        return new ProviderCliPlan(
            launchSpec.command(args),
            truncate(prompt, 240),
            model,
            null,
            Map.of("execution_runtime", "reasonix", "delegated_provider", "deepseek"),
            launchSpec.configuredBinary(),
            launchSpec.executableTarget(),
            launchSpec.launchMode(),
            profile,
            List.of()
        );
    }

    @Override
    public WorkerExecutionResult parseOutput(byte[] raw,
                                           ProviderCliPlan plan,
                                           long durationMs,
                                           Map<String, Object> baseMetadata) {
        // Parse reasonix-style output: skip MCP status lines, cost summaries
        StringBuilder output = new StringBuilder();
        String text = raw != null && raw.length > 0 ? new String(raw, StandardCharsets.UTF_8) : "";
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isBlank()) continue;
                if (trimmed.startsWith("\u2318") || trimmed.startsWith("\u2014")) continue; // MCP status, cost line
                if (trimmed.startsWith("[skills]")) continue;
                if (trimmed.startsWith("- turns:")) continue;
                output.append(trimmed).append("\n");
            }
        } catch (IOException ignored) {
            output = new StringBuilder(text.trim());
        }

        String outputText = output.toString().trim();
        String status = failureStatusFromOutput(outputText);
        String errorText = "failed".equals(status) ? outputText : null;
        String summary = summarize(outputText);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
        metadata.put("provider_output_parser", "deepseek_reasonix_text");
        metadata.put("execution_runtime", "reasonix");
        if (errorText != null && !errorText.isBlank()) {
            metadata.put("provider_error", errorText);
        }

        return new WorkerExecutionResult(
            summary,
            outputText,
            false,
            "",
            "",
            "",
            "medium",
            status,
            List.of(),
            errorText == null ? List.of() : List.of(errorText),
            0,
            durationMs,
            Map.copyOf(metadata),
            "failed".equals(status) ? ExecutionOutcome.FAILED : ExecutionOutcome.COMPLETED
        );
    }

    private String failureStatusFromOutput(String outputText) {
        String normalized = outputText == null ? "" : outputText.trim().toLowerCase();
        if (normalized.startsWith("error:")
            || normalized.contains("unexpected argument")
            || normalized.contains("unknown option")
            || normalized.contains("unknown argument")
            || normalized.contains("unrecognized option")
            || normalized.contains("invalid argument")) {
            return "failed";
        }
        return "completed";
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "...";
    }

    private String summarize(String outputText) {
        if (outputText == null || outputText.isBlank()) {
            return "";
        }
        String normalized = outputText.replaceAll("\\s+", " ").trim();
        return normalized.length() > 240 ? normalized.substring(0, 240) + "..." : normalized;
    }
}
