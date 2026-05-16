package com.agentcloud.engine;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderDescriptor;
import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.AgentProviderResolver;
import com.agentcloud.agent.AgentProviderStatus;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.AgentRunRecord;
import com.agentcloud.model.AgentRunArtifactView;
import com.agentcloud.model.AgentRunEventView;
import com.agentcloud.model.Artifact;
import com.agentcloud.model.Event;
import com.agentcloud.model.ProviderSelectionView;
import com.agentcloud.model.ProviderRuntimeStats;
import com.agentcloud.model.RuntimeHealthView;
import com.agentcloud.model.Task;
import com.agentcloud.model.Worker;
import com.agentcloud.store.AgentRunDao;
import com.agentcloud.store.ArtifactDao;
import com.agentcloud.store.EventDao;
import com.agentcloud.worker.WorkerExecutionResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Agent Provider run 的最小落盘服务。
 */
public class AgentRunService {
    private final AgentRunDao agentRunDao;
    private final AgentProviderRegistry providerRegistry;
    private final EventDao eventDao;
    private final ArtifactDao artifactDao;

    public AgentRunService(AgentRunDao agentRunDao, AgentProviderRegistry providerRegistry) {
        this(agentRunDao, providerRegistry, null, null);
    }

    public AgentRunService(AgentRunDao agentRunDao, AgentProviderRegistry providerRegistry,
                           EventDao eventDao, ArtifactDao artifactDao) {
        this.agentRunDao = agentRunDao;
        this.providerRegistry = providerRegistry;
        this.eventDao = eventDao;
        this.artifactDao = artifactDao;
    }

    public AgentRunRecord latestByTask(String taskId) {
        return agentRunDao == null ? null : agentRunDao.latestByTask(taskId).orElse(null);
    }

    public AgentRunRecord findById(String runId) {
        return agentRunDao == null ? null : agentRunDao.findById(runId).orElse(null);
    }

    public List<AgentRunRecord> listByProvider(String providerId, int limit) {
        if (agentRunDao == null) {
            return List.of();
        }
        return agentRunDao.listByProvider(providerId, Math.max(1, Math.min(limit, 50)));
    }

    public List<AgentRunRecord> listByProvider(String providerId, String status, int limit) {
        if (agentRunDao == null) {
            return List.of();
        }
        int boundedLimit = Math.max(1, Math.min(limit, 50));
        String normalizedStatus = status == null ? null : status.trim().toLowerCase(Locale.ROOT);
        if (normalizedStatus == null || normalizedStatus.isBlank()) {
            return agentRunDao.listByProvider(providerId, boundedLimit);
        }
        return agentRunDao.listByProviderAndStatus(providerId, normalizedStatus, boundedLimit);
    }

    public List<AgentRunRecord> search(String providerId,
                                       String status,
                                       String workerRole,
                                       String taskId,
                                       int limit) {
        if (agentRunDao == null) {
            return List.of();
        }
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return agentRunDao.search(
            trimFilter(providerId),
            normalizeEnumFilter(status),
            normalizeEnumFilter(workerRole),
            trimFilter(taskId),
            boundedLimit
        );
    }

    public boolean shouldDeprioritizeProvider(String providerId) {
        String normalizedProviderId = trimFilter(providerId);
        if (normalizedProviderId == null || agentRunDao == null) {
            return false;
        }
        List<AgentRunRecord> recentRuns = agentRunDao.listByProvider(normalizedProviderId, 3);
        if (recentRuns.size() < 2) {
            return false;
        }
        int transientFailureCount = 0;
        for (AgentRunRecord run : recentRuns) {
            if (isProviderTransientFailure(run)) {
                transientFailureCount++;
            }
        }
        return transientFailureCount >= 2;
    }

