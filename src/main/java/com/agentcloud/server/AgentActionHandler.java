package com.agentcloud.server;

import com.agentcloud.model.ApiResponse;
import com.agentcloud.store.AgentActionDao;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

class AgentActionHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(AgentActionHandler.class);
    private final AgentActionDao agentActionDao;

    AgentActionHandler(AgentActionDao agentActionDao) {
        this.agentActionDao = agentActionDao;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());

            if ("GET".equals(method) && path.equals("/api/v1/agent_actions")) {
                int limit = parseLimit(params.get("limit"));
                String taskId = blankToNull(params.get("task_id"));
                String sessionId = blankToNull(params.get("session_id"));
                String actionType = normalizeActionType(params.get("action_type"));
                String status = blankToNull(params.get("status"));

                if (taskId != null) {
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(agentActionDao.listByTask(taskId, limit)));
                    return;
                }
                if (sessionId != null) {
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(agentActionDao.listBySession(sessionId, limit)));
                    return;
                }
                if (actionType != null && status != null) {
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(agentActionDao.listByTypeAndStatus(actionType, status, limit)));
                    return;
                }
                if (actionType != null) {
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(agentActionDao.listByType(actionType, limit)));
                    return;
                }
                if (status != null) {
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(agentActionDao.listByStatus(status, limit)));
                    return;
                }
                NioHttpServer.sendBadRequest(ex, "task_id, session_id, action_type, or status is required");
                return;
            }

            if ("GET".equals(method) && path.startsWith("/api/v1/agent_actions/")) {
                String actionId = NioHttpServer.pathVar(ex, 4);
                var action = agentActionDao.findById(actionId);
                if (action.isEmpty()) {
                    NioHttpServer.sendNotFound(ex);
                } else {
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(action.get()));
                }
                return;
            }

            NioHttpServer.sendMethodNotAllowed(ex);
        } catch (IllegalArgumentException e) {
            log.warn("AgentActionHandler validation error: {}", e.getMessage());
            NioHttpServer.sendIllegalArgument(ex, e);
        } catch (Exception e) {
            log.error("AgentActionHandler error", e);
            NioHttpServer.sendInternalError(ex);
        }
    }

    private int parseLimit(String raw) {
        try {
            int limit = raw == null ? 50 : Integer.parseInt(raw);
            return Math.max(1, Math.min(limit, 200));
        } catch (Exception ignored) {
            return 50;
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) {
            return map;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private String normalizeActionType(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toUpperCase().replace('-', '_');
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
