package com.agentcloud.server;

import com.agentcloud.engine.LearningMemoryService;
import com.agentcloud.model.ApiResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

class LearningMemoryHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(LearningMemoryHandler.class);
    private final LearningMemoryService svc;

    LearningMemoryHandler(LearningMemoryService svc) {
        this.svc = svc;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());

            if ("GET".equals(method) && path.equals("/api/v1/learning_memories")) {
                int limit = parseLimit(params.get("limit"));
                if (params.get("task_id") != null && !params.get("task_id").isBlank()) {
                    var list = svc.listByTask(params.get("task_id"), limit);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(list));
                    return;
                }
                if (params.get("memory_type") != null && !params.get("memory_type").isBlank()) {
                    var list = svc.listByType(params.get("memory_type"), limit);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(list));
                    return;
                }
                NioHttpServer.sendBadRequest(ex, "task_id or memory_type is required");
                return;
            }

            if ("GET".equals(method) && path.startsWith("/api/v1/learning_memories/")) {
                String taskId = NioHttpServer.pathVar(ex, 4);
                int limit = parseLimit(params.get("limit"));
                var list = svc.listByTask(taskId, limit);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(list));
                return;
            }

            NioHttpServer.sendMethodNotAllowed(ex);
        } catch (IllegalArgumentException e) {
            log.warn("LearningMemoryHandler validation error: {}", e.getMessage());
            NioHttpServer.sendIllegalArgument(ex, e);
        } catch (Exception e) {
            log.error("LearningMemoryHandler error", e);
            NioHttpServer.sendInternalError(ex);
        }
    }

    private int parseLimit(String raw) {
        try {
            int limit = raw == null ? 20 : Integer.parseInt(raw);
            return Math.max(1, Math.min(limit, 100));
        } catch (Exception ignored) {
            return 20;
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
}
