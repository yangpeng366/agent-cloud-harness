package com.agentcloud.server;

import com.agentcloud.engine.AgentRunService;
import com.agentcloud.model.ApiResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class AgentRunHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(AgentRunHandler.class);
    private final AgentRunService agentRunService;

    AgentRunHandler(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if ("GET".equals(method) && path.equals("/api/v1/agent_runs")) {
                Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
                List<?> runs = agentRunService == null
                    ? List.of()
                    : agentRunService.search(
                        firstNonBlank(params.get("provider_id"), params.get("provider")),
                        params.get("status"),
                        firstNonBlank(params.get("role"), params.get("worker_role")),
                        params.get("task_id"),
                        parseLimit(ex.getRequestURI().getQuery(), 20, 100)
                    );
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(runs));
                return;
            }

            if ("GET".equals(method) && path.matches("/api/v1/agent_runs/[^/]+/events")) {
                String runId = NioHttpServer.pathVar(ex, 4);
                var events = agentRunService != null
                    ? agentRunService.listEvents(runId, parseLimit(ex.getRequestURI().getQuery(), 100, 200))
                    : null;
                if (events == null) {
                    NioHttpServer.sendNotFound(ex);
                } else {
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(events));
                }
                return;
            }

            if ("GET".equals(method) && path.matches("/api/v1/agent_runs/[^/]+/artifacts")) {
                String runId = NioHttpServer.pathVar(ex, 4);
                var artifacts = agentRunService != null
                    ? agentRunService.listArtifacts(runId, parseLimit(ex.getRequestURI().getQuery(), 50, 100))
                    : null;
                if (artifacts == null) {
                    NioHttpServer.sendNotFound(ex);
                } else {
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(artifacts));
                }
                return;
            }

            if ("GET".equals(method) && path.matches("/api/v1/agent_runs/[^/]+")) {
                String runId = NioHttpServer.pathVar(ex, 4);
                var run = agentRunService != null ? agentRunService.findById(runId) : null;
                if (run == null) {
                    NioHttpServer.sendNotFound(ex);
                } else {
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(run));
                }
                return;
            }

            NioHttpServer.sendMethodNotAllowed(ex);
        } catch (IllegalArgumentException e) {
            log.warn("AgentRunHandler validation error: {}", e.getMessage());
            NioHttpServer.sendIllegalArgument(ex, e);
        } catch (Exception e) {
            log.error("AgentRunHandler error", e);
            NioHttpServer.sendInternalError(ex);
        }
    }

    private int parseLimit(String query, int defaultLimit, int maxLimit) {
        if (query == null || query.isBlank()) {
            return defaultLimit;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && "limit".equals(parts[0])) {
                try {
                    return Math.max(1, Math.min(Integer.parseInt(parts[1]), maxLimit));
                } catch (NumberFormatException ignored) {
                    return defaultLimit;
                }
            }
        }
        return defaultLimit;
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isBlank()) {
            return params;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                params.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                );
            }
        }
        return params;
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
}
