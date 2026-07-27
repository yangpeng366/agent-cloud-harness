package com.agentcloud.engine.router;

import com.agentcloud.agent.providers.ProviderProfileConfig;
import com.agentcloud.engine.LearningMemoryService;
import com.agentcloud.engine.TaskTypeHeuristics;
import com.agentcloud.model.Task;
import com.agentcloud.model.Worker;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    public List<Worker> listReadyWorkers() {
        return registry.listReady();
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
        String originalTaskType = TaskTypeHeuristics.effectiveTaskType(task, "general");
        String taskType = normalizeTaskTypeForRouting(task, originalTaskType);
        if (!java.util.Objects.equals(originalTaskType, taskType)) {
            log.info("Routing taskType promoted: task={} from={} to={} due to workspace mutation intent",
                task != null ? task.id() : null, originalTaskType, taskType);
        }
        String preferredModelTier = resolvePreferredModelTier(task);
        String pinnedWorker = resolvePinnedWorker(task);
        boolean freeFirstRouting = prefersFreeFirstRouting(task);

        if (pinnedWorker != null) {
            Worker pinned = registry.get(pinnedWorker);
            WorkerRegistry.ReadinessCheck pinnedReadiness = pinned != null
                ? registry.checkReadiness(pinnedWorker, "dispatch")
                : null;
            LocalWorkspaceRequirement workspaceRequirement = localWorkspaceRequirement(task, taskType);
            boolean pinnedLacksWorkspaceAccess = workspaceRequirement.required()
                && pinned != null
                && !hasLocalWorkspaceAccess(pinned);
            // 与 selectWorkerWithoutPinned 对齐：pinned worker 仍须满足 auto_route_task_types 契约。
            // 例如 research 任务因本地写文件意图被提升为 coding 后，openclaw-native 只声明
            // browser/doc/message/search/reading，不应继续被 pin 吸走，应回退到 codex 等编码 worker。
            boolean pinnedNotAllowedForTaskType = shouldApplyAutoRouteTaskTypeContract(taskType)
                && pinned != null
                && !autoRouteAllowedForTaskType(pinned, taskType);
            if (pinned != null && pinnedReadiness.ready()
                && !pinnedLacksWorkspaceAccess && !pinnedNotAllowedForTaskType) {
                List<Worker> capable = registry.findCapable(taskType);
                List<String> candidateWorkers = capable.isEmpty()
                    ? registry.listReady().stream().map(Worker::workerId).toList()
                    : capable.stream().map(Worker::workerId).toList();
                String reason = "selected by task-pinned worker: taskType=" + taskType + ", worker=" + pinned.workerId();
                log.info(reason);
                RouteResult pinnedResult = routeResult(task.id(), pinned, List.of(), reason,
                    "task_pinned", taskType, pinnedWorker, false, candidateWorkers,
                    null, null, List.of(), freeFirstRouting, List.of(), List.of(), null,
                    false, null, List.of());
                pinnedResult = applyCodexProfileRouting(task, pinnedResult);
                return attachCodexProfileRouteContext(task, pinnedResult);
            }
            String fallbackReason = pinned == null
                ? "task-pinned worker '" + pinnedWorker + "' not registered"
                : pinnedLacksWorkspaceAccess
                ? "task-pinned worker '" + pinnedWorker + "' lacks local workspace access required by "
                + firstNonBlank(workspaceRequirement.reason(), "task")
                : pinnedNotAllowedForTaskType
                ? "task-pinned worker '" + pinnedWorker + "' not allowed for taskType=" + taskType
                + " by auto_route_task_types contract"
                : "task-pinned worker '" + pinnedWorker + "' not dispatch ready: "
                + firstNonBlank(pinnedReadiness != null ? pinnedReadiness.reason() : null, "readiness unknown");
            RouteResult fallback = selectWorkerWithoutPinned(task, taskType, preferredModelTier, freeFirstRouting);
            fallback = applyCodexProfileRouting(task, fallback);
            return attachCodexProfileRouteContext(task, new RouteResult(
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
                fallback.freeFirstRouting(),
                fallback.freeCandidateWorkers(),
                fallback.paidCandidateWorkers(),
                fallback.costRouteStage(),
                fallback.manualWindowRequired(),
                fallback.recommendedManualProvider(),
                fallback.manualWindowCandidates(),
                fallback.currentPinnedRoute(),
                fallback.recoveryUnpinnedRecommendation(),
                fallback.dispatchSkippedWorkers()
            ));
        }

        RouteResult result = selectWorkerWithoutPinned(task, taskType, preferredModelTier, freeFirstRouting);
        // codex profile 二层路由：如果选到了 codex，且任务有 workflow_stage 或 preferred_provider_profile，
        // 则按阶段或显式 pin 切换到对应的 codex profile worker
        result = applyCodexProfileRouting(task, result);
        return attachCodexProfileRouteContext(task, result);
    }

    /**
     * Codex profile 二层路由。
     * 规则：
     * 1. 若 selected worker 已经是 codex profile lane（codex-openai/xfyun/deepseek），直接返回。
     * 2. 若任务显式指定 preferred_provider_profile，按 profile 选 codex lane。
     * 3. 否则按 workflow_stage 选 codex lane。
     * 4. 无阶段且无显式 pin 时保持保守（沿用旧 codex worker）。
     * 5. codex profile lane 不可用时，回退到旧 codex worker。
     */
    private RouteResult applyCodexProfileRouting(Task task, RouteResult result) {
        if (result == null || !"codex".equals(result.selectedWorker())) {
            return result;
        }
        // 已经是 codex profile lane，不需要二次路由
        if (isCodexProfileWorker(result.selectedWorker())) {
            return result;
        }

        String taskType = result.taskType();
        String preferredProfile = resolvePreferredProviderProfile(task);
        String workflowStage = resolveWorkflowStage(task);

        String targetWorkerId = null;
        String routeReason = null;

        if (preferredProfile != null && !preferredProfile.isBlank()) {
            targetWorkerId = codexProfileWorkerForProfileId(preferredProfile);
            routeReason = "codex profile selected by preferred_provider_profile=" + preferredProfile;
        } else if (workflowStage != null && !workflowStage.isBlank()) {
            targetWorkerId = codexProfileWorkerForStage(workflowStage);
            routeReason = "codex profile selected by workflow_stage=" + workflowStage;
        }

        if (targetWorkerId == null || targetWorkerId.isBlank()) {
            // 无阶段、无显式 pin：保持保守
            return result;
        }

        Worker targetWorker = registry.get(targetWorkerId);
        WorkerRegistry.ReadinessCheck readiness = targetWorker != null
            ? registry.checkReadiness(targetWorkerId, "dispatch") : null;

        if (targetWorker != null && readiness != null && readiness.ready()) {
            log.info("Codex profile routing: task={} from=codex to={} reason={}",
                task.id(), targetWorkerId, routeReason);
            return new RouteResult(
                result.taskId(),
                targetWorkerId,
                result.fallbackWorkers(),
                routeReason,
                "codex_profile_routing",
                result.taskType(),
                result.preferredWorkerHint(),
                result.learningHintApplied(),
                result.candidateWorkers(),
                targetWorker.workerType(),
                result.selectedModelTier(),
                result.selectedExecutionRole(),
                result.selectionScope(),
                routeReason,
                result.fallbackReason(),
                result.recoveryProviderDeprioritized(),
                result.recoveryDeprioritizedProvider(),
                result.recoveryDeprioritizationReason(),
                result.recoveryExecutionMode(),
                result.freeFirstRouting(),
                result.freeCandidateWorkers(),
                result.paidCandidateWorkers(),
                result.costRouteStage(),
                result.manualWindowRequired(),
                result.recommendedManualProvider(),
                result.manualWindowCandidates(),
                result.currentPinnedRoute(),
                result.recoveryUnpinnedRecommendation(),
                result.dispatchSkippedWorkers()
            );
        }

        // codex profile lane 不可用，回退
        String fallbackReason = "codex profile " + targetWorkerId + " not available; fallback to default codex";
        log.info(fallbackReason);
        return new RouteResult(
            result.taskId(),
            result.selectedWorker(),
            result.fallbackWorkers(),
            result.routeReason(),
            result.routeSource(),
            result.taskType(),
            result.preferredWorkerHint(),
            result.learningHintApplied(),
            result.candidateWorkers(),
            result.selectedWorkerType(),
            result.selectedModelTier(),
            result.selectedExecutionRole(),
            result.selectionScope(),
            result.whySelected(),
            mergeReasons(result.fallbackReason(), fallbackReason),
            result.recoveryProviderDeprioritized(),
            result.recoveryDeprioritizedProvider(),
            result.recoveryDeprioritizationReason(),
            result.recoveryExecutionMode(),
            result.freeFirstRouting(),
            result.freeCandidateWorkers(),
            result.paidCandidateWorkers(),
            result.costRouteStage(),
            result.manualWindowRequired(),
            result.recommendedManualProvider(),
            result.manualWindowCandidates(),
            result.currentPinnedRoute(),
            result.recoveryUnpinnedRecommendation(),
            result.dispatchSkippedWorkers()
        );
    }

    private RouteResult attachCodexProfileRouteContext(Task task, RouteResult result) {
        if (result == null) {
            return null;
        }
        String selectedProviderProfile = resolveSelectedProviderProfile(result.selectedWorker());
        String preferredProviderProfile = resolvePreferredProviderProfile(task);
        String workflowStage = resolveWorkflowStage(task);
        if (java.util.Objects.equals(result.selectedProviderProfile(), selectedProviderProfile)
            && java.util.Objects.equals(result.preferredProviderProfile(), preferredProviderProfile)
            && java.util.Objects.equals(result.workflowStage(), workflowStage)) {
            return result;
        }
        return new RouteResult(
            result.taskId(),
            result.selectedWorker(),
            result.fallbackWorkers(),
            result.routeReason(),
            result.routeSource(),
            result.taskType(),
            result.preferredWorkerHint(),
            result.learningHintApplied(),
            result.candidateWorkers(),
            result.selectedWorkerType(),
            result.selectedModelTier(),
            result.selectedExecutionRole(),
            result.selectionScope(),
            result.whySelected(),
            result.fallbackReason(),
            result.recoveryProviderDeprioritized(),
            result.recoveryDeprioritizedProvider(),
            result.recoveryDeprioritizationReason(),
            result.recoveryExecutionMode(),
            result.freeFirstRouting(),
            result.freeCandidateWorkers(),
            result.paidCandidateWorkers(),
            result.costRouteStage(),
            result.manualWindowRequired(),
            result.recommendedManualProvider(),
            result.manualWindowCandidates(),
            selectedProviderProfile,
            preferredProviderProfile,
            workflowStage,
            result.currentPinnedRoute(),
            result.recoveryUnpinnedRecommendation(),
            result.dispatchSkippedWorkers()
        );
    }

    private boolean isCodexProfileWorker(String workerId) {
        return "codex-openai".equals(workerId)
            || "codex-xfyun".equals(workerId)
            || "codex-deepseek".equals(workerId);
    }

    private String resolvePreferredProviderProfile(Task task) {
        if (task == null || task.metadata() == null) {
            return null;
        }
        Object value = task.metadata().get("preferred_provider_profile");
        return value == null ? null : value.toString();
    }

    private String resolveWorkflowStage(Task task) {
        if (task == null || task.metadata() == null) {
            return null;
        }
        Object value = task.metadata().get("workflow_stage");
        return value == null ? null : value.toString();
    }

    private String codexProfileWorkerForProfileId(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return null;
        }
        String normalized = profileId.toLowerCase();
        if (normalized.contains("openai") || normalized.contains("strong")) {
            return "codex-openai";
        }
        if (normalized.contains("xfyun") || normalized.contains("execute")) {
            return "codex-xfyun";
        }
        if (normalized.contains("deepseek") || normalized.contains("fallback")) {
            return "codex-deepseek";
        }
        return null;
    }

    private String codexProfileWorkerForStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return null;
        }
        return switch (stage.toLowerCase()) {
            case "design" -> "codex-openai";
            case "implement" -> "codex-xfyun";
            case "verify" -> "codex-openai";
            default -> null;
        };
    }

    private String resolveSelectedProviderProfile(String workerId) {
        String normalizedWorkerId = blankToNull(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        Worker worker = registry.get(normalizedWorkerId);
        if (worker == null || worker.metadata() == null) {
            return null;
        }
        Object value = worker.metadata().get("provider_profile_id");
        return value == null ? null : blankToNull(value.toString());
    }

    private RouteResult selectWorkerWithoutPinned(Task task,
                                                  String taskType,
                                                  String preferredModelTier,
                                                  boolean freeFirstRouting) {

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
        List<Worker> autoRouteEligibleCapable = capable.stream()
            .filter(this::isAutoRouteEligible)
            .toList();
        if (autoRouteEligibleCapable.size() != capable.size()) {
            fallbackReason = mergeReasons(
                fallbackReason,
                "auto-route policy skipped manual-only candidate(s)"
            );
            capable = autoRouteEligibleCapable;
        }
        List<String> candidateWorkers = capable.stream().map(Worker::workerId).toList();
        DispatchReadinessSelection dispatchSelection =
            selectDispatchReadyWorkers(capable, taskType, !freeFirstRouting, preferredWorker);
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
                selectDispatchReadyWorkers(tierFallbackCapable, taskType, !freeFirstRouting, preferredWorker);
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

        List<String> manualWindowCandidates = availableManualWindowCandidates(task, taskType);
        List<String> freeCandidateWorkers = freeFirstRouting
            ? dispatchReadyCapable.stream()
                .filter(this::isFreeAutoWorker)
                .map(Worker::workerId)
                .toList()
            : List.of();
        List<String> paidCandidateWorkers = freeFirstRouting
            ? dispatchReadyCapable.stream()
                .filter(this::isPaidAutoWorker)
                .map(Worker::workerId)
                .toList()
            : List.of();
        String costRouteStage = null;
        String quotaFallbackReason = null;
        String recommendedManualProvider = null;
        boolean manualWindowRequired = false;

        if (freeFirstRouting) {
            List<Worker> freeWorkers = dispatchReadyCapable.stream()
                .filter(this::isFreeAutoWorker)
                .toList();
            List<Worker> paidWorkers = dispatchReadyCapable.stream()
                .filter(this::isPaidAutoWorker)
                .toList();
            List<Worker> quotaBlockedFreeWorkers = dispatchReadyCapable.stream()
                .filter(this::isFreeAutoWorker)
                .filter(worker -> isQuotaExhausted(task, worker))
                .toList();
            if (!quotaBlockedFreeWorkers.isEmpty()) {
                quotaFallbackReason = "free provider quota exhausted: " + String.join(", ",
                    quotaBlockedFreeWorkers.stream().map(Worker::workerId).toList());
                freeWorkers = freeWorkers.stream()
                    .filter(worker -> !isQuotaExhausted(task, worker))
                    .toList();
            }
            if (!freeWorkers.isEmpty()) {
                dispatchReadyCapable = freeWorkers.stream()
                    .sorted(freeFirstFreeWorkerComparator(taskType).reversed())
                    .toList();
                costRouteStage = "free_auto";
                fallbackReason = mergeReasons(fallbackReason, quotaFallbackReason);
            } else if (!paidWorkers.isEmpty() && paidFallbackAllowed(task)) {
                dispatchReadyCapable = paidWorkers;
                costRouteStage = "paid_auto";
                fallbackReason = mergeReasons(
                    fallbackReason,
                    firstNonBlank(quotaFallbackReason, "free_auto unavailable; fallback to paid_auto")
                );
            } else if (manualWindowFallbackAllowed(task) && !manualWindowCandidates.isEmpty()) {
                dispatchReadyCapable = List.of();
                manualWindowRequired = true;
                costRouteStage = "manual_window_recommendation";
                recommendedManualProvider = manualWindowCandidates.get(0);
                fallbackReason = mergeReasons(
                    fallbackReason,
                    firstNonBlank(quotaFallbackReason, "no automatic provider available; manual window required")
                );
            } else {
                fallbackReason = mergeReasons(fallbackReason, quotaFallbackReason);
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
                    dispatchSkippedWorkers, freeFirstRouting, freeCandidateWorkers, paidCandidateWorkers,
                    costRouteStage, false, null, manualWindowCandidates);
            }
            fallbackReason = mergeReasons(
                fallbackReason,
                explainLearningHintFallback(preferredWorker, candidateWorkers, dispatchSkippedWorkers)
            );
        }

        // 简单策略：优先找 readiness 全过的，按 capability 匹配数排序
        Worker selected = dispatchReadyCapable.isEmpty() ? null : dispatchReadyCapable.get(0);

        if (selected == null) {
            if (manualWindowRequired) {
                return routeResult(task.id(), null, List.of(), "manual window provider required",
                    "manual_window_required", taskType, preferredWorker, false, candidateWorkers,
                    fallbackReason, null, dispatchSkippedWorkers, freeFirstRouting, freeCandidateWorkers,
                    paidCandidateWorkers, costRouteStage, true, recommendedManualProvider, manualWindowCandidates);
            }
            return routeResult(task.id(), null, List.of(), "no capable worker found",
                "none", taskType, preferredWorker, false, candidateWorkers, fallbackReason, null,
                dispatchSkippedWorkers, freeFirstRouting, freeCandidateWorkers, paidCandidateWorkers,
                costRouteStage, false, null, manualWindowCandidates);
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
            dispatchSkippedWorkers, freeFirstRouting, freeCandidateWorkers, paidCandidateWorkers,
            costRouteStage, false, null, manualWindowCandidates);
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
                                    List<RouteSkippedWorker> dispatchSkippedWorkers,
                                    boolean freeFirstRouting,
                                    List<String> freeCandidateWorkers,
                                    List<String> paidCandidateWorkers,
                                    String costRouteStage,
                                    boolean manualWindowRequired,
                                    String recommendedManualProvider,
                                    List<String> manualWindowCandidates) {
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
            freeFirstRouting,
            freeCandidateWorkers == null ? List.of() : freeCandidateWorkers,
            paidCandidateWorkers == null ? List.of() : paidCandidateWorkers,
            blankToNull(costRouteStage),
            manualWindowRequired,
            blankToNull(recommendedManualProvider),
            manualWindowCandidates == null ? List.of() : manualWindowCandidates,
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

    private String normalizeTaskTypeForRouting(Task task, String baseTaskType) {
        String normalized = blankToNull(baseTaskType);
        if (!"research".equalsIgnoreCase(normalized)) {
            return baseTaskType;
        }
        if (expectsWorkspaceMutation(task)) {
            return "coding";
        }
        return baseTaskType;
    }

    /**
     * 判断任务是否明确要求本地工作区写入/修改。
     * 这类 research 任务应按 coding 路由，避免被 tool-suggest 型 worker 吸走后只做规划不稳定收口。
     */
    private boolean expectsWorkspaceMutation(Task task) {
        if (task == null) {
            return false;
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
        if (lower.isBlank()) {
            return false;
        }

        boolean action = lower.contains("写入") || lower.contains("写到") || lower.contains("保存到")
            || lower.contains("输出到") || lower.contains("创建") || lower.contains("新建")
            || lower.contains("修改") || lower.contains("更新") || lower.contains("删除")
            || lower.contains("append") || lower.contains("write") || lower.contains("create")
            || lower.contains("modify") || lower.contains("update") || lower.contains("delete")
            || lower.contains("patch");
        boolean fileSignal = lower.contains("文件") || lower.contains("file") || lower.contains("path")
            || lower.contains(".tmp") || lower.contains(".md") || lower.contains(".txt")
            || lower.contains(".json") || lower.contains(".yaml") || lower.contains(".yml")
            || lower.contains(".java") || lower.contains(".py") || lower.contains(".xml")
            || lower.contains("src/") || lower.contains("src\\") || lower.contains("pom.xml")
            || lower.contains("package.json") || WINDOWS_ABSOLUTE_PATH.matcher(combined).find();
        return action && fileSignal;
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

    private boolean prefersFreeFirstRouting(Task task) {
        String policy = metadataString(task != null ? task.metadata() : null, "provider_routing_policy");
        return "free_first".equalsIgnoreCase(blankToNull(policy));
    }

    private boolean paidFallbackAllowed(Task task) {
        return metadataBoolean(task != null ? task.metadata() : null, "paid_fallback_allowed", true);
    }

    private boolean manualWindowFallbackAllowed(Task task) {
        return metadataBoolean(task != null ? task.metadata() : null, "manual_window_fallback_allowed", true);
    }

    private boolean metadataBoolean(Map<String, Object> metadata, String key, boolean defaultValue) {
        String value = metadataString(metadata, key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private boolean isAutoRouteEligible(Worker worker) {
        String policy = metadataString(worker != null ? worker.metadata() : null, "auto_route_policy");
        return !"manual_only".equalsIgnoreCase(blankToNull(policy));
    }

    private boolean isFreeAutoWorker(Worker worker) {
        String costClass = metadataString(worker != null ? worker.metadata() : null, "provider_cost_class");
        return "free_auto".equalsIgnoreCase(costClass) || "free_auto_guarded".equalsIgnoreCase(costClass);
    }

    private boolean isPaidAutoWorker(Worker worker) {
        String costClass = metadataString(worker != null ? worker.metadata() : null, "provider_cost_class");
        return "paid_auto".equalsIgnoreCase(costClass);
    }

    private boolean isQuotaExhausted(Task task, Worker worker) {
        if (worker == null) {
            return false;
        }
        Map<String, Object> taskMetadata = task != null ? task.metadata() : null;
        if (metadataBoolean(worker.metadata(), "quota_exhausted", false)) {
            return true;
        }
        Map<String, Object> quotaState = metadataMap(taskMetadata, "user_reported_quota_state");
        if (quotaState == null || quotaState.isEmpty()) {
            return false;
        }
        String state = metadataString(quotaState, worker.workerId());
        return "quota_exhausted".equalsIgnoreCase(blankToNull(state))
            || "user_reported_exhausted".equalsIgnoreCase(blankToNull(state));
    }

    private List<String> manualWindowCandidates(Task task, String taskType) {
        List<String> declared = metadataStringList(task != null ? task.metadata() : null, "manual_window_candidates");
        if (!declared.isEmpty()) {
            return declared;
        }
        if ("coding".equalsIgnoreCase(blankToNull(taskType)) || "continuation".equalsIgnoreCase(blankToNull(taskType))) {
            return List.of("trae", "zcode");
        }
        return List.of();
    }

    private List<String> availableManualWindowCandidates(Task task, String taskType) {
        List<String> declared = manualWindowCandidates(task, taskType);
        if (declared.isEmpty()) {
            return List.of();
        }
        Map<String, Object> quotaState = metadataMap(task != null ? task.metadata() : null, "user_reported_quota_state");
        List<String> available = declared.stream()
            .filter(candidate -> !manualWindowQuotaExhausted(quotaState, candidate))
            .toList();
        if (!available.isEmpty()) {
            return available;
        }
        return List.of();
    }

    private boolean manualWindowQuotaExhausted(Map<String, Object> quotaState, String candidate) {
        String state = metadataString(quotaState, candidate);
        return "quota_exhausted".equalsIgnoreCase(blankToNull(state))
            || "user_reported_exhausted".equalsIgnoreCase(blankToNull(state));
    }

    private Map<String, Object> metadataMap(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return Map.of();
        }
        Object value = metadata.get(key);
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    converted.put(entry.getKey().toString(), entry.getValue());
                }
            }
            return converted;
        }
        return Map.of();
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
        return metadataBoolean(metadata, key, false);
    }

    private DispatchReadinessSelection selectDispatchReadyWorkers(List<Worker> workers,
                                                                  String taskType,
                                                                  boolean stopAfterFirstReady,
                                                                  String preferredWorkerId) {
        Map<String, WorkerRegistry.ReadinessCheck> readinessByWorker = new LinkedHashMap<>();
        List<Worker> readyWorkers = new ArrayList<>();
        if (workers == null) {
            return new DispatchReadinessSelection(List.of(), readinessByWorker);
        }
        List<Worker> ordered = workers.stream()
            .filter(worker -> worker != null && worker.workerId() != null)
            .sorted(routeComparator(taskType).reversed())
            .toList();
        boolean firstReadyFound = false;
        boolean preferredEvaluated = preferredWorkerId == null || preferredWorkerId.isBlank();
        for (Worker worker : ordered) {
            if (worker == null || worker.workerId() == null) {
                continue;
            }
            WorkerRegistry.ReadinessCheck readiness = registry.checkReadiness(worker.workerId(), "dispatch");
            readinessByWorker.put(worker.workerId(), readiness);
            if (!preferredEvaluated && preferredWorkerId.equals(worker.workerId())) {
                preferredEvaluated = true;
            }
            if (readiness != null && readiness.ready()) {
                readyWorkers.add(worker);
                firstReadyFound = true;
                if (stopAfterFirstReady && preferredEvaluated) {
                    break;
                }
            }
            if (stopAfterFirstReady && firstReadyFound && preferredEvaluated) {
                break;
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

    private Comparator<Worker> freeFirstFreeWorkerComparator(String taskType) {
        return Comparator
            .comparingInt((Worker worker) -> exactCapabilityMatches(worker, taskType))
            .thenComparingInt(this::freeAutoPreference)
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

    private int freeAutoPreference(Worker worker) {
        String costClass = metadataString(worker != null ? worker.metadata() : null, "provider_cost_class");
        if ("free_auto".equalsIgnoreCase(costClass)) {
            return 2;
        }
        if ("free_auto_guarded".equalsIgnoreCase(costClass)) {
            return 1;
        }
        return 0;
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

    public static String manualFollowupInstruction(String recommendedManualProvider) {
        String recommended = firstNonBlankStatic(recommendedManualProvider, "trae");
        return "请切到 " + recommended + " 窗口手动输入当前任务，完成后将结果回填到当前 task 再继续。";
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

    private static String firstNonBlankStatic(String... values) {
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
        boolean freeFirstRouting,
        List<String> freeCandidateWorkers,
        List<String> paidCandidateWorkers,
        String costRouteStage,
        boolean manualWindowRequired,
        String recommendedManualProvider,
        List<String> manualWindowCandidates,
        String selectedProviderProfile,
        String preferredProviderProfile,
        String workflowStage,
        RouteDiagnostic currentPinnedRoute,
        RouteDiagnostic recoveryUnpinnedRecommendation,
        List<RouteSkippedWorker> dispatchSkippedWorkers
    ) {
        @JsonProperty("manual_followup_instruction")
        public String manualFollowupInstruction() {
            return manualWindowRequired ? WorkerRouter.manualFollowupInstruction(recommendedManualProvider) : null;
        }

        public RouteResult(
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
            boolean freeFirstRouting,
            List<String> freeCandidateWorkers,
            List<String> paidCandidateWorkers,
            String costRouteStage,
            boolean manualWindowRequired,
            String recommendedManualProvider,
            List<String> manualWindowCandidates,
            RouteDiagnostic currentPinnedRoute,
            RouteDiagnostic recoveryUnpinnedRecommendation,
            List<RouteSkippedWorker> dispatchSkippedWorkers
        ) {
            this(
                taskId,
                selectedWorker,
                fallbackWorkers,
                routeReason,
                routeSource,
                taskType,
                preferredWorkerHint,
                learningHintApplied,
                candidateWorkers,
                selectedWorkerType,
                selectedModelTier,
                selectedExecutionRole,
                selectionScope,
                whySelected,
                fallbackReason,
                recoveryProviderDeprioritized,
                recoveryDeprioritizedProvider,
                recoveryDeprioritizationReason,
                recoveryExecutionMode,
                freeFirstRouting,
                freeCandidateWorkers,
                paidCandidateWorkers,
                costRouteStage,
                manualWindowRequired,
                recommendedManualProvider,
                manualWindowCandidates,
                null,
                null,
                null,
                currentPinnedRoute,
                recoveryUnpinnedRecommendation,
                dispatchSkippedWorkers
            );
        }
    }

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
        String selectedProviderProfile,
        String preferredProviderProfile,
        String workflowStage,
        String recoveryExecutionMode,
        Boolean providerDeprioritized,
        String deprioritizedProvider,
        String deprioritizationReason,
        List<String> candidateWorkers,
        List<String> fallbackWorkers
    ) {
        public RouteDiagnostic(
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
        ) {
            this(
                selectedWorker,
                routeSource,
                taskType,
                selectedWorkerType,
                selectedModelTier,
                selectedExecutionRole,
                selectionScope,
                whySelected,
                fallbackReason,
                preferredWorkerHint,
                learningHintApplied,
                null,
                null,
                null,
                recoveryExecutionMode,
                providerDeprioritized,
                deprioritizedProvider,
                deprioritizationReason,
                candidateWorkers,
                fallbackWorkers
            );
        }
    }
}
