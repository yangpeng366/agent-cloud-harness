package com.agentcloud.engine.router;

import com.agentcloud.model.Worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class WorkerRegistry {
    private static final Logger log = LoggerFactory.getLogger(WorkerRegistry.class);
    private final Map<String, Worker> workers = new ConcurrentHashMap<>();

    public WorkerRegistry() {
        // 预注册内置 worker
        register(new Worker("openclaw-native", "native-tool",
            List.of("browser", "doc", "message", "search"),
            List.of(),
            List.of(),
            Map.of("config_present", true, "backend_reachable", true),
            Map.of(), false, true));
        register(new Worker("codex", "codex",
            List.of("coding", "reading", "ops"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(), false, true));
        register(new Worker("kimi", "kimi",
            List.of("coding", "research", "browser"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(), true, true));
    }

    public void register(Worker worker) {
        workers.put(worker.workerId(), worker);
        log.info("Worker registered: {} (type={}, caps={}, tools={}, suggestOnly={})",
            worker.workerId(), worker.workerType(), worker.capabilities(),
            worker.toolCapabilities(), worker.suggestOnly());
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

    public List<Worker> findCapable(String taskType) {
        return workers.values().stream()
            .filter(w -> w.capabilities().contains(taskType) || w.capabilities().contains("general"))
            .filter(Worker::ready)
            .collect(Collectors.toList());
    }

    public ReadinessCheck checkReadiness(String workerId) {
        Worker w = workers.get(workerId);
        if (w == null) return new ReadinessCheck(workerId, false, Map.of(), "worker not found");
        Map<String, Boolean> checks = new ConcurrentHashMap<>();
        if (w.dependencies() != null) {
            w.dependencies().forEach((k, v) -> checks.put(k, v));
        }
        boolean allOk = checks.values().stream().allMatch(Boolean::booleanValue);
        return new ReadinessCheck(workerId, allOk && w.ready(), checks, allOk ? "ready" : "dependency not satisfied");
    }

    public record ReadinessCheck(String workerId, boolean ready, Map<String, Boolean> checks, String reason) {}
}
