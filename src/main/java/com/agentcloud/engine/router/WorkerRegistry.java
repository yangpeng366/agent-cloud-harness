package com.agentcloud.engine.router;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.AgentProviderResolver;
import com.agentcloud.agent.AgentProviderStatus;
import com.agentcloud.model.Worker;
import com.agentcloud.tool.HostToolAvailability;
import com.agentcloud.worker.ProviderExecutionSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class WorkerRegistry {
    private static final Logger log = LoggerFactory.getLogger(WorkerRegistry.class);
    private static final long DEFAULT_TEMPORARY_UNAVAILABLE_MS = 10 * 60 * 1000L;
    private static final long DEFAULT_DISPATCH_PREFLIGHT_CACHE_MS = 2 * 60 * 1000L;
    private static final long DEFAULT_DISPATCH_PREFLIGHT_UNAVAILABLE_MS = 10 * 60 * 1000L;
    private final Map<String, Worker> workers = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, TemporaryUnavailability> temporarilyUnavailableWorkers =
        Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, DispatchPreflightStatus> dispatchPreflightCache =
        Collections.synchronizedMap(new LinkedHashMap<>());
    private final AgentProviderRegistry agentProviderRegistry;

    public WorkerRegistry() {
        this(null);
    }

    public WorkerRegistry(AgentProviderRegistry agentProviderRegistry) {
        this.agentProviderRegistry = agentProviderRegistry;
        String defaultToolScope = Path.of(System.getProperty("user.dir", "."))
            .toAbsolutePath()
            .normalize()
            .toString();

        // 预注册内置 worker
        register(new Worker("openclaw-native", "native-tool",
            List.of("browser", "doc", "message", "search"),
            List.of(),
            List.of(),
            Map.of("config_present", true, "backend_reachable", true),
            Map.of(
                "model_tier", "tool",
                "primary_role", "tool_executor",
                "selection_priority", 120,
                "execution_backend", "tool_aware",
                "auto_route_task_types", List.of("browser", "doc", "message", "search", "reading"),
                "prefer_harness_tools", true,
                "tool_provider", "openclaw_embedded"
            ), false, true));
        register(new Worker("codex", "codex",
            List.of("coding", "reading", "ops"),
            defaultCodexToolCapabilities(),
            List.of(defaultToolScope),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "strong",
                "primary_role", "planner_executor",
                "selection_priority", 100,
                "default_tool_scope", defaultToolScope,
                "local_workspace_access", true,
                "workspace_access_mode", "codex_app_server_cwd",
                "tool_command_mode", "guarded",
                "execution_backend", "provider_app_server",
                "auto_route_task_types", List.of("coding", "reading", "ops")
            ), false, true));
        register(new Worker("claude", "claude",
            List.of("coding", "reading", "writing"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "strong",
                "primary_role", "planner_executor",
                "selection_priority", 92,
                "local_workspace_access", true,
                "workspace_access_mode", "native_cli_cwd",
                "execution_backend", "provider_native_cli",
                "auto_route_task_types", List.of("coding", "reading", "writing")
            ),
            false, true));
        register(new Worker("cursor", "cursor",
            List.of("coding", "reading", "ops"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "strong",
                "primary_role", "planner_executor",
                "selection_priority", 91,
                "local_workspace_access", true,
                "workspace_access_mode", "native_cli_workspace_arg",
                "execution_backend", "provider_native_cli",
                "auto_route_task_types", List.of("coding", "reading", "ops")
            ),
            false, true));
        register(new Worker("copilot", "copilot",
            List.of("coding", "reading"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "strong",
                "primary_role", "planner_executor",
                "selection_priority", 90,
                "local_workspace_access", true,
                "workspace_access_mode", "native_cli_cwd",
                "execution_backend", "provider_native_cli",
                "auto_route_task_types", List.of("coding", "reading")
            ),
            false, true));
        register(new Worker("opencode", "opencode",
            List.of("coding", "ops", "writing"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "strong",
                "primary_role", "planner_executor",
                "selection_priority", 89,
                "local_workspace_access", true,
                "workspace_access_mode", "native_cli_cwd",
                "execution_backend", "provider_native_cli",
                "auto_route_task_types", List.of("coding", "ops", "writing")
            ),
            false, true));
        register(new Worker("gemini", "gemini",
            List.of("coding", "research", "browser"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "strong",
                "primary_role", "planner_executor",
                "selection_priority", 88,
                "local_workspace_access", true,
                "workspace_access_mode", "native_cli_cwd",
                "execution_backend", "provider_native_cli",
                "auto_route_task_types", List.of("research", "browser")
            ),
            false, true));
        register(new Worker("deepseek", "deepseek",
            List.of("coding", "reading", "writing"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "strong",
                "primary_role", "planner_executor",
                "selection_priority", 87,
                "local_workspace_access", true,
                "workspace_access_mode", "native_cli_cwd",
                "execution_backend", "provider_native_cli",
                "auto_route_task_types", List.of("coding", "reading", "writing")
            ),
            false, true));
        register(new Worker("reasonix", "reasonix",
            List.of("coding", "reading", "writing", "research"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "strong",
                "primary_role", "planner_executor",
                "selection_priority", 88,
                "local_workspace_access", true,
                "workspace_access_mode", "native_cli_cwd",
                "execution_backend", "provider_native_cli",
                "auto_route_task_types", List.of("coding", "reading", "writing", "research")
            ),
            false, true));
        register(new Worker("kimi", "kimi",
            List.of("coding", "research", "browser"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "small",
                "primary_role", "executor",
                "selection_priority", 80,
                "local_workspace_access", true,
                "workspace_access_mode", "native_cli_work_dir_arg",
                "execution_backend", "provider_native_cli",
                "auto_route_task_types", List.of("coding", "research", "browser")
            ), false, true));
        register(new Worker("hermes", "hermes",
            List.of("coding", "research", "writing"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "small",
                "primary_role", "executor",
                "selection_priority", 72,
                "execution_backend", "provider_native_cli",
                "auto_route_task_types", List.of("research", "writing")
            ),
            false, true));
        register(new Worker("pi", "pi",
            List.of("research", "writing", "message"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "small",
                "primary_role", "assistant",
                "selection_priority", 70,
                "execution_backend", "provider_native_cli",
                "auto_route_task_types", List.of("research", "writing", "message")
            ),
            false, true));
        register(new Worker("kiro", "kiro",
            List.of("coding", "reading"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "small",
                "primary_role", "executor",
                "selection_priority", 69,
                "local_workspace_access", false,
                "workspace_access_mode", "unknown",
                "execution_backend", "provider_native_cli",
                "auto_route_task_types", List.of("coding", "reading")
            ),
            false, true));
        register(new Worker("codebuddy", "codebuddy",
            List.of("coding", "reading", "writing"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "strong",
                "primary_role", "planner_executor",
                "selection_priority", 86,
                "local_workspace_access", false,
                "workspace_access_mode", "executor_not_supported",
                "execution_backend", "provider_native_cli",
                "auto_route_task_types", List.of("coding", "reading", "writing")
            ),
            false, true));
        register(new Worker("trae", "trae",
            List.of("coding", "reading", "session"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "strong",
                "primary_role", "planner_executor",
                "selection_priority", 85,
                "local_workspace_access", false,
                "workspace_access_mode", "executor_not_supported",
                "execution_backend", "provider_native_cli",
                "auto_route_task_types", List.of("coding", "reading", "session")
            ),
            false, true));
    }

    public Worker register(Worker worker) {
        Worker enriched = enrich(worker);
        workers.put(enriched.workerId(), enriched);
        log.info("Worker registered: {} (type={}, caps={}, tools={}, suggestOnly={})",
            enriched.workerId(), enriched.workerType(), enriched.capabilities(),
            enriched.toolCapabilities(), enriched.suggestOnly());
        return enriched;
    }

    public Worker registerProviderNativeWorker(String providerId,
                                               List<String> capabilities,
                                               Map<String, Object> metadata) {
        String normalizedProviderId = blankToNull(providerId);
        if (normalizedProviderId == null) {
            throw new IllegalArgumentException("provider id is required");
        }
        LinkedHashMap<String, Object> workerMetadata = new LinkedHashMap<>();
        workerMetadata.put("model_tier", "strong");
        workerMetadata.put("primary_role", "planner_executor");
        workerMetadata.put("selection_priority", 60);
        workerMetadata.put("local_workspace_access", true);
        workerMetadata.put("workspace_access_mode", "native_cli_cwd");
        workerMetadata.put("execution_backend", "provider_native_cli");
        workerMetadata.put("auto_route_task_types", capabilities == null || capabilities.isEmpty()
            ? List.of("coding", "reading", "session")
            : List.copyOf(capabilities));
        workerMetadata.put("provider_discovery", true);
        if (metadata != null) {
            workerMetadata.putAll(metadata);
        }
        return register(new Worker(
            normalizedProviderId,
            normalizedProviderId,
            capabilities == null || capabilities.isEmpty() ? List.of("coding", "reading", "session") : capabilities,
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.copyOf(workerMetadata),
            false,
            true
        ));
    }

    public List<Worker> listAll() {
        return List.copyOf(workers.values());
    }

    public Worker get(String workerId) {
        return workers.get(workerId);
    }

    public boolean supportsTool(String workerId, String toolName) {
        Worker worker = workers.get(workerId);
        return worker != null && worker.toolCapabilities() != null && worker.toolCapabilities().contains(toolName);
    }

    public List<String> toolScope(String workerId) {
        Worker worker = workers.get(workerId);
        return worker == null || worker.toolScope() == null ? List.of() : worker.toolScope();
    }

    public List<Worker> listReady() {
        return workers.values().stream()
            .filter(worker -> checkReadiness(worker.workerId()).ready())
            .toList();
    }

    /**
     * 启动期预热本机 provider worker 的 dispatch preflight。
     * 只跑 help/version 级探测，不发送真实 prompt；结果会进入 dispatch preflight 短期缓存。
     */
    public Map<String, ReadinessCheck> warmupDispatchPreflight() {
        LinkedHashMap<String, ReadinessCheck> results = new LinkedHashMap<>();
        for (Worker worker : listAll()) {
            if (worker == null || worker.suggestOnly()) {
                continue;
            }
            String backend = metadataString(worker.metadata(), "execution_backend");
            if (!"provider_native_cli".equals(backend) && !"provider_app_server".equals(backend)) {
                continue;
            }
            ReadinessCheck check = checkReadiness(worker.workerId(), "dispatch");
            results.put(worker.workerId(), check);
            if (check.ready()) {
                log.info("Worker dispatch preflight warmup ready. worker={} mode={} cached={}",
                    worker.workerId(), check.dispatchPreflightMode(), check.dispatchPreflightCached());
            } else {
                log.warn("Worker dispatch preflight warmup not ready. worker={} reason={}",
                    worker.workerId(), check.reason());
            }
        }
        return Map.copyOf(results);
    }

    public List<Worker> findCapable(String taskType) {
        return workers.values().stream()
            .filter(w -> w.capabilities().contains(taskType) || w.capabilities().contains("general"))
            .filter(w -> checkReadiness(w.workerId()).ready())
            .collect(Collectors.toList());
    }

    public void markTemporarilyUnavailable(String workerId, String reason) {
        markTemporarilyUnavailable(workerId, DEFAULT_TEMPORARY_UNAVAILABLE_MS, reason);
    }

    public void markTemporarilyUnavailable(String workerId, long durationMs, String reason) {
        String normalizedWorkerId = blankToNull(workerId);
        if (normalizedWorkerId == null) {
            return;
        }
        long safeDurationMs = durationMs > 0 ? durationMs : DEFAULT_TEMPORARY_UNAVAILABLE_MS;
        long unavailableUntilEpochMs = System.currentTimeMillis() + safeDurationMs;
        String normalizedReason = blankToNull(reason);
        temporarilyUnavailableWorkers.put(
            normalizedWorkerId,
            new TemporaryUnavailability(unavailableUntilEpochMs, normalizedReason)
        );
        log.warn(
            "Worker marked temporarily unavailable: worker={} durationMs={} reason={}",
            normalizedWorkerId,
            safeDurationMs,
            firstNonBlank(normalizedReason, "unspecified")
        );
    }

    public boolean isTemporarilyUnavailable(String workerId) {
        return currentTemporaryUnavailability(workerId).isPresent();
    }

    public String temporaryUnavailableReason(String workerId) {
        return currentTemporaryUnavailability(workerId)
            .map(TemporaryUnavailability::reason)
            .orElse(null);
    }

    public ReadinessCheck checkReadiness(String workerId) {
        return checkReadiness(workerId, "passive");
    }

    public ReadinessCheck checkReadiness(String workerId, String mode) {
        String normalizedMode = "dispatch".equalsIgnoreCase(mode) ? "dispatch" : "passive";
        Worker w = workers.get(workerId);
        if (w == null) return new ReadinessCheck(workerId, false, Map.of(), "worker not found",
            normalizedMode, null, null, null, null, null, Map.of(), Map.of(), null, null, null);
        Map<String, Boolean> checks = new LinkedHashMap<>();
        if (w.dependencies() != null) {
            w.dependencies().forEach((k, v) -> checks.put(k, v));
        }
        checks.putAll(HostToolAvailability.readinessChecks(w.toolCapabilities()));
        String providerId = providerId(w);
        String executionBackend = metadataString(w.metadata(), "execution_backend");
        if (providerBacked(w)) {
            checks.put(
                "executor_backend:" + executionBackend,
                ProviderExecutionSupport.supportsBackend(providerId, executionBackend)
            );
        }
        AgentProviderStatus providerStatus = null;
        if (providerId != null && agentProviderRegistry != null) {
            providerStatus = agentProviderRegistry.status(providerId);
            checks.put("provider:" + providerId, providerStatus != null && providerStatus.ready());
        }
        TemporaryUnavailability temporaryUnavailability = currentTemporaryUnavailability(workerId).orElse(null);
        checks.put("runtime_available", temporaryUnavailability == null);
        boolean passiveOk = checks.values().stream().allMatch(Boolean::booleanValue) && w.ready();
        DispatchPreflightStatus dispatchPreflight = null;
        if ("dispatch".equals(normalizedMode) && passiveOk) {
            dispatchPreflight = dispatchPreflight(workerId, w, providerId);
            checks.put("dispatch_preflight", dispatchPreflight.ready());
        }
        boolean allOk = checks.values().stream().allMatch(Boolean::booleanValue);
        return new ReadinessCheck(
            workerId,
            allOk && w.ready(),
            Map.copyOf(checks),
            readinessReason(w, checks, providerId, providerStatus, temporaryUnavailability, dispatchPreflight),
            normalizedMode,
            dispatchPreflight != null ? dispatchPreflight.ready() : null,
            dispatchPreflight != null ? dispatchPreflight.reason() : null,
            dispatchPreflight != null ? dispatchPreflight.cached() : null,
            dispatchPreflight != null ? dispatchPreflight.mode() : null,
            dispatchPreflight != null ? dispatchPreflight.activeProbe() : null,
            dispatchPreflight != null ? dispatchPreflight.metadata() : null,
            cliProfileMetadata(providerStatus, dispatchPreflight),
            providerFailureValue("provider_failure_class", providerStatus, dispatchPreflight),
            providerFailureValue("provider_failure_reason", providerStatus, dispatchPreflight),
            providerFailureRetryable(providerStatus, dispatchPreflight)
        );
    }

    private static List<String> defaultHarnessToolCapabilities() {
        List<String> tools = new ArrayList<>(List.of(
            "list_files", "read_file", "search_text", "write_file", "write_files", "patch_file"
        ));
        tools.addAll(HostToolAvailability.supportedCommandToolCapabilities());
        return List.copyOf(tools);
    }

    private static List<String> defaultCodexToolCapabilities() {
        return defaultHarnessToolCapabilities();
    }

    private Worker enrich(Worker worker) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(worker.metadata());
        metadata.put("host_platform", HostToolAvailability.isWindowsHost() ? "windows" : "posix");
        String providerId = providerId(worker);
        if (providerId != null) {
            metadata.put("provider_id", providerId);
        }
        applyWorkerCapabilityMatrixDefaults(metadata, providerId);

        List<String> toolCapabilities = effectiveToolCapabilities(worker, metadata);
        List<String> toolScope = effectiveToolScope(worker, metadata, toolCapabilities);
        Map<String, Boolean> toolAvailability = HostToolAvailability.declaredToolAvailability(toolCapabilities);
        if (!toolAvailability.isEmpty()) {
            metadata.put("host_tool_availability", toolAvailability);
        }
        if (!toolCapabilities.isEmpty()) {
            metadata.putIfAbsent("harness_tool_access", true);
            metadata.putIfAbsent("harness_tool_scope_source", toolScope.isEmpty() ? "none" : "worker_or_default");
        }

        return new Worker(
            worker.workerId(),
            worker.workerType(),
            worker.capabilities(),
            toolCapabilities,
            toolScope,
            worker.dependencies(),
            Map.copyOf(metadata),
            worker.suggestOnly(),
            worker.ready()
        );
    }

    private List<String> effectiveToolCapabilities(Worker worker, Map<String, Object> metadata) {
        if (worker == null || worker.suggestOnly()) {
            return worker == null || worker.toolCapabilities() == null ? List.of() : worker.toolCapabilities();
        }
        LinkedHashMap<String, Boolean> ordered = new LinkedHashMap<>();
        if (worker.toolCapabilities() != null) {
            for (String toolCapability : worker.toolCapabilities()) {
                if (toolCapability != null && !toolCapability.isBlank()) {
                    ordered.put(toolCapability, true);
                }
            }
        }
        String backend = metadataString(metadata, "execution_backend");
        if ("provider_native_cli".equals(backend)
            || "provider_app_server".equals(backend)
            || "tool_aware".equals(backend)) {
            for (String toolCapability : defaultHarnessToolCapabilities()) {
                ordered.put(toolCapability, true);
            }
        }
        return List.copyOf(ordered.keySet());
    }

    private List<String> effectiveToolScope(Worker worker, Map<String, Object> metadata, List<String> toolCapabilities) {
        if (worker == null || worker.toolScope() == null) {
            return List.of();
        }
        if (!worker.toolScope().isEmpty()) {
            return worker.toolScope();
        }
        if (worker.suggestOnly() || toolCapabilities == null || toolCapabilities.isEmpty()) {
            return List.of();
        }
        String defaultToolScope = metadataString(metadata, "default_tool_scope");
        if (defaultToolScope != null && !defaultToolScope.isBlank()) {
            return List.of(defaultToolScope);
        }
        return List.of(Path.of(System.getProperty("user.dir", "."))
            .toAbsolutePath()
            .normalize()
            .toString());
    }

    private void applyWorkerCapabilityMatrixDefaults(Map<String, Object> metadata, String providerId) {
        String backend = metadataString(metadata, "execution_backend");
        if (backend == null) {
            metadata.putIfAbsent("execution_backend", "default_llm");
            metadata.putIfAbsent("workspace_access_mode", "none");
            metadata.putIfAbsent("input_mode", "prompt_text");
            metadata.putIfAbsent("output_mode", "text");
            metadata.putIfAbsent("output_contract", "plain_text");
            metadata.putIfAbsent("recovery_resume_policy", "fresh_only");
            metadata.putIfAbsent("side_effect_risk", "low");
            return;
        }
        switch (backend) {
            case "provider_app_server" -> {
                metadata.putIfAbsent("command_shape", providerAppServerCommandShape(providerId));
                metadata.putIfAbsent("input_mode", "json_rpc");
                metadata.putIfAbsent("output_mode", "json_rpc_events");
                metadata.putIfAbsent("output_contract", "provider_app_server_events");
                metadata.putIfAbsent("workspace_access_mode", "codex_app_server_cwd");
                metadata.putIfAbsent("recovery_resume_policy", "fresh_on_recovery");
                metadata.putIfAbsent("supports_resume", true);
                metadata.putIfAbsent("side_effect_risk", "high");
            }
            case "provider_native_cli" -> {
                metadata.putIfAbsent("command_shape", providerNativeCommandShape(providerId));
                metadata.putIfAbsent("input_mode", providerNativeInputMode(providerId));
                metadata.putIfAbsent("output_mode", providerNativeOutputMode(providerId));
                metadata.putIfAbsent("output_contract", "provider_native_cli_events");
                metadata.putIfAbsent("workspace_access_mode", providerNativeWorkspaceAccessMode(providerId));
                metadata.putIfAbsent("recovery_resume_policy", providerNativeRecoveryResumePolicy(providerId));
                metadata.putIfAbsent("supports_resume", providerNativeSupportsResume(providerId));
                metadata.putIfAbsent("side_effect_risk", "high");
            }
            case "tool_aware" -> {
                metadata.putIfAbsent("command_shape", "harness tool registry");
                metadata.putIfAbsent("input_mode", "tool_request");
                metadata.putIfAbsent("output_mode", "tool_result");
                metadata.putIfAbsent("output_contract", "harness_tool_trace");
                metadata.putIfAbsent("workspace_access_mode", "tool_scope");
                metadata.putIfAbsent("recovery_resume_policy", "stateless");
                metadata.putIfAbsent("supports_resume", false);
                metadata.putIfAbsent("side_effect_risk", "medium");
            }
            case "unsupported" -> {
                metadata.putIfAbsent("command_shape", "unsupported");
                metadata.putIfAbsent("input_mode", "none");
                metadata.putIfAbsent("output_mode", "none");
                metadata.putIfAbsent("output_contract", "unsupported");
                metadata.putIfAbsent("workspace_access_mode", "none");
                metadata.putIfAbsent("recovery_resume_policy", "unsupported");
                metadata.putIfAbsent("supports_resume", false);
                metadata.putIfAbsent("side_effect_risk", "unknown");
            }
            default -> {
                metadata.putIfAbsent("input_mode", "prompt_text");
                metadata.putIfAbsent("output_mode", "text");
                metadata.putIfAbsent("output_contract", backend);
                metadata.putIfAbsent("workspace_access_mode", "unknown");
                metadata.putIfAbsent("recovery_resume_policy", "fresh_only");
                metadata.putIfAbsent("side_effect_risk", "medium");
            }
        }
    }

    private String providerAppServerCommandShape(String providerId) {
        return "codex".equals(providerId) ? "codex app-server --listen stdio://" : "provider app-server";
    }

    private String providerNativeCommandShape(String providerId) {
        return switch (providerId == null ? "" : providerId) {
            case "cursor" -> "cursor chat -p <prompt> --output-format stream-json --workspace <cwd>";
            case "openclaw" -> "openclaw agent --local --json --session-id <id> --message <prompt>";
            case "claude" -> "claude -p --output-format stream-json --input-format stream-json";
            case "gemini" -> "gemini -p <prompt> -o stream-json";
            case "deepseek" -> "reasonix run --no-config --no-proxy --model deepseek-v4-flash <prompt>";
            case "reasonix" -> "reasonix run --no-config --no-proxy <prompt>";
            case "trae" -> "trae chat --mode agent <prompt>";
            case "codebuddy" -> "codebuddy <prompt>";
            case "hermes" -> "hermes <prompt>";
            case "pi" -> "pi <prompt>";
            case "kiro" -> "kiro-cli <prompt>";
            case "kimi" -> "kimi --print --output-format stream-json --work-dir <cwd> --prompt <prompt>";
            case "copilot" -> "copilot -p <prompt> --output-format json";
            case "opencode" -> "opencode run --format json <prompt>";
            default -> "provider native cli";
        };
    }

    private String providerNativeInputMode(String providerId) {
        return "claude".equals(providerId) ? "stdin_jsonl" : "argv_prompt";
    }

    private String providerNativeOutputMode(String providerId) {
        return switch (providerId == null ? "" : providerId) {
            case "copilot", "opencode" -> "json";
            default -> "stream_json";
        };
    }

    private String providerNativeWorkspaceAccessMode(String providerId) {
        return switch (providerId == null ? "" : providerId) {
            case "cursor" -> "native_cli_workspace_arg";
            case "kimi" -> "native_cli_work_dir_arg";
            default -> "native_cli_cwd";
        };
    }

    private String providerNativeRecoveryResumePolicy(String providerId) {
        return switch (providerId == null ? "" : providerId) {
            case "cursor", "gemini", "kimi", "copilot", "opencode" -> "resume_if_session_id";
            case "openclaw" -> "resume_if_session_id_required";
            case "claude" -> "resume_if_session_id";
            default -> "fresh_only";
        };
    }

    private boolean providerNativeSupportsResume(String providerId) {
        return switch (providerId == null ? "" : providerId) {
            case "cursor", "openclaw", "claude", "gemini", "kimi", "copilot", "opencode" -> true;
            default -> false;
        };
    }

    private String readinessReason(Worker worker,
                                   Map<String, Boolean> checks,
                                   String providerId,
                                   AgentProviderStatus providerStatus,
                                   TemporaryUnavailability temporaryUnavailability,
                                   DispatchPreflightStatus dispatchPreflight) {
        String executionBackend = metadataString(worker != null ? worker.metadata() : null, "execution_backend");
        if (dispatchPreflight != null && !dispatchPreflight.ready()) {
            return firstNonBlank(dispatchPreflight.reason(), "dispatch preflight failed");
        }
        if (temporaryUnavailability != null) {
            String reason = blankToNull(temporaryUnavailability.reason());
            return reason != null
                ? "temporarily unavailable: " + reason
                : "temporarily unavailable due to recent worker failure";
        }
        if (providerStatus != null && !providerStatus.ready()) {
            String providerReason = blankToNull(providerStatus.readinessReason());
            return providerReason != null ? providerReason : "provider not ready: " + providerId;
        }
        if (providerId != null && agentProviderRegistry != null && providerStatus == null) {
            return "provider not registered: " + providerId;
        }
        if (providerBacked(worker) && !ProviderExecutionSupport.supportsBackend(providerId, executionBackend)) {
            return ProviderExecutionSupport.unsupportedReason(providerId, executionBackend);
        }
        for (Map.Entry<String, Boolean> entry : checks.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            if (entry.getKey().startsWith("tool:")) {
                String toolCapability = entry.getKey().substring("tool:".length());
                return HostToolAvailability.unavailableReason(toolCapability);
            }
            if (entry.getKey().startsWith("executor_backend:")) {
                return ProviderExecutionSupport.unsupportedReason(providerId, executionBackend);
            }
            if ("runtime_available".equals(entry.getKey())) {
                String reason = temporaryUnavailability != null ? blankToNull(temporaryUnavailability.reason()) : null;
                return reason != null
                    ? "temporarily unavailable: " + reason
                    : "temporarily unavailable due to recent worker failure";
            }
            return "dependency not satisfied: " + entry.getKey();
        }
        return worker.ready() ? "ready" : "worker marked not ready";
    }

    private DispatchPreflightStatus dispatchPreflight(String workerId, Worker worker, String providerId) {
        long now = System.currentTimeMillis();
        DispatchPreflightStatus cached = dispatchPreflightCache.get(workerId);
        if (cached != null && cached.expiresAtEpochMs() > now) {
            return cached.withCached(true);
        }
        DispatchPreflightStatus status = runDispatchPreflight(workerId, worker, providerId, now);
        dispatchPreflightCache.put(workerId, status);
        if (!status.ready()) {
            markTemporarilyUnavailable(
                workerId,
                dispatchPreflightUnavailableMs(),
                firstNonBlank(status.reason(), "dispatch preflight failed")
            );
        }
        return status;
    }

    private DispatchPreflightStatus runDispatchPreflight(String workerId, Worker worker, String providerId, long now) {
        if (!providerBacked(worker)) {
            return new DispatchPreflightStatus(true, "dispatch preflight not required for non-provider worker",
                false, "not_required", now + dispatchPreflightCacheMs(), Map.of());
        }
        if (agentProviderRegistry == null) {
            return new DispatchPreflightStatus(true, "dispatch preflight skipped: provider registry unavailable",
                false, "skipped", now + dispatchPreflightCacheMs(), Map.of());
        }
        if (providerId == null) {
            return new DispatchPreflightStatus(false, "dispatch preflight provider not registered",
                false, "missing_provider", now + dispatchPreflightCacheMs(), Map.of());
        }
        try {
            AgentProviderStatus status = agentProviderRegistry.dispatchPreflight(providerId);
            String mode = dispatchPreflightMode(status);
            boolean activeProbeRequiredButMissing = requireActiveDispatchPreflight()
                && !"active_probe".equals(mode);
            boolean ready = status != null && status.ready() && !activeProbeRequiredButMissing;
            String reason = ready
                ? "dispatch preflight ready"
                : activeProbeRequiredButMissing
                    ? "dispatch preflight active probe required but provider returned " + mode
                : firstNonBlank(status != null ? status.readinessReason() : null, "dispatch preflight failed");
            return new DispatchPreflightStatus(
                ready,
                reason,
                false,
                mode,
                now + dispatchPreflightCacheMs(),
                dispatchPreflightMetadata(status)
            );
        } catch (RuntimeException e) {
            return new DispatchPreflightStatus(false, "dispatch preflight failed: " + e.getMessage(),
                false, "error", now + dispatchPreflightCacheMs(), Map.of());
        }
    }

    public static long dispatchPreflightCacheMs() {
        return durationPropertyMs("agentcloud.dispatch.preflight.cache_ms", DEFAULT_DISPATCH_PREFLIGHT_CACHE_MS);
    }

    public static long dispatchPreflightUnavailableMs() {
        return durationPropertyMs("agentcloud.dispatch.preflight.unavailable_ms", DEFAULT_DISPATCH_PREFLIGHT_UNAVAILABLE_MS);
    }

    private static long durationPropertyMs(String key, long fallbackMs) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallbackMs;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : fallbackMs;
        } catch (NumberFormatException e) {
            return fallbackMs;
        }
    }

    private Map<String, Object> dispatchPreflightMetadata(AgentProviderStatus status) {
        if (status == null || status.metadata() == null || status.metadata().isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        copyMetadataKey(status.metadata(), metadata, "configured_binary");
        copyMetadataKey(status.metadata(), metadata, "binary_source");
        copyMetadataKey(status.metadata(), metadata, "launch_target");
        copyMetadataKey(status.metadata(), metadata, "launch_mode");
        copyMetadataKey(status.metadata(), metadata, "launch_available");
        copyMetadataKey(status.metadata(), metadata, "configured_model");
        copyMetadataKey(status.metadata(), metadata, "model_source");
        copyMetadataKey(status.metadata(), metadata, "dispatch_preflight_probe_kind");
        copyMetadataKey(status.metadata(), metadata, "dispatch_preflight_probe_args");
        copyMetadataKey(status.metadata(), metadata, "dispatch_preflight_command_shape");
        copyMetadataKey(status.metadata(), metadata, "dispatch_preflight_exit_code");
        copyMetadataKey(status.metadata(), metadata, "dispatch_preflight_output_preview");
        copyMetadataKey(status.metadata(), metadata, "cli_profile_evidence_available");
        copyMetadataKey(status.metadata(), metadata, "supports_yolo");
        copyMetadataKey(status.metadata(), metadata, "supports_model");
        copyMetadataKey(status.metadata(), metadata, "supports_json_output");
        copyMetadataKey(status.metadata(), metadata, "supports_resume");
        copyMetadataKey(status.metadata(), metadata, "supports_workspace_arg");
        copyMetadataKey(status.metadata(), metadata, "supports_work_dir_arg");
        copyMetadataKey(status.metadata(), metadata, "supports_output_file");
        copyMetadataKey(status.metadata(), metadata, "provider_failure_class");
        copyMetadataKey(status.metadata(), metadata, "provider_failure_reason");
        copyMetadataKey(status.metadata(), metadata, "provider_retryable");
        return Map.copyOf(metadata);
    }

    private Map<String, Object> cliProfileMetadata(AgentProviderStatus providerStatus,
                                                   DispatchPreflightStatus dispatchPreflight) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if (providerStatus != null && providerStatus.metadata() != null) {
            copyMetadataKey(providerStatus.metadata(), metadata, "cli_profile_evidence_available");
            copyMetadataKey(providerStatus.metadata(), metadata, "supports_yolo");
            copyMetadataKey(providerStatus.metadata(), metadata, "supports_model");
            copyMetadataKey(providerStatus.metadata(), metadata, "supports_json_output");
            copyMetadataKey(providerStatus.metadata(), metadata, "supports_resume");
            copyMetadataKey(providerStatus.metadata(), metadata, "supports_workspace_arg");
            copyMetadataKey(providerStatus.metadata(), metadata, "supports_work_dir_arg");
            copyMetadataKey(providerStatus.metadata(), metadata, "supports_output_file");
            copyMetadataKey(providerStatus.metadata(), metadata, "cli_profile_cached_at");
        }
        if (dispatchPreflight != null && dispatchPreflight.metadata() != null) {
            for (String key : List.of(
                "cli_profile_evidence_available",
                "supports_yolo",
                "supports_model",
                "supports_json_output",
                "supports_resume",
                "supports_workspace_arg",
                "supports_work_dir_arg",
                "supports_output_file",
                "cli_profile_cached_at"
            )) {
                if (!metadata.containsKey(key)) {
                    copyMetadataKey(dispatchPreflight.metadata(), metadata, key);
                }
            }
        }
        return Map.copyOf(metadata);
    }

    private String providerFailureValue(String key,
                                        AgentProviderStatus providerStatus,
                                        DispatchPreflightStatus dispatchPreflight) {
        String fromDispatch = dispatchPreflight != null && dispatchPreflight.metadata() != null
            ? metadataString(dispatchPreflight.metadata(), key)
            : null;
        if (fromDispatch != null && !fromDispatch.isBlank()) {
            return fromDispatch;
        }
        return providerStatus != null && providerStatus.metadata() != null
            ? metadataString(providerStatus.metadata(), key)
            : null;
    }

    private Boolean providerFailureRetryable(AgentProviderStatus providerStatus,
                                             DispatchPreflightStatus dispatchPreflight) {
        Object fromDispatch = dispatchPreflight != null && dispatchPreflight.metadata() != null
            ? dispatchPreflight.metadata().get("provider_retryable")
            : null;
        if (fromDispatch != null) {
            return Boolean.parseBoolean(fromDispatch.toString());
        }
        Object fromProvider = providerStatus != null && providerStatus.metadata() != null
            ? providerStatus.metadata().get("provider_retryable")
            : null;
        return fromProvider == null ? null : Boolean.parseBoolean(fromProvider.toString());
    }

    private void copyMetadataKey(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null || !source.containsKey(key)) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private String dispatchPreflightMode(AgentProviderStatus status) {
        if (status == null || status.metadata() == null) {
            return "unknown";
        }
        Object value = status.metadata().get("dispatch_preflight_mode");
        String mode = value == null ? null : value.toString();
        return mode == null || mode.isBlank() ? "unknown" : mode;
    }

    private boolean requireActiveDispatchPreflight() {
        return Boolean.getBoolean("agentcloud.dispatch.preflight.require_active_probe");
    }

    private Optional<TemporaryUnavailability> currentTemporaryUnavailability(String workerId) {
        String normalizedWorkerId = blankToNull(workerId);
        if (normalizedWorkerId == null) {
            return Optional.empty();
        }
        TemporaryUnavailability status = temporarilyUnavailableWorkers.get(normalizedWorkerId);
        if (status == null) {
            return Optional.empty();
        }
        if (status.unavailableUntilEpochMs() <= System.currentTimeMillis()) {
            temporarilyUnavailableWorkers.remove(normalizedWorkerId);
            log.info("Worker temporary unavailability expired: worker={}", normalizedWorkerId);
            return Optional.empty();
        }
        return Optional.of(status);
    }

    private String providerId(Worker worker) {
        if (!providerBacked(worker)) {
            return null;
        }
        return AgentProviderResolver.providerIdForWorker(
            worker != null ? worker.workerId() : null,
            worker != null ? worker.workerType() : null
        );
    }

    private boolean providerBacked(Worker worker) {
        String backend = metadataString(worker != null ? worker.metadata() : null, "execution_backend");
        return backend != null && backend.startsWith("provider_");
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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

    public record ReadinessCheck(String workerId,
                                 boolean ready,
                                 Map<String, Boolean> checks,
                                 String reason,
                                 String mode,
                                 Boolean dispatchPreflightReady,
                                 String dispatchPreflightReason,
                                 Boolean dispatchPreflightCached,
                                 String dispatchPreflightMode,
                                 Boolean dispatchPreflightActiveProbe,
                                 Map<String, Object> dispatchPreflightMetadata,
                                 Map<String, Object> cliProfile,
                                 String providerFailureClass,
                                 String providerFailureReason,
                                 Boolean providerRetryable) {
        public ReadinessCheck {
            if (dispatchPreflightMetadata == null) dispatchPreflightMetadata = Map.of();
            if (cliProfile == null) cliProfile = Map.of();
        }
    }

    private record TemporaryUnavailability(long unavailableUntilEpochMs, String reason) {}

    private record DispatchPreflightStatus(boolean ready,
                                           String reason,
                                           boolean cached,
                                           String mode,
                                           long expiresAtEpochMs,
                                           Map<String, Object> metadata) {
        private DispatchPreflightStatus {
            if (metadata == null) metadata = Map.of();
        }

        boolean activeProbe() {
            return "active_probe".equals(mode);
        }

        DispatchPreflightStatus withCached(boolean cached) {
            return new DispatchPreflightStatus(ready, reason, cached, mode, expiresAtEpochMs, metadata);
        }
    }
}
