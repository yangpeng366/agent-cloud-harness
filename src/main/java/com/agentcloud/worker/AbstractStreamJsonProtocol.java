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
 * Stream-JSON 型 ProviderProtocol 基类。
 * 封装 detect / buildPlan / parseOutput 公共逻辑及工具方法，
 * 子类只需实现 {@link #providerId()}、{@link #buildArgs}、{@link #parseStream}。
 */
public abstract class AbstractStreamJsonProtocol implements ProviderProtocol {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 子类必须实现 ──

    @Override
    public abstract String providerId();

    /**
     * 构建命令参数（不含 binary 前缀，由 buildPlan 统一 prepend）。
     *
     * @param prompt             已构建的 prompt 文本
     * @param model              已解析的 model（可能为 null）
     * @param profileAdjustments 子类可向此列表追加 profile 调整说明
     */
    protected abstract List<String> buildArgs(LocalCliProviderConfig.ResolvedConfig config,
                                              TaskRuntimeContext context,
                                              String cwd,
                                              CliCapabilityProfile profile,
                                              String prompt,
                                              String model,
                                              List<String> profileAdjustments);

    /**
     * 解析 stream-json 原始输出。
     */
    protected abstract ParsedStreamOutput parseStream(String raw);

    // ── 子类可选覆盖 ──

    /** stdin prompt（null 表示无 stdin）。 */
    protected String buildStdinPrompt(String prompt) { return null; }

    /** launchMode 覆盖（null 表示用 config 默认）。 */
    protected String launchModeOverride() { return null; }

    // ── ProviderProtocol 实现 ──

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
        String model = configuredModel(config, context);
        ArrayList<String> profileAdjustments = new ArrayList<>();
        List<String> args = buildArgs(config, context, cwd, profile, prompt, model, profileAdjustments);
        LocalCliProviderConfig.LaunchSpec launchSpec = config.launchSpec();
        String launchMode = launchModeOverride() != null ? launchModeOverride() : launchSpec.launchMode();
        return new ProviderCliPlan(
            launchSpec.command(args),
            truncate(prompt, 240),
            model,
            buildStdinPrompt(prompt),
            Map.of(),
            launchSpec.configuredBinary(),
            launchSpec.executableTarget(),
            launchMode,
            profile,
            List.copyOf(profileAdjustments)
        );
    }

    @Override
    public WorkerExecutionResult parseOutput(byte[] raw,
                                             ProviderCliPlan plan,
                                             long durationMs,
                                             Map<String, Object> baseMetadata) {
        ParsedStreamOutput parsed = parseStream(raw != null ? new String(raw, StandardCharsets.UTF_8) : "");
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
        metadata.put("provider_output_parser", providerId() + "_stream_json");
        if (parsed.sessionId != null && !parsed.sessionId.isBlank()) {
            metadata.put("provider_session_id", parsed.sessionId);
        }
        if (parsed.errorText != null && !parsed.errorText.isBlank()) {
            metadata.put("provider_error", parsed.errorText);
        }
        return new WorkerExecutionResult(
            summarize(parsed.outputText, parsed.errorText, parsed.status),
            parsed.outputText,
            false, "", "", "",
            "medium",
            parsed.status,
            List.of(),
            parsed.errorText == null || parsed.errorText.isBlank() ? List.of() : List.of(parsed.errorText),
            0, durationMs,
            Map.copyOf(metadata),
            "failed".equals(parsed.status) ? ExecutionOutcome.FAILED : ExecutionOutcome.COMPLETED
        );
    }

    // ── 公共数据结构 ──

    protected record ParsedStreamOutput(String status, String outputText, String errorText, String sessionId) {}

    // ── 共享工具方法 ──

    protected String configuredModel(LocalCliProviderConfig.ResolvedConfig config, TaskRuntimeContext context) {
        if (context != null && context.task() != null) {
            String taskModel = ProviderTaskPromptBuilder.metadataString(context.task().metadata(), "provider_model");
            if (taskModel != null && !taskModel.isBlank()) return taskModel;
        }
        return config.model().value();
    }

    protected String resumeId(TaskRuntimeContext context) {
        if (context == null || context.task() == null) return null;
        String recoveryStage = ProviderTaskPromptBuilder.metadataString(context.task().metadata(), "recovery_stage");
        if ("same_worker_retry_scheduled".equalsIgnoreCase(recoveryStage)
            || "auto_handoff_scheduled".equalsIgnoreCase(recoveryStage)) return null;
        return firstNonBlank(
            ProviderTaskPromptBuilder.metadataString(context.task().metadata(), "provider_session_id"),
            ProviderTaskPromptBuilder.metadataString(context.task().metadata(), "provider_thread_id"),
            ProviderTaskPromptBuilder.metadataString(context.task().metadata(), "resume_provider_session_id")
        );
    }

    protected boolean profileUnsupported(CliCapabilityProfile profile, String capability) {
        return profile != null && profile.explicitlyUnsupported(capability);
    }

    protected String summarize(String outputText, String errorText, String status) {
        String base = firstNonBlank(outputText, errorText, status);
        if (base == null) return "";
        String normalized = base.replaceAll("\\s+", " ").trim();
        return normalized.length() > 240 ? normalized.substring(0, 240) + "..." : normalized;
    }

    protected String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit) + "...";
    }

    protected void appendLine(StringBuilder target, String text) {
        if (target == null || text == null || text.isBlank()) return;
        if (!target.isEmpty()) target.append('\n');
        target.append(text.trim());
    }

    protected String text(JsonNode node, String field) {
        return node == null || field == null ? null : blankToNull(node.path(field).asText(""));
    }

    protected String nestedText(JsonNode node, String... path) {
        JsonNode current = node;
        for (String step : path) {
            if (current == null || step == null) return null;
            current = current.path(step);
        }
        return current == null ? null : blankToNull(current.asText(""));
    }

    protected String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    protected String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
