package com.agentcloud.engine.router;

import com.agentcloud.model.Task;
import com.agentcloud.model.Worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class WorkerRouter {
    private static final Logger log = LoggerFactory.getLogger(WorkerRouter.class);
    private final WorkerRegistry registry;

    public WorkerRouter(WorkerRegistry registry) {
        this.registry = registry;
    }

    public RouteResult selectWorker(Task task) {
        String taskType = task.metadata() != null && task.metadata().get("task_type") instanceof String
            ? (String) task.metadata().get("task_type") : "general";

        List<Worker> capable = registry.findCapable(taskType);
        if (capable.isEmpty()) {
            capable = registry.listAll().stream().filter(Worker::ready).toList();
        }

        // 简单策略：优先找 readiness 全过的，按 capability 匹配数排序
        Worker selected = capable.stream()
            .filter(w -> registry.checkReadiness(w.workerId()).ready())
            .max((a, b) -> {
                int matchA = (int) a.capabilities().stream().filter(c -> c.equals(taskType)).count();
                int matchB = (int) b.capabilities().stream().filter(c -> c.equals(taskType)).count();
                return Integer.compare(matchA, matchB);
            })
            .orElse(capable.isEmpty() ? null : capable.get(0));

        if (selected == null) {
            return new RouteResult(task.id(), null, List.of(), "no capable worker found");
        }

        List<String> fallbacks = capable.stream()
            .filter(w -> !w.workerId().equals(selected.workerId()))
            .map(Worker::workerId)
            .limit(2)
            .toList();

        String reason = "selected by capability match: taskType=" + taskType + ", worker=" + selected.workerId();
        log.info(reason);
        return new RouteResult(task.id(), selected.workerId(), fallbacks, reason);
    }

    public record RouteResult(String taskId, String selectedWorker, List<String> fallbackWorkers, String routeReason) {}
}
