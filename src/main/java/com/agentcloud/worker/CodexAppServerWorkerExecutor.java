package com.agentcloud.worker;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.AgentProviderResolver;
import com.agentcloud.agent.AgentProviderStatus;
import com.agentcloud.agent.providers.LocalCliAgentProvider;
import com.agentcloud.agent.providers.LocalCliProviderConfig;
import com.agentcloud.agent.providers.ProviderDefaultProfile;
import com.agentcloud.agent.providers.ProviderProfileConfig;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Task;
import com.agentcloud.model.Worker;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Codex app-server 单轮执行器。
 * 对齐 multica 的 JSON-RPC 握手，但先收口为最小单轮能力。
 */
public class CodexAppServerWorkerExecutor implements WorkerExecutor {
    private static final Logger log = LoggerFactory.getLogger(CodexAppServerWorkerExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CODEX_EXEC_NO_ALT_SCREEN_FLAG = "--no-alt-screen";
    private static final long PROCESS_TIMEOUT_MS = 180_000L;
    private static final long APP_SERVER_SHUTDOWN_GRACE_MS = 5_000L;
    private static final long HANDSHAKE_TIMEOUT_MS = 30_000L;
    private static final long DEFAULT_TURN_ACTIVITY_TIMEOUT_MS = 180_000L;
    private static final long DEFAULT_TURN_MAX_DURATION_MS = 900_000L;
    private static final long DEFAULT_CODING_TURN_MAX_DURATION_MS = 900_000L;
    private static final int DEFAULT_PARTIAL_TIMEOUT_OUTPUT_THRESHOLD = 200;

    private final AgentProviderRegistry providerRegistry;
    private final WorkerRegistry workerRegistry;
    private final LocalCliProviderConfig providerConfig;
    private final Map<String, String> workspaceAliases;

    public CodexAppServerWorkerExecutor(AgentProviderRegistry providerRegistry, WorkerRegistry workerRegistry) {
        this(providerRegistry, workerRegistry, Map.of());
    }

    public CodexAppServerWorkerExecutor(AgentProviderRegistry providerRegistry, WorkerRegistry workerRegistry,
                                        Map<String, String> workspaceAliases) {
        this.providerRegistry = providerRegistry;
        this.workerRegistry = workerRegistry;
        this.providerConfig = resolveProviderConfig(providerRegistry);
        this.workspaceAliases = workspaceAliases == null ? Map.of() : workspaceAliases;
    }

    @Override
    public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
        String providerId = providerId(context, workerId);
        if (!supportsProvider(providerId)) {
            throw new IllegalArgumentException("codex app-server executor does not support provider: " + providerId);
        }

        Worker worker = lookupWorker(context, workerId);
        LocalCliProviderConfig.ResolvedConfig config = providerConfig.resolve();
        AgentProviderStatus providerStatus = providerStatus(providerId);
        String cwd = resolveWorkingDirectory(context, worker);
        if (shouldUseExecJsonMode()) {
            return executeExecJsonRound(context, workerId, providerId, config, providerStatus, cwd);
        }
        CodexExecutionPlan plan = buildPlan(config, context, cwd);
        ProviderRunFiles runFiles = ProviderRunFiles.create(providerId, taskId(context), workerId, plan);

        long startedAtMs = System.currentTimeMillis();
        Process process = null;
        CodexSessionOutput output;
        int partialOutputThreshold = partialTimeoutOutputThreshold();
        try {
            ProcessBuilder builder = new ProcessBuilder(plan.command())
                .directory(cwd == null || cwd.isBlank() ? null : Path.of(cwd).toFile())
                .redirectErrorStream(true);
            process = builder.start();
            output = runSession(process, plan, runFiles, partialOutputThreshold);
            if (process.waitFor(APP_SERVER_SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                output = output.withExitCode(process.exitValue());
            } else {
                process.destroy();
                if (!process.waitFor(APP_SERVER_SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startedAtMs;
            return failureResult("failed", "failed to start codex app-server: " + e.getMessage(),
                providerId, workerId, cwd, plan, providerStatus, durationMs, null, null, runFiles);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = System.currentTimeMillis() - startedAtMs;
            return failureResult("cancelled", "codex app-server interrupted",
                providerId, workerId, cwd, plan, providerStatus, durationMs, null, null, runFiles);
        } catch (IllegalStateException e) {
            long durationMs = System.currentTimeMillis() - startedAtMs;
            // Try to recover output from run files even on protocol error
            String partialOutput = recoverPartialOutput(runFiles);
            if (partialOutput != null && !partialOutput.isBlank()) {
                log.warn("Codex app-server protocol error but partial output available. worker={} error={}", workerId, e.getMessage());
                return failureResultWithOutput("partial_timeout", "codex protocol error: " + e.getMessage(),
                    partialOutput, providerId, workerId, cwd, plan, providerStatus, durationMs, null, null, runFiles);
            }
            return failureResult("failed", e.getMessage(),
                providerId, workerId, cwd, plan, providerStatus, durationMs, null, null, runFiles);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            runFiles.closeQuietly();
        }

        long durationMs = System.currentTimeMillis() - startedAtMs;
        String normalizedStatus = normalizeAppServerStatus(output.status());
        LinkedHashMap<String, Object> metadata = baseMetadata(providerId, workerId, cwd, plan, providerStatus, durationMs);
        appendRunFileMetadata(metadata, runFiles);
        if (output.exitCode() != null) {
            metadata.put("exit_code", output.exitCode());
        }
        metadata.put("provider_output_parser", output.protocol());
        metadata.put("tool_invocation_count", output.toolInvocationCount());
        if (output.threadId() != null && !output.threadId().isBlank()) {
            metadata.put("provider_session_id", output.threadId());
            metadata.put("provider_thread_id", output.threadId());
        }
        if (!output.toolInvocationIds().isEmpty()) {
            metadata.put("tool_invocation_ids", output.toolInvocationIds());
        }
        if (!output.protocolTrace().isEmpty()) {
            metadata.put("provider_protocol_trace", output.protocolTrace());
        }
        if (output.turnStatus() != null && !output.turnStatus().isBlank()) {
            metadata.put("provider_turn_status", output.turnStatus());
        }
        if (output.timeoutKind() != null && !output.timeoutKind().isBlank()) {
            metadata.put("provider_timeout_kind", output.timeoutKind());
        }
        if (output.abortReason() != null && !output.abortReason().isBlank()) {
            metadata.put("provider_abort_reason", output.abortReason());
        }
        if (output.errorText() != null && !output.errorText().isBlank()) {
            metadata.put("provider_error", output.errorText());
        }
        String outputText = output.outputText() == null ? "" : output.outputText().trim();
        long activityTimeoutMs = turnActivityTimeoutMs();
        metadata.put("provider_turn_activity_timeout_ms", activityTimeoutMs);
        metadata.put("provider_activity_timeout_ms", activityTimeoutMs);
        metadata.put("provider_turn_max_duration_ms", turnMaxDurationMs(plan));
        metadata.put("partial_timeout_min_output_chars", partialOutputThreshold);
        metadata.put("partial_output", "partial_timeout".equals(normalizedStatus));
        metadata.put("partial_output_chars", outputText.length());
        attachProviderFailureClassification(metadata, normalizedStatus, output.errorText());
        String summary = summarize(outputText, output.errorText(), normalizedStatus);
        runFiles.writeLastMessage(outputText);
        runFiles.writeMetadata(metadata);

        log.info("Codex app-server round completed. worker={} status={} exitCode={} durationMs={} threadId={}",
            workerId, normalizedStatus, output.exitCode(), durationMs, output.threadId());

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
            normalizedStatus.equals("failed") && !outputText.isBlank() ? List.of("codex app-server output requires inspection") : List.of(),
            0,
            durationMs,
            Map.copyOf(metadata),
            outcomeFromStatus(normalizedStatus)
        );
    }

    private WorkerExecutionResult executeExecJsonRound(TaskRuntimeContext context,
                                                       String workerId,
                                                       String providerId,
                                                       LocalCliProviderConfig.ResolvedConfig config,
                                                       AgentProviderStatus providerStatus,
                                                       String cwd) {
        CodexExecutionPlan initialPlan = buildExecJsonPlan(config, context, cwd);
        ProviderRunFiles runFiles = ProviderRunFiles.create(providerId, taskId(context), workerId, initialPlan);
        if (!runFiles.available()) {
            return failureResult("failed", "codex exec_json run files unavailable",
                providerId, workerId, cwd, initialPlan, providerStatus, 0L, null, null, runFiles);
        }
        CodexExecutionPlan plan = initialPlan.withCommand(execJsonCommand(config, runFiles, initialPlan));
        long startedAtMs = System.currentTimeMillis();
        Process process = null;
        Integer exitCode = null;
        try {
            runFiles.closeQuietly();
            ProcessBuilder builder = new ProcessBuilder(plan.command())
                .directory(cwd == null || cwd.isBlank() ? null : Path.of(cwd).toFile())
                .redirectInput(runFiles.promptPath().toFile())
                .redirectOutput(runFiles.eventsPath().toFile())
                .redirectErrorStream(true);
            process = builder.start();
            long processTimeoutMs = turnMaxDurationMs(plan);
            if (!process.waitFor(processTimeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                long durationMs = System.currentTimeMillis() - startedAtMs;
                return failureResult("timeout", "codex exec --json timed out",
                    providerId, workerId, cwd, plan, providerStatus, durationMs, null, null, runFiles);
            }
            exitCode = process.exitValue();
        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startedAtMs;
            return failureResult("failed", "failed to start codex exec --json: " + e.getMessage(),
                providerId, workerId, cwd, plan, providerStatus, durationMs, exitCode, null, runFiles);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = System.currentTimeMillis() - startedAtMs;
            return failureResult("cancelled", "codex exec --json interrupted",
                providerId, workerId, cwd, plan, providerStatus, durationMs, exitCode, null, runFiles);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }

        long durationMs = System.currentTimeMillis() - startedAtMs;
        CodexExecJsonOutput output = consumeExecJson(runFiles, exitCode);
        String normalizedStatus = normalizeStatus(output.status(), exitCode);
        LinkedHashMap<String, Object> metadata = baseMetadata(providerId, workerId, cwd, plan, providerStatus, durationMs);
        appendRunFileMetadata(metadata, runFiles);
        metadata.put("exit_code", exitCode);
        metadata.put("provider_turn_max_duration_ms", turnMaxDurationMs(plan));
        metadata.put("provider_output_parser", "codex_exec_json");
        if (output.sessionId() != null && !output.sessionId().isBlank()) {
            metadata.put("provider_session_id", output.sessionId());
            metadata.put("provider_thread_id", output.sessionId());
        }
        if (output.errorText() != null && !output.errorText().isBlank()) {
            metadata.put("provider_error", output.errorText());
        }
        attachProviderFailureClassification(metadata, normalizedStatus, output.errorText());

        String outputText = output.outputText() == null ? "" : output.outputText().trim();
        String summary = summarize(outputText, output.errorText(), normalizedStatus);
        runFiles.writeMetadata(metadata);

        log.info("Codex exec_json round completed. worker={} status={} exitCode={} durationMs={} sessionId={}",
            workerId, normalizedStatus, exitCode, durationMs, output.sessionId());

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
            normalizedStatus.equals("failed") && !outputText.isBlank() ? List.of("codex exec_json output requires inspection") : List.of(),
            0,
            durationMs,
            Map.copyOf(metadata),
            outcomeFromStatus(normalizedStatus)
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
        return ProviderExecutionSupport.supportsProviderAppServer(providerId);
    }

    private CodexExecutionPlan buildPlan(LocalCliProviderConfig.ResolvedConfig config,
                                         TaskRuntimeContext context,
                                         String cwd) {
        String prompt = ProviderTaskPromptBuilder.build(context) + buildWorkspaceGuidance(cwd);
        String model = configuredModel(config, context);
        String resumeThreadId = resumeThreadId(context);
        ProviderProfileConfig profile = resolveProfile(config, context);
        LocalCliProviderConfig.LaunchSpec launchSpec = config.launchSpec();
        List<String> baseArgs = new ArrayList<>(List.of(
            "app-server",
            "--listen",
            "stdio://"
        ));
        List<String> command = launchSpec.command(appendProfileArgs(baseArgs, profile));
        return new CodexExecutionPlan(
            command,
            prompt,
            truncate(prompt, 240),
            profile.model().isBlank() ? model : profile.model(),
            cwd,
            resumeThreadId,
            systemPrompt(context),
            launchSpec.configuredBinary(),
            launchSpec.executableTarget(),
            launchSpec.launchMode(),
            "provider_app_server",
            profile.providerProfileId(),
            profile.modelProvider(),
            profile.cliProfile(),
            profile.configOverrides()
        );
    }

    private CodexExecutionPlan buildExecJsonPlan(LocalCliProviderConfig.ResolvedConfig config,
                                                 TaskRuntimeContext context,
                                                 String cwd) {
        String prompt = ProviderTaskPromptBuilder.build(context) + buildWorkspaceGuidance(cwd);
        String model = configuredModel(config, context);
        ProviderProfileConfig profile = resolveProfile(config, context);
        LocalCliProviderConfig.LaunchSpec launchSpec = config.launchSpec();
        return new CodexExecutionPlan(
            List.of(),
            prompt,
            truncate(prompt, 240),
            profile.model().isBlank() ? model : profile.model(),
            cwd,
            resumeThreadId(context),
            systemPrompt(context),
            launchSpec.configuredBinary(),
            launchSpec.executableTarget(),
            launchSpec.launchMode(),
            "provider_native_cli_json",
            profile.providerProfileId(),
            profile.modelProvider(),
            profile.cliProfile(),
            profile.configOverrides()
        );
    }

    private List<String> execJsonCommand(LocalCliProviderConfig.ResolvedConfig config,
                                         ProviderRunFiles runFiles,
                                         CodexExecutionPlan plan) {
        ArrayList<String> args = new ArrayList<>();
        args.add("exec");
        args.add(CODEX_EXEC_NO_ALT_SCREEN_FLAG);
        args.add("--json");
        args.add("-o");
        args.add(runFiles.lastMessagePath().toString());
        args.add("--skip-git-repo-check");
        return config.launchSpec().command(appendProfileArgs(args, toProfileConfig(plan)));
    }

    private boolean shouldUseExecJsonMode() {
        String mode = firstNonBlank(
            System.getProperty("agentcloud.providers.codex.execution_mode"),
            System.getenv("AGENTCLOUD_CODEX_EXECUTION_MODE"),
            "app_server"
        );
        return "exec_json".equalsIgnoreCase(mode.trim());
    }

    private CodexSessionOutput runSession(Process process,
                                          CodexExecutionPlan plan,
                                          ProviderRunFiles runFiles,
                                          int partialOutputThreshold)
        throws IOException, InterruptedException {
        try (Writer writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
             BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            JsonRpcSession session = new JsonRpcSession(writer, reader, runFiles.events(), partialOutputThreshold);
            try {
                session.request("initialize", Map.of(
                    "clientInfo", Map.of(
                        "name", "agent-cloud-harness",
                        "title", "Agent Cloud Harness",
                        "version", "0.2.0"
                    ),
                    "capabilities", Map.of(
                        "experimentalApi", true
                    )
                ));
                session.notify("initialized");
                String threadId = startOrResumeThread(session, plan);
                String turnStatus = startTurn(session, threadId, plan.prompt());
                String terminalTurnStatus = session.awaitTurnCompletion(
                    turnStatus,
                    turnActivityTimeoutMs(),
                    turnMaxDurationMs(plan),
                    partialOutputThreshold
                );
                return session.toOutput(threadId, terminalTurnStatus);
            } catch (IOException | IllegalStateException e) {
                CodexSessionOutput partial = session.partialFailureOutput(e.getMessage());
                if (partial != null) {
                    return partial;
                }
                throw e;
            }
        }
    }

    private String startOrResumeThread(JsonRpcSession session, CodexExecutionPlan plan)
        throws IOException, InterruptedException {
        String threadId = plan.resumeThreadId();
        if (threadId != null && !threadId.isBlank()) {
            try {
                LinkedHashMap<String, Object> params = new LinkedHashMap<>();
                params.put("threadId", threadId);
                params.put("cwd", plan.cwd());
                params.put("model", nilIfBlank(plan.model()));
                params.put("developerInstructions", nilIfBlank(plan.systemPrompt()));
                JsonNode resumed = session.request("thread/resume", params);
                String resumedThreadId = extractThreadId(resumed);
                if (resumedThreadId != null && !resumedThreadId.isBlank()) {
                    return resumedThreadId;
                }
            } catch (IllegalStateException e) {
                log.info("Codex thread/resume failed, falling back to thread/start. threadId={} reason={}",
                    threadId, e.getMessage());
            }
        }

        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("model", nilIfBlank(plan.model()));
        params.put("modelProvider", nilIfBlank(plan.modelProvider()));
        params.put("profile", nilIfBlank(plan.cliProfile()));
        params.put("cwd", plan.cwd());
        params.put("approvalPolicy", null);
        params.put("sandbox", null);
        params.put("config", buildConfigParam(plan));
        params.put("baseInstructions", null);
        params.put("developerInstructions", nilIfBlank(plan.systemPrompt()));
        params.put("compactPrompt", null);
        params.put("includeApplyPatchTool", null);
        params.put("experimentalRawEvents", false);
        params.put("persistExtendedHistory", true);

        JsonNode started = session.request("thread/start", params);
        String startedThreadId = extractThreadId(started);
        if (startedThreadId == null || startedThreadId.isBlank()) {
            throw new IllegalStateException("codex thread/start returned no thread ID");
        }
        return startedThreadId;
    }

    private String startTurn(JsonRpcSession session, String threadId, String prompt)
        throws IOException, InterruptedException {
        JsonNode result = session.request("turn/start", Map.of(
            "threadId", threadId,
            "input", List.of(Map.of(
                "type", "text",
                "text", prompt
            ))
        ));
        return extractTurnStatus(result);
    }

    private String extractThreadId(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return firstNonBlank(
            text(node, "threadId"),
            nestedText(node, "thread", "id"),
            nestedText(node, "data", "threadId"),
            nestedText(node, "data", "thread", "id")
        );
    }

    private String extractTurnStatus(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "completed";
        }
        return firstNonBlank(
            text(node, "status"),
            nestedText(node, "turn", "status"),
            nestedText(node, "data", "status"),
            "completed"
        );
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

    private AgentProviderStatus providerStatus(String providerId) {
        return providerRegistry != null ? providerRegistry.status(providerId) : null;
    }

    private LocalCliProviderConfig resolveProviderConfig(AgentProviderRegistry providerRegistry) {
        if (providerRegistry != null) {
            AgentProvider provider = providerRegistry.get("codex");
            if (provider instanceof LocalCliAgentProvider localCliProvider) {
                return localCliProvider.cliConfig();
            }
        }
        return new LocalCliProviderConfig("codex", "codex", "MULTICA_CODEX_PATH", "MULTICA_CODEX_MODEL");
    }

    private String taskId(TaskRuntimeContext context) {
        return context != null && context.task() != null && context.task().id() != null && !context.task().id().isBlank()
            ? context.task().id()
            : "unknown_task";
    }

    private String configuredModel(LocalCliProviderConfig.ResolvedConfig config, TaskRuntimeContext context) {
        String taskModel = context == null || context.task() == null ? null : metadataString(context.task().metadata(), "provider_model");
        if (taskModel != null && !taskModel.isBlank()) {
            return taskModel;
        }
        return config.model().value();
    }

    /**
     * 解析 codex profile 配置，优先级：task metadata > worker metadata > provider 默认值。
     */
    private ProviderProfileConfig resolveProfile(LocalCliProviderConfig.ResolvedConfig config, TaskRuntimeContext context) {
        // 1. Provider 级默认值
        ProviderProfileConfig providerDefault = resolveProviderDefaultProfile();
        // 2. Worker metadata profile
        ProviderProfileConfig workerProfile = resolveWorkerProfile(context);
        // 3. Task metadata profile
        ProviderProfileConfig taskProfile = resolveTaskProfile(context);
        // 合并：task > worker > provider default
        return providerDefault.merge(workerProfile).merge(taskProfile);
    }

    private ProviderProfileConfig resolveProviderDefaultProfile() {
        AgentProvider provider = providerRegistry != null ? providerRegistry.get("codex") : null;
        if (provider instanceof LocalCliAgentProvider localCliProvider) {
            ProviderDefaultProfile defaultProfile = localCliProvider.resolveDefaultProfile();
            return defaultProfile.toProfileConfig();
        }
        return new ProviderProfileConfig("", "", "", "", Map.of());
    }

    private ProviderProfileConfig resolveWorkerProfile(TaskRuntimeContext context) {
        String workerId = context != null && context.task() != null ? context.task().assignedWorker() : null;
        if (workerId == null || workerId.isBlank()) {
            return new ProviderProfileConfig("", "", "", "", Map.of());
        }
        Worker worker = workerRegistry != null ? workerRegistry.get(workerId) : null;
        if (worker == null) {
            return new ProviderProfileConfig("", "", "", "", Map.of());
        }
        return ProviderProfileConfig.fromWorkerMetadata(worker.metadata());
    }

    private ProviderProfileConfig resolveTaskProfile(TaskRuntimeContext context) {
        if (context == null || context.task() == null) {
            return new ProviderProfileConfig("", "", "", "", Map.of());
        }
        return ProviderProfileConfig.fromTaskMetadata(context.task().metadata());
    }

    /**
     * 将 profile 配置追加到 CLI 启动参数（-c/-m/-p）。
     */
    private List<String> appendProfileArgs(List<String> args, ProviderProfileConfig profile) {
        if (profile == null || !profile.hasSubstantiveConfig()) {
            return args;
        }
        ArrayList<String> result = new ArrayList<>(args);
        if (!profile.modelProvider().isBlank()) {
            result.add("-c");
            result.add("model_provider=" + profile.modelProvider());
        }
        if (!profile.model().isBlank()) {
            result.add("-m");
            result.add(profile.model());
        }
        if (!profile.cliProfile().isBlank()) {
            result.add("-p");
            result.add(profile.cliProfile());
        }
        if (profile.configOverrides() != null && !profile.configOverrides().isEmpty()) {
            for (Map.Entry<String, String> entry : profile.configOverrides().entrySet()) {
                result.add("-c");
                result.add(entry.getKey() + "=" + entry.getValue());
            }
        }
        return result;
    }

    /**
     * 将 profile configOverrides 构建 thread/start 的 config 参数。
     */
    private Map<String, Object> buildConfigParam(CodexExecutionPlan plan) {
        if (plan.configOverrides() == null || plan.configOverrides().isEmpty()) {
            return null;
        }
        LinkedHashMap<String, Object> config = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : plan.configOverrides().entrySet()) {
            config.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(config);
    }

    /**
     * 从 CodexExecutionPlan 提取 profile 配置并追加到 CLI 参数。
     */
    private List<String> appendProfileArgs(List<String> args, CodexExecutionPlan plan) {
        if (plan == null || !plan.hasProfileConfig()) {
            return args;
        }
        ArrayList<String> result = new ArrayList<>(args);
        if (!plan.modelProvider().isBlank()) {
            result.add("-c");
            result.add("model_provider=" + plan.modelProvider());
        }
        if (!plan.model().isBlank()) {
            result.add("-m");
            result.add(plan.model());
        }
        if (!plan.cliProfile().isBlank()) {
            result.add("-p");
            result.add(plan.cliProfile());
        }
        if (plan.configOverrides() != null && !plan.configOverrides().isEmpty()) {
            for (Map.Entry<String, String> entry : plan.configOverrides().entrySet()) {
                result.add("-c");
                result.add(entry.getKey() + "=" + entry.getValue());
            }
        }
        return result;
    }

    private ProviderProfileConfig toProfileConfig(CodexExecutionPlan plan) {
        if (plan == null) {
            return new ProviderProfileConfig("", "", "", "", Map.of());
        }
        return new ProviderProfileConfig(
            plan.providerProfileId(),
            plan.modelProvider(),
            plan.model(),
            plan.cliProfile(),
            plan.configOverrides()
        );
    }

    private String systemPrompt(TaskRuntimeContext context) {
        String metadataPrompt = metadataString(context == null || context.task() == null ? null : context.task().metadata(), "system_prompt");
        if (metadataPrompt != null && !metadataPrompt.isBlank()) {
            return metadataPrompt;
        }
        return ProviderTaskPromptBuilder.defaultSystemPrompt("Codex");
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
        }
        if (worker != null && worker.toolScope() != null && !worker.toolScope().isEmpty()) {
            String scope = worker.toolScope().get(0);
            if (scope != null && !scope.isBlank()) {
                return scope;
            }
        }
        if (!workspaceAliases.isEmpty()) {
            String neutral = neutralWorkspaceDir();
            if (neutral != null) {
                return neutral;
            }
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().toString();
    }

    private String neutralWorkspaceDir() {
        if (workspaceAliases.isEmpty()) {
            return null;
        }
        String first = workspaceAliases.values().iterator().next();
        if (first == null || first.isBlank()) {
            return null;
        }
        java.nio.file.Path parent = java.nio.file.Path.of(first).getParent();
        return parent == null ? null : parent.toAbsolutePath().normalize().toString();
    }

    private String buildWorkspaceGuidance(String cwd) {
        if (workspaceAliases.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nAvailable Workspaces:");
        for (var entry : workspaceAliases.entrySet()) {
            sb.append("\n- ").append(entry.getKey()).append(" -> ").append(entry.getValue());
        }
        sb.append("\n\nWorkspace Boundary:");
        sb.append("\n- Identify which workspace above matches this task, then cd into that directory before doing any work.");
        sb.append("\n- Work only inside the target repository; do NOT read or modify the harness repository's own STATE.md / docs / source code.");
        if (cwd != null && !cwd.isBlank()) {
            sb.append("\n- Current working directory: ").append(cwd).append(" (neutral start; cd to the target workspace if not already there).");
        }
        return sb.toString();
    }

    private String singleWorkspaceRoot(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object raw = metadata.get("workspace_roots");
        if (raw == null) {
            return null;
        }
        java.util.ArrayList<String> roots = new java.util.ArrayList<>();
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

    private void addWorkspaceRoot(java.util.List<String> roots, Object raw) {
        if (raw == null) {
            return;
        }
        String value = raw.toString().trim();
        if (!value.isBlank() && !roots.contains(value)) {
            roots.add(value);
        }
    }

    private String resumeThreadId(TaskRuntimeContext context) {
        if (context == null || context.task() == null) {
            return null;
        }
        String recoveryStage = metadataString(context.task().metadata(), "recovery_stage");
        if ("same_worker_retry_scheduled".equalsIgnoreCase(recoveryStage)
            || "auto_handoff_scheduled".equalsIgnoreCase(recoveryStage)) {
            return null;
        }
        return firstNonBlank(
            metadataString(context.task().metadata(), "codex_thread_id"),
            metadataString(context.task().metadata(), "provider_thread_id"),
            metadataString(context.task().metadata(), "provider_session_id"),
            metadataString(context.task().metadata(), "resume_provider_session_id")
        );
    }

    private LinkedHashMap<String, Object> baseMetadata(String providerId,
                                                       String workerId,
                                                       String cwd,
                                                       CodexExecutionPlan plan,
                                                       AgentProviderStatus providerStatus,
                                                       long durationMs) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider_id", providerId);
        metadata.put("selected_worker", workerId);
        metadata.put("execution_backend", plan.executionBackend());
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
        if (plan.resumeThreadId() != null && !plan.resumeThreadId().isBlank()) {
            metadata.put("resume_provider_session_id", plan.resumeThreadId());
        }
        // profile trace
        if (plan.providerProfileId() != null && !plan.providerProfileId().isBlank()) {
            metadata.put("selected_provider_profile", plan.providerProfileId());
        }
        if (plan.modelProvider() != null && !plan.modelProvider().isBlank()) {
            metadata.put("configured_model_provider", plan.modelProvider());
        }
        if (plan.cliProfile() != null && !plan.cliProfile().isBlank()) {
            metadata.put("configured_cli_profile", plan.cliProfile());
        }
        if (plan.configOverrides() != null && !plan.configOverrides().isEmpty()) {
            metadata.put("configured_config_overrides", Map.copyOf(plan.configOverrides()));
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

    private CodexExecJsonOutput consumeExecJson(ProviderRunFiles runFiles, Integer exitCode) {
        String lastMessage = readFile(runFiles == null ? null : runFiles.lastMessagePath());
        String status = exitCode != null && exitCode == 0 ? "completed" : "failed";
        String sessionId = null;
        String errorText = null;
        StringBuilder fallbackOutput = new StringBuilder();
        Path eventsPath = runFiles == null ? null : runFiles.eventsPath();
        if (eventsPath != null && Files.isRegularFile(eventsPath)) {
            try (BufferedReader reader = Files.newBufferedReader(eventsPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isBlank()) {
                        continue;
                    }
                    try {
                        JsonNode node = MAPPER.readTree(trimmed);
                        sessionId = firstNonBlank(sessionId, extractThreadId(node), text(node, "session_id"), text(node, "sessionId"));
                        status = firstNonBlank(statusFromExecJsonEvent(node), status);
                        String eventError = firstNonBlank(
                            text(node, "error"),
                            text(node, "message"),
                            nestedText(node, "error", "message")
                        );
                        if (eventError != null && looksLikeErrorEvent(node)) {
                            errorText = firstNonBlank(errorText, eventError);
                        }
                        appendFallbackOutput(fallbackOutput, node);
                    } catch (Exception ignored) {
                        if (exitCode != null && exitCode != 0) {
                            errorText = firstNonBlank(errorText, trimmed);
                        }
                    }
                }
            } catch (IOException e) {
                errorText = firstNonBlank(errorText, "failed to read codex exec_json events: " + e.getMessage());
            }
        }
        String outputText = firstNonBlank(lastMessage, fallbackOutput.toString().trim());
        if ((outputText == null || outputText.isBlank()) && exitCode != null && exitCode != 0) {
            outputText = "";
            errorText = firstNonBlank(errorText, "codex exec_json exited with code " + exitCode);
        }
        return new CodexExecJsonOutput(status, outputText, errorText, sessionId);
    }

    private String readFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private String statusFromExecJsonEvent(JsonNode node) {
        String raw = firstNonBlank(
            text(node, "status"),
            nestedText(node, "data", "status"),
            nestedText(node, "turn", "status")
        );
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "completed", "success", "succeeded" -> "completed";
            case "failed", "error" -> "failed";
            case "cancelled", "canceled", "aborted" -> "cancelled";
            case "timeout" -> "timeout";
            default -> null;
        };
    }

    private boolean looksLikeErrorEvent(JsonNode node) {
        String type = firstNonBlank(text(node, "type"), text(node, "event"), text(node, "kind"));
        return type != null && type.toLowerCase(Locale.ROOT).contains("error");
    }

    private void appendFallbackOutput(StringBuilder output, JsonNode node) {
        String type = firstNonBlank(text(node, "type"), text(node, "event"), text(node, "kind"));
        String text = firstNonBlank(
            text(node, "text"),
            nestedText(node, "message", "content"),
            nestedText(node, "data", "text"),
            nestedText(node, "data", "message")
        );
        if (text == null || text.isBlank()) {
            return;
        }
        if (type != null) {
            String normalizedType = type.toLowerCase(Locale.ROOT);
            if (!normalizedType.contains("message") && !normalizedType.contains("answer") && !normalizedType.contains("output")) {
                return;
            }
        }
        if (!output.isEmpty()) {
            output.append('\n');
        }
        output.append(text.trim());
    }

    private void appendRunFileMetadata(Map<String, Object> metadata, ProviderRunFiles runFiles) {
        if (metadata == null || runFiles == null || !runFiles.available()) {
            return;
        }
        metadata.put("provider_run_dir", runFiles.runDir().toString());
        metadata.put("provider_prompt_path", runFiles.promptPath().toString());
        metadata.put("provider_event_log_path", runFiles.eventsPath().toString());
        metadata.put("provider_last_message_path", runFiles.lastMessagePath().toString());
        metadata.put("provider_run_metadata_path", runFiles.metadataPath().toString());
    }

    private WorkerExecutionResult failureResult(String status,
                                                String errorText,
                                                String providerId,
                                                String workerId,
                                                String cwd,
                                                CodexExecutionPlan plan,
                                                AgentProviderStatus providerStatus,
                                                long durationMs,
                                                Integer exitCode,
                                                String threadId,
                                                ProviderRunFiles runFiles) {
        LinkedHashMap<String, Object> metadata = baseMetadata(providerId, workerId, cwd, plan, providerStatus, durationMs);
        metadata.put("provider_error", errorText);
        metadata.put("provider_output_parser", outputParserFor(plan));
        if ("provider_native_cli_json".equalsIgnoreCase(plan.executionBackend())) {
            metadata.put("provider_turn_max_duration_ms", turnMaxDurationMs(plan));
        }
        appendRunFileMetadata(metadata, runFiles);
        attachProviderFailureClassification(metadata, status, errorText);
        if (exitCode != null) {
            metadata.put("exit_code", exitCode);
        }
        if (threadId != null && !threadId.isBlank()) {
            metadata.put("provider_session_id", threadId);
            metadata.put("provider_thread_id", threadId);
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
            Map.copyOf(metadata),
            ExecutionOutcome.FAILED
        );
    }

    private String recoverPartialOutput(ProviderRunFiles runFiles) {
        if (runFiles == null || !runFiles.available()) {
            return null;
        }
        try {
            String lastMessage = Files.readString(runFiles.lastMessagePath());
            return lastMessage != null && !lastMessage.isBlank() ? lastMessage : null;
        } catch (Exception e) {
            return null;
        }
    }

    private WorkerExecutionResult failureResultWithOutput(String status,
                                                          String errorText,
                                                          String outputText,
                                                          String providerId,
                                                          String workerId,
                                                          String cwd,
                                                          CodexExecutionPlan plan,
                                                          AgentProviderStatus providerStatus,
                                                          long durationMs,
                                                          Integer exitCode,
                                                          String threadId,
                                                          ProviderRunFiles runFiles) {
        LinkedHashMap<String, Object> metadata = baseMetadata(providerId, workerId, cwd, plan, providerStatus, durationMs);
        metadata.put("provider_error", errorText);
        metadata.put("provider_output_parser", outputParserFor(plan));
        metadata.put("partial_output", true);
        metadata.put("partial_output_chars", outputText != null ? outputText.length() : 0);
        if ("provider_native_cli_json".equalsIgnoreCase(plan.executionBackend())) {
            metadata.put("provider_turn_max_duration_ms", turnMaxDurationMs(plan));
        }
        appendRunFileMetadata(metadata, runFiles);
        attachProviderFailureClassification(metadata, "partial_timeout", errorText);
        if (exitCode != null) {
            metadata.put("exit_code", exitCode);
        }
        if (threadId != null && !threadId.isBlank()) {
            metadata.put("provider_session_id", threadId);
            metadata.put("provider_thread_id", threadId);
        }
        if (runFiles != null) {
            runFiles.writeLastMessage(outputText != null ? outputText : "");
            runFiles.writeMetadata(metadata);
        }
        return new WorkerExecutionResult(
            summarize(outputText, errorText, "partial_timeout"),
            outputText != null ? outputText : "",
            false,
            "",
            "",
            "",
            "medium",
            "partial_timeout",
            List.of(),
            List.of(),
            0,
            durationMs,
            Map.copyOf(metadata),
            ExecutionOutcome.COMPLETED_PARTIAL
        );
    }

    private String outputParserFor(CodexExecutionPlan plan) {
        return plan != null && "provider_native_cli_json".equalsIgnoreCase(plan.executionBackend())
            ? "codex_exec_json"
            : "codex_json_rpc";
    }

    private String summarize(String outputText, String errorText, String status) {
        String base = firstNonBlank(outputText, errorText, status);
        if (base == null) {
            return "";
        }
        String normalized = base.replaceAll("\\s+", " ").trim();
        return normalized.length() > 240 ? normalized.substring(0, 240) + "..." : normalized;
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
            case "completed", "failed", "cancelled", "timeout", "partial_timeout" -> value;
            case "aborted", "canceled", "interrupted" -> "cancelled";
            case "error" -> "failed";
            default -> "completed";
        };
    }

    private String normalizeAppServerStatus(String rawStatus) {
        String value = rawStatus == null || rawStatus.isBlank() ? "completed" : rawStatus.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "completed", "failed", "cancelled", "timeout", "partial_timeout" -> value;
            case "aborted", "canceled", "interrupted" -> "cancelled";
            case "error" -> "failed";
            default -> "completed";
        };
    }

    private ExecutionOutcome outcomeFromStatus(String status) {
        if (status == null) {
            return ExecutionOutcome.COMPLETED;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "partial_timeout" -> ExecutionOutcome.COMPLETED_PARTIAL;
            case "completed" -> ExecutionOutcome.COMPLETED;
            default -> ExecutionOutcome.FAILED;
        };
    }

    private long turnActivityTimeoutMs() {
        return longProperty(
            List.of("agentcloud.providers.codex.turn_activity_timeout_ms", "agentcloud.codex.turnActivityTimeoutMs"),
            List.of("AGENTCLOUD_CODEX_TURN_ACTIVITY_TIMEOUT_MS"),
            DEFAULT_TURN_ACTIVITY_TIMEOUT_MS
        );
    }

    private long turnMaxDurationMs(CodexExecutionPlan plan) {
        if (looksLikeCodingPlan(plan)) {
            return longProperty(
                List.of("agentcloud.providers.codex.coding_turn_max_duration_ms", "agentcloud.codex.codingTurnMaxDurationMs"),
                List.of("AGENTCLOUD_CODEX_CODING_TURN_MAX_DURATION_MS"),
                longProperty(
                    List.of("agentcloud.providers.codex.turn_max_duration_ms", "agentcloud.codex.turnMaxDurationMs"),
                    List.of("AGENTCLOUD_CODEX_TURN_MAX_DURATION_MS"),
                    DEFAULT_CODING_TURN_MAX_DURATION_MS
                )
            );
        }
        return longProperty(
            List.of("agentcloud.providers.codex.turn_max_duration_ms", "agentcloud.codex.turnMaxDurationMs"),
            List.of("AGENTCLOUD_CODEX_TURN_MAX_DURATION_MS"),
            DEFAULT_TURN_MAX_DURATION_MS
        );
    }

    private int partialTimeoutOutputThreshold() {
        long value = longProperty(
            List.of("agentcloud.providers.codex.partial_timeout_min_output_chars", "agentcloud.codex.partialTimeoutMinOutputChars"),
            List.of("AGENTCLOUD_CODEX_PARTIAL_TIMEOUT_MIN_OUTPUT_CHARS"),
            DEFAULT_PARTIAL_TIMEOUT_OUTPUT_THRESHOLD
        );
        return value > Integer.MAX_VALUE ? DEFAULT_PARTIAL_TIMEOUT_OUTPUT_THRESHOLD : (int) value;
    }

    private boolean looksLikeCodingPlan(CodexExecutionPlan plan) {
        String prompt = plan == null ? null : plan.prompt();
        if (prompt == null || prompt.isBlank()) {
            return true;
        }
        String lower = prompt.toLowerCase(Locale.ROOT);
        return lower.contains("task type: coding")
            || lower.contains("task type: research")
            || lower.contains("task type: investigation")
            || lower.contains("workspace")
            || lower.contains("代码")
            || lower.contains("code")
            || lower.contains("repo")
            || lower.contains("debug")
            || lower.contains("fix");
    }

    private long longProperty(List<String> systemKeys, List<String> envKeys, long defaultValue) {
        String raw = firstNonBlank(
            systemKeys == null ? null : systemKeys.stream()
                .map(System::getProperty)
                .map(CodexAppServerWorkerExecutor::blankToNull)
                .filter(value -> value != null)
                .findFirst()
                .orElse(null),
            envKeys == null ? null : envKeys.stream()
                .map(System::getenv)
                .map(CodexAppServerWorkerExecutor::blankToNull)
                .filter(value -> value != null)
                .findFirst()
                .orElse(null)
        );
        if (raw == null) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "...";
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        return ProviderTaskPromptBuilder.metadataString(metadata, key);
    }

    private static String text(JsonNode node, String field) {
        return node == null || field == null ? null : blankToNull(node.path(field).asText(""));
    }

    private static String nestedText(JsonNode node, String... path) {
        JsonNode current = node;
        for (String step : path) {
            if (current == null || step == null) {
                return null;
            }
            current = current.path(step);
        }
        return current == null ? null : blankToNull(current.asText(""));
    }

    private String nilIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void putIfNonBlank(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    private static final class JsonRpcSession {
        private final Writer writer;
        private final BufferedReader reader;
        private final OutputStream events;
        private final StringBuilder output = new StringBuilder();
        private final List<String> protocolTrace = new ArrayList<>();
        private final Set<String> toolInvocationIds = new LinkedHashSet<>();
        private int nextId;
        private int toolInvocationCount;
        private String threadId;
        private String turnStatus = "unknown";
        private String errorText;
        private String protocol = "codex_json_rpc";
        private String timeoutKind;
        private String abortReason;
        private final int partialOutputThreshold;
        private long lastActivityAtMs = System.currentTimeMillis();

        private JsonRpcSession(Writer writer, BufferedReader reader, OutputStream events) {
            this(writer, reader, events, DEFAULT_PARTIAL_TIMEOUT_OUTPUT_THRESHOLD);
        }

        private JsonRpcSession(Writer writer, BufferedReader reader, OutputStream events, int partialOutputThreshold) {
            this.writer = writer;
            this.reader = reader;
            this.events = events == null ? OutputStream.nullOutputStream() : events;
            this.partialOutputThreshold = Math.max(1, partialOutputThreshold);
        }

        private JsonNode request(String method, Map<String, Object> params) throws IOException, InterruptedException {
            int id = ++nextId;
            sendObject(Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", method,
                "params", params == null ? Map.of() : params
            ));
            long deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                JsonNode envelope = nextEnvelope(deadline);
                if (envelope == null) {
                    break;
                }
                JsonNode responseId = envelope.get("id");
                if (responseId != null && responseId.isInt() && responseId.asInt() == id) {
                    JsonNode error = envelope.get("error");
                    if (error != null && !error.isNull()) {
                        throw new IllegalStateException(method + ": " + firstNonBlank(
                            text(error, "message"),
                            error.toString()
                        ));
                    }
                    JsonNode result = envelope.path("result");
                    updateThreadId(result);
                    return result;
                }
                handleEnvelope(envelope);
            }
            throw new IllegalStateException(method + ": timed out waiting for response");
        }

        private void notify(String method) throws IOException {
            sendObject(Map.of(
                "jsonrpc", "2.0",
                "method", method
            ));
        }

        private void sendObject(Object payload) throws IOException {
            String line = MAPPER.writeValueAsString(payload);
            writeEvent("harness_send", line);
            writer.write(line);
            writer.write("\n");
            writer.flush();
        }

        private JsonNode nextEnvelope(long deadlineAtMs) throws IOException, InterruptedException {
            while (System.currentTimeMillis() < deadlineAtMs) {
                if (!reader.ready()) {
                    TimeUnit.MILLISECONDS.sleep(10L);
                    continue;
                }
                String line = reader.readLine();
                if (line == null) {
                    return null;
                }
                String trimmed = line.trim();
                if (trimmed.isBlank()) {
                    continue;
                }
                markActivity();
                writeEvent("provider_recv", trimmed);
                try {
                    return MAPPER.readTree(trimmed);
                } catch (Exception ignored) {
                    appendOutput(trimmed);
                }
            }
            return null;
        }

        private void writeEvent(String direction, String line) throws IOException {
            if (line == null || line.isBlank()) {
                return;
            }
            LinkedHashMap<String, Object> event = new LinkedHashMap<>();
            event.put("direction", direction);
            event.put("line", line);
            events.write(MAPPER.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
            events.write('\n');
        }

        private void handleEnvelope(JsonNode envelope) throws IOException {
            if (envelope == null || envelope.isMissingNode() || envelope.isNull()) {
                return;
            }
            JsonNode errorNode = envelope.get("error");
            if (errorNode != null && !errorNode.isNull()) {
                String message = firstNonBlank(text(errorNode, "message"), errorNode.toString());
                errorText = firstNonBlank(errorText, message);
                abortReason = firstNonBlank(abortReason, message);
                turnStatus = hasPartialOutput(partialOutputThreshold) ? "partial_timeout" : "failed";
                return;
            }
            JsonNode methodNode = envelope.get("method");
            JsonNode idNode = envelope.get("id");
            if (methodNode != null && !methodNode.isNull() && idNode != null && !idNode.isNull()) {
                handleServerRequest(envelope, methodNode.asText(""), idNode.asInt());
                return;
            }
            if (methodNode != null && !methodNode.isNull()) {
                handleNotification(methodNode.asText(""), envelope.path("params"));
                return;
            }
            JsonNode result = envelope.get("result");
            if (result != null && !result.isNull()) {
                updateThreadId(result);
            }
        }

        private void updateThreadId(JsonNode result) {
            threadId = firstNonBlank(threadId,
                text(result, "threadId"),
                nestedText(result, "thread", "id"),
                nestedText(result, "data", "threadId"),
                nestedText(result, "data", "thread", "id"));
        }

        private void handleServerRequest(JsonNode envelope, String method, int id) throws IOException {
            protocolTrace.add(method);
            switch (method) {
                case "item/commandExecution/requestApproval", "execCommandApproval" -> sendObject(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of("decision", "accept")
                ));
                case "item/fileChange/requestApproval", "applyPatchApproval" -> sendObject(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of("decision", "accept")
                ));
                case "mcpServer/elicitation/request" -> {
                    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                    result.put("action", "accept");
                    result.put("content", null);
                    result.put("_meta", null);
                    sendObject(Map.of(
                        "jsonrpc", "2.0",
                        "id", id,
                        "result", result
                    ));
                }
                default -> sendObject(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "error", Map.of(
                        "code", -32601,
                        "message", "unhandled server request: " + method
                    )
                ));
            }
        }

        private void handleNotification(String method, JsonNode params) {
            String notificationThreadId = firstNonBlank(
                text(params, "threadId"),
                nestedText(params, "thread", "id")
            );
            if (threadId != null && !threadId.isBlank()
                && notificationThreadId != null && !notificationThreadId.isBlank()
                && !threadId.equals(notificationThreadId)) {
                return;
            }
            protocolTrace.add(method);
            threadId = firstNonBlank(threadId,
                notificationThreadId);
            if ("codex/event".equals(method) || method.startsWith("codex/event/")) {
                protocol = "codex_legacy_event";
                handleLegacyEvent(params.path("msg"));
                return;
            }
            protocol = "codex_json_rpc";
            switch (method) {
                case "turn/started" -> turnStatus = "running";
                case "turn/completed" -> {
                    turnStatus = firstNonBlank(nestedText(params, "turn", "status"), "completed");
                    if ("failed".equalsIgnoreCase(turnStatus)) {
                        errorText = firstNonBlank(errorText, nestedText(params, "turn", "error", "message"), "codex turn failed");
                    }
                }
                case "thread/status/changed" -> {
                    String statusType = nestedText(params, "status", "type");
                    if ("idle".equalsIgnoreCase(statusType) && "running".equalsIgnoreCase(turnStatus)) {
                        turnStatus = "completed";
                    }
                }
                case "error" -> {
                    boolean willRetry = params.path("willRetry").asBoolean(false);
                    String message = firstNonBlank(nestedText(params, "error", "message"), text(params, "message"));
                    if (!willRetry) {
                        errorText = firstNonBlank(errorText, message);
                    }
                }
                default -> {
                    if (method.startsWith("item/")) {
                        handleItemNotification(method, params.path("item"));
                    }
                }
            }
        }

        private String awaitTurnCompletion(String requestTurnStatus,
                                           long activityTimeoutMs,
                                           long maxDurationMs,
                                           int partialOutputThreshold) throws IOException, InterruptedException {
            if (isTerminalStatus(requestTurnStatus)) {
                turnStatus = requestTurnStatus;
                return turnStatus;
            }
            long startedAtMs = System.currentTimeMillis();
            markActivity();
            // 最大时长是硬上限；活动超时只控制“多久无事件算卡死”，不能延长硬上限。
            long hardDeadline = startedAtMs + Math.max(1L, maxDurationMs);
            while (System.currentTimeMillis() < hardDeadline) {
                long now = System.currentTimeMillis();
                long idleDeadline = lastActivityAtMs + activityTimeoutMs;
                long pollDeadline = Math.min(hardDeadline, idleDeadline);
                JsonNode envelope = nextEnvelope(pollDeadline);
                if (envelope == null) {
                    if (System.currentTimeMillis() >= hardDeadline) {
                        applyTimeoutState("max_duration", partialOutputThreshold,
                            "codex turn max duration reached after partial output",
                            "codex turn max duration reached");
                        break;
                    }
                    if (System.currentTimeMillis() >= idleDeadline) {
                        applyTimeoutState("activity_timeout", partialOutputThreshold,
                            "codex turn activity timed out after partial output",
                            "codex turn activity timed out");
                        break;
                    }
                    continue;
                }
                handleEnvelope(envelope);
                if (isTerminalStatus(turnStatus)) {
                    return turnStatus;
                }
            }
            if (System.currentTimeMillis() >= hardDeadline) {
                applyTimeoutState("max_duration", partialOutputThreshold,
                    "codex turn max duration reached after partial output",
                    "codex turn max duration reached");
            }
            if ("unknown".equalsIgnoreCase(turnStatus)) {
                turnStatus = firstNonBlank(requestTurnStatus, errorText == null ? "completed" : "failed");
            }
            return turnStatus;
        }

        private boolean isTerminalStatus(String status) {
            if (status == null || status.isBlank()) {
                return false;
            }
            String normalized = status.trim().toLowerCase(Locale.ROOT);
            return "completed".equals(normalized)
                || "failed".equals(normalized)
                || "cancelled".equals(normalized)
                || "timeout".equals(normalized)
                || "partial_timeout".equals(normalized)
                || "aborted".equals(normalized)
                || "canceled".equals(normalized)
                || "interrupted".equals(normalized);
        }

        private void handleLegacyEvent(JsonNode event) {
            String eventType = text(event, "type");
            if (eventType == null) {
                return;
            }
            switch (eventType) {
                case "task_started" -> turnStatus = "running";
                case "agent_message" -> appendOutput(text(event, "message"));
                case "exec_command_begin", "patch_apply_begin" -> {
                    markActivity();
                    toolInvocationCount++;
                    String callId = text(event, "call_id");
                    if (callId != null) {
                        toolInvocationIds.add(callId);
                    }
                }
                case "exec_command_end" -> appendOutput(text(event, "output"));
                case "task_complete" -> turnStatus = "completed";
                case "turn_aborted" -> {
                    abortReason = firstNonBlank(
                        abortReason,
                        text(event, "reason"),
                        text(event, "message"),
                        text(event, "error"),
                        nestedText(event, "error", "message"),
                        "turn_aborted"
                    );
                    turnStatus = hasPartialOutput(partialOutputThreshold) ? "partial_timeout" : "cancelled";
                }
                default -> {
                }
            }
        }

        private void handleItemNotification(String method, JsonNode item) {
            String itemType = text(item, "type");
            String itemId = text(item, "id");
            if ("item/started".equals(method) && ("commandExecution".equals(itemType) || "fileChange".equals(itemType))) {
                markActivity();
                toolInvocationCount++;
                if (itemId != null) {
                    toolInvocationIds.add(itemId);
                }
                return;
            }
            if ("item/completed".equals(method) && "commandExecution".equals(itemType)) {
                appendOutput(text(item, "aggregatedOutput"));
                return;
            }
            if ("item/completed".equals(method) && "agentMessage".equals(itemType)) {
                appendOutput(text(item, "text"));
                String phase = text(item, "phase");
                if ("final_answer".equalsIgnoreCase(phase) && "running".equalsIgnoreCase(turnStatus)) {
                    turnStatus = "completed";
                }
            }
        }

        private void appendOutput(String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            markActivity();
            if (!output.isEmpty()) {
                output.append('\n');
            }
            output.append(text.trim());
        }

        private CodexSessionOutput toOutput(String threadId, String requestTurnStatus) {
            String effectiveTurnStatus = firstNonBlank(
                normalizeUnknownTurnStatus(turnStatus),
                requestTurnStatus,
                "completed"
            );
            String status = normalizeTurnStatus(effectiveTurnStatus, errorText);
            return new CodexSessionOutput(
                status,
                output.toString().trim(),
                errorText,
                threadId,
                null,
                protocol,
                effectiveTurnStatus,
                timeoutKind,
                abortReason,
                toolInvocationCount,
                List.copyOf(toolInvocationIds),
                List.copyOf(protocolTrace)
            );
        }

        private String normalizeUnknownTurnStatus(String value) {
            return "unknown".equalsIgnoreCase(value) ? null : value;
        }

        private String normalizeTurnStatus(String value, String error) {
            String normalized = value == null || value.isBlank()
                ? (error == null || error.isBlank() ? "completed" : "failed")
                : value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "completed", "failed", "cancelled", "timeout", "partial_timeout" -> normalized;
                case "running" -> error == null || error.isBlank() ? "completed" : "failed";
                case "aborted", "canceled", "interrupted" -> "cancelled";
                default -> error == null || error.isBlank() ? "completed" : "failed";
            };
        }

