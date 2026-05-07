package com.agentcloud.worker;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.AgentProviderResolver;
import com.agentcloud.agent.AgentProviderStatus;
import com.agentcloud.agent.providers.LocalCliAgentProvider;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.llm.LlmImageInput;
import com.agentcloud.llm.LlmImageInputResolver;
import com.agentcloud.model.Task;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 面向本地 agent CLI 的单轮执行器。
 * 当前优先覆盖 multica 风格的一次性 CLI：cursor/openclaw。
 */
public class ProviderCliWorkerExecutor implements WorkerExecutor {
    private static final Logger log = LoggerFactory.getLogger(ProviderCliWorkerExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long PROCESS_TIMEOUT_MS = 180_000L;

    private final AgentProviderRegistry providerRegistry;
    private final WorkerRegistry workerRegistry;
    private final Map<String, LocalCliProviderConfig> providerConfigs;

    public ProviderCliWorkerExecutor(AgentProviderRegistry providerRegistry) {
        this(providerRegistry, null);
    }

    public ProviderCliWorkerExecutor(AgentProviderRegistry providerRegistry, WorkerRegistry workerRegistry) {
        this.providerRegistry = providerRegistry;
        this.workerRegistry = workerRegistry;
        this.providerConfigs = new LinkedHashMap<>();
        if (providerRegistry != null) {
            for (AgentProvider provider : providerRegistry.list()) {
                if (provider instanceof LocalCliAgentProvider localCliProvider) {
                    LocalCliProviderConfig config = localCliProvider.cliConfig();
                    providerConfigs.put(config.resolve().providerId(), config);
                }
            }
        }
        providerConfigs.putIfAbsent("openclaw", new LocalCliProviderConfig(
            "openclaw",
            "openclaw",
            "MULTICA_OPENCLAW_PATH",
            "MULTICA_OPENCLAW_MODEL"
        ));
    }

    @Override
    public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
        String providerId = providerId(context, workerId);
        if (!supportsProvider(providerId)) {
            throw new IllegalArgumentException("provider-native cli executor does not support provider: " + providerId);
        }

        Worker worker = lookupWorker(context, workerId);
        LocalCliProviderConfig.ResolvedConfig config = resolvedConfig(providerId);
        AgentProviderStatus providerStatus = providerStatus(providerId);
        String cwd = resolveWorkingDirectory(context, worker);
        ProviderCliPlan plan = buildPlan(providerId, config, context, cwd);

        long startedAtMs = System.currentTimeMillis();
        Process process = null;
        ProviderCliOutput output;
        try {
            process = new ProcessBuilder(plan.command())
                .directory(cwd == null || cwd.isBlank() ? null : Path.of(cwd).toFile())
                .redirectErrorStream(true)
                .start();
            output = consume(process, providerId);
            if (!process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                long durationMs = System.currentTimeMillis() - startedAtMs;
                return failureResult("timeout", "provider-native cli timed out",
                    providerId, workerId, cwd, plan, providerStatus, durationMs, null);
            }
            output = output.withExitCode(process.exitValue());
        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startedAtMs;
            return failureResult("failed", "failed to start provider-native cli: " + e.getMessage(),
                providerId, workerId, cwd, plan, providerStatus, durationMs, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = System.currentTimeMillis() - startedAtMs;
            return failureResult("cancelled", "provider-native cli interrupted",
                providerId, workerId, cwd, plan, providerStatus, durationMs, null);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }

        long durationMs = System.currentTimeMillis() - startedAtMs;
        String normalizedStatus = normalizeStatus(output.status(), output.exitCode());
        LinkedHashMap<String, Object> metadata = baseMetadata(providerId, workerId, cwd, plan, providerStatus, durationMs);
        metadata.put("exit_code", output.exitCode());
        if (output.sessionId() != null && !output.sessionId().isBlank()) {
            metadata.put("provider_session_id", output.sessionId());
        }
        metadata.put("provider_output_parser", output.parser());
        if (output.version() != null && !output.version().isBlank()) {
            metadata.put("provider_version", output.version());
        }

        String outputText = output.outputText() == null ? "" : output.outputText().trim();
        String summary = summarize(outputText, output.errorText(), normalizedStatus);
        if (output.errorText() != null && !output.errorText().isBlank()) {
            metadata.put("provider_error", output.errorText());
        }

        log.info("Provider-native CLI round completed. provider={} worker={} status={} exitCode={} durationMs={}",
            providerId, workerId, normalizedStatus, output.exitCode(), durationMs);

        return new WorkerExecutionResult(
            summary,
            outputText,
            false,
            "",
            "",
            "",
            "medium",
            normalizedStatus,
            List.of(),
            normalizedStatus.equals("failed") && !outputText.isBlank() ? List.of("cli output requires inspection") : List.of(),
            0,
            durationMs,
            Map.copyOf(metadata)
        );
    }

    public boolean supports(String workerId, Worker worker) {
        String providerId = AgentProviderResolver.providerIdForWorker(
            workerId,
            worker != null ? worker.workerType() : null
        );
        return supportsProvider(providerId);
    }

    private boolean supportsProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return false;
        }
        return "cursor".equalsIgnoreCase(providerId) || "openclaw".equalsIgnoreCase(providerId);
    }

    private ProviderCliPlan buildPlan(String providerId,
                                      LocalCliProviderConfig.ResolvedConfig config,
                                      TaskRuntimeContext context,
                                      String cwd) {
        String prompt = buildPrompt(context);
        return switch (providerId.toLowerCase(Locale.ROOT)) {
            case "cursor" -> buildCursorPlan(config, prompt, context, cwd);
            case "openclaw" -> buildOpenClawPlan(config, prompt, context);
            default -> throw new IllegalArgumentException("unsupported provider-native cli provider: " + providerId);
        };
    }

    private ProviderCliPlan buildCursorPlan(LocalCliProviderConfig.ResolvedConfig config,
                                            String prompt,
                                            TaskRuntimeContext context,
                                            String cwd) {
        ArrayList<String> command = new ArrayList<>();
        command.add(config.binary().value());
        command.add("chat");
        command.add("-p");
        command.add(prompt);
        command.add("--output-format");
        command.add("stream-json");
        command.add("--yolo");
        if (cwd != null && !cwd.isBlank()) {
            command.add("--workspace");
            command.add(cwd);
        }
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank()) {
            command.add("--model");
            command.add(model);
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank()) {
            command.add("--resume");
            command.add(resumeId);
        }
        return new ProviderCliPlan(command, truncate(prompt, 240), model);
    }

    private ProviderCliPlan buildOpenClawPlan(LocalCliProviderConfig.ResolvedConfig config,
                                              String prompt,
                                              TaskRuntimeContext context) {
        ArrayList<String> command = new ArrayList<>();
        command.add(config.binary().value());
        command.add("agent");
        command.add("--local");
        command.add("--json");
        command.add("--session-id");
        command.add(resumeId(context));
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank()) {
            command.add("--agent");
            command.add(model);
        }
        command.add("--message");
        command.add(prompt);
        return new ProviderCliPlan(command, truncate(prompt, 240), model);
    }

    private ProviderCliOutput consume(Process process, String providerId) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            return switch (providerId.toLowerCase(Locale.ROOT)) {
                case "cursor" -> consumeCursor(reader);
                case "openclaw" -> consumeOpenClaw(reader);
                default -> throw new IllegalArgumentException("unsupported provider-native cli provider: " + providerId);
            };
        }
    }

    private ProviderCliOutput consumeCursor(BufferedReader reader) throws IOException {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
        String status = "completed";
        String errorText = null;
        String line;
        while ((line = reader.readLine()) != null) {
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
        return new ProviderCliOutput(
            status,
            output.toString().trim(),
            errorText,
            sessionId,
            null,
            null,
            "cursor_stream_json"
        );
    }

    private ProviderCliOutput consumeOpenClaw(BufferedReader reader) throws IOException {
        StringBuilder output = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        String sessionId = null;
        String status = "completed";
        String errorText = null;
        String version = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (raw.length() > 0) {
                raw.append('\n');
            }
            raw.append(line);

            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank() || trimmed.charAt(0) != '{') {
                appendLine(output, trimmed);
                continue;
            }
            try {
                JsonNode event = MAPPER.readTree(trimmed);
                String type = text(event, "type");
                if ("text".equals(type)) {
                    appendLine(output, text(event, "text"));
                } else if ("error".equals(type)) {
                    status = "failed";
                    errorText = firstNonBlank(errorText, nestedText(event, "error", "message"), text(event, "message"), trimmed);
                } else if ("lifecycle".equals(type)) {
                    String phase = text(event, "phase");
                    if ("error".equalsIgnoreCase(phase) || "failed".equalsIgnoreCase(phase) || "cancelled".equalsIgnoreCase(phase)) {
                        status = "failed";
                        errorText = firstNonBlank(errorText, nestedText(event, "error", "message"), text(event, "message"), phase);
                    }
                } else if (event.has("payloads") || event.has("meta")) {
                    appendOpenClawBlob(output, event);
                    sessionId = firstNonBlank(sessionId,
                        nestedText(event, "meta", "agentMeta", "sessionId"),
                        nestedText(event, "meta", "sessionId"));
                    version = firstNonBlank(version,
                        nestedText(event, "meta", "agentMeta", "version"),
                        nestedText(event, "meta", "version"));
                }
                sessionId = firstNonBlank(sessionId, text(event, "sessionId"), text(event, "session_id"));
            } catch (Exception ignored) {
                appendLine(output, trimmed);
            }
        }
        if (output.isEmpty() && raw.length() > 0) {
            appendLine(output, raw.toString().trim());
        }
        return new ProviderCliOutput(
            status,
            output.toString().trim(),
            errorText,
            sessionId,
            null,
            version,
            "openclaw_json"
        );
    }

    private void appendOpenClawBlob(StringBuilder output, JsonNode blob) {
        JsonNode payloads = blob.path("payloads");
        if (payloads.isArray()) {
            for (JsonNode payload : payloads) {
                appendLine(output, text(payload, "text"));
            }
        }
    }

    private Worker lookupWorker(TaskRuntimeContext context, String workerId) {
        if (workerRegistry == null) {
            return null;
        }
        String resolvedWorkerId = firstNonBlank(
            workerId,
            context != null && context.task() != null ? context.task().assignedWorker() : null
        );
        return resolvedWorkerId == null ? null : workerRegistry.get(resolvedWorkerId);
    }

    private String providerId(TaskRuntimeContext context, String workerId) {
        String workerType = context != null && context.task() != null
            ? metadataString(context.task().metadata(), "selected_worker_type")
            : null;
        return AgentProviderResolver.providerIdForWorker(workerId, workerType);
    }

    private LocalCliProviderConfig.ResolvedConfig resolvedConfig(String providerId) {
        LocalCliProviderConfig providerConfig = providerConfigs.get(providerId);
        if (providerConfig == null) {
            throw new IllegalArgumentException("provider config not found: " + providerId);
        }
        return providerConfig.resolve();
    }

    private AgentProviderStatus providerStatus(String providerId) {
        AgentProvider provider = providerRegistry != null ? providerRegistry.get(providerId) : null;
        return provider != null ? provider.detect() : null;
    }

    private String configuredModel(LocalCliProviderConfig.ResolvedConfig config, TaskRuntimeContext context) {
        String taskModel = context == null || context.task() == null ? null : metadataString(context.task().metadata(), "provider_model");
        if (taskModel != null && !taskModel.isBlank()) {
            return taskModel;
        }
        return config.model().value();
    }

    private String buildPrompt(TaskRuntimeContext context) {
        if (context == null || context.task() == null) {
            return "Execute the assigned task.";
        }
        Task task = context.task();
        StringBuilder sb = new StringBuilder();
        sb.append("Task Title: ").append(task.title()).append("\n");
        if (task.goal() != null && !task.goal().isBlank()) {
            sb.append("Goal: ").append(task.goal()).append("\n");
        }
        if (task.summary() != null && !task.summary().isBlank()) {
            sb.append("Summary: ").append(task.summary()).append("\n");
        }
        if (task.nextStep() != null && !task.nextStep().isBlank()) {
            sb.append("Next Step: ").append(task.nextStep()).append("\n");
        }
        String intent = metadataString(task.metadata(), "intent");
        if (intent != null && !intent.isBlank()) {
            sb.append("Intent: ").append(intent).append("\n");
        }
        if (context.activeContext() != null && context.activeContext().synthesizedContext() != null
            && !context.activeContext().synthesizedContext().isBlank()) {
            sb.append("\nActive Context:\n");
            sb.append(context.activeContext().synthesizedContext()).append("\n");
        }
        List<LlmImageInput> imageInputs = LlmImageInputResolver.resolve(context);
        if (!imageInputs.isEmpty()) {
            sb.append("\nImage Inputs:\n");
            for (LlmImageInput imageInput : imageInputs) {
                sb.append("- ").append(imageInput.path());
                if (imageInput.mediaType() != null && !imageInput.mediaType().isBlank()) {
                    sb.append(" (").append(imageInput.mediaType()).append(")");
                }
                sb.append("\n");
            }
        }
        sb.append("\nReturn the best concrete execution result for this task.");
        return sb.toString();
    }

    private String resolveWorkingDirectory(TaskRuntimeContext context, Worker worker) {
        if (context != null && context.task() != null) {
            Map<String, Object> metadata = context.task().metadata();
            String taskPath = firstNonBlank(
                metadataString(metadata, "cwd"),
                metadataString(metadata, "workspace"),
                metadataString(metadata, "working_directory"),
                metadataString(metadata, "workspace_root")
            );
            if (taskPath != null && !taskPath.isBlank()) {
                return taskPath;
            }
        }
        if (worker != null && worker.toolScope() != null && !worker.toolScope().isEmpty()) {
            String scope = worker.toolScope().get(0);
            if (scope != null && !scope.isBlank()) {
                return scope;
            }
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().toString();
    }

    private String resumeId(TaskRuntimeContext context) {
        if (context == null || context.task() == null) {
            return "agentcloud-session";
        }
        return context.task().sessionId() != null && !context.task().sessionId().isBlank()
            ? context.task().sessionId()
            : context.task().id();
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

    private LinkedHashMap<String, Object> baseMetadata(String providerId,
                                                       String workerId,
                                                       String cwd,
                                                       ProviderCliPlan plan,
                                                       AgentProviderStatus providerStatus,
                                                       long durationMs) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider_id", providerId);
        metadata.put("selected_worker", workerId);
        metadata.put("execution_backend", "provider_native_cli");
        metadata.put("cli_binary", plan.command().isEmpty() ? null : plan.command().get(0));
        metadata.put("cli_cwd", cwd);
        metadata.put("cli_command_preview", plan.commandPreview());
        metadata.put("prompt_preview", plan.promptPreview());
        metadata.put("duration_ms", durationMs);
        if (plan.model() != null && !plan.model().isBlank()) {
            metadata.put("configured_model", plan.model());
        }
        if (providerStatus != null) {
            metadata.put("provider_ready", providerStatus.ready());
            if (providerStatus.version() != null && !providerStatus.version().isBlank()) {
                metadata.put("provider_detected_version", providerStatus.version());
            }
            if (providerStatus.metadata() != null && !providerStatus.metadata().isEmpty()) {
                metadata.put("provider_status_metadata", providerStatus.metadata());
            }
        }
        return metadata;
    }

    private WorkerExecutionResult failureResult(String status,
                                                String errorText,
                                                String providerId,
                                                String workerId,
                                                String cwd,
                                                ProviderCliPlan plan,
                                                AgentProviderStatus providerStatus,
                                                long durationMs,
                                                Integer exitCode) {
        LinkedHashMap<String, Object> metadata = baseMetadata(providerId, workerId, cwd, plan, providerStatus, durationMs);
        metadata.put("provider_error", errorText);
        if (exitCode != null) {
            metadata.put("exit_code", exitCode);
        }
        return new WorkerExecutionResult(
            summarize("", errorText, status),
            "",
            false,
            "",
            "",
            "",
            "low",
            status,
            List.of(),
            List.of(errorText),
            0,
            durationMs,
            Map.copyOf(metadata)
        );
    }

    private String summarize(String outputText, String errorText, String status) {
        String base = firstNonBlank(outputText, errorText, status);
        if (base == null) {
            return "";
        }
        String normalized = base.replaceAll("\\s+", " ").trim();
        return normalized.length() > 240 ? normalized.substring(0, 240) + "..." : normalized;
    }

    private String normalizeStatus(String rawStatus, Integer exitCode) {
        String value = rawStatus == null || rawStatus.isBlank() ? "completed" : rawStatus.trim().toLowerCase(Locale.ROOT);
        if ("completed".equals(value) && exitCode != null && exitCode != 0) {
            return "failed";
        }
        return switch (value) {
            case "completed", "failed", "cancelled", "timeout" -> value;
            case "aborted" -> "cancelled";
            case "error" -> "failed";
            default -> "completed";
        };
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

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private String text(JsonNode node, String field) {
        return node == null || field == null ? null : blankToNull(node.path(field).asText(""));
    }

    private String nestedText(JsonNode node, String... path) {
        JsonNode current = node;
        for (String step : path) {
            if (current == null || step == null) {
                return null;
            }
            current = current.path(step);
        }
        return current == null ? null : blankToNull(current.asText(""));
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

    private record ProviderCliPlan(List<String> command, String promptPreview, String model) {
        private ProviderCliPlan {
            if (command == null) command = List.of();
            if (promptPreview == null) promptPreview = "";
        }

        private String commandPreview() {
            return String.join(" ", command);
        }
    }

    private record ProviderCliOutput(String status,
                                     String outputText,
                                     String errorText,
                                     String sessionId,
                                     Integer exitCode,
                                     String version,
                                     String parser) {
        private ProviderCliOutput {
            if (status == null || status.isBlank()) status = "completed";
            if (outputText == null) outputText = "";
            if (parser == null || parser.isBlank()) parser = "unknown";
        }

        private ProviderCliOutput withExitCode(int value) {
            return new ProviderCliOutput(status, outputText, errorText, sessionId, value, version, parser);
        }
    }
}
