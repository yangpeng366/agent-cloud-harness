package com.agentcloud.worker;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.AgentProviderResolver;
import com.agentcloud.agent.AgentProviderStatus;
import com.agentcloud.agent.providers.LocalCliAgentProvider;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.agent.providers.CliCapabilityProfile;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.TextDecoding;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向本地 agent CLI 的单轮执行器。
 * 当前优先覆盖 multica 风格的一次性 CLI：cursor/openclaw/claude/gemini/deepseek/kimi。
 */
public class ProviderCliWorkerExecutor implements WorkerExecutor {
    private static final Logger log = LoggerFactory.getLogger(ProviderCliWorkerExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long PROCESS_TIMEOUT_MS = 180_000L;
    private static final int MAX_CAPTURE_BYTES = 1_048_576;
    private static final int SQLITE_OUTPUT_TEXT_LIMIT = 16_384;
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("(?i)\\b[a-z]:\\\\[^\\s\"'<>|，,；;。)）]+");

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
        ProviderCliPlan plan = buildPlan(providerId, config, context, cwd, cliProfile(providerStatus));
        ProviderRunFiles runFiles = ProviderRunFiles.create(providerId, taskId(context), workerId, plan);

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
            Process runningProcess = process;
            OutputCapture capture = new OutputCapture();
            Thread drainer = Thread.ofVirtual().start(() -> capture.drain(runningProcess.getInputStream(), runFiles.stdout()));
            if (plan.stdinPrompt() != null && !plan.stdinPrompt().isBlank()) {
                writePromptToStdin(process, plan.stdinPrompt());
            }
            if (!process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                drainer.join(2_000);
                long durationMs = System.currentTimeMillis() - startedAtMs;
                runFiles.closeQuietly();
                return failureResult("timeout", "provider-native cli timed out",
                    providerId, workerId, cwd, plan, providerStatus, durationMs, null, runFiles);
            }
            drainer.join(2_000);
            output = consume(capture.bytes(), providerId);
            if (capture.truncated()) {
                output = output.withOutputLimitExceeded(capture.totalBytes(), MAX_CAPTURE_BYTES);
            }
            output = output.withExitCode(process.exitValue());
        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startedAtMs;
            runFiles.closeQuietly();
            return failureResult("failed", "failed to start provider-native cli: " + e.getMessage(),
                providerId, workerId, cwd, plan, providerStatus, durationMs, null, runFiles);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = System.currentTimeMillis() - startedAtMs;
            runFiles.closeQuietly();
            return failureResult("cancelled", "provider-native cli interrupted",
                providerId, workerId, cwd, plan, providerStatus, durationMs, null, runFiles);
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
        if (output.outputTruncated()) {
            metadata.put("provider_output_truncated", true);
            metadata.put("provider_output_total_bytes", output.outputTotalBytes());
            metadata.put("provider_output_capture_limit_bytes", output.outputCaptureLimitBytes());
        }
        if (output.version() != null && !output.version().isBlank()) {
            metadata.put("provider_version", output.version());
        }

        String outputText = output.outputText() == null ? "" : output.outputText().trim();
        runFiles.writeLastMessage(outputText);
        appendRunFileMetadata(metadata, runFiles);
        String summary = summarize(outputText, output.errorText(), normalizedStatus);
        String providerDiagnostic = providerDiagnostic(output.errorText(), outputText, normalizedStatus);
        if (output.errorText() != null && !output.errorText().isBlank()) {
            metadata.put("provider_error", output.errorText());
        } else if ("failed".equals(normalizedStatus) && providerDiagnostic != null && !providerDiagnostic.isBlank()) {
            metadata.put("provider_error", providerDiagnostic);
        }
        attachProviderFailureClassification(metadata, normalizedStatus, providerDiagnostic);
        String sqliteOutputText = sqliteOutputText(outputText, metadata);
        runFiles.writeMetadata(metadata);

        log.info("Provider-native CLI round completed. provider={} worker={} status={} exitCode={} durationMs={}",
            providerId, workerId, normalizedStatus, output.exitCode(), durationMs);

        return new WorkerExecutionResult(
            summary,
            sqliteOutputText,
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
        return buildPlan(providerId, config, context, cwd, null);
    }

    private ProviderCliPlan buildPlan(String providerId,
                                      LocalCliProviderConfig.ResolvedConfig config,
                                      TaskRuntimeContext context,
                                      String cwd,
                                      CliCapabilityProfile profile) {
        String prompt = ProviderTaskPromptBuilder.build(context);
        return switch (providerId.toLowerCase(Locale.ROOT)) {
            case "cursor" -> buildCursorPlan(config, prompt, context, cwd, profile);
            case "openclaw" -> buildOpenClawPlan(config, prompt, context);
            case "claude" -> buildClaudePlan(config, prompt, context, profile);
            case "gemini" -> buildGeminiPlan(config, prompt, context, profile);
            case "deepseek" -> buildDeepSeekPlan(config, prompt, context);
            case "kimi" -> buildKimiPlan(config, prompt, context, cwd, profile);
            case "copilot" -> buildCopilotPlan(config, prompt, context, profile);
            case "opencode" -> buildOpenCodePlan(config, prompt, context);
            default -> throw new IllegalArgumentException("unsupported provider-native cli provider: " + providerId);
        };
    }

    private ProviderCliPlan buildCursorPlan(LocalCliProviderConfig.ResolvedConfig config,
                                            String prompt,
                                            TaskRuntimeContext context,
                                            String cwd,
                                            CliCapabilityProfile profile) {
        ArrayList<String> args = new ArrayList<>();
        args.add("chat");
        args.add("-p");
        args.add(prompt);
        args.add("--output-format");
        args.add("stream-json");
        ArrayList<String> profileAdjustments = new ArrayList<>();
        if (!profileUnsupported(profile, "yolo")) {
            args.add("--yolo");
        } else {
            profileAdjustments.add("dropped --yolo");
        }
        if (cwd != null && !cwd.isBlank() && !profileUnsupported(profile, "workspace_arg")) {
            args.add("--workspace");
            args.add(cwd);
        } else if (cwd != null && !cwd.isBlank()) {
            profileAdjustments.add("dropped --workspace");
        }
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank() && !profileUnsupported(profile, "model")) {
            args.add("--model");
            args.add(model);
        } else if (model != null && !model.isBlank()) {
            profileAdjustments.add("dropped --model");
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank() && !profileUnsupported(profile, "resume")) {
            args.add("--resume");
            args.add(resumeId);
        } else if (resumeId != null && !resumeId.isBlank()) {
            profileAdjustments.add("dropped --resume");
        }
        return providerCliPlan(config.launchSpec(), args, truncate(prompt, 240), model, profile, profileAdjustments);
    }

