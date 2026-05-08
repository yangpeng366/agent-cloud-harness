package com.agentcloud.engine.router;

import com.agentcloud.model.Worker;
import com.agentcloud.tool.HostToolAvailability;
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

    public WorkerRegistry() {
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
                "execution_backend", "provider_native_cli"
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
        register(new Worker("kimi", "kimi",
            List.of("coding", "research", "browser"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of("model_tier", "small", "primary_role", "executor", "selection_priority", 80), true, true));
        register(new Worker("hermes", "hermes",
            List.of("coding", "research", "writing"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of("model_tier", "small", "primary_role", "executor", "selection_priority", 72),
            true, true));
        register(new Worker("pi", "pi",
            List.of("research", "writing", "message"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of("model_tier", "small", "primary_role", "assistant", "selection_priority", 70),
            true, true));
        register(new Worker("kiro", "kiro",
            List.of("coding", "reading"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of("model_tier", "small", "primary_role", "executor", "selection_priority", 69),
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
        boolean allOk = checks.values().stream().allMatch(Boolean::booleanValue);
        return new ReadinessCheck(workerId, allOk && w.ready(), Map.copyOf(checks), readinessReason(w, checks));
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

    private String readinessReason(Worker worker, Map<String, Boolean> checks) {
        for (Map.Entry<String, Boolean> entry : checks.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            if (entry.getKey().startsWith("tool:")) {
                String toolCapability = entry.getKey().substring("tool:".length());
                return HostToolAvailability.unavailableReason(toolCapability);
            }
            return "dependency not satisfied: " + entry.getKey();
        }
        return worker.ready() ? "ready" : "worker marked not ready";
    }

    public record ReadinessCheck(String workerId, boolean ready, Map<String, Boolean> checks, String reason) {}
}
