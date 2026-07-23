package com.agentcloud.server;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderDescriptor;
import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.AgentProviderResolver;
import com.agentcloud.agent.AgentProviderStatus;
import com.agentcloud.engine.ExperimentMatrixService;
import com.agentcloud.engine.TaskService;
import com.agentcloud.engine.router.WorkerRouter;
import com.agentcloud.model.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class TaskHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(TaskHandler.class);
    private final TaskService svc;
    private final ExperimentMatrixService experimentMatrixService;
    private final AgentProviderRegistry agentProviderRegistry;
    private final ObjectMapper mapper;

    TaskHandler(TaskService svc, ObjectMapper mapper) {
        this(svc, null, null, mapper);
    }

    TaskHandler(TaskService svc, ExperimentMatrixService experimentMatrixService, ObjectMapper mapper) {
        this(svc, experimentMatrixService, null, mapper);
    }

    TaskHandler(TaskService svc, ExperimentMatrixService experimentMatrixService,
                AgentProviderRegistry agentProviderRegistry, ObjectMapper mapper) {
        this.svc = svc;
        this.experimentMatrixService = experimentMatrixService;
        this.agentProviderRegistry = agentProviderRegistry;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();

            if ("POST".equals(method) && path.equals("/api/v1/tasks")) {
                TaskCreateRequest req = mapper.readValue(NioHttpServer.readBody(ex), TaskCreateRequest.class);
                Task t = svc.createTask(req, requestMetadata("POST", path, false));
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(t));
            } else if ("POST".equals(method) && path.matches("/api/v1/tasks/[^/]+/(pause|resume|continue|escalate)")) {
                String id = NioHttpServer.pathVar(ex, 4);
                String action = NioHttpServer.pathVar(ex, 5);
                Map<String, Object> body = readJsonBodyAsMap(ex);
                Map<String, Object> actionMetadata = new LinkedHashMap<>(body);
                actionMetadata.putAll(requestMetadata("POST", path, false));
                TaskControlResult result = switch (action) {
                    case "pause" -> svc.pauseTask(id, stringValue(body, "reason", "manual pause via API"), actionMetadata);
                    case "resume" -> svc.resumeTask(id, actionMetadata);
                    case "continue" -> svc.continueTask(id, actionMetadata);
                    case "escalate" -> svc.escalateTask(id, stringValue(body, "reason", "manual escalation via API"), actionMetadata);
                    default -> throw new IllegalArgumentException("unsupported control action");
                };
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(result));
            } else if ("POST".equals(method) && path.matches("/api/v1/tasks/[^/]+/recover")) {
                String id = NioHttpServer.pathVar(ex, 4);
                Map<String, Object> body = readJsonBodyAsMap(ex);
                Map<String, Object> request = new LinkedHashMap<>(body);
                request.putAll(requestMetadata("POST", path, false));
                boolean async = isAsyncRecoveryRequested(query, body);
                TaskRecoveryResult result = async ? svc.recoverTaskAsync(id, request) : svc.recoverTask(id, request);
                NioHttpServer.sendJson(ex, async ? 202 : 200, ApiResponse.ok(result));
            } else if ("GET".equals(method) && path.equals("/api/v1/tasks")) {
                Map<String, String> params = parseQuery(query);
                String status = params.get("status") != null ? params.get("status") : params.get("state");
                var list = svc.listTasks(status, params.get("task_type"), params.get("assigned_worker"));
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(list));
            } else if ("GET".equals(method) && path.equals("/api/v1/tasks/recoverable")) {
                Map<String, String> params = parseQuery(query);
                int limit = parseLimit(params.get("limit"));
                var list = svc.listRecoverableTasks(limit);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(list));
            } else if ("GET".equals(method) && path.startsWith("/api/v1/tasks/")) {
                String id = NioHttpServer.pathVar(ex, 4);
                if (path.endsWith("/packet")) {
                    var p = svc.getLatestPacket(id);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(p));
                } else if (path.endsWith("/refresh_packet")) {
                    var p = svc.refreshResumePacket(id);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(p));
                } else if (path.endsWith("/select_worker")) {
                    var route = svc.selectWorker(id);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(route));
                } else if (path.endsWith("/provider_selection")) {
                    Task task = svc.getTask(id);
                    var route = svc.selectWorker(id);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(providerSelectionView(route, task)));
                } else if (path.endsWith("/agent_run")) {
                    var run = svc.getLatestAgentRun(id);
                    if (run == null) {
                        NioHttpServer.sendNotFound(ex);
                    } else {
                        NioHttpServer.sendJson(ex, 200, ApiResponse.ok(run));
                    }
                } else if (path.endsWith("/runtime_context")) {
                    var context = svc.getRuntimeContext(id);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(context));
                } else if (path.endsWith("/judgment_trace")) {
                    var trace = svc.getJudgmentTrace(id);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(trace));
                } else if (path.endsWith("/live_flow")) {
                    Map<String, String> params = parseQuery(query);
                    int limit = parseLimit(params.get("limit"));
                    var flow = svc.getLiveFlow(id, limit);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(flow));
                } else if (path.endsWith("/events")) {
                    Map<String, String> params = parseQuery(query);
                    if (acceptsEventStream(ex, params)) {
                        streamTaskEvents(ex, id, params);
                    } else {
                        int limit = parseLimit(params.get("limit"));
                        var events = svc.listEvents(id, limit);
                        NioHttpServer.sendJson(ex, 200, ApiResponse.ok(events));
                    }
                } else if (path.endsWith("/provider_run_file")) {
                    Map<String, String> params = parseQuery(query);
                    if (acceptsEventStream(ex, params)) {
                        streamProviderRunFile(ex, id, params);
                    } else {
                        var file = svc.getProviderRunFile(
                            id,
                            params.get("kind"),
                            isTruthy(params.get("tail")),
                            parseOptionalPositiveInt(params.get("max_lines"))
                        );
                        NioHttpServer.sendJson(ex, 200, ApiResponse.ok(file));
                    }
                } else if (path.endsWith("/experiment_run")) {
                    var run = svc.getExperimentRun(id);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(run));
                } else if (path.endsWith("/experiment_summary")) {
                    if (experimentMatrixService == null) {
                        throw new IllegalStateException("experiment matrix service unavailable");
                    }
                    var run = svc.getExperimentRun(id);
                    if (run == null || run.experimentName() == null || run.experimentName().isBlank()) {
                        NioHttpServer.sendNotFound(ex);
                    } else {
                        var summary = experimentMatrixService.summarizeExperiment(run.experimentName());
                        NioHttpServer.sendJson(ex, 200, ApiResponse.ok(summary));
                    }
                } else if (path.endsWith("/harness_trace")) {
                    Map<String, String> params = parseQuery(query);
                    int limit = parseLimit(params.get("limit"));
                    var trace = svc.getHarnessTrace(id, limit);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(trace));
                } else if (path.endsWith("/tool_trace")) {
                    Map<String, String> params = parseQuery(query);
                    int limit = parseLimit(params.get("limit"));
                    var trace = svc.listToolInvocations(id, limit);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(trace));
                } else if (path.endsWith("/recovery_jobs")) {
                    Map<String, String> params = parseQuery(query);
                    int limit = parseLimit(params.get("limit"));
                    var jobs = svc.listRecoveryJobs(id, limit);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(jobs));
                } else if (path.endsWith("/artifacts")) {
                    Map<String, String> params = parseQuery(query);
                    int limit = parseLimit(params.get("limit"));
                    var artifacts = svc.listArtifacts(id, limit);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(artifacts));
                } else if (path.endsWith("/handoff_packet")) {
                    Map<String, String> params = parseQuery(query);
                    String target = params.getOrDefault("target_worker", "codex");
                    var packet = svc.getHandoffPacket(id, target);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(packet));
                } else if (path.endsWith("/pause")) {
                    NioHttpServer.markDeprecatedWriteRoute(ex, "POST", path);
                    var result = svc.pauseTask(id, "manual pause via API", requestMetadata("GET", path, true));
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(result));
                } else if (path.endsWith("/resume")) {
                    NioHttpServer.markDeprecatedWriteRoute(ex, "POST", path);
                    var result = svc.resumeTask(id, requestMetadata("GET", path, true));
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(result));
                } else if (path.endsWith("/continue")) {
                    NioHttpServer.markDeprecatedWriteRoute(ex, "POST", path);
                    var result = svc.continueTask(id, requestMetadata("GET", path, true));
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(result));
                } else if (path.endsWith("/escalate")) {
                    NioHttpServer.markDeprecatedWriteRoute(ex, "POST", path);
                    var result = svc.escalateTask(id, "manual escalation via API", requestMetadata("GET", path, true));
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(result));
                } else {
                    Task t = svc.getTask(id);
                    if (t == null) NioHttpServer.sendNotFound(ex);
                    else NioHttpServer.sendJson(ex, 200, ApiResponse.ok(t));
                }
            } else if ("POST".equals(method) && path.matches("/api/v1/tasks/[^/]+/handoff")) {
                String id = NioHttpServer.pathVar(ex, 4);
                Map<String, Object> body = readJsonBodyAsMap(ex);
                String target = body.getOrDefault("target_worker", "codex").toString();
                HandoffResult result = svc.handoffTask(id, target, requestMetadata("POST", path, false));
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(result));
            } else if ("POST".equals(method) && path.matches("/api/v1/tasks/[^/]+/state")) {
                String id = NioHttpServer.pathVar(ex, 4);
                Map<String, Object> body = readJsonBodyAsMap(ex);
                String state = body.getOrDefault("state", "active").toString();
                String reason = body.getOrDefault("reason", "api update").toString();
                Task t = svc.updateTaskState(id, state, reason, requestMetadata("POST", path, false));
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(t));
            } else {
                NioHttpServer.sendMethodNotAllowed(ex);
            }
        } catch (JsonProcessingException e) {
            log.warn("TaskHandler invalid json: {}", e.getOriginalMessage());
            NioHttpServer.sendMalformedJson(ex);
        } catch (IllegalArgumentException e) {
            log.warn("TaskHandler validation error: {}", e.getMessage());
            NioHttpServer.sendIllegalArgument(ex, e);
        } catch (Exception e) {
            log.error("TaskHandler error", e);
            NioHttpServer.sendInternalError(ex);
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new java.util.HashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) map.put(kv[0], java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        return map;
    }

    private int parseLimit(String raw) {
        try {
            int limit = raw == null ? 5 : Integer.parseInt(raw);
            return Math.max(1, Math.min(limit, 20));
        } catch (Exception ignored) {
            return 5;
        }
    }

    private boolean acceptsEventStream(HttpExchange ex, Map<String, String> params) {
        String stream = params.get("stream");
        String accept = ex.getRequestHeaders().getFirst("Accept");
        return "true".equalsIgnoreCase(stream)
            || "1".equals(stream)
            || (accept != null && accept.toLowerCase().contains("text/event-stream"));
    }

    private void streamTaskEvents(HttpExchange ex, String taskId, Map<String, String> params) throws IOException {
        int limit = parseLimit(params.get("limit"));
        int intervalMs = parsePositiveInt(params.get("interval_ms"), 1500, 250, 10000);
        int maxTicks = parsePositiveInt(params.get("max_ticks"), 120, 1, 600);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        Set<String> sentIds = new LinkedHashSet<>();
        try (OutputStream body = ex.getResponseBody()) {
            writeSseEvent(body, "task.snapshot", Map.of(
                "task_id", taskId,
                "events", svc.listEvents(taskId, limit),
                "created_at", Instant.now()
            ));
            for (int tick = 0; tick < maxTicks; tick++) {
                List<Event> events = svc.listEvents(taskId, limit).stream()
                    .sorted(Comparator.comparing(Event::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
                for (Event event : events) {
                    if (event != null && sentIds.add(event.id())) {
                        writeSseEvent(body, event.eventType() == null ? "task.event" : event.eventType(), event);
                    }
                }
                writeSseComment(body, "heartbeat " + Instant.now());
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            writeSseEvent(body, "task.stream.done", Map.of("task_id", taskId, "created_at", Instant.now()));
        } catch (IOException e) {
            if (NioHttpServer.isClientDisconnect(e)) {
                return;
            }
            throw e;
        } finally {
            ex.close();
        }
    }

    private void streamProviderRunFile(HttpExchange ex, String taskId, Map<String, String> params) throws IOException {
        String kind = params.get("kind");
        boolean tail = providerRunFileStreamDefaultsToTail(kind);
        if (params.containsKey("tail")) {
            tail = isTruthy(params.get("tail"));
        }
        Integer maxLines = parseOptionalPositiveInt(params.get("max_lines"));
        int intervalMs = parsePositiveInt(params.get("interval_ms"), 1500, 250, 10000);
        int maxTicks = parsePositiveInt(params.get("max_ticks"), 120, 1, 600);
        ProviderRunFileView initialFile = svc.getProviderRunFile(taskId, kind, tail, maxLines);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        String lastFingerprint = null;
        try (OutputStream body = ex.getResponseBody()) {
            for (int tick = 0; tick < maxTicks; tick++) {
                ProviderRunFileView file = tick == 0
                    ? initialFile
                    : svc.getProviderRunFile(taskId, kind, tail, maxLines);
                String fingerprint = providerRunFileFingerprint(file);
                if (tick == 0) {
                    writeSseEvent(body, "provider_run_file.snapshot", file);
                } else if (!fingerprint.equals(lastFingerprint)) {
                    writeSseEvent(body, "provider_run_file.update", file);
                }
                lastFingerprint = fingerprint;
                writeSseComment(body, "heartbeat " + Instant.now());
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            writeSseEvent(body, "provider_run_file.stream.done", Map.of(
                "task_id", taskId,
                "kind", kind == null ? "last_message" : kind,
                "created_at", Instant.now()
            ));
        } catch (IOException e) {
            if (NioHttpServer.isClientDisconnect(e)) {
                return;
            }
            throw e;
        } finally {
            ex.close();
        }
    }

    private String providerRunFileFingerprint(ProviderRunFileView file) {
        if (file == null) {
            return "";
        }
        return String.join("|",
            String.valueOf(file.kind()),
            String.valueOf(file.path()),
            String.valueOf(file.sizeBytes()),
            String.valueOf(file.offsetBytes()),
            String.valueOf(file.content())
        );
    }

    private boolean providerRunFileStreamDefaultsToTail(String kind) {
        if (kind == null || kind.isBlank()) {
            return false;
        }
        String normalized = kind.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return "events".equals(normalized)
            || "event_log".equals(normalized)
            || "events_jsonl".equals(normalized)
            || "stdout".equals(normalized)
            || "output".equals(normalized);
    }

    private void writeSseEvent(OutputStream body, String eventName, Object payload) throws IOException {
        body.write(("event: " + eventName + "\n").getBytes(StandardCharsets.UTF_8));
        body.write("data: ".getBytes(StandardCharsets.UTF_8));
        body.write(mapper.writeValueAsBytes(payload));
        body.write("\n\n".getBytes(StandardCharsets.UTF_8));
        body.flush();
    }

    private void writeSseComment(OutputStream body, String comment) throws IOException {
        body.write((": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8));
        body.flush();
    }

    private int parsePositiveInt(String raw, int fallback, int min, int max) {
        try {
            int parsed = raw == null ? fallback : Integer.parseInt(raw);
            return Math.max(min, Math.min(parsed, max));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean isAsyncRecoveryRequested(String query, Map<String, Object> body) {
        Map<String, String> params = parseQuery(query);
        if (isTruthy(params.get("async")) || isTruthy(params.get("background"))) {
            return true;
        }
        Object async = body != null ? body.get("async") : null;
        if (async instanceof Boolean bool) {
            return bool;
        }
        if (isTruthy(stringValue(async))) {
            return true;
        }
        Object wait = body != null ? body.get("wait") : null;
        if (wait instanceof Boolean bool) {
            return !bool;
        }
        String waitValue = stringValue(wait);
        return waitValue != null && ("false".equalsIgnoreCase(waitValue) || "0".equals(waitValue));
    }

    private boolean isTruthy(String value) {
        return value != null
            && ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value));
    }

    private Integer parseOptionalPositiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Map<String, Object> readJsonBodyAsMap(HttpExchange ex) throws IOException {
        String body = NioHttpServer.readBody(ex);
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(body, Map.class);
    }

    private Map<String, Object> requestMetadata(String method, String path, boolean legacyControlRoute) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("requested_via", "http_api");
        metadata.put("request_method", method);
        metadata.put("request_path", path);
        if (legacyControlRoute) {
            metadata.put("legacy_control_route", true);
        }
        return metadata;
    }

    private String stringValue(Map<String, Object> body, String key, String fallback) {
        if (body == null || key == null || key.isBlank()) {
            return fallback;
        }
        Object value = body.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private ProviderSelectionView providerSelectionView(WorkerRouter.RouteResult route, Task task) {
        String selectedProvider = providerIdForWorker(route.selectedWorker(), route.selectedWorkerType());
        AgentProvider provider = provider(selectedProvider);
        AgentProviderDescriptor descriptor = provider != null ? provider.descriptor() : null;
        AgentProviderStatus status = agentProviderRegistry != null ? agentProviderRegistry.status(selectedProvider) : null;

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "model_mode", metadataString(task != null ? task.metadata() : null, "model_mode"));
        putIfNotBlank(metadata, "route_source", route.routeSource());
        putIfNotBlank(metadata, "task_type", route.taskType());
        putIfNotBlank(metadata, "selected_provider_profile", route.selectedProviderProfile());
        putIfNotBlank(metadata, "preferred_provider_profile", route.preferredProviderProfile());
        putIfNotBlank(metadata, "workflow_stage", route.workflowStage());
        putIfNotBlank(metadata, "selected_worker_type", route.selectedWorkerType());
        putIfNotBlank(metadata, "preferred_worker_hint", route.preferredWorkerHint());
        metadata.put("learning_hint_applied", route.learningHintApplied());
        if (route.candidateWorkers() != null && !route.candidateWorkers().isEmpty()) {
            metadata.put("candidate_workers", route.candidateWorkers());
        }
        if (route.fallbackWorkers() != null && !route.fallbackWorkers().isEmpty()) {
            metadata.put("fallback_workers", route.fallbackWorkers());
        }
        metadata.put("free_first_routing", route.freeFirstRouting());
        if (route.freeCandidateWorkers() != null && !route.freeCandidateWorkers().isEmpty()) {
            metadata.put("free_candidate_workers", route.freeCandidateWorkers());
        }
        if (route.paidCandidateWorkers() != null && !route.paidCandidateWorkers().isEmpty()) {
            metadata.put("paid_candidate_workers", route.paidCandidateWorkers());
        }
        putIfNotBlank(metadata, "cost_route_stage", route.costRouteStage());
        metadata.put("manual_window_required", route.manualWindowRequired());
        putIfNotBlank(metadata, "recommended_manual_provider", route.recommendedManualProvider());
        putIfNotBlank(metadata, "manual_followup_instruction", route.manualFollowupInstruction());
        if (route.manualWindowCandidates() != null && !route.manualWindowCandidates().isEmpty()) {
            metadata.put("manual_window_candidates", route.manualWindowCandidates());
        }
        if (route.recoveryUnpinnedRecommendation() != null
            && Boolean.TRUE.equals(route.recoveryUnpinnedRecommendation().providerDeprioritized())) {
            metadata.put("provider_deprioritized", true);
            putIfNotBlank(metadata, "deprioritized_provider", route.recoveryUnpinnedRecommendation().deprioritizedProvider());
            putIfNotBlank(metadata, "deprioritization_reason", route.recoveryUnpinnedRecommendation().deprioritizationReason());
        }
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

    private List<String> candidateProviders(WorkerRouter.RouteResult route, String selectedProvider) {
        Set<String> providerIds = new LinkedHashSet<>();
        if (route.candidateWorkers() != null) {
            for (String workerId : route.candidateWorkers()) {
                String providerId = providerIdForWorker(workerId, workerId);
                if (providerId != null) {
                    providerIds.add(providerId);
                }
            }
        }
        if (route.manualWindowCandidates() != null) {
            for (String candidate : route.manualWindowCandidates()) {
                String providerId = providerIdForWorker(candidate, candidate);
                if (providerId != null) {
                    providerIds.add(providerId);
                }
            }
        }
        if (providerIds.isEmpty() && selectedProvider != null) {
            providerIds.add(selectedProvider);
        }
        return List.copyOf(providerIds);
    }

    private AgentProvider provider(String providerId) {
        return agentProviderRegistry == null || providerId == null ? null : agentProviderRegistry.get(providerId);
    }

    private String providerIdForWorker(String workerId, String workerType) {
        return AgentProviderResolver.providerIdForWorker(workerId, workerType);
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

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
