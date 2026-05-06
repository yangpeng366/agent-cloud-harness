package com.agentcloud.server;

import com.agentcloud.engine.AgentRunService;
import com.agentcloud.model.ApiResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

class RuntimeHealthHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(RuntimeHealthHandler.class);
    private final AgentRunService agentRunService;

    RuntimeHealthHandler(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if ("GET".equals(method) && path.equals("/api/v1/runtime_health")) {
                var health = agentRunService != null
                    ? agentRunService.runtimeHealth(parseLimit(ex.getRequestURI().getQuery()))
                    : null;
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(health));
                return;
            }
            NioHttpServer.sendMethodNotAllowed(ex);
        } catch (Exception e) {
            log.error("RuntimeHealthHandler error", e);
            NioHttpServer.sendInternalError(ex);
        }
    }

    private int parseLimit(String query) {
        if (query == null || query.isBlank()) {
            return 20;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && "limit".equals(parts[0])) {
                try {
                    return Math.max(1, Math.min(Integer.parseInt(parts[1]), 100));
                } catch (NumberFormatException ignored) {
                    return 20;
                }
            }
        }
        return 20;
    }
}
