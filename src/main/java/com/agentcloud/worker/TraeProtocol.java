package com.agentcloud.worker;

import com.agentcloud.agent.providers.CliCapabilityProfile;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.runtime.TaskRuntimeContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trae provider protocol。
 *
 * <p>命令形态：
 * <pre>
 * trae chat --mode agent [--add-file &lt;path&gt;] &lt;prompt&gt;
 * </pre>
 *
 * <p>Trae CN 是 GUI-first CLI，{@code chat} 子命令会打开窗口进入交互会话。
 * 当前不支持 {@code --print} 或 {@code --output-format stream-json}，
 * 因此输出按纯文本解析，launchMode 为 {@code app_server}。
 *
 * <p>当 Trae 后续支持非交互模式时，可升级为 stream-json 解析。
 */
public class TraeProtocol implements ProviderProtocol {

    @Override
    public String providerId() {
        return "trae";
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
        args.add("chat");
        args.add("--mode");
        args.add("agent");
        ArrayList<String> profileAdjustments = new ArrayList<>();
        // cwd 通过 --add-file 间接提供（Trae 不支持 --cwd）
        if (cwd != null && !cwd.isBlank() && !profileUnsupported(profile, "work_dir_arg")) {
            // Trae 没有 --cwd，但可以 --add-file 把工作目录加入上下文
            // 暂不自动添加，避免污染上下文
            profileAdjustments.add("cwd not supported by trae chat, skipped");
        }
        // prompt 作为最后位置参数
        args.add(prompt);
        return new ProviderCliPlan(
            launchSpec.command(args),
            truncate(prompt, 240),
            null,
            null,
            Map.of(),
            launchSpec.configuredBinary(),
            launchSpec.executableTarget(),
            "app_server",
            profile,
            List.copyOf(profileAdjustments)
        );
    }

    @Override
    public WorkerExecutionResult parseOutput(byte[] raw,
                                              ProviderCliPlan plan,
                                              long durationMs,
                                              Map<String, Object> baseMetadata) {
        String outputText = raw != null ? new String(raw, StandardCharsets.UTF_8).trim() : "";
        String status = "completed";
        String errorText = null;

        // Trae GUI 模式下 stdout 通常为空或只有启动信息
        // 如果有输出，按纯文本处理
        if (outputText.isBlank()) {
            // GUI 模式正常：进程启动了窗口，stdout 为空
            outputText = "";
        }

        // 检查常见错误模式
        if (outputText.contains("Error:") || outputText.contains("error:")
            || outputText.contains("command not found") || outputText.contains("not recognized")) {
            status = "failed";
            errorText = outputText.length() > 240 ? outputText.substring(0, 240) : outputText;
        }

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
        metadata.put("provider_output_parser", "trae_text");
        metadata.put("provider_launch_mode", "app_server");

        return new WorkerExecutionResult(
            summarize(outputText, errorText, status),
            outputText,
            false,
            "",
            "",
            "",
            "strong",
            status,
            List.of(),
            errorText != null && !errorText.isBlank() ? List.of(errorText) : List.of(),
            0,
            durationMs,
            Map.copyOf(metadata),
            "failed".equals(status) ? ExecutionOutcome.FAILED : ExecutionOutcome.COMPLETED
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
}