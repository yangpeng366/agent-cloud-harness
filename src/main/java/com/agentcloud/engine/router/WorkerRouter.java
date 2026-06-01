package com.agentcloud.engine.router;

import com.agentcloud.engine.TaskTypeHeuristics;
import com.agentcloud.engine.LearningMemoryService;
import com.agentcloud.model.Task;
import com.agentcloud.model.Worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

public class WorkerRouter {
    private static final Logger log = LoggerFactory.getLogger(WorkerRouter.class);
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("(?i)\\b[a-z]:\\\\[^\\r\\n]+");
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

    public void markWorkerTemporarilyUnavailable(String workerId, String reason) {
        registry.markTemporarilyUnavailable(workerId, reason);
    }

    public boolean isWorkerReady(String workerId) {
        return registry.checkReadiness(workerId).ready();
    }

    public String workerReadinessReason(String workerId) {
        WorkerRegistry.ReadinessCheck readiness = registry.checkReadiness(workerId);
        return readiness != null ? readiness.reason() : null;
    }

    public WorkerRegistry.ReadinessCheck checkDispatchReadiness(String workerId) {
        return registry.checkReadiness(workerId, "dispatch");
    }

    public RouteResult selectWorker(Task task) {
        String taskType = TaskTypeHeuristics.effectiveTaskType(task, "general");
        String preferredModelTier = resolvePreferredModelTier(task);
        String pinnedWorker = resolvePinnedWorker(task);

        if (pinnedWorker != null) {
            Worker pinned = registry.get(pinnedWorker);
            WorkerRegistry.ReadinessCheck pinnedReadiness = pinned != null
                ? registry.checkReadiness(pinnedWorker, "dispatch")
                : null;
            LocalWorkspaceRequirement workspaceRequirement = localWorkspaceRequirement(task, taskType);
            boolean pinnedLacksWorkspaceAccess = workspaceRequirement.required()
                && pinned != null
                && !hasLocalWorkspaceAccess(pinned);
            if (pinned != null && pinnedReadiness.ready() && !pinnedLacksWorkspaceAccess) {
                List<Worker> capable = registry.findCapable(taskType);
                List<String> candidateWorkers = capable.isEmpty()
                    ? registry.listReady().stream().map(Worker::workerId).toList()
                    : capable.stream().map(Worker::workerId).toList();
                String reason = "selected by task-pinned worker: taskType=" + taskType + ", worker=" + pinned.workerId();
                log.info(reason);
                return routeResult(task.id(), pinned, List.of(), reason,
                    "task_pinned", taskType, pinnedWorker, false, candidateWorkers, null, null, List.of());
            }
            String fallbackReason = pinned == null
                ? "task-pinned worker '" + pinnedWorker + "' not registered"
                : pinnedLacksWorkspaceAccess
                ? "task-pinned worker '" + pinnedWorker + "' lacks local workspace access required by "
                + firstNonBlank(workspaceRequirement.reason(), "task")
                : "task-pinned worker '" + pinnedWorker + "' not dispatch ready: "
                + firstNonBlank(pinnedReadiness != null ? pinnedReadiness.reason() : null, "readiness unknown");
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
                mergeReasons(fallbackReason, fallback.fallbackReason()),
                fallback.recoveryProviderDeprioritized(),
                fallback.recoveryDeprioritizedProvider(),
                fallback.recoveryDeprioritizationReason(),
                fallback.recoveryExecutionMode(),
                fallback.currentPinnedRoute(),
                fallback.recoveryUnpinnedRecommendation(),
                fallback.dispatchSkippedWorkers()
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
        List<Worker> tierFallbackCapable = capable;
        boolean preferredTierApplied = false;
        if (preferredModelTier != null) {
            List<Worker> tierPreferred = capable.stream()
                .filter(worker -> preferredModelTier.equalsIgnoreCase(metadataString(worker.metadata(), "model_tier")))
                .toList();
            if (!tierPreferred.isEmpty()) {
                capable = tierPreferred;
                preferredTierApplied = true;
            } else {
                fallbackReason = mergeReasons(
                    fallbackReason,
                    "no ready worker matched preferred model tier=" + preferredModelTier
                );
            }
        }
        LocalWorkspaceRequirement workspaceRequirement = localWorkspaceRequirement(task, taskType);
        if (workspaceRequirement.required()) {
            List<Worker> workspaceCapable = capable.stream()
                .filter(this::hasLocalWorkspaceAccess)
                .toList();
            if (!workspaceCapable.isEmpty()) {
                fallbackReason = mergeReasons(
                    fallbackReason,
                    explainLocalWorkspaceAccessFallback(capable, workspaceCapable, workspaceRequirement.reason())
                );
                capable = workspaceCapable;
            } else {
                fallbackReason = mergeReasons(
                    fallbackReason,
                    "local workspace access required but no current candidate declares local_workspace_access=true"
                );
            }
        }
        if (shouldApplyAutoRouteTaskTypeContract(taskType)) {
            List<Worker> taskContractCapable = capable.stream()
                .filter(worker -> autoRouteAllowedForTaskType(worker, taskType))
                .toList();
            if (!taskContractCapable.isEmpty()) {
                fallbackReason = mergeReasons(
                    fallbackReason,
                    explainAutoRouteTaskTypeFallback(capable, taskContractCapable, taskType)
                );
            } else if (!capable.isEmpty()) {
                fallbackReason = mergeReasons(
                    fallbackReason,
                    "auto-route task type contract rejected all current candidates for taskType=" + taskType
                );
            }
            capable = taskContractCapable;
        }
        List<String> candidateWorkers = capable.stream().map(Worker::workerId).toList();
        DispatchReadinessSelection dispatchSelection = selectDispatchReadyWorkers(capable, taskType, true);
        Map<String, WorkerRegistry.ReadinessCheck> dispatchReadinessByWorker = dispatchSelection.readinessByWorker();
        List<Worker> dispatchReadyCapable = dispatchSelection.readyWorkers();
        fallbackReason = mergeReasons(
            fallbackReason,
            explainDispatchReadinessFallback(capable, dispatchReadyCapable, dispatchReadinessByWorker)
        );
        List<RouteSkippedWorker> dispatchSkippedWorkers =
            dispatchSkippedWorkers(capable, dispatchReadyCapable, dispatchReadinessByWorker);
        if (dispatchReadyCapable.isEmpty() && preferredModelTier != null && tierFallbackCapable != capable) {
            DispatchReadinessSelection tierFallbackDispatchSelection =
                selectDispatchReadyWorkers(tierFallbackCapable, taskType, true);
            Map<String, WorkerRegistry.ReadinessCheck> tierFallbackDispatchReadinessByWorker =
                tierFallbackDispatchSelection.readinessByWorker();
            List<Worker> tierFallbackDispatchReady = tierFallbackDispatchSelection.readyWorkers();
            if (!tierFallbackDispatchReady.isEmpty()) {
                fallbackReason = mergeReasons(
                    fallbackReason,
                    "no dispatch-ready worker matched preferred model tier=" + preferredModelTier
                );
                capable = tierFallbackCapable;
                preferredTierApplied = false;
                dispatchReadyCapable = tierFallbackDispatchReady;
                dispatchSkippedWorkers = dispatchSkippedWorkers(
                    tierFallbackCapable,
                    tierFallbackDispatchReady,
                    tierFallbackDispatchReadinessByWorker
                );
            }
        }

        if (preferredWorker != null && !preferredWorker.isBlank()) {
            Worker hinted = dispatchReadyCapable.stream()
                .filter(w -> preferredWorker.equals(w.workerId()))
                .findFirst()
                .orElse(null);
            if (hinted != null) {
                List<String> fallbacks = dispatchReadyCapable.stream()
                    .filter(w -> !w.workerId().equals(hinted.workerId()))
                    .map(Worker::workerId)
                    .limit(2)
                    .toList();
                String reason = "selected by learning memory hint: taskType=" + taskType + ", worker=" + hinted.workerId();
                log.info(reason);
                return routeResult(task.id(), hinted, fallbacks, reason,
                    "learning_memory", taskType, preferredWorker, true, candidateWorkers,
                    suppressNonBlockingFallbackReason(fallbackReason), null,
                    dispatchSkippedWorkers);
            }
            fallbackReason = mergeReasons(
                fallbackReason,
                explainLearningHintFallback(preferredWorker, candidateWorkers, dispatchSkippedWorkers)
            );
        }

        // 简单策略：优先找 readiness 全过的，按 capability 匹配数排序
        Worker selected = dispatchReadyCapable.isEmpty() ? null : dispatchReadyCapable.get(0);

        if (selected == null) {
            return routeResult(task.id(), null, List.of(), "no capable worker found",
                "none", taskType, preferredWorker, false, candidateWorkers, fallbackReason, null,
                dispatchSkippedWorkers);
        }

        List<String> fallbacks = dispatchReadyCapable.stream()
            .filter(w -> !w.workerId().equals(selected.workerId()))
            .map(Worker::workerId)
            .limit(2)
            .toList();

        String reason;
        if (preferredModelTier != null && preferredTierApplied) {
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
            taskType, preferredWorker, false, candidateWorkers, fallbackReason, null,
            dispatchSkippedWorkers);
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
                                    String fallbackReason,
                                    String recoveryExecutionMode,
                                    List<RouteSkippedWorker> dispatchSkippedWorkers) {
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
            blankToNull(fallbackReason),
            null,
            null,
            null,
            blankToNull(recoveryExecutionMode),
            null,
            null,
            dispatchSkippedWorkers == null ? List.of() : dispatchSkippedWorkers
        );
    }

    private String explainLearningHintFallback(String preferredWorker,
                                               List<String> candidateWorkers,
                                               List<RouteSkippedWorker> dispatchSkippedWorkers) {
        if (preferredWorker == null || preferredWorker.isBlank()) {
            return null;
        }
        Worker hinted = registry.get(preferredWorker);
        if (hinted == null) {
            return "learning memory hint '" + preferredWorker + "' not registered";
        }
        WorkerRegistry.ReadinessCheck dispatchReadiness = registry.checkReadiness(preferredWorker, "dispatch");
        if (!dispatchReadiness.ready()) {
            String reason = routeSkippedReason(preferredWorker, dispatchSkippedWorkers);
            return "learning memory hint '" + preferredWorker + "' not dispatch ready: "
                + firstNonBlank(reason, dispatchReadiness.dispatchPreflightReason(), dispatchReadiness.reason(), "readiness unknown");
        }
        if (!hinted.ready() || !registry.checkReadiness(preferredWorker).ready()) {
            return "learning memory hint '" + preferredWorker + "' not ready";
        }
        if (candidateWorkers == null || !candidateWorkers.contains(preferredWorker)) {
            return "learning memory hint '" + preferredWorker + "' not in current candidate set";
        }
        return null;
    }

    private String routeSkippedReason(String workerId, List<RouteSkippedWorker> dispatchSkippedWorkers) {
        if (workerId == null || workerId.isBlank() || dispatchSkippedWorkers == null || dispatchSkippedWorkers.isEmpty()) {
            return null;
        }
        return dispatchSkippedWorkers.stream()
            .filter(skipped -> workerId.equals(skipped.workerId()))
            .map(RouteSkippedWorker::reason)
            .filter(reason -> reason != null && !reason.isBlank())
            .findFirst()
            .orElse(null);
    }

    private LocalWorkspaceRequirement localWorkspaceRequirement(Task task, String taskType) {
        String normalizedTaskType = blankToNull(taskType);
        if (task == null || (!"coding".equalsIgnoreCase(normalizedTaskType)
            && !"ops".equalsIgnoreCase(normalizedTaskType))) {
            return new LocalWorkspaceRequirement(false, null);
        }
        Map<String, Object> metadata = task.metadata();
        String combined = joinNonBlank(
            task.goal(),
            task.title(),
            metadataString(metadata, "goal"),
            metadataString(metadata, "intent"),
            metadataString(metadata, "workspace_root"),
            metadataString(metadata, "workspace"),
            metadataString(metadata, "working_directory"),
            metadataString(metadata, "cwd"),
            metadataString(metadata, "repo_path")
        );
        String lower = combined.toLowerCase(Locale.ROOT);
        boolean explicitWorkspace = firstNonBlank(
            metadataString(metadata, "workspace_root"),
            metadataString(metadata, "workspace"),
            metadataString(metadata, "working_directory"),
            metadataString(metadata, "cwd"),
            metadataString(metadata, "repo_path")
        ) != null;
        boolean mentionsLocalPath = WINDOWS_ABSOLUTE_PATH.matcher(combined).find()
            || lower.contains("\\gitall\\")
            || lower.contains("/src/main/")
            || lower.contains("\\src\\main\\")
            || lower.contains("pom.xml")
            || lower.contains("package.json");
        if (!explicitWorkspace && !mentionsLocalPath) {
            return new LocalWorkspaceRequirement(false, null);
        }
        return new LocalWorkspaceRequirement(true, explicitWorkspace ? "explicit workspace metadata" : "local path signal");
    }

