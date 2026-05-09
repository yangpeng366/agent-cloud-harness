package com.agentcloud.worker;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.AgentProviderResolver;
import com.agentcloud.agent.AgentProviderStatus;
import com.agentcloud.agent.providers.LocalCliAgentProvider;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Task;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 面向本地 agent CLI 的单轮执行器。
 * 当前优先覆盖 multica 风格的一次性 CLI：cursor/openclaw/claude/gemini/deepseek/kimi。
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
        providerConfigs.putIfAbsent("deepseek", new LocalCliProviderConfig(
            "deepseek",
            "deepseek",
            "MULTICA_DEEPSEEK_PATH",
            "MULTICA_DEEPSEEK_MODEL"
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
            ProcessBuilder builder = new ProcessBuilder(plan.command())
                .directory(cwd == null || cwd.isBlank() ? null : Path.of(cwd).toFile())
                .redirectErrorStream(true);
            if (plan.environment() != null && !plan.environment().isEmpty()) {
                builder.environment().putAll(plan.environment());
            }
            process = builder.start();
            if (plan.stdinPrompt() != null && !plan.stdinPrompt().isBlank()) {
                writePromptToStdin(process, plan.stdinPrompt());
            }
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
        return ProviderExecutionSupport.supportsProviderNativeCli(providerId);
    }

    private ProviderCliPlan buildPlan(String providerId,
                                      LocalCliProviderConfig.ResolvedConfig config,
                                      TaskRuntimeContext context,
                                      String cwd) {
        String prompt = ProviderTaskPromptBuilder.build(context);
        return switch (providerId.toLowerCase(Locale.ROOT)) {
            case "cursor" -> buildCursorPlan(config, prompt, context, cwd);
            case "openclaw" -> buildOpenClawPlan(config, prompt, context);
            case "claude" -> buildClaudePlan(config, prompt, context);
            case "gemini" -> buildGeminiPlan(config, prompt, context);
            case "deepseek" -> buildDeepSeekPlan(config, prompt, context);
            case "kimi" -> buildKimiPlan(config, prompt, context, cwd);
            case "copilot" -> buildCopilotPlan(config, prompt, context);
            case "opencode" -> buildOpenCodePlan(config, prompt, context);
            default -> throw new IllegalArgumentException("unsupported provider-native cli provider: " + providerId);
        };
    }

    private ProviderCliPlan buildCursorPlan(LocalCliProviderConfig.ResolvedConfig config,
                                            String prompt,
                                            TaskRuntimeContext context,
                                            String cwd) {
        ArrayList<String> args = new ArrayList<>();
        args.add("chat");
        args.add("-p");
        args.add(prompt);
        args.add("--output-format");
        args.add("stream-json");
        args.add("--yolo");
        if (cwd != null && !cwd.isBlank()) {
            args.add("--workspace");
            args.add(cwd);
        }
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank()) {
            args.add("--model");
            args.add(model);
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank()) {
            args.add("--resume");
            args.add(resumeId);
        }
        return providerCliPlan(config.launchSpec(), args, truncate(prompt, 240), model);
    }

    private ProviderCliPlan buildOpenClawPlan(LocalCliProviderConfig.ResolvedConfig config,
                                              String prompt,
                                              TaskRuntimeContext context) {
        ArrayList<String> args = new ArrayList<>();
        args.add("agent");
        args.add("--local");
        args.add("--json");
        args.add("--session-id");
        args.add(resumeId(context));
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank()) {
            args.add("--agent");
            args.add(model);
        }
        args.add("--message");
        args.add(prompt);
        return providerCliPlan(config.launchSpec(), args, truncate(prompt, 240), model);
    }

    private ProviderCliPlan buildClaudePlan(LocalCliProviderConfig.ResolvedConfig config,
                                            String prompt,
                                            TaskRuntimeContext context) {
        ArrayList<String> args = new ArrayList<>();
        args.add("-p");
        args.add("--output-format");
        args.add("stream-json");
        args.add("--input-format");
        args.add("stream-json");
        args.add("--verbose");
        args.add("--strict-mcp-config");
        args.add("--permission-mode");
        args.add("bypassPermissions");
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank()) {
            args.add("--model");
            args.add(model);
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank()) {
            args.add("--resume");
            args.add(resumeId);
        }
        return providerCliPlan(config.launchSpec(), args, truncate(prompt, 240), model, buildClaudeInput(prompt));
    }

    private ProviderCliPlan buildGeminiPlan(LocalCliProviderConfig.ResolvedConfig config,
                                            String prompt,
                                            TaskRuntimeContext context) {
        ArrayList<String> args = new ArrayList<>();
        args.add("-p");
        args.add(prompt);
        args.add("--yolo");
        args.add("-o");
        args.add("stream-json");
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank()) {
            args.add("-m");
            args.add(model);
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank()) {
            args.add("-r");
            args.add(resumeId);
        }
        return providerCliPlan(config.launchSpec(), args, truncate(prompt, 240), model);
    }

    private ProviderCliPlan buildDeepSeekPlan(LocalCliProviderConfig.ResolvedConfig config,
                                              String prompt,
                                              TaskRuntimeContext context) {
        ArrayList<String> args = new ArrayList<>();
        String binary = config.binary().value();

        String effectiveModel = null;
        if (isDeepSeekFacadeBinary(binary)) {
            args.add("--provider");
            args.add("deepseek");
            effectiveModel = configuredModel(config, context);
            if (effectiveModel != null && !effectiveModel.isBlank()) {
                args.add("--model");
                args.add(effectiveModel);
            }
        }
        args.add("exec");
        args.add(prompt);
        return providerCliPlan(config.launchSpec(), args, truncate(prompt, 240), effectiveModel);
    }

    private ProviderCliPlan buildKimiPlan(LocalCliProviderConfig.ResolvedConfig config,
                                          String prompt,
                                          TaskRuntimeContext context,
                                          String cwd) {
        ArrayList<String> args = new ArrayList<>();
        args.add("--print");
        args.add("--output-format");
        args.add("stream-json");
        if (cwd != null && !cwd.isBlank()) {
            args.add("--work-dir");
            args.add(cwd);
        }
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank()) {
            args.add("--model");
            args.add(model);
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank()) {
            args.add("--session");
            args.add(resumeId);
        }
        args.add("--prompt");
        args.add(prompt);
        return providerCliPlan(config.launchSpec(), args, truncate(prompt, 240), model);
    }

    private ProviderCliPlan buildCopilotPlan(LocalCliProviderConfig.ResolvedConfig config,
                                             String prompt,
                                             TaskRuntimeContext context) {
        ArrayList<String> args = new ArrayList<>();
        args.add("-p");
        args.add(prompt);
        args.add("--output-format");
        args.add("json");
        args.add("--allow-all");
        args.add("--no-ask-user");
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank()) {
            args.add("--model");
            args.add(model);
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank()) {
            args.add("--resume");
            args.add(resumeId);
        }
        return providerCliPlan(config.launchSpec(), args, truncate(prompt, 240), model);
    }

    private ProviderCliPlan buildOpenCodePlan(LocalCliProviderConfig.ResolvedConfig config,
                                              String prompt,
                                              TaskRuntimeContext context) {
        LocalCliProviderConfig.LaunchSpec launchSpec = config.launchSpec();
        String resolvedExecutable = resolveOpenCodeExecutable(launchSpec.executableTarget());
        ArrayList<String> args = new ArrayList<>();
        args.add("run");
        args.add("--format");
        args.add("json");
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank()) {
            args.add("--model");
            args.add(model);
        }
        String systemPrompt = systemPrompt(context, "OpenCode");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            args.add("--prompt");
            args.add(systemPrompt);
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank()) {
            args.add("--session");
            args.add(resumeId);
        }
        args.add(prompt);
        return providerCliPlan(
            launchSpec.withExecutableTarget(resolvedExecutable),
            args,
            truncate(prompt, 240),
            model,
            null,
            Map.of("OPENCODE_PERMISSION", "{\"*\":\"allow\"}")
        );
    }

    private ProviderCliPlan providerCliPlan(LocalCliProviderConfig.LaunchSpec launchSpec,
                                            List<String> args,
                                            String promptPreview,
                                            String model) {
        return providerCliPlan(launchSpec, args, promptPreview, model, null, Map.of());
    }

    private ProviderCliPlan providerCliPlan(LocalCliProviderConfig.LaunchSpec launchSpec,
                                            List<String> args,
                                            String promptPreview,
                                            String model,
                                            String stdinPrompt) {
        return providerCliPlan(launchSpec, args, promptPreview, model, stdinPrompt, Map.of());
    }

    private ProviderCliPlan providerCliPlan(LocalCliProviderConfig.LaunchSpec launchSpec,
                                            List<String> args,
                                            String promptPreview,
                                            String model,
                                            String stdinPrompt,
                                            Map<String, String> environment) {
        return new ProviderCliPlan(
            launchSpec.command(args),
            promptPreview,
            model,
            stdinPrompt,
            environment,
            launchSpec.configuredBinary(),
            launchSpec.executableTarget(),
            launchSpec.launchMode()
        );
    }

    private ProviderCliOutput consume(Process process, String providerId) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            return switch (providerId.toLowerCase(Locale.ROOT)) {
                case "cursor" -> consumeCursor(reader);
                case "openclaw" -> consumeOpenClaw(reader);
                case "claude" -> consumeClaude(reader);
                case "gemini" -> consumeGemini(reader);
                case "deepseek" -> consumeDeepSeek(reader);
                case "kimi" -> consumeKimi(reader);
                case "copilot" -> consumeCopilot(reader);
                case "opencode" -> consumeOpenCode(reader);
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

    private ProviderCliOutput consumeClaude(BufferedReader reader) throws IOException {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
        String status = "completed";
        String errorText = null;
        String line;
        while ((line = reader.readLine()) != null) {
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
        return new ProviderCliOutput(
            status,
            output.toString().trim(),
            errorText,
            sessionId,
            null,
            null,
            "claude_stream_json"
        );
    }

    private ProviderCliOutput consumeGemini(BufferedReader reader) throws IOException {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
        String status = "completed";
        String errorText = null;
        String line;
        while ((line = reader.readLine()) != null) {
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
                } else if ("result".equals(type)) {
                    if ("error".equalsIgnoreCase(text(event, "status"))) {
                        status = "failed";
                        errorText = firstNonBlank(errorText,
                            nestedText(event, "error", "message"),
                            text(event, "message"),
                            trimmed);
                    }
                }
                sessionId = firstNonBlank(sessionId, text(event, "session_id"), text(event, "sessionId"));
            } catch (Exception ignored) {
                appendLine(output, trimmed);
            }
        }
        return new ProviderCliOutput(
            status,
            output.toString().trim(),
            errorText,
            sessionId,
            null,
            null,
            "gemini_stream_json"
        );
    }

    private ProviderCliOutput consumeDeepSeek(BufferedReader reader) throws IOException {
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            appendLine(output, line);
        }
        return new ProviderCliOutput(
            "completed",
            output.toString().trim(),
            null,
            null,
            null,
            null,
            "deepseek_exec_text"
        );
    }

    private ProviderCliOutput consumeKimi(BufferedReader reader) throws IOException {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
        String status = "completed";
        String errorText = null;
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.regionMatches(true, 0, "To resume this session:", 0, "To resume this session:".length())) {
                String hintedSession = extractKimiResumeSession(trimmed);
                sessionId = firstNonBlank(sessionId, hintedSession);
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
                        nestedText(event, "error", "message"),
                        trimmed);
                }
            } catch (Exception ignored) {
                appendLine(output, trimmed);
            }
        }
        return new ProviderCliOutput(
            status,
            output.toString().trim(),
            errorText,
            sessionId,
            null,
            null,
            "kimi_stream_json"
        );
    }

    private ProviderCliOutput consumeCopilot(BufferedReader reader) throws IOException {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
        String activeModel = null;
        String status = "completed";
        String errorText = null;
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank() || trimmed.charAt(0) != '{') {
                appendLine(output, trimmed);
                continue;
            }
            try {
                JsonNode event = MAPPER.readTree(trimmed);
                String type = text(event, "type");
                JsonNode data = event.path("data");
                if ("session.start".equals(type)) {
                    sessionId = firstNonBlank(sessionId, text(data, "sessionId"));
                    activeModel = firstNonBlank(activeModel, text(data, "selectedModel"));
                } else if ("assistant.message_delta".equals(type)) {
                    appendRaw(output, text(data, "deltaContent"));
                } else if ("assistant.message".equals(type)) {
                    String content = text(data, "content");
                    if (content != null && !content.isBlank()) {
                        output.setLength(0);
                        output.append(content.trim());
                    }
                    activeModel = firstNonBlank(text(data, "selectedModel"), activeModel);
                } else if ("assistant.reasoning".equals(type) || "assistant.reasoning_delta".equals(type)) {
                    appendLine(output, firstNonBlank(text(data, "content"), text(data, "deltaContent")));
                } else if ("session.error".equals(type)) {
                    status = "failed";
                    errorText = firstNonBlank(errorText, text(data, "message"), trimmed);
                } else if ("result".equals(type)) {
                    sessionId = firstNonBlank(text(event, "sessionId"), sessionId);
                    if (event.path("exitCode").asInt(0) != 0) {
                        status = "failed";
                        errorText = firstNonBlank(errorText, "copilot exited with code " + event.path("exitCode").asInt());
                    }
                }
                if (activeModel != null && !activeModel.isBlank()) {
                    sessionId = firstNonBlank(sessionId, text(data, "sessionId"));
                }
            } catch (Exception ignored) {
                appendLine(output, trimmed);
            }
        }
        return new ProviderCliOutput(
            status,
            output.toString().trim(),
            errorText,
            sessionId,
            null,
            activeModel,
            "copilot_jsonl"
        );
    }

    private ProviderCliOutput consumeOpenCode(BufferedReader reader) throws IOException {
        StringBuilder output = new StringBuilder();
        String sessionId = null;
        String status = "completed";
        String errorText = null;
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank() || trimmed.charAt(0) != '{') {
                appendLine(output, trimmed);
                continue;
            }
            try {
                JsonNode event = MAPPER.readTree(trimmed);
                String type = text(event, "type");
                JsonNode part = event.path("part");
                sessionId = firstNonBlank(sessionId, text(event, "sessionID"), text(part, "sessionID"));
                if ("text".equals(type)) {
                    appendRaw(output, text(part, "text"));
                } else if ("error".equals(type)) {
                    status = "failed";
                    errorText = firstNonBlank(errorText,
                        nestedText(event, "error", "data", "message"),
                        text(event.path("error"), "name"),
                        trimmed);
                }
            } catch (Exception ignored) {
                appendLine(output, trimmed);
            }
        }
        return new ProviderCliOutput(
            status,
            output.toString().trim(),
            errorText,
            sessionId,
            null,
            null,
            "opencode_json"
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

    private void writePromptToStdin(Process process, String stdinPrompt) throws IOException {
        try (Writer writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(stdinPrompt);
            writer.flush();
        }
    }

    private String buildClaudeInput(String prompt) {
        try {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "user");
            payload.put("message", Map.of(
                "role", "user",
                "content", List.of(Map.of(
                    "type", "text",
                    "text", prompt
                ))
            ));
            return MAPPER.writeValueAsString(payload) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("failed to build claude stdin payload", e);
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
        return providerRegistry != null ? providerRegistry.status(providerId) : null;
    }

    private String configuredModel(LocalCliProviderConfig.ResolvedConfig config, TaskRuntimeContext context) {
        String taskModel = context == null || context.task() == null ? null : metadataString(context.task().metadata(), "provider_model");
        if (taskModel != null && !taskModel.isBlank()) {
            return taskModel;
        }
        return config.model().value();
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

    private String systemPrompt(TaskRuntimeContext context, String providerDisplayName) {
        String metadataPrompt = metadataString(context == null || context.task() == null ? null : context.task().metadata(), "system_prompt");
        if (metadataPrompt != null && !metadataPrompt.isBlank()) {
            return metadataPrompt;
        }
        return ProviderTaskPromptBuilder.defaultSystemPrompt(providerDisplayName);
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

    private String extractKimiResumeSession(String line) {
        String trimmed = line == null ? "" : line.trim();
        int marker = trimmed.lastIndexOf("-r ");
        if (marker < 0) {
            marker = trimmed.lastIndexOf("--resume ");
            if (marker < 0) {
                return null;
            }
            return blankToNull(trimmed.substring(marker + "--resume ".length()).trim());
        }
        return blankToNull(trimmed.substring(marker + 3).trim());
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
        metadata.put("cli_binary", plan.configuredBinary());
        metadata.put("cli_cwd", cwd);
        metadata.put("cli_command_preview", plan.commandPreview());
        putIfNonBlank(metadata, "cli_resolved_binary", plan.executableTarget());
        putIfNonBlank(metadata, "cli_launch_mode", plan.launchMode());
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

    private void appendRaw(StringBuilder target, String text) {
        if (target == null || text == null || text.isBlank()) {
            return;
        }
        target.append(text);
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        return ProviderTaskPromptBuilder.metadataString(metadata, key);
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

    private boolean isDeepSeekFacadeBinary(String binary) {
        return "deepseek".equals(normalizeBinaryName(binary));
    }

    private String normalizeBinaryName(String binary) {
        if (binary == null || binary.isBlank()) {
            return "";
        }
        String name = new File(binary).getName().toLowerCase(Locale.ROOT);
        for (String extension : List.of(".exe", ".cmd", ".bat", ".ps1", ".sh")) {
            if (name.endsWith(extension)) {
                return name.substring(0, name.length() - extension.length());
            }
        }
        return name;
    }

    private String resolveOpenCodeExecutable(String configuredBinary) {
        if (!isWindowsHost()) {
            return configuredBinary;
        }
        Path binaryPath = directPath(configuredBinary);
        if (binaryPath != null) {
            String nativePath = resolveOpenCodeNativeFromShim(binaryPath);
            if (nativePath != null) {
                return nativePath;
            }
            return configuredBinary;
        }
        Path located = locateOnPath(configuredBinary);
        if (located == null) {
            return configuredBinary;
        }
        String nativePath = resolveOpenCodeNativeFromShim(located);
        return nativePath != null ? nativePath : configuredBinary;
    }

    private String resolveOpenCodeNativeFromShim(Path shimPath) {
        if (shimPath == null) {
            return null;
        }
        String fileName = shimPath.getFileName() == null ? "" : shimPath.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".cmd")) {
            return null;
        }
        Path prefix = shimPath.getParent();
        if (prefix == null) {
            return null;
        }
        for (String packageName : openCodeWindowsPackageCandidates()) {
            Path candidate = prefix
                .resolve("node_modules")
                .resolve("opencode-ai")
                .resolve("node_modules")
                .resolve(packageName)
                .resolve("bin")
                .resolve("opencode.exe");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    private List<String> openCodeWindowsPackageCandidates() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("arm")) {
            return List.of("opencode-windows-arm64", "opencode-windows-x64", "opencode-windows-x64-baseline");
        }
        return List.of("opencode-windows-x64", "opencode-windows-x64-baseline", "opencode-windows-arm64");
    }

    private boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private Path directPath(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        if (toolName.contains("/") || toolName.contains("\\")) {
            try {
                return Paths.get(toolName).toAbsolutePath().normalize();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Path locateOnPath(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return null;
        }
        for (String rawEntry : pathValue.split(File.pathSeparator)) {
            String entry = rawEntry == null ? "" : rawEntry.trim();
            if (entry.isBlank()) {
                continue;
            }
            Path dir;
            try {
                dir = Paths.get(unquote(entry));
            } catch (Exception ignored) {
                continue;
            }
            for (String candidate : candidateExecutableNames(toolName)) {
                Path path = dir.resolve(candidate);
                if (Files.isRegularFile(path)) {
                    return path.toAbsolutePath().normalize();
                }
            }
        }
        return null;
    }

    private Set<String> candidateExecutableNames(String toolName) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(toolName);
        if (!isWindowsHost() || toolName.contains(".")) {
            return names;
        }
        String pathExtValue = System.getenv("PATHEXT");
        List<String> extensions = (pathExtValue == null || pathExtValue.isBlank())
            ? List.of(".exe", ".cmd", ".bat", ".com")
            : List.of(pathExtValue.split(";"));
        for (String rawExtension : extensions) {
            String extension = rawExtension == null ? "" : rawExtension.trim().toLowerCase(Locale.ROOT);
            if (!extension.isBlank()) {
                names.add(toolName + extension);
            }
        }
        return names;
    }

    private String unquote(String value) {
        if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private void putIfNonBlank(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    private record ProviderCliPlan(List<String> command,
                                   String promptPreview,
                                   String model,
                                   String stdinPrompt,
                                   Map<String, String> environment,
                                   String configuredBinary,
                                   String executableTarget,
                                   String launchMode) {
        private ProviderCliPlan {
            if (command == null) command = List.of();
            if (promptPreview == null) promptPreview = "";
            if (environment == null) environment = Map.of();
            if (configuredBinary == null) configuredBinary = "";
            if (launchMode == null || launchMode.isBlank()) launchMode = "direct";
        }

        private ProviderCliPlan(List<String> command, String promptPreview, String model) {
            this(command, promptPreview, model, null, Map.of(), "", "", "direct");
        }

        private ProviderCliPlan(List<String> command, String promptPreview, String model, String stdinPrompt) {
            this(command, promptPreview, model, stdinPrompt, Map.of(), "", "", "direct");
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
