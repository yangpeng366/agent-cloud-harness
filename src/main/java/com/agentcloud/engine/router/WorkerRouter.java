package com.agentcloud.engine.router;

import com.agentcloud.engine.LearningMemoryService;
import com.agentcloud.model.Task;
import com.agentcloud.model.Worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class WorkerRouter {
    private static final Logger log = LoggerFactory.getLogger(WorkerRouter.class);
    private final WorkerRegistry registry;
    private final LearningMemoryService learningMemoryService;

    public WorkerRouter(WorkerRegistry registry) {
        this(registry, null);
    }

    public WorkerRouter(WorkerRegistry registry, LearningMemoryService learningMemoryService) {
        this.registry = registry;
        this.learningMemoryService = learningMemoryService;
    }

    public RouteResult selectWorker(Task task) {
        String taskType = task.metadata() != null && task.metadata().get("task_type") instanceof String
            ? (String) task.metadata().get("task_type") : "general";

        String preferredWorker = learningMemoryService != null
            ? learningMemoryService.selectPreferredWorker(taskType)
            : null;

        List<Worker> capable = registry.findCapable(taskType);
        if (capable.isEmpty()) {
            capable = registry.listAll().stream().filter(Worker::ready).toList();
        }
        List<String> candidateWorkers = capable.stream().map(Worker::workerId).toList();

        if (preferredWorker != null && !preferredWorker.isBlank()) {
            Worker hinted = capable.stream()
                .filter(w -> preferredWorker.equals(w.workerId()))
                .filter(w -> registry.checkReadiness(w.workerId()).ready())
                .findFirst()
                .orElse(null);
            if (hinted != null) {
                List<String> fallbacks = capable.stream()
                    .filter(w -> !w.workerId().equals(hinted.workerId()))
                    .map(Worker::workerId)
                    .limit(2)
                    .toList();
                String reason = "selected by learning memory hint: taskType=" + taskType + ", worker=" + hinted.workerId();
                log.info(reason);
                return new RouteResult(task.id(), hinted.workerId(), fallbacks, reason,
                    "learning_memory", taskType, preferredWorker, true, candidateWorkers);
            }
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
            return new RouteResult(task.id(), null, List.of(), "no capable worker found",
                "none", taskType, preferredWorker, false, candidateWorkers);
        }

        List<String> fallbacks = capable.stream()
            .filter(w -> !w.workerId().equals(selected.workerId()))
            .map(Worker::workerId)
            .limit(2)
            .toList();

        String reason = "selected by capability match: taskType=" + taskType + ", worker=" + selected.workerId();
        log.info(reason);
        return new RouteResult(task.id(), selected.workerId(), fallbacks, reason,
            "capability_match", taskType, preferredWorker, false, candidateWorkers);
    }

    public record RouteResult(
        String taskId,
        String selectedWorker,
        List<String> fallbackWorkers,
        String routeReason,
        String routeSource,
        String taskType,
        String preferredWorkerHint,
        boolean learningHintApplied,
        List<String> candidateWorkers
    ) {}
}