    public RuntimeHealthView runtimeHealth(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        int statsLimit = Math.max(boundedLimit, 200);
        Instant checkedAt = Instant.now();
        Instant cutoff = checkedAt.minus(Duration.ofHours(24));
        List<AgentRunRecord> activeRuns = agentRunDao == null
            ? List.of()
            : agentRunDao.listActive(boundedLimit);
        List<AgentRunRecord> sampledRuns = agentRunDao == null
            ? List.of()
            : agentRunDao.listRecent(statsLimit);
        List<AgentRunRecord> recentRuns = sampledRuns.stream()
            .limit(boundedLimit)
            .toList();
        List<AgentRunRecord> windowRuns = sampledRuns.stream()
            .filter(run -> withinWindow(run, cutoff))
            .toList();
        List<AgentRunRecord> recentFailures = windowRuns.stream()
            .filter(this::countsAsProviderFailure)
            .limit(boundedLimit)
            .toList();
        long durationCount = windowRuns.stream()
            .filter(run -> run.durationMs() != null && run.durationMs() > 0)
            .count();
        Long averageDurationMs = durationCount == 0
            ? null
            : Math.round(windowRuns.stream()
                .filter(run -> run.durationMs() != null && run.durationMs() > 0)
                .mapToLong(AgentRunRecord::durationMs)
                .average()
                .orElse(0));
        List<AgentProviderStatus> providerStatuses = providerRegistry == null
            ? List.of()
            : providerRegistry.listStatuses();
        List<AgentProviderStatus> unavailableProviders = providerStatuses.stream()
            .filter(status -> !status.ready())
            .toList();
        List<AgentProviderStatus> authProblemProviders = providerStatuses.stream()
            .filter(status -> "auth_needed".equalsIgnoreCase(status.authStatus()))
            .toList();
        List<String> deprioritizedProviders = windowRuns.stream()
            .map(AgentRunRecord::providerId)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(providerId -> !providerId.isBlank())
            .distinct()
            .filter(this::shouldDeprioritizeProvider)
            .toList();

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stats_window", "24h");
        metadata.put("recent_sample_size", sampledRuns.size());
        metadata.put("approximate_counts", true);
        if (!deprioritizedProviders.isEmpty()) {
            metadata.put("deprioritized_providers", deprioritizedProviders);
        }

        return new RuntimeHealthView(
            checkedAt,
            activeRuns.size(),
            (int) windowRuns.stream().filter(this::countsAsProviderFailure).count(),
            (int) windowRuns.stream().filter(this::isCrashedRun).count(),
            (int) windowRuns.stream().filter(run -> "cancelled".equals(normalizeStatus(run.status()))).count(),
            unavailableProviders.size(),
            authProblemProviders.size(),
            averageDurationMs,
            providerFailureRate(windowRuns),
            providerRuntimeStats(windowRuns),
            activeRuns,
            recentFailures,
            unavailableProviders,
            authProblemProviders,
            recentRuns,
            metadata
        );
    }

    public ProviderSelectionView providerSelection(Task task, WorkerRouter.RouteResult route) {
        if (route == null) {
            return null;
        }
        String selectedProvider = firstNonBlank(
            AgentProviderResolver.providerIdForWorker(route.selectedWorker(), route.selectedWorkerType()),
            "unknown"
        );
        AgentProvider provider = providerRegistry != null ? providerRegistry.get(selectedProvider) : null;
        AgentProviderDescriptor descriptor = provider != null ? provider.descriptor() : null;
        AgentProviderStatus status = providerRegistry != null ? providerRegistry.status(selectedProvider) : null;

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "route_source", route.routeSource());
        putIfNotBlank(metadata, "task_type", firstNonBlank(
            route.taskType(),
            task != null ? TaskTypeHeuristics.effectiveTaskType(task, null) : null
        ));
        putIfNotBlank(metadata, "selected_worker_type", route.selectedWorkerType());
        putIfNotBlank(metadata, "preferred_worker_hint", route.preferredWorkerHint());
        metadata.put("learning_hint_applied", route.learningHintApplied());
        if (route.candidateWorkers() != null && !route.candidateWorkers().isEmpty()) {
            metadata.put("candidate_workers", route.candidateWorkers());
        }
        if (route.fallbackWorkers() != null && !route.fallbackWorkers().isEmpty()) {
            metadata.put("fallback_workers", route.fallbackWorkers());
        }
        appendProviderDeprioritizationMetadata(route, metadata);
        metadata.put("provider_registered", provider != null);
        if (status != null) {
            putIfNotBlank(metadata, "provider_readiness_reason", status.readinessReason());
        }