    private ProviderCliPlan buildOpenClawPlan(LocalCliProviderConfig.ResolvedConfig config,
                                              String prompt,
                                              TaskRuntimeContext context) {
        ArrayList<String> args = new ArrayList<>();
        args.add("agent");
        args.add("--local");
        args.add("--json");
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank()) {
            args.add("--session-id");
            args.add(resumeId);
        }
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
                                            TaskRuntimeContext context,
                                            CliCapabilityProfile profile) {
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
        ArrayList<String> profileAdjustments = new ArrayList<>();
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank() && !profileUnsupported(profile, "model")) {
            args.add("--model");
            args.add(model);
        } else if (model != null && !model.isBlank()) {
            profileAdjustments.add("dropped --model");
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank() && !profileUnsupported(profile, "resume")) {
            args.add("--resume");
            args.add(resumeId);
        } else if (resumeId != null && !resumeId.isBlank()) {
            profileAdjustments.add("dropped --resume");
        }
        return providerCliPlan(config.launchSpec(), args, truncate(prompt, 240), model, buildClaudeInput(prompt), Map.of(),
            profile, profileAdjustments);
    }

    private ProviderCliPlan buildGeminiPlan(LocalCliProviderConfig.ResolvedConfig config,
                                            String prompt,
                                            TaskRuntimeContext context,
                                            CliCapabilityProfile profile) {
        ArrayList<String> args = new ArrayList<>();
        args.add("-p");
        args.add(prompt);
        ArrayList<String> profileAdjustments = new ArrayList<>();
        if (!profileUnsupported(profile, "yolo")) {
            args.add("--yolo");
        } else {
            profileAdjustments.add("dropped --yolo");
        }
        args.add("-o");
        args.add("stream-json");
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank() && !profileUnsupported(profile, "model")) {
            args.add("-m");
            args.add(model);
        } else if (model != null && !model.isBlank()) {
            profileAdjustments.add("dropped -m");
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank() && !profileUnsupported(profile, "resume")) {
            args.add("-r");
            args.add(resumeId);
        } else if (resumeId != null && !resumeId.isBlank()) {
            profileAdjustments.add("dropped -r");
        }
        return providerCliPlan(config.launchSpec(), args, truncate(prompt, 240), model, profile, profileAdjustments);
    }

    private ProviderCliPlan buildDeepSeekPlan(LocalCliProviderConfig.ResolvedConfig config,
                                              String prompt,
                                              TaskRuntimeContext context) {
        ArrayList<String> args = new ArrayList<>();
        args.add("exec");
        args.add(prompt);
        return providerCliPlan(config.launchSpec(), args, truncate(prompt, 240), null);
    }

    private ProviderCliPlan buildKimiPlan(LocalCliProviderConfig.ResolvedConfig config,
                                          String prompt,
                                          TaskRuntimeContext context,
                                          String cwd,
                                          CliCapabilityProfile profile) {
        ArrayList<String> args = new ArrayList<>();
        args.add("--print");
        args.add("--output-format");
        args.add("stream-json");
        ArrayList<String> profileAdjustments = new ArrayList<>();
        if (cwd != null && !cwd.isBlank() && !profileUnsupported(profile, "work_dir_arg")) {
            args.add("--work-dir");
            args.add(cwd);
        } else if (cwd != null && !cwd.isBlank()) {
            profileAdjustments.add("dropped --work-dir");
        }
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank() && !profileUnsupported(profile, "model")) {
            args.add("--model");
            args.add(model);
        } else if (model != null && !model.isBlank()) {
            profileAdjustments.add("dropped --model");
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank() && !profileUnsupported(profile, "resume")) {
            args.add("--session");
            args.add(resumeId);
        } else if (resumeId != null && !resumeId.isBlank()) {
            profileAdjustments.add("dropped --session");
        }
        args.add("--prompt");
        args.add(prompt);
        return providerCliPlan(config.launchSpec(), args, truncate(prompt, 240), model, profile, profileAdjustments);
    }

    private ProviderCliPlan buildCopilotPlan(LocalCliProviderConfig.ResolvedConfig config,
                                             String prompt,
                                             TaskRuntimeContext context,
                                             CliCapabilityProfile profile) {
        ArrayList<String> args = new ArrayList<>();
        args.add("-p");
        args.add(prompt);
        args.add("--output-format");
        args.add("json");
        args.add("--allow-all");
        args.add("--no-ask-user");
        ArrayList<String> profileAdjustments = new ArrayList<>();
        String model = configuredModel(config, context);
        if (model != null && !model.isBlank() && !profileUnsupported(profile, "model")) {
            args.add("--model");
            args.add(model);
        } else if (model != null && !model.isBlank()) {
            profileAdjustments.add("dropped --model");
        }
        String resumeId = resumeId(context);
        if (resumeId != null && !resumeId.isBlank() && !profileUnsupported(profile, "resume")) {
            args.add("--resume");
            args.add(resumeId);
        } else if (resumeId != null && !resumeId.isBlank()) {
            profileAdjustments.add("dropped --resume");
        }
        return providerCliPlan(config.launchSpec(), args, truncate(prompt, 240), model, profile, profileAdjustments);
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
        return providerCliPlan(launchSpec, args, promptPreview, model, stdinPrompt, environment, null, List.of());
    }

    private ProviderCliPlan providerCliPlan(LocalCliProviderConfig.LaunchSpec launchSpec,
                                            List<String> args,
                                            String promptPreview,
                                            String model,
                                            CliCapabilityProfile profile,
                                            List<String> profileAdjustments) {
        return providerCliPlan(launchSpec, args, promptPreview, model, null, Map.of(), profile, profileAdjustments);
    }

    private ProviderCliPlan providerCliPlan(LocalCliProviderConfig.LaunchSpec launchSpec,
                                            List<String> args,
                                            String promptPreview,
                                            String model,
                                            String stdinPrompt,
                                            Map<String, String> environment,
                                            CliCapabilityProfile profile,
                                            List<String> profileAdjustments) {
        return new ProviderCliPlan(
            launchSpec.command(args),
            promptPreview,
            model,
            stdinPrompt,
            environment,
            launchSpec.configuredBinary(),
            launchSpec.executableTarget(),
            launchSpec.launchMode(),
            profile,
            profileAdjustments == null ? List.of() : List.copyOf(profileAdjustments)
        );
    }

    private ProviderCliOutput consume(byte[] bytes, String providerId) throws IOException {
        String decoded = TextDecoding.decodeExternalProcessOutput(bytes);
        try (BufferedReader reader = new BufferedReader(new StringReader(decoded))) {
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

    private static final class OutputCapture {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private long totalBytes;
        private boolean truncated;

        private void drain(InputStream input, OutputStream rawOutput) {
            byte[] chunk = new byte[8192];
            try (input) {
                int read;
                while ((read = input.read(chunk)) != -1) {
                    totalBytes += read;
                    if (rawOutput != null) {
                        rawOutput.write(chunk, 0, read);
                    }
                    int remaining = MAX_CAPTURE_BYTES - buffer.size();
                    if (remaining > 0) {
                        buffer.write(chunk, 0, Math.min(read, remaining));
                    }
                    if (read > remaining) {
                        truncated = true;
                    }
                }
                if (rawOutput != null) {
                    rawOutput.flush();
                }
            } catch (IOException ignored) {
                // 让上层用已有内容继续解析，避免输出链因为收尾失败彻底丢失。
            } finally {
                if (rawOutput != null) {
                    try {
                        rawOutput.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        private byte[] bytes() {
            return buffer.toByteArray();
        }

        private long totalBytes() {
            return totalBytes;
        }

        private boolean truncated() {
            return truncated;
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
                metadataString(metadata, "workspace_root"),
                singleWorkspaceRoot(metadata)
            );
            if (taskPath != null && !taskPath.isBlank()) {
                return taskPath;
            }
            String inferred = inferWorkspaceRoot(context.task());
            if (inferred != null && !inferred.isBlank()) {
                return inferred;
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

    private String inferWorkspaceRoot(Task task) {
        if (task == null) {
            return null;
        }
        String source = firstNonBlank(
            task.goal(),
            task.title(),
            task.summary(),
            metadataString(task.metadata(), "goal"),
            metadataString(task.metadata(), "intent"),
            metadataString(task.metadata(), "target_path")
        );
        if (source == null) {
            return null;
        }
        Matcher matcher = WINDOWS_ABSOLUTE_PATH.matcher(source);
        while (matcher.find()) {
            String root = workspaceRootFromPath(matcher.group());
            if (root != null) {
                return root;
            }
        }
        return null;
    }

    private String singleWorkspaceRoot(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object raw = metadata.get("workspace_roots");
        if (raw == null) {
            return null;
        }
        ArrayList<String> roots = new ArrayList<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addWorkspaceRoot(roots, item);
            }
        } else if (raw.getClass().isArray() && raw instanceof Object[] array) {
            for (Object item : array) {
                addWorkspaceRoot(roots, item);
            }
        } else {
            String text = raw.toString();
            if (text.contains("\n")) {
                for (String part : text.split("\\R")) {
                    addWorkspaceRoot(roots, part);
                }
            } else if (text.contains("|")) {
                for (String part : text.split("\\|")) {
                    addWorkspaceRoot(roots, part);
                }
            } else {
                addWorkspaceRoot(roots, raw);
            }
        }
        return roots.size() == 1 ? roots.get(0) : null;
    }

    private void addWorkspaceRoot(List<String> roots, Object raw) {
        if (raw == null) {
            return;
        }
        String value = raw.toString().trim();
        if (!value.isBlank() && !roots.contains(value)) {
            roots.add(value);
        }
    }

    private String workspaceRootFromPath(String rawPath) {
        String normalized = stripTrailingPathNoise(rawPath);
        if (normalized == null) {
            return null;
        }
        try {
            Path path = Paths.get(normalized).toAbsolutePath().normalize();
            Path cursor = Files.isDirectory(path) ? path : path.getParent();
            while (cursor != null) {
                if (Files.isDirectory(cursor.resolve(".git"))
                    || Files.exists(cursor.resolve("pom.xml"))
                    || Files.exists(cursor.resolve("package.json"))) {
                    return cursor.toString();
                }
                cursor = cursor.getParent();
            }
        } catch (RuntimeException ignored) {
            // Fall back to textual D:\gitAll\<repo> extraction.
        }
        return gitAllRepoRootFromText(normalized);
    }

    private String gitAllRepoRootFromText(String pathText) {
        String normalized = blankToNull(pathText);
        if (normalized == null) {
            return null;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        int marker = lower.indexOf("\\gitall\\");
        if (marker < 0) {
            return null;
        }
        int start = marker + "\\gitall\\".length();
        int nextSlash = normalized.indexOf('\\', start);
        if (nextSlash <= start) {
            return null;
        }
        return normalized.substring(0, nextSlash);
    }

    private String stripTrailingPathNoise(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        while (!normalized.isEmpty()) {
            char last = normalized.charAt(normalized.length() - 1);
            if (last == '.' || last == ',' || last == ';' || last == '，' || last == '；' || last == '。') {
                normalized = normalized.substring(0, normalized.length() - 1);
                continue;
            }
            break;
        }
        return normalized;
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
            return null;
        }
        String recoveryStage = metadataString(context.task().metadata(), "recovery_stage");
        if ("same_worker_retry_scheduled".equalsIgnoreCase(recoveryStage)
            || "auto_handoff_scheduled".equalsIgnoreCase(recoveryStage)) {
            return null;
        }
        return firstNonBlank(
            metadataString(context.task().metadata(), "provider_session_id"),
            metadataString(context.task().metadata(), "provider_thread_id"),
            metadataString(context.task().metadata(), "resume_provider_session_id")
        );
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
        metadata.put("cli_command_shape", sanitizedCommandShape(providerId, plan));
        metadata.put("cli_command_arg_count", plan.command().size());
        metadata.put("cli_prompt_delivery", promptDeliveryMode(plan));
        metadata.put("cli_uses_stdin", plan.stdinPrompt() != null && !plan.stdinPrompt().isBlank());
        metadata.put("cli_uses_resume", hasResumeArg(plan));
        putIfNonBlank(metadata, "cli_resume_arg_name", resumeArgName(plan));
        metadata.put("provider_expected_output_mode", expectedOutputMode(providerId));
        metadata.put("provider_expected_parser", expectedParser(providerId));
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
        if (plan.cliProfile() != null) {
            metadata.put("cli_profile", plan.cliProfile().metadata());
        }
        if (plan.cliProfileAdjustments() != null && !plan.cliProfileAdjustments().isEmpty()) {
            metadata.put("cli_profile_adjustments", plan.cliProfileAdjustments());
        }
        return metadata;
    }

    private CliCapabilityProfile cliProfile(AgentProviderStatus providerStatus) {
        if (providerStatus == null || providerStatus.metadata() == null) {
            return null;
        }
        return CliCapabilityProfile.fromMetadata(providerStatus.metadata());
    }

    private boolean profileUnsupported(CliCapabilityProfile profile, String capability) {
        return profile != null && profile.explicitlyUnsupported(capability);
    }

    private List<String> sanitizedCommandShape(String providerId, ProviderCliPlan plan) {
        if (plan == null || plan.command() == null || plan.command().isEmpty()) {
            return List.of();
        }
        return plan.command().stream()
            .map(arg -> sanitizeCommandArg(providerId, arg))
            .toList();
    }

    private String sanitizeCommandArg(String providerId, String arg) {
        if (arg == null) {
            return "";
        }
        String value = arg.trim();
        String normalizedProvider = providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
        if (!normalizedProvider.isBlank() && value.equalsIgnoreCase(normalizedProvider)) {
            return value;
        }
        if (looksLikePromptArg(value)) {
            return "<prompt>";
        }
        return value;
    }

    private boolean looksLikePromptArg(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.length() > 160
            || value.contains("\n")
            || value.contains("Task Focus:")
            || value.contains("Workspaces:")
            || value.contains("Expected Deliverables:")
            || value.contains("Required Checks:");
    }

    private String promptDeliveryMode(ProviderCliPlan plan) {
        if (plan != null && plan.stdinPrompt() != null && !plan.stdinPrompt().isBlank()) {
            return "stdin_jsonl";
        }
        return "argv_prompt";
    }

    private boolean hasResumeArg(ProviderCliPlan plan) {
        return resumeArgName(plan) != null;
    }

    private String resumeArgName(ProviderCliPlan plan) {
        if (plan == null || plan.command() == null || plan.command().isEmpty()) {
            return null;
        }
        for (String arg : plan.command()) {
            if ("--resume".equals(arg) || "-r".equals(arg) || "--session".equals(arg) || "--session-id".equals(arg)) {
                return arg;
            }
        }
        return null;
    }

    private String expectedOutputMode(String providerId) {
        return switch (providerId == null ? "" : providerId.toLowerCase(Locale.ROOT)) {
            case "deepseek" -> "text";
            case "copilot" -> "jsonl";
            case "opencode" -> "json";
            default -> "stream_json";
        };
    }

    private String expectedParser(String providerId) {
        return switch (providerId == null ? "" : providerId.toLowerCase(Locale.ROOT)) {
            case "cursor" -> "cursor_stream_json";
            case "openclaw" -> "openclaw_json";
            case "claude" -> "claude_stream_json";
            case "gemini" -> "gemini_stream_json";
            case "deepseek" -> "deepseek_exec_text";
            case "kimi" -> "kimi_stream_json";
            case "copilot" -> "copilot_jsonl";
            case "opencode" -> "opencode_json";
            default -> "unknown";
        };
    }

    private WorkerExecutionResult failureResult(String status,
                                                String errorText,
                                                String providerId,
                                                String workerId,
                                                String cwd,
                                                ProviderCliPlan plan,
                                                AgentProviderStatus providerStatus,
                                                long durationMs,
                                                Integer exitCode,
                                                ProviderRunFiles runFiles) {
        LinkedHashMap<String, Object> metadata = baseMetadata(providerId, workerId, cwd, plan, providerStatus, durationMs);
        metadata.put("provider_error", errorText);
        appendRunFileMetadata(metadata, runFiles);
        attachProviderFailureClassification(metadata, status, errorText);
        if (exitCode != null) {
            metadata.put("exit_code", exitCode);
        }
        if (runFiles != null) {
            runFiles.writeLastMessage("");
            runFiles.writeMetadata(metadata);
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

    private String taskId(TaskRuntimeContext context) {
        return context != null && context.task() != null && context.task().id() != null && !context.task().id().isBlank()
            ? context.task().id()
            : "unknown_task";
    }

    private void appendRunFileMetadata(Map<String, Object> metadata, ProviderRunFiles runFiles) {
        if (metadata == null || runFiles == null || !runFiles.available()) {
            return;
        }
        metadata.put("provider_run_dir", runFiles.runDir().toString());
        metadata.put("provider_prompt_path", runFiles.promptPath().toString());
        metadata.put("provider_stdout_path", runFiles.stdoutPath().toString());
        metadata.put("provider_last_message_path", runFiles.lastMessagePath().toString());
        metadata.put("provider_run_metadata_path", runFiles.metadataPath().toString());
    }

    private String sqliteOutputText(String outputText, Map<String, Object> metadata) {
        String value = outputText == null ? "" : outputText;
        if (value.length() <= SQLITE_OUTPUT_TEXT_LIMIT
            && !Boolean.parseBoolean(String.valueOf(metadata != null ? metadata.get("provider_output_truncated") : null))) {
            return value;
        }
        if (metadata != null) {
            metadata.put("provider_output_truncated", true);
            metadata.put("provider_output_sqlite_limit_chars", SQLITE_OUTPUT_TEXT_LIMIT);
        }
        return value.length() <= SQLITE_OUTPUT_TEXT_LIMIT
            ? value
            : value.substring(0, SQLITE_OUTPUT_TEXT_LIMIT);
    }

    private String summarize(String outputText, String errorText, String status) {
        String base = firstNonBlank(outputText, errorText, status);
        if (base == null) {
            return "";
        }
        String normalized = base.replaceAll("\\s+", " ").trim();
        return normalized.length() > 240 ? normalized.substring(0, 240) + "..." : normalized;
    }

    private String providerDiagnostic(String errorText, String outputText, String status) {
        return firstNonBlank(errorText, outputText, status);
    }

    private void attachProviderFailureClassification(Map<String, Object> metadata, String status, String message) {
        ProviderFailureClassifier.Classification classification = ProviderFailureClassifier.classify(status, message);
        if (classification == null) {
            return;
        }
        metadata.put("provider_failure_class", classification.failureClass());
        metadata.put("provider_failure_reason", classification.reason());
        metadata.put("provider_retryable", classification.retryable());
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
                                   String launchMode,
                                   CliCapabilityProfile cliProfile,
                                   List<String> cliProfileAdjustments) {
        private ProviderCliPlan {
            if (command == null) command = List.of();
            if (promptPreview == null) promptPreview = "";
            if (environment == null) environment = Map.of();
            if (configuredBinary == null) configuredBinary = "";
            if (launchMode == null || launchMode.isBlank()) launchMode = "direct";
            if (cliProfileAdjustments == null) cliProfileAdjustments = List.of();
        }

        private ProviderCliPlan(List<String> command, String promptPreview, String model) {
            this(command, promptPreview, model, null, Map.of(), "", "", "direct", null, List.of());
        }

        private ProviderCliPlan(List<String> command, String promptPreview, String model, String stdinPrompt) {
            this(command, promptPreview, model, stdinPrompt, Map.of(), "", "", "direct", null, List.of());
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
                                     String parser,
                                     boolean outputTruncated,
                                     Long outputTotalBytes,
                                     Integer outputCaptureLimitBytes) {
        private ProviderCliOutput {
            if (status == null || status.isBlank()) status = "completed";
            if (outputText == null) outputText = "";
            if (parser == null || parser.isBlank()) parser = "unknown";
        }

        private ProviderCliOutput(String status,
                                  String outputText,
                                  String errorText,
                                  String sessionId,
                                  Integer exitCode,
                                  String version,
                                  String parser) {
            this(status, outputText, errorText, sessionId, exitCode, version, parser, false, null, null);
        }

        private ProviderCliOutput withExitCode(int value) {
            return new ProviderCliOutput(status, outputText, errorText, sessionId, value, version, parser,
                outputTruncated, outputTotalBytes, outputCaptureLimitBytes);
        }

        private ProviderCliOutput withOutputLimitExceeded(long totalBytes, int captureLimitBytes) {
            String message = "provider output too large, truncated at " + captureLimitBytes
                + " bytes (total bytes read " + totalBytes + ")";
            String boundedOutput = outputText == null ? "" : outputText;
            return new ProviderCliOutput(
                "failed",
                boundedOutput,
                errorText == null || errorText.isBlank() ? message : errorText,
                sessionId,
                exitCode,
                version,
                parser,
                true,
                totalBytes,
                captureLimitBytes
            );
        }
    }

    private static final class ProviderRunFiles implements Closeable {
        private final Path runDir;
        private final Path promptPath;
        private final Path stdoutPath;
        private final Path lastMessagePath;
        private final Path metadataPath;
        private final OutputStream stdout;
        private final boolean available;

        private ProviderRunFiles(Path runDir,
                                 Path promptPath,
                                 Path stdoutPath,
                                 Path lastMessagePath,
                                 Path metadataPath,
                                 OutputStream stdout,
                                 boolean available) {
            this.runDir = runDir;
            this.promptPath = promptPath;
            this.stdoutPath = stdoutPath;
            this.lastMessagePath = lastMessagePath;
            this.metadataPath = metadataPath;
            this.stdout = stdout;
            this.available = available;
        }

        private static ProviderRunFiles create(String providerId, String taskId, String workerId, ProviderCliPlan plan) {
            try {
                String executionId = "run-" + System.currentTimeMillis() + "-" + sanitizePathSegment(workerId);
                Path taskRunDir = ProviderRunFileSupport.providerRunRoot()
                    .resolve(sanitizePathSegment(providerId))
                    .resolve(sanitizePathSegment(taskId))
                    .toAbsolutePath()
                    .normalize();
                ProviderRunFileSupport.cleanupTaskRuns(taskRunDir, log);
                Path runDir = taskRunDir.resolve(executionId);
                Files.createDirectories(runDir);
                Path promptPath = runDir.resolve("prompt.txt");
                Path stdoutPath = runDir.resolve("stdout.log");
                Path lastMessagePath = runDir.resolve("last_message.md");
                Path metadataPath = runDir.resolve("metadata.json");
                String prompt = plan != null && plan.stdinPrompt() != null && !plan.stdinPrompt().isBlank()
                    ? plan.stdinPrompt()
                    : plan != null ? plan.promptPreview() : "";
                Files.writeString(promptPath, prompt == null ? "" : prompt, StandardCharsets.UTF_8);
                return new ProviderRunFiles(
                    runDir,
                    promptPath,
                    stdoutPath,
                    lastMessagePath,
                    metadataPath,
                    Files.newOutputStream(stdoutPath),
                    true
                );
            } catch (IOException e) {
                log.warn("Provider run files unavailable. provider={} worker={} reason={}", providerId, workerId, e.getMessage());
                return new ProviderRunFiles(null, null, null, null, null, OutputStream.nullOutputStream(), false);
            }
        }

        private static String sanitizePathSegment(String value) {
            return ProviderRunFileSupport.sanitizePathSegment(value);
        }

        private boolean available() {
            return available;
        }

        private Path runDir() {
            return runDir;
        }

        private Path promptPath() {
            return promptPath;
        }

        private Path stdoutPath() {
            return stdoutPath;
        }

        private Path lastMessagePath() {
            return lastMessagePath;
        }

        private Path metadataPath() {
            return metadataPath;
        }

        private OutputStream stdout() {
            return stdout;
        }

        private void writeLastMessage(String outputText) {
            if (!available || lastMessagePath == null) {
                return;
            }
            try {
                Files.writeString(lastMessagePath, outputText == null ? "" : outputText, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Failed to write provider last message file. path={} reason={}", lastMessagePath, e.getMessage());
            }
        }

        private void writeMetadata(Map<String, Object> metadata) {
            if (!available || metadataPath == null) {
                return;
            }
            try {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), metadata == null ? Map.of() : metadata);
            } catch (IOException e) {
                log.warn("Failed to write provider run metadata file. path={} reason={}", metadataPath, e.getMessage());
            }
        }

        private void closeQuietly() {
            try {
                close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() throws IOException {
            if (stdout != null) {
                stdout.close();
            }
        }
    }
}
