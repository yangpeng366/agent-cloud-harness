package com.agentcloud.engine.router;

import com.agentcloud.engine.LearningMemoryService;
import com.agentcloud.model.Task;
import com.agentcloud.model.Worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

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

    public Worker getWorker(String workerId) {
        return registry.get(workerId);
    }

    public RouteResult selectWorker(Task task) {
        String taskType = task.metadata() != null && task.metadata().get("task_type") instanceof String
            ? (String) task.metadata().get("task_type") : "general";
        String preferredModelTier = resolvePreferredModelTier(task);
        String pinnedWorker = resolvePinnedWorker(task);

        if (pinnedWorker != null) {
            Worker pinned = registry.get(pinnedWorker);
            if (pinned != null && registry.checkReadiness(pinnedWorker).ready()) {
                List<Worker> capable = registry.findCapable(taskType);
                List<String> candidateWorkers = capable.isEmpty()
                    ? registry.listReady().stream().map(Worker::workerId).toList()
                    : capable.stream().map(Worker::workerId).toList();
                String reason = "selected by task-pinned worker: taskType=" + taskType + ", worker=" + pinned.workerId();
                log.info(reason);
                return routeResult(task.id(), pinned, List.of(), reason,
                    "task_pinned", taskType, pinnedWorker, false, candidateWorkers, null);
            }
            String fallbackReason = pinned == null
                ? "task-pinned worker '" + pinnedWorker + "' not registered"
                : "task-pinned worker '" + pinnedWorker + "' not ready";
            RouteResult fallback = selectWorkerWithoutPinned(task, taskType, preferredModelTier);
            return new RouteResult(
                fallback.taskId(),
                fallback.selectedWorker(),
                fallback.fallbackWorkers(),
                fallback.routeReason(),
                fallback.routeSource(),
                fallback.taskType(),
                pinnedWorker,
                fallback.learningHintApplied(),
                fallback.candidateWorkers(),
                fallback.selectedWorkerType(),
                fallback.selectedModelTier(),
                fallback.selectedExecutionRole(),
                fallback.selectionScope(),
                fallback.whySelected(),
                mergeReasons(fallbackReason, fallback.fallbackReason())
            );
        }

        return selectWorkerWithoutPinned(task, taskType, preferredModelTier);
    }

    private RouteResult selectWorkerWithoutPinned(Task task, String taskType, String preferredModelTier) {

        String preferredWorker = learningMemoryService != null
            ? learningMemoryService.selectPreferredWorker(taskType)
            : null;

        List<Worker> capable = registry.findCapable(taskType);
        boolean readyFallback = capable.isEmpty();
        String fallbackReason = null;
        if (capable.isEmpty()) {
            capable = registry.listReady();
            fallbackReason = "no ready worker advertised taskType=" + taskType + ", fallback to any ready worker";
        }
        if (preferredModelTier != null) {
            List<Worker> tierPreferred = capable.stream()
                .filter(worker -> preferredModelTier.equalsIgnoreCase(metadataString(worker.metadata(), "model_tier")))
                .toList();
            if (!tierPreferred.isEmpty()) {
                capable = tierPreferred;
            } else {
                fallbackReason = mergeReasons(
                    fallbackReason,
                    "no ready worker matched preferred model tier=" + preferredModelTier
                );
            }
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
                return routeResult(task.id(), hinted, fallbacks, reason,
                    "learning_memory", taskType, preferredWorker, true, candidateWorkers, fallbackReason);
            }
            fallbackReason = mergeReasons(fallbackReason, explainLearningHintFallback(preferredWorker, candidateWorkers));
        }

        // 简单策略：优先找 readiness 全过的，按 capability 匹配数排序
        Worker selected = capable.stream()
            .filter(w -> registry.checkReadiness(w.workerId()).ready())
            .max(routeComparator(taskType))
            .orElse(null);

        if (selected == null) {
            return routeResult(task.id(), null, List.of(), "no capable worker found",
                "none", taskType, preferredWorker, false, candidateWorkers, fallbackReason);
        }

        List<String> fallbacks = capable.stream()
            .filter(w -> !w.workerId().equals(selected.workerId()))
            .map(Worker::workerId)
            .limit(2)
            .toList();

        String reason;
        if (preferredModelTier != null) {
            reason = readyFallback
                ? "selected by model tier preference (" + preferredModelTier + ") on ready-worker fallback: taskType=" + taskType + ", worker=" + selected.workerId()
                : "selected by model tier preference (" + preferredModelTier + ") on capability match: taskType=" + taskType + ", worker=" + selected.workerId();
        } else {
            reason = readyFallback
                ? "selected by ready-worker fallback: taskType=" + taskType + ", worker=" + selected.workerId()
                : "selected by capability match: taskType=" + taskType + ", worker=" + selected.workerId();
        }
        log.info(reason);
        return routeResult(task.id(), selected, fallbacks, reason,
            readyFallback ? "ready_fallback" : "capability_match",
            taskType, preferredWorker, false, candidateWorkers, fallbackReason);
    }

    private String resolvePreferredModelTier(Task task) {
        if (task == null) {
            return null;
        }
        String modelMode = metadataString(task.metadata(), "model_mode");
        if (modelMode == null || modelMode.isBlank()) {
            return null;
        }
        return switch (modelMode.toLowerCase()) {
            case "strong_only" -> "strong";
            case "small_only" -> "small";
            case "orchestrated" -> resolveOrchestratedTier(task);
            default -> null;
        };
    }

    private String resolveOrchestratedTier(Task task) {
        String stage = metadataString(task.metadata(), "orchestration_stage");
        if (stage == null || stage.isBlank()) {
            return "strong";
        }
        String normalized = stage.toLowerCase();
        if (normalized.startsWith("execution")) {
            return "small";
        }
        if ("completed".equals(normalized)) {
            return "strong";
        }
        return "strong";
    }

    private RouteResult routeResult(String taskId,
                                    Worker selected,
                                    List<String> fallbackWorkers,
                                    String routeReason,
                                    String routeSource,
                                    String taskType,
                                    String preferredWorkerHint,
                                    boolean learningHintApplied,
                                    List<String> candidateWorkers,
                                    String fallbackReason) {
        return new RouteResult(
            taskId,
            selected != null ? selected.workerId() : null,
            fallbackWorkers,
            routeReason,
            routeSource,
            taskType,
            preferredWorkerHint,
            learningHintApplied,
            candidateWorkers,
            selected != null ? selected.workerType() : null,
            metadataString(selected != null ? selected.metadata() : null, "model_tier"),
            metadataString(selected != null ? selected.metadata() : null, "primary_role"),
            null,
            routeReason,
            blankToNull(fallbackReason)
        );
    }

    private String explainLearningHintFallback(String preferredWorker, List<String> candidateWorkers) {
        if (preferredWorker == null || preferredWorker.isBlank()) {
            return null;
        }
        Worker hinted = registry.get(preferredWorker);
        if (hinted == null) {
            return "learning memory hint '" + preferredWorker + "' not registered";
        }
        if (!hinted.ready() || !registry.checkReadiness(preferredWorker).ready()) {
            return "learning memory hint '" + preferredWorker + "' not ready";
        }
        if (candidateWorkers == null || !candidateWorkers.contains(preferredWorker)) {
            return "learning memory hint '" + preferredWorker + "' not in current candidate set";
        }
        return null;
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private Comparator<Worker> routeComparator(String taskType) {
        return Comparator
            .comparingInt((Worker worker) -> exactCapabilityMatches(worker, taskType))
            .thenComparingInt(this::selectionPriority)
            .thenComparing(Worker::workerId);
    }

    private int exactCapabilityMatches(Worker worker, String taskType) {
        if (worker == null || worker.capabilities() == null || taskType == null || taskType.isBlank()) {
            return 0;
        }
        return (int) worker.capabilities().stream()
            .filter(capability -> taskType.equals(capability))
            .count();
    }

    private int selectionPriority(Worker worker) {
        String priority = metadataString(worker != null ? worker.metadata() : null, "selection_priority");
        if (priority == null) {
            return 0;
        }
        try {
            return Integer.parseInt(priority);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String resolvePinnedWorker(Task task) {
        if (task == null) {
            return null;
        }
        String assignedWorker = blankToNull(task.assignedWorker());
        if (assignedWorker != null) {
            return assignedWorker;
        }
        return firstNonBlank(
            metadataString(task.metadata(), "assigned_worker"),
            metadataString(task.metadata(), "target_worker"),
            metadataString(task.metadata(), "preferred_worker"),
            metadataString(task.metadata(), "provider_worker"),
            metadataString(task.metadata(), "execution_worker")
        );
    }

    private String mergeReasons(String left, String right) {
        String normalizedLeft = blankToNull(left);
        String normalizedRight = blankToNull(right);
        if (normalizedLeft == null) {
            return normalizedRight;
        }
        if (normalizedRight == null) {
            return normalizedLeft;
        }
        return normalizedLeft + "; " + normalizedRight;
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

    public record RouteResult(
        String taskId,
        String selectedWorker,
        List<String> fallbackWorkers,
        String routeReason,
        String routeSource,
        String taskType,
        String preferredWorkerHint,
        boolean learningHintApplied,
        List<String> candidateWorkers,
        String selectedWorkerType,
        String selectedModelTier,
        String selectedExecutionRole,
        String selectionScope,
        String whySelected,
        String fallbackReason
    ) {}
}
