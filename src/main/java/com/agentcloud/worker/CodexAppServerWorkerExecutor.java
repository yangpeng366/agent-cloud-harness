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
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
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
    private static final long PROCESS_TIMEOUT_MS = 180_000L;
    private static final long HANDSHAKE_TIMEOUT_MS = 30_000L;
    private static final long TURN_COMPLETION_TIMEOUT_MS = 150_000L;

    private final AgentProviderRegistry providerRegistry;
    private final WorkerRegistry workerRegistry;
    private final LocalCliProviderConfig providerConfig;

    public CodexAppServerWorkerExecutor(AgentProviderRegistry providerRegistry, WorkerRegistry workerRegistry) {
        this.providerRegistry = providerRegistry;
        this.workerRegistry = workerRegistry;
        this.providerConfig = resolveProviderConfig(providerRegistry);
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
        CodexExecutionPlan plan = buildPlan(config, context, cwd);

        long startedAtMs = System.currentTimeMillis();
        Process process = null;
        CodexSessionOutput output;
        try {
            ProcessBuilder builder = new ProcessBuilder(plan.command())
                .directory(cwd == null || cwd.isBlank() ? null : Path.of(cwd).toFile())
                .redirectErrorStream(true);
            process = builder.start();
            output = runSession(process, plan);
            if (!process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                long durationMs = System.currentTimeMillis() - startedAtMs;
                return failureResult("timeout", "codex app-server timed out",
                    providerId, workerId, cwd, plan, providerStatus, durationMs, null, null);
            }
            output = output.withExitCode(process.exitValue());
        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - startedAtMs;
            return failureResult("failed", "failed to start codex app-server: " + e.getMessage(),
                providerId, workerId, cwd, plan, providerStatus, durationMs, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = System.currentTimeMillis() - startedAtMs;
            return failureResult("cancelled", "codex app-server interrupted",
                providerId, workerId, cwd, plan, providerStatus, durationMs, null, null);
        } catch (IllegalStateException e) {
            long durationMs = System.currentTimeMillis() - startedAtMs;
            return failureResult("failed", e.getMessage(),
                providerId, workerId, cwd, plan, providerStatus, durationMs, null, null);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }

        long durationMs = System.currentTimeMillis() - startedAtMs;
        String normalizedStatus = normalizeStatus(output.status(), output.exitCode());
        LinkedHashMap<String, Object> metadata = baseMetadata(providerId, workerId, cwd, plan, providerStatus, durationMs);
        metadata.put("exit_code", output.exitCode());
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
        if (output.errorText() != null && !output.errorText().isBlank()) {
            metadata.put("provider_error", output.errorText());
        }

        String outputText = output.outputText() == null ? "" : output.outputText().trim();
        String summary = summarize(outputText, output.errorText(), normalizedStatus);

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
        return ProviderExecutionSupport.supportsProviderAppServer(providerId);
    }

    private CodexExecutionPlan buildPlan(LocalCliProviderConfig.ResolvedConfig config,
                                         TaskRuntimeContext context,
                                         String cwd) {
        String prompt = ProviderTaskPromptBuilder.build(context);
        String model = configuredModel(config, context);
        String resumeThreadId = resumeThreadId(context);
        LocalCliProviderConfig.LaunchSpec launchSpec = config.launchSpec();
        List<String> command = launchSpec.command(List.of(
            "app-server",
            "--listen",
            "stdio://"
        ));
        return new CodexExecutionPlan(
            command,
            prompt,
            truncate(prompt, 240),
            model,
            cwd,
            resumeThreadId,
            systemPrompt(context),
            launchSpec.configuredBinary(),
            launchSpec.executableTarget(),
            launchSpec.launchMode()
        );
    }

    private CodexSessionOutput runSession(Process process, CodexExecutionPlan plan)
        throws IOException, InterruptedException {
        try (Writer writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
             BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            JsonRpcSession session = new JsonRpcSession(writer, reader);
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
            String terminalTurnStatus = session.awaitTurnCompletion(turnStatus);
            return session.toOutput(threadId, terminalTurnStatus);
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
        params.put("modelProvider", null);
        params.put("profile", null);
        params.put("cwd", plan.cwd());
        params.put("approvalPolicy", null);
        params.put("sandbox", null);
        params.put("config", null);
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

    private String configuredModel(LocalCliProviderConfig.ResolvedConfig config, TaskRuntimeContext context) {
        String taskModel = context == null || context.task() == null ? null : metadataString(context.task().metadata(), "provider_model");
        if (taskModel != null && !taskModel.isBlank()) {
            return taskModel;
        }
        return config.model().value();
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
        metadata.put("execution_backend", "provider_app_server");
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
                                                CodexExecutionPlan plan,
                                                AgentProviderStatus providerStatus,
                                                long durationMs,
                                                Integer exitCode,
                                                String threadId) {
        LinkedHashMap<String, Object> metadata = baseMetadata(providerId, workerId, cwd, plan, providerStatus, durationMs);
        metadata.put("provider_error", errorText);
        metadata.put("provider_output_parser", "codex_json_rpc");
        if (exitCode != null) {
            metadata.put("exit_code", exitCode);
        }
        if (threadId != null && !threadId.isBlank()) {
            metadata.put("provider_session_id", threadId);
            metadata.put("provider_thread_id", threadId);
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
            case "aborted", "canceled", "interrupted" -> "cancelled";
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
        private final StringBuilder output = new StringBuilder();
        private final List<String> protocolTrace = new ArrayList<>();
        private final Set<String> toolInvocationIds = new LinkedHashSet<>();
        private int nextId;
        private int toolInvocationCount;
        private String threadId;
        private String turnStatus = "unknown";
        private String errorText;
        private String protocol = "codex_json_rpc";

        private JsonRpcSession(Writer writer, BufferedReader reader) {
            this.writer = writer;
            this.reader = reader;
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
            writer.write(MAPPER.writeValueAsString(payload));
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
                try {
                    return MAPPER.readTree(trimmed);
                } catch (Exception ignored) {
                    appendOutput(trimmed);
                }
            }
            return null;
        }

        private void handleEnvelope(JsonNode envelope) throws IOException {
            if (envelope == null || envelope.isMissingNode() || envelope.isNull()) {
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

        private String awaitTurnCompletion(String requestTurnStatus) throws IOException, InterruptedException {
            if (isTerminalStatus(requestTurnStatus)) {
                turnStatus = requestTurnStatus;
                return turnStatus;
            }
            long deadline = System.currentTimeMillis() + TURN_COMPLETION_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                JsonNode envelope = nextEnvelope(deadline);
                if (envelope == null) {
                    break;
                }
                handleEnvelope(envelope);
                if (isTerminalStatus(turnStatus)) {
                    return turnStatus;
                }
            }
            if ("running".equalsIgnoreCase(turnStatus)) {
                turnStatus = "timeout";
                errorText = firstNonBlank(errorText, "codex turn completion timed out");
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
                    toolInvocationCount++;
                    String callId = text(event, "call_id");
                    if (callId != null) {
                        toolInvocationIds.add(callId);
                    }
                }
                case "exec_command_end" -> appendOutput(text(event, "output"));
                case "task_complete" -> turnStatus = "completed";
                case "turn_aborted" -> turnStatus = "cancelled";
                default -> {
                }
            }
        }

        private void handleItemNotification(String method, JsonNode item) {
            String itemType = text(item, "type");
            String itemId = text(item, "id");
            if ("item/started".equals(method) && ("commandExecution".equals(itemType) || "fileChange".equals(itemType))) {
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
                case "completed", "failed", "cancelled", "timeout" -> normalized;
                case "running" -> error == null || error.isBlank() ? "completed" : "failed";
                case "aborted", "canceled", "interrupted" -> "cancelled";
                default -> error == null || error.isBlank() ? "completed" : "failed";
            };
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
                                      String launchMode) {
        private CodexExecutionPlan {
            if (command == null) command = List.of();
            if (prompt == null) prompt = "";
            if (promptPreview == null) promptPreview = "";
            if (configuredBinary == null) configuredBinary = "";
            if (launchMode == null || launchMode.isBlank()) launchMode = "direct";
        }

        private String commandPreview() {
            return String.join(" ", command);
        }
    }

    private record CodexSessionOutput(String status,
                                      String outputText,
                                      String errorText,
                                      String threadId,
                                      Integer exitCode,
                                      String protocol,
                                      String turnStatus,
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
                toolInvocationCount, toolInvocationIds, protocolTrace
            );
        }
    }
}
