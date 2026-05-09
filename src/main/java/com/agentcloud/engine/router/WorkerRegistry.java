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
import java.util.stream.Collectors;

public class WorkerRegistry {
    private static final Logger log = LoggerFactory.getLogger(WorkerRegistry.class);
    private final Map<String, Worker> workers = Collections.synchronizedMap(new LinkedHashMap<>());
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
                "tool_command_mode", "guarded",
                "execution_backend", "provider_app_server"
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
                "execution_backend", "provider_native_cli"
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
                "execution_backend", "provider_native_cli"
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
                "execution_backend", "provider_native_cli"
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
                "execution_backend", "provider_native_cli"
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
                "execution_backend", "provider_native_cli"
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
                "execution_backend", "provider_native_cli"
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
                "execution_backend", "provider_native_cli"
            ), true, true));
        register(new Worker("hermes", "hermes",
            List.of("coding", "research", "writing"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "small",
                "primary_role", "executor",
                "selection_priority", 72,
                "execution_backend", "provider_native_cli"
            ),
            true, true));
        register(new Worker("pi", "pi",
            List.of("research", "writing", "message"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "small",
                "primary_role", "assistant",
                "selection_priority", 70,
                "execution_backend", "provider_native_cli"
            ),
            true, true));
        register(new Worker("kiro", "kiro",
            List.of("coding", "reading"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "small",
                "primary_role", "executor",
                "selection_priority", 69,
                "execution_backend", "provider_native_cli"
            ),
            true, true));
    }

    public Worker register(Worker worker) {
        Worker enriched = enrich(worker);
        workers.put(enriched.workerId(), enriched);
        log.info("Worker registered: {} (type={}, caps={}, tools={}, suggestOnly={})",
            enriched.workerId(), enriched.workerType(), enriched.capabilities(),
            enriched.toolCapabilities(), enriched.suggestOnly());
        return enriched;
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

    public List<Worker> findCapable(String taskType) {
        return workers.values().stream()
            .filter(w -> w.capabilities().contains(taskType) || w.capabilities().contains("general"))
            .filter(w -> checkReadiness(w.workerId()).ready())
            .collect(Collectors.toList());
    }

    public ReadinessCheck checkReadiness(String workerId) {
        Worker w = workers.get(workerId);
        if (w == null) return new ReadinessCheck(workerId, false, Map.of(), "worker not found");
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
        boolean allOk = checks.values().stream().allMatch(Boolean::booleanValue);
        return new ReadinessCheck(
            workerId,
            allOk && w.ready(),
            Map.copyOf(checks),
            readinessReason(w, checks, providerId, providerStatus)
        );
    }

    private static List<String> defaultCodexToolCapabilities() {
        List<String> tools = new ArrayList<>(List.of(
            "list_files", "read_file", "search_text", "write_file", "write_files", "patch_file"
        ));
        tools.addAll(HostToolAvailability.supportedCommandToolCapabilities());
        return List.copyOf(tools);
    }

    private Worker enrich(Worker worker) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(worker.metadata());
        metadata.put("host_platform", HostToolAvailability.isWindowsHost() ? "windows" : "posix");
        String providerId = providerId(worker);
        if (providerId != null) {
            metadata.put("provider_id", providerId);
        }

        Map<String, Boolean> toolAvailability = HostToolAvailability.declaredToolAvailability(worker.toolCapabilities());
        if (!toolAvailability.isEmpty()) {
            metadata.put("host_tool_availability", toolAvailability);
        }

        return new Worker(
            worker.workerId(),
            worker.workerType(),
            worker.capabilities(),
            worker.toolCapabilities(),
            worker.toolScope(),
            worker.dependencies(),
            Map.copyOf(metadata),
            worker.suggestOnly(),
            worker.ready()
        );
    }

    private String readinessReason(Worker worker,
                                   Map<String, Boolean> checks,
                                   String providerId,
                                   AgentProviderStatus providerStatus) {
        String executionBackend = metadataString(worker != null ? worker.metadata() : null, "execution_backend");
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
            return "dependency not satisfied: " + entry.getKey();
        }
        return worker.ready() ? "ready" : "worker marked not ready";
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

    public record ReadinessCheck(String workerId, boolean ready, Map<String, Boolean> checks, String reason) {}
}
