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
                Map<String, Object> actionMetadata = requestMetadata("POST", path, false);
                TaskControlResult result = switch (action) {
                    case "pause" -> svc.pauseTask(id, stringValue(body, "reason", "manual pause via API"), actionMetadata);
                    case "resume" -> svc.resumeTask(id, actionMetadata);
                    case "continue" -> svc.continueTask(id, actionMetadata);
                    case "escalate" -> svc.escalateTask(id, stringValue(body, "reason", "manual escalation via API"), actionMetadata);
                    default -> throw new IllegalArgumentException("unsupported control action");
                };
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(result));
            } else if ("GET".equals(method) && path.equals("/api/v1/tasks")) {
                Map<String, String> params = parseQuery(query);
                String status = params.get("status") != null ? params.get("status") : params.get("state");
                var list = svc.listTasks(status, params.get("task_type"), params.get("assigned_worker"));
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
                    var route = svc.selectWorker(id);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(providerSelectionView(route)));
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

    private ProviderSelectionView providerSelectionView(WorkerRouter.RouteResult route) {
        String selectedProvider = providerIdForWorker(route.selectedWorker(), route.selectedWorkerType());
        AgentProvider provider = provider(selectedProvider);
        AgentProviderDescriptor descriptor = provider != null ? provider.descriptor() : null;
        AgentProviderStatus status = agentProviderRegistry != null ? agentProviderRegistry.status(selectedProvider) : null;

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, "route_source", route.routeSource());
        putIfNotBlank(metadata, "task_type", route.taskType());
        putIfNotBlank(metadata, "selected_worker_type", route.selectedWorkerType());
        putIfNotBlank(metadata, "preferred_worker_hint", route.preferredWorkerHint());
        metadata.put("learning_hint_applied", route.learningHintApplied());
        if (route.candidateWorkers() != null && !route.candidateWorkers().isEmpty()) {
            metadata.put("candidate_workers", route.candidateWorkers());
        }
        if (route.fallbackWorkers() != null && !route.fallbackWorkers().isEmpty()) {
            metadata.put("fallback_workers", route.fallbackWorkers());
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

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