    private String joinNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String item = blankToNull(value);
            if (item != null) {
                normalized.add(item);
            }
        }
        return String.join("\n", normalized);
    }

    private boolean hasLocalWorkspaceAccess(Worker worker) {
        if (worker == null) {
            return false;
        }
        if (metadataBoolean(worker.metadata(), "local_workspace_access")) {
            return true;
        }
        return worker.toolScope() != null && !worker.toolScope().isEmpty()
            && worker.toolCapabilities() != null && !worker.toolCapabilities().isEmpty();
    }

    private String explainLocalWorkspaceAccessFallback(List<Worker> before,
                                                       List<Worker> after,
                                                       String reason) {
        List<String> kept = after == null ? List.of() : after.stream().map(Worker::workerId).toList();
        List<String> skipped = before == null ? List.of() : before.stream()
            .filter(worker -> worker != null && !kept.contains(worker.workerId()))
            .map(worker -> worker.workerId() + " skipped: local_workspace_access=false")
            .limit(5)
            .toList();
        if (skipped.isEmpty()) {
            return null;
        }
        return "local workspace access required (" + firstNonBlank(reason, "task needs local files")
            + "); " + String.join(", ", skipped);
    }

    private boolean autoRouteAllowedForTaskType(Worker worker, String taskType) {
        if (worker == null || taskType == null || taskType.isBlank()) {
            return true;
        }
        List<String> allowedTaskTypes = metadataStringList(worker.metadata(), "auto_route_task_types");
        if (allowedTaskTypes.isEmpty()) {
            return true;
        }
        return allowedTaskTypes.stream()
            .anyMatch(allowed -> taskType.equalsIgnoreCase(allowed) || "general".equalsIgnoreCase(allowed));
    }

    private boolean shouldApplyAutoRouteTaskTypeContract(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            return false;
        }
        return switch (taskType.toLowerCase(Locale.ROOT)) {
            case "coding", "ops", "reading", "writing", "research", "message", "browser", "doc", "search", "session" -> true;
            default -> false;
        };
    }

    private String explainAutoRouteTaskTypeFallback(List<Worker> before, List<Worker> after, String taskType) {
        List<String> kept = after == null ? List.of() : after.stream().map(Worker::workerId).toList();
        List<String> skipped = before == null ? List.of() : before.stream()
            .filter(worker -> worker != null && !kept.contains(worker.workerId()))
            .map(worker -> worker.workerId() + " skipped: auto_route_task_types="
                + metadataStringList(worker.metadata(), "auto_route_task_types"))
            .limit(5)
            .toList();
        if (skipped.isEmpty()) {
            return null;
        }
        return "auto-route task type contract for taskType=" + taskType + "; " + String.join(", ", skipped);
    }

    private boolean metadataBoolean(Map<String, Object> metadata, String key) {
        String value = metadataString(metadata, key);
        return value != null && Boolean.parseBoolean(value);
    }

    private DispatchReadinessSelection selectDispatchReadyWorkers(List<Worker> workers,
                                                                  String taskType,
                                                                  boolean stopAfterFirstReady) {
        Map<String, WorkerRegistry.ReadinessCheck> readinessByWorker = new LinkedHashMap<>();
        List<Worker> readyWorkers = new ArrayList<>();
        if (workers == null) {
            return new DispatchReadinessSelection(List.of(), readinessByWorker);
        }
        List<Worker> ordered = workers.stream()
            .filter(worker -> worker != null && worker.workerId() != null)
            .sorted(routeComparator(taskType).reversed())
            .toList();
        for (Worker worker : ordered) {
            if (worker == null || worker.workerId() == null) {
                continue;
            }
            WorkerRegistry.ReadinessCheck readiness = registry.checkReadiness(worker.workerId(), "dispatch");
            readinessByWorker.put(worker.workerId(), readiness);
            if (readiness != null && readiness.ready()) {
                readyWorkers.add(worker);
                if (stopAfterFirstReady) {
                    break;
                }
            }
        }
        return new DispatchReadinessSelection(readyWorkers, readinessByWorker);
    }

    private boolean isDispatchReady(Worker worker, Map<String, WorkerRegistry.ReadinessCheck> readinessByWorker) {
        if (worker == null) {
            return false;
        }
        WorkerRegistry.ReadinessCheck readiness = readinessByWorker != null
            ? readinessByWorker.get(worker.workerId())
            : null;
        return readiness != null && readiness.ready();
    }

    private String explainDispatchReadinessFallback(List<Worker> capable,
                                                    List<Worker> dispatchReadyCapable,
                                                    Map<String, WorkerRegistry.ReadinessCheck> readinessByWorker) {
        List<RouteSkippedWorker> skippedWorkers = dispatchSkippedWorkers(capable, dispatchReadyCapable, readinessByWorker);
        if (skippedWorkers.isEmpty()) {
            return null;
        }
        return "dispatch readiness skipped worker(s): " + String.join(", ", skippedWorkers.stream()
            .map(skipped -> skipped.workerId() + " skipped: " + skipped.reason())
            .limit(3)
            .toList());
    }

    private List<RouteSkippedWorker> dispatchSkippedWorkers(List<Worker> capable,
                                                            List<Worker> dispatchReadyCapable,
                                                            Map<String, WorkerRegistry.ReadinessCheck> readinessByWorker) {
        if (capable == null || capable.isEmpty()) {
            return List.of();
        }
        List<String> readyWorkerIds = dispatchReadyCapable == null
            ? List.of()
            : dispatchReadyCapable.stream().map(Worker::workerId).toList();
        return capable.stream()
            .filter(worker -> worker != null && !readyWorkerIds.contains(worker.workerId()))
            .filter(worker -> readinessByWorker == null || readinessByWorker.containsKey(worker.workerId()))
            .map(worker -> {
                WorkerRegistry.ReadinessCheck readiness = readinessByWorker != null
                    ? readinessByWorker.get(worker.workerId())
                    : null;
                String reason = firstNonBlank(
                    readiness != null ? readiness.dispatchPreflightReason() : null,
                    readiness != null ? readiness.reason() : null,
                    "not dispatch ready"
                );
                return new RouteSkippedWorker(
                    worker.workerId(),
                    reason,
                    readiness != null ? readiness.providerFailureClass() : null,
                    readiness != null ? readiness.providerFailureReason() : null,
                    readiness != null ? readiness.providerRetryable() : null
                );
            })
            .limit(3)
            .toList();
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private List<String> metadataStringList(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return List.of();
        }
        Object value = metadata.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                .map(item -> item == null ? null : item.toString())
                .filter(item -> item != null && !item.isBlank())
                .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.split(",")).stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
        }
        return List.of();
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

    private String suppressNonBlockingFallbackReason(String fallbackReason) {
        String normalized = blankToNull(fallbackReason);
        if (normalized == null) {
            return null;
        }
        if (normalized.startsWith("auto-route task type contract for taskType=") && !normalized.contains("; dispatch ")) {
            return null;
        }
        return normalized;
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

    private record LocalWorkspaceRequirement(boolean required, String reason) {}

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
        String fallbackReason,
        Boolean recoveryProviderDeprioritized,
        String recoveryDeprioritizedProvider,
        String recoveryDeprioritizationReason,
        String recoveryExecutionMode,
        RouteDiagnostic currentPinnedRoute,
        RouteDiagnostic recoveryUnpinnedRecommendation,
        List<RouteSkippedWorker> dispatchSkippedWorkers
    ) {}

    public record RouteSkippedWorker(
        String workerId,
        String reason,
        String providerFailureClass,
        String providerFailureReason,
        Boolean providerRetryable
    ) {}

    private record DispatchReadinessSelection(
        List<Worker> readyWorkers,
        Map<String, WorkerRegistry.ReadinessCheck> readinessByWorker
    ) {
        private DispatchReadinessSelection {
            if (readyWorkers == null) readyWorkers = List.of();
            if (readinessByWorker == null) readinessByWorker = Map.of();
        }
    }

    public record RouteDiagnostic(
        String selectedWorker,
        String routeSource,
        String taskType,
        String selectedWorkerType,
        String selectedModelTier,
        String selectedExecutionRole,
        String selectionScope,
        String whySelected,
        String fallbackReason,
        String preferredWorkerHint,
        boolean learningHintApplied,
        String recoveryExecutionMode,
        Boolean providerDeprioritized,
        String deprioritizedProvider,
        String deprioritizationReason,
        List<String> candidateWorkers,
        List<String> fallbackWorkers
    ) {}
}