        private void markActivity() {
            lastActivityAtMs = System.currentTimeMillis();
        }

        private boolean hasPartialOutput(int threshold) {
            return output.toString().trim().length() >= Math.max(1, threshold);
        }

        private CodexSessionOutput partialFailureOutput(String message) {
            if (!hasPartialOutput(partialOutputThreshold)) {
                return null;
            }
            turnStatus = "partial_timeout";
            errorText = firstNonBlank(
                errorText,
                message,
                "codex app-server communication failed after partial output"
            );
            abortReason = firstNonBlank(abortReason, message, "communication_failed_after_partial_output");
            return toOutput(threadId, "partial_timeout");
        }

        private void applyTimeoutState(String kind,
                                       int partialOutputThreshold,
                                       String partialMessage,
                                       String timeoutMessage) {
            if (!"running".equalsIgnoreCase(turnStatus) && !"unknown".equalsIgnoreCase(turnStatus)) {
                return;
            }
            timeoutKind = kind;
            if (hasPartialOutput(partialOutputThreshold)) {
                turnStatus = "partial_timeout";
                errorText = firstNonBlank(errorText, partialMessage);
                return;
            }
            turnStatus = "timeout";
            errorText = firstNonBlank(errorText, timeoutMessage);
        }
    }

    private record CodexExecutionPlan(List<String> command,
                                      String prompt,
                                      String promptPreview,
                                      String model,
                                      String cwd,
                                      String resumeThreadId,
                                      String systemPrompt,
                                      String configuredBinary,
                                      String executableTarget,
                                      String launchMode,
                                      String executionBackend,
                                      String providerProfileId,
                                      String modelProvider,
                                      String cliProfile,
                                      Map<String, String> configOverrides) {
        private CodexExecutionPlan {
            if (command == null) command = List.of();
            if (prompt == null) prompt = "";
            if (promptPreview == null) promptPreview = "";
            if (configuredBinary == null) configuredBinary = "";
            if (launchMode == null || launchMode.isBlank()) launchMode = "direct";
            if (executionBackend == null || executionBackend.isBlank()) executionBackend = "provider_app_server";
            if (providerProfileId == null) providerProfileId = "";
            if (modelProvider == null) modelProvider = "";
            if (cliProfile == null) cliProfile = "";
            if (configOverrides == null) configOverrides = Map.of();
        }

        private String commandPreview() {
            return String.join(" ", command);
        }

        private CodexExecutionPlan withCommand(List<String> value) {
            return new CodexExecutionPlan(
                value,
                prompt,
                promptPreview,
                model,
                cwd,
                resumeThreadId,
                systemPrompt,
                configuredBinary,
                executableTarget,
                launchMode,
                executionBackend,
                providerProfileId,
                modelProvider,
                cliProfile,
                configOverrides
            );
        }

        private boolean hasProfileConfig() {
            return !modelProvider.isBlank()
                || !cliProfile.isBlank()
                || !configOverrides.isEmpty();
        }
    }

    private record CodexExecJsonOutput(String status,
                                       String outputText,
                                       String errorText,
                                       String sessionId) {
        private CodexExecJsonOutput {
            if (status == null || status.isBlank()) status = "completed";
            if (outputText == null) outputText = "";
        }
    }

    private record CodexSessionOutput(String status,
                                      String outputText,
                                      String errorText,
                                      String threadId,
                                      Integer exitCode,
                                      String protocol,
                                      String turnStatus,
                                      String timeoutKind,
                                      String abortReason,
                                      int toolInvocationCount,
                                      List<String> toolInvocationIds,
                                      List<String> protocolTrace) {
        private CodexSessionOutput {
            if (status == null || status.isBlank()) status = "completed";
            if (outputText == null) outputText = "";
            if (protocol == null || protocol.isBlank()) protocol = "codex_json_rpc";
            if (turnStatus == null || turnStatus.isBlank()) turnStatus = status;
            if (toolInvocationIds == null) toolInvocationIds = List.of();
            if (protocolTrace == null) protocolTrace = List.of();
        }

        private CodexSessionOutput withExitCode(int value) {
            return new CodexSessionOutput(
                status, outputText, errorText, threadId, value, protocol, turnStatus,
                timeoutKind, abortReason, toolInvocationCount, toolInvocationIds, protocolTrace
            );
        }
    }

    private static final class ProviderRunFiles implements Closeable {
        private final Path runDir;
        private final Path promptPath;
        private final Path eventsPath;
        private final Path lastMessagePath;
        private final Path metadataPath;
        private final OutputStream events;
        private final boolean available;

        private ProviderRunFiles(Path runDir,
                                 Path promptPath,
                                 Path eventsPath,
                                 Path lastMessagePath,
                                 Path metadataPath,
                                 OutputStream events,
                                 boolean available) {
            this.runDir = runDir;
            this.promptPath = promptPath;
            this.eventsPath = eventsPath;
            this.lastMessagePath = lastMessagePath;
            this.metadataPath = metadataPath;
            this.events = events;
            this.available = available;
        }

        private static ProviderRunFiles create(String providerId, String taskId, String workerId, CodexExecutionPlan plan) {
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
                Path eventsPath = runDir.resolve("events.jsonl");
                Path lastMessagePath = runDir.resolve("last_message.md");
                Path metadataPath = runDir.resolve("metadata.json");
                Files.writeString(promptPath, plan == null ? "" : plan.prompt(), StandardCharsets.UTF_8);
                return new ProviderRunFiles(
                    runDir,
                    promptPath,
                    eventsPath,
                    lastMessagePath,
                    metadataPath,
                    Files.newOutputStream(eventsPath),
                    true
                );
            } catch (IOException e) {
                log.warn("Codex provider run files unavailable. provider={} worker={} reason={}", providerId, workerId, e.getMessage());
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

        private Path eventsPath() {
            return eventsPath;
        }

        private Path lastMessagePath() {
            return lastMessagePath;
        }

        private Path metadataPath() {
            return metadataPath;
        }

        private OutputStream events() {
            return events;
        }

        private void writeLastMessage(String outputText) {
            if (!available || lastMessagePath == null) {
                return;
            }
            try {
                Files.writeString(lastMessagePath, outputText == null ? "" : outputText, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Failed to write codex provider last message file. path={} reason={}", lastMessagePath, e.getMessage());
            }
        }

        private void writeMetadata(Map<String, Object> metadata) {
            if (!available || metadataPath == null) {
                return;
            }
            try {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), metadata == null ? Map.of() : metadata);
            } catch (IOException e) {
                log.warn("Failed to write codex provider run metadata file. path={} reason={}", metadataPath, e.getMessage());
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
            if (events != null) {
                events.close();
            }
        }
    }
}