        return new ProviderSelectionView(
            route.taskId(),
            selectedProvider,
            descriptor != null ? descriptor.displayName() : selectedProvider,
            status != null && status.ready(),
            status != null ? status.authStatus() : "unknown",
            status != null ? status.version() : null,
            route.selectedExecutionRole(),
            route.selectedWorker(),
            route.selectedModelTier(),
            firstNonBlank(route.whySelected(), route.routeReason()),
            route.fallbackReason(),
            candidateProviders(route, selectedProvider),
            metadata
        );
    }

    public List<AgentRunEventView> listEvents(String runId, int limit) {
        AgentRunRecord run = findById(runId);
        if (run == null) {
            return null;
        }
        if (eventDao == null) {
            return List.of();
        }
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        List<Event> events = eventDao.listBySessionAndTask(run.sessionId(), run.taskId(), boundedLimit);
        List<Event> matched = events.stream()
            .filter(event -> matchesAgentRun(event.payload(), run.runId()))
            .toList();
        List<Event> chronological = new ArrayList<>(matched.isEmpty() ? events : matched);
        Collections.reverse(chronological);
        return chronological.stream()
            .limit(boundedLimit)
            .map(event -> new AgentRunEventView(
                event.id(),
                run.runId(),
                event.eventType(),
                event.createdAt(),
                event.summary(),
                event.payload()
            ))
            .toList();
    }

    public List<AgentRunArtifactView> listArtifacts(String runId, int limit) {
        AgentRunRecord run = findById(runId);
        if (run == null) {
            return null;
        }
        if (artifactDao == null) {
            return List.of();
        }
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        List<Artifact> artifacts = artifactDao.listBySessionAndTask(run.sessionId(), run.taskId(), boundedLimit);
        List<Artifact> matched = artifacts.stream()
            .filter(artifact -> matchesAgentRun(artifact.metadata(), run.runId()))
            .toList();
        return (matched.isEmpty() ? artifacts : matched).stream()
            .limit(boundedLimit)
            .map(artifact -> new AgentRunArtifactView(
                artifact.id(),
                run.runId(),
                run.providerId(),
                artifact.artifactType(),
                artifact.title(),
                artifact.uri(),
                artifact.summary(),
                artifact.createdAt(),
                artifact.metadata()
            ))
            .toList();
    }

    public AgentRunRecord recordCompletedWorkerRun(Task task,
                                                   WorkerRouter.RouteResult route,
                                                   Worker selectedWorker,
                                                   WorkerExecutionResult result,
                                                   Instant startedAt,
                                                   Instant endedAt) {
        return recordWorkerRun(task, route, selectedWorker, result, startedAt, endedAt, null, null);
    }

    public AgentRunRecord recordFailedWorkerRun(Task task,
                                                WorkerRouter.RouteResult route,
                                                Worker selectedWorker,
                                                Instant startedAt,
                                                Instant endedAt,
                                                Throwable error) {
        return recordWorkerRun(task, route, selectedWorker, null, startedAt, endedAt, "failed", error);
    }

    private AgentRunRecord recordWorkerRun(Task task,
                                           WorkerRouter.RouteResult route,
                                           Worker selectedWorker,
                                           WorkerExecutionResult result,
                                           Instant startedAt,
                                           Instant endedAt,
                                           String forcedStatus,
                                           Throwable error) {
        if (agentRunDao == null || task == null) {
            return null;
        }
        Instant runStartedAt = startedAt != null ? startedAt : Instant.now();
        Instant runEndedAt = endedAt != null ? endedAt : Instant.now();
        String workerId = firstNonBlank(
            route != null ? route.selectedWorker() : null,
            task.assignedWorker(),
            selectedWorker != null ? selectedWorker.workerId() : null
        );
        String workerType = firstNonBlank(
            route != null ? route.selectedWorkerType() : null,
            selectedWorker != null ? selectedWorker.workerType() : null
        );
        String providerId = firstNonBlank(AgentProviderResolver.providerIdForWorker(workerId, workerType), "unknown");
        AgentProvider provider = providerRegistry != null ? providerRegistry.get(providerId) : null;
        AgentProviderDescriptor descriptor = provider != null ? provider.descriptor() : null;
        AgentProviderStatus providerStatus = providerRegistry != null ? providerRegistry.status(providerId) : null;
        String rawExecutionStatus = result != null ? result.executionStatus() : null;
        String status = normalizeRunStatus(firstNonBlank(forcedStatus, rawExecutionStatus));
        long durationMs = result != null && result.durationMs() != null && result.durationMs() > 0
            ? result.durationMs()
            : Math.max(0L, Duration.between(runStartedAt, runEndedAt).toMillis());
        int artifactCount = result != null && result.producedArtifact() ? 1 : 0;
        String summary = summarize(result, error);

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "worker_execution_projection");
        putIfNotBlank(metadata, "selected_worker_type", workerType);
        putIfNotBlank(metadata, "route_source", route != null ? route.routeSource() : "preassigned");
        putIfNotBlank(metadata, "task_type", route != null ? route.taskType() : metadataString(task.metadata(), "task_type"));
        putIfNotBlank(metadata, "selection_reason", route != null ? firstNonBlank(route.whySelected(), route.routeReason()) : null);
        putIfNotBlank(metadata, "fallback_reason", route != null ? route.fallbackReason() : null);
        putIfNotBlank(metadata, "preferred_worker_hint", route != null ? route.preferredWorkerHint() : null);
        if (route != null) {
            metadata.put("learning_hint_applied", route.learningHintApplied());
            metadata.put("candidate_workers", route.candidateWorkers());
            metadata.put("fallback_workers", route.fallbackWorkers());
        }
        putIfNotBlank(metadata, "worker_execution_status", rawExecutionStatus);
        if (result != null) {
            metadata.put("token_usage", result.tokenUsage());
            putIfNotBlank(metadata, "confidence", result.confidence());
            putIfNotBlank(metadata, "suggested_next_step", result.suggestedNextStep());
            if (result.evidenceRefs() != null && !result.evidenceRefs().isEmpty()) {
                metadata.put("evidence_refs", result.evidenceRefs());
            }
            if (result.unfinishedItems() != null && !result.unfinishedItems().isEmpty()) {
                metadata.put("unfinished_items", result.unfinishedItems());
            }
            if (result.metadata() != null && !result.metadata().isEmpty()) {
                metadata.put("worker_metadata", result.metadata());
            }
        }
        metadata.put("provider_registered", provider != null);
        if (providerStatus != null) {
            metadata.put("provider_ready", providerStatus.ready());
            putIfNotBlank(metadata, "provider_auth_status", providerStatus.authStatus());
            putIfNotBlank(metadata, "provider_readiness_reason", providerStatus.readinessReason());
        }
        if (error != null) {
            metadata.put("error_type", error.getClass().getSimpleName());
        }

        AgentRunRecord record = new AgentRunRecord(
            IdGenerator.newId("arun"),
            task.id(),
            task.sessionId(),
            providerId,
            descriptor != null ? descriptor.displayName() : providerId,
            firstNonBlank(route != null ? route.selectedExecutionRole() : null, workerMetadata(selectedWorker, "primary_role"), "executor"),
            workerId,
            firstNonBlank(route != null ? route.selectedModelTier() : null, workerMetadata(selectedWorker, "model_tier")),
            status,
            runStartedAt,
            runEndedAt,
            durationMs,
            summary,
            countsAsProviderFailureStatus(status) ? "run.failed" : "run.completed",
            artifactCount,
            metadata
        );
        agentRunDao.insert(record);
        return record;
    }

    private String normalizeRunStatus(String raw) {
        String value = raw == null || raw.isBlank() ? "completed" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "queued", "starting", "running", "completed", "failed", "cancelled",
                 "timeout", "blocked", "empty", "unknown" -> value;
            case "done", "success", "succeeded", "ok" -> "completed";
            case "error" -> "failed";
            default -> "unknown";
        };
    }

    private String summarize(WorkerExecutionResult result, Throwable error) {
        if (error != null) {
            return "Worker execution failed: " + error.getClass().getSimpleName();
        }
        if (result == null) {
            return null;
        }
        String summary = firstNonBlank(result.summary(), result.outputText(), result.artifactContent());
        if (summary != null && summary.length() > 500) {
            return summary.substring(0, 500) + "...";
        }
        return summary;
    }

    private String workerMetadata(Worker worker, String key) {
        return worker == null ? null : metadataString(worker.metadata(), key);
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private boolean matchesAgentRun(Map<String, Object> metadata, String runId) {
        if (metadata == null || metadata.isEmpty() || runId == null || runId.isBlank()) {
            return false;
        }
        Object value = metadata.get("agent_run_id");
        return value != null && runId.equals(value.toString());
    }

    private boolean withinWindow(AgentRunRecord run, Instant cutoff) {
        if (run == null || cutoff == null) {
            return false;
        }
        Instant reference = run.endedAt() != null ? run.endedAt() : run.startedAt();
        return reference != null && !reference.isBefore(cutoff);
    }

    private boolean isCrashedRun(AgentRunRecord run) {
        if (!"failed".equals(normalizeStatus(run != null ? run.status() : null))) {
            return false;
        }
        Map<String, Object> metadata = run.metadata();
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        Object errorType = metadata.get("error_type");
        Object exitCode = metadata.get("exit_code");
        return errorType != null || exitCode != null;
    }

    private boolean isProviderTransientFailure(AgentRunRecord run) {
        if (run == null) {
            return false;
        }
        String executionStatus = metadataString(run.metadata(), "worker_execution_status");
        if (isTransientFailureStatus(executionStatus)) {
            return true;
        }
        if (isTransientFailureStatus(run.status())) {
            return true;
        }
        String text = firstNonBlank(
            metadataString(run.metadata(), "failure_summary_readable"),
            run.summary(),
            metadataString(run.metadata(), "provider_readiness_reason"),
            metadataString(run.metadata(), "error_type")
        );
        return looksLikeTransientProviderFailure(text);
    }

    private boolean isTransientFailureStatus(String status) {
        String normalized = normalizeStatus(status);
        return Objects.equals(normalized, "failed")
            || Objects.equals(normalized, "timeout")
            || Objects.equals(normalized, "empty")
            || Objects.equals(normalized, "blocked");
    }

    private boolean looksLikeTransientProviderFailure(String text) {
        String normalized = trimFilter(text);
        if (normalized == null) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.contains("thread not found")
            || lower.contains("provider unavailable")
            || lower.contains("session expired")
            || lower.contains("failed to start")
            || lower.contains("connection reset")
            || lower.contains("timeout")
            || normalized.contains("没找到线程")
            || normalized.contains("未找到线程");
    }

    private Map<String, Double> providerFailureRate(List<AgentRunRecord> runs) {
        if (runs == null || runs.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, int[]> counts = new LinkedHashMap<>();
        for (AgentRunRecord run : runs) {
            String providerId = firstNonBlank(run.providerId(), "unknown");
            int[] pair = counts.computeIfAbsent(providerId, ignored -> new int[2]);
            pair[0]++;
            if (countsAsProviderFailure(run)) {
                pair[1]++;
            }
        }
        LinkedHashMap<String, Double> rates = new LinkedHashMap<>();
        counts.forEach((providerId, pair) -> {
            double rate = pair[0] == 0 ? 0 : (double) pair[1] / pair[0];
            rates.put(providerId, Math.round(rate * 1000.0) / 1000.0);
        });
        return rates;
    }

    private List<ProviderRuntimeStats> providerRuntimeStats(List<AgentRunRecord> runs) {
        if (runs == null || runs.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, ProviderStatsAccumulator> stats = new LinkedHashMap<>();
        for (AgentRunRecord run : runs) {
            String providerId = firstNonBlank(run.providerId(), "unknown");
            ProviderStatsAccumulator accumulator = stats.computeIfAbsent(providerId, ProviderStatsAccumulator::new);
            accumulator.record(
                run,
                normalizeStatus(run.status()),
                isCrashedRun(run),
                countsAsProviderFailure(run)
            );
        }
        return stats.values().stream()
            .map(accumulator -> accumulator.toView(shouldDeprioritizeProvider(accumulator.providerId)))
            .sorted((left, right) -> {
                int failedCompare = Integer.compare(right.failedRuns(), left.failedRuns());
                if (failedCompare != 0) {
                    return failedCompare;
                }
                int rateCompare = Double.compare(
                    right.failureRate() != null ? right.failureRate() : 0,
                    left.failureRate() != null ? left.failureRate() : 0
                );
                if (rateCompare != 0) {
                    return rateCompare;
                }
                return left.providerId().compareTo(right.providerId());
            })
            .toList();
    }

    private static final class ProviderStatsAccumulator {
        private final String providerId;
        private int totalRuns;
        private int activeRuns;
        private int completedRuns;
        private int failedRuns;
        private int cancelledRuns;
        private int crashedRuns;
        private long durationTotalMs;
        private int durationCount;
        private Instant lastRunAt;
        private Instant lastFailureAt;
        private String lastFailureSummary;

        private ProviderStatsAccumulator(String providerId) {
            this.providerId = providerId;
        }

        private void record(AgentRunRecord run, String status, boolean crashed, boolean countsAsFailure) {
            if (run == null) {
                return;
            }
            totalRuns++;
            switch (status) {
                case "queued", "starting", "running" -> activeRuns++;
                case "completed" -> completedRuns++;
                case "failed", "timeout", "blocked", "empty", "unknown" -> failedRuns++;
                case "cancelled" -> cancelledRuns++;
                default -> {
                    // 未知状态只计入总量，避免误导成功率。
                }
            }
            if (crashed) {
                crashedRuns++;
            }
            if (run.durationMs() != null && run.durationMs() > 0) {
                durationTotalMs += run.durationMs();
                durationCount++;
            }
            Instant reference = run.endedAt() != null ? run.endedAt() : run.startedAt();
            if (reference != null && (lastRunAt == null || reference.isAfter(lastRunAt))) {
                lastRunAt = reference;
            }
            if (countsAsFailure
                && reference != null
                && (lastFailureAt == null || reference.isAfter(lastFailureAt))) {
                lastFailureAt = reference;
                lastFailureSummary = run.summary();
            }
        }

        private ProviderRuntimeStats toView(boolean providerDeprioritized) {
            Long averageDurationMs = durationCount == 0
                ? null
                : Math.round((double) durationTotalMs / durationCount);
            double rawFailureRate = totalRuns == 0 ? 0 : (double) failedRuns / totalRuns;
            double failureRate = Math.round(rawFailureRate * 1000.0) / 1000.0;
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("stats_window", "24h");
            if (providerDeprioritized) {
                metadata.put("provider_deprioritized", true);
                metadata.put("deprioritization_reason", "recent transient provider failures");
            }
            return new ProviderRuntimeStats(
                providerId,
                totalRuns,
                activeRuns,
                completedRuns,
                failedRuns,
                cancelledRuns,
                crashedRuns,
                averageDurationMs,
                failureRate,
                lastRunAt,
                lastFailureAt,
                lastFailureSummary,
                metadata
            );
        }
    }

    private String normalizeStatus(String status) {
        return status == null || status.isBlank() ? "unknown" : status.trim().toLowerCase(Locale.ROOT);
    }

    private boolean countsAsProviderFailure(AgentRunRecord run) {
        String status = normalizeStatus(run != null ? run.status() : null);
        return countsAsProviderFailureStatus(status);
    }

    private boolean countsAsProviderFailureStatus(String status) {
        String normalized = normalizeStatus(status);
        return switch (normalized) {
            case "failed", "timeout", "blocked", "empty", "unknown" -> true;
            default -> false;
        };
    }

    private String normalizeEnumFilter(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String trimFilter(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<String> candidateProviders(WorkerRouter.RouteResult route, String selectedProvider) {
        List<String> providerIds = new ArrayList<>();
        if (route != null && route.candidateWorkers() != null) {
            for (String workerId : route.candidateWorkers()) {
                String providerId = AgentProviderResolver.providerIdForWorker(workerId, workerId);
                if (providerId != null && !providerId.isBlank() && !providerIds.contains(providerId)) {
                    providerIds.add(providerId);
                }
            }
        }
        if (providerIds.isEmpty() && selectedProvider != null && !selectedProvider.isBlank()) {
            providerIds.add(selectedProvider);
        }
        return List.copyOf(providerIds);
    }

    private void appendProviderDeprioritizationMetadata(WorkerRouter.RouteResult route, Map<String, Object> metadata) {
        if (route == null || metadata == null || route.recoveryUnpinnedRecommendation() == null) {
            return;
        }
        WorkerRouter.RouteDiagnostic diagnostic = route.recoveryUnpinnedRecommendation();
        if (!Boolean.TRUE.equals(diagnostic.providerDeprioritized())) {
            return;
        }
        metadata.put("provider_deprioritized", true);
        putIfNotBlank(metadata, "deprioritized_provider", diagnostic.deprioritizedProvider());
        putIfNotBlank(metadata, "deprioritization_reason", diagnostic.deprioritizationReason());
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

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (target != null && key != null && value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
