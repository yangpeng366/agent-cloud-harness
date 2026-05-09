package com.agentcloud.server;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderStatus;
import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.engine.AgentRunService;
import com.agentcloud.model.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class AgentHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(AgentHandler.class);

    private final AgentProviderRegistry registry;
    private final AgentRunService agentRunService;
    private final ObjectMapper mapper;

    AgentHandler(AgentProviderRegistry registry, ObjectMapper mapper) {
        this(registry, null, mapper);
    }

    AgentHandler(AgentProviderRegistry registry, AgentRunService agentRunService, ObjectMapper mapper) {
        this.registry = registry;
        this.agentRunService = agentRunService;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if ("GET".equals(method) && path.equals("/api/v1/agents")) {
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(
                    registry.list().stream().map(this::toView).toList()
                ));
            } else if ("GET".equals(method) && path.matches("/api/v1/agents/[^/]+/runs")) {
                String id = NioHttpServer.pathVar(ex, 4);
                AgentProvider provider = registry.get(id);
                if (provider == null) {
                    NioHttpServer.sendNotFound(ex);
                } else {
                    Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
                    String status = params.get("status");
                    List<?> runs = agentRunService == null
                        ? List.of()
                        : agentRunService.listByProvider(id, status, parseLimit(params.get("limit")));
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(runs));
                }
            } else if ("GET".equals(method) && path.matches("/api/v1/agents/[^/]+")) {
                String id = NioHttpServer.pathVar(ex, 4);
                AgentProvider provider = registry.get(id);
                if (provider == null) {
                    NioHttpServer.sendNotFound(ex);
                } else {
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(toView(provider)));
                }
            } else if ("POST".equals(method) && path.matches("/api/v1/agents/[^/]+/refresh")) {
                String id = NioHttpServer.pathVar(ex, 4);
                AgentProvider provider = registry.get(id);
                if (provider == null) {
                    NioHttpServer.sendNotFound(ex);
                } else {
                    AgentProviderStatus refreshed = registry.refresh(id);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(toView(provider, refreshed)));
                }
            } else {
                NioHttpServer.sendMethodNotAllowed(ex);
            }
        } catch (IllegalArgumentException e) {
            log.warn("AgentHandler validation error: {}", e.getMessage());
            NioHttpServer.sendIllegalArgument(ex, e);
        } catch (Exception e) {
            log.error("AgentHandler error", e);
            NioHttpServer.sendInternalError(ex);
        }
    }

    private Map<String, Object> toView(AgentProvider provider) {
        return toView(provider, registry.status(provider.descriptor().providerId()));
    }

    private Map<String, Object> toView(AgentProvider provider, AgentProviderStatus status) {
        var descriptor = provider.descriptor();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("provider_id", descriptor.providerId());
        view.put("display_name", descriptor.displayName());
        view.put("provider_type", descriptor.providerType());
        view.put("transport", descriptor.transport());
        view.put("capabilities", descriptor.capabilities());
        view.put("installed", status.installed());
        view.put("version", status.version());
        view.put("auth_status", status.authStatus());
        view.put("ready", status.ready());
        view.put("readiness_reason", status.readinessReason());
        view.put("checked_at", status.checkedAt());
        view.put("metadata", mergeMetadata(descriptor.metadata(), status.metadata()));
        return view;
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) {
            return map;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0], java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private int parseLimit(String raw) {
        try {
            int limit = raw == null ? 20 : Integer.parseInt(raw);
            return Math.max(1, Math.min(limit, 100));
        } catch (Exception ignored) {
            return 20;
        }
    }

    private Map<String, Object> mergeMetadata(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (left != null) {
            merged.putAll(left);
        }
        if (right != null) {
            merged.putAll(right);
        }
        return merged;
    }
}
