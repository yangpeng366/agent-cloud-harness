package com.agentcloud.server;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

class WorkerHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(WorkerHandler.class);
    private final WorkerRegistry registry;
    private final ObjectMapper mapper;

    WorkerHandler(WorkerRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if ("GET".equals(method) && path.equals("/api/v1/workers")) {
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(registry.listAll()));
            } else if ("GET".equals(method) && path.matches("/api/v1/workers/[^/]+")) {
                String id = NioHttpServer.pathVar(ex, 4);
                var w = registry.get(id);
                if (w == null) NioHttpServer.sendJson(ex, 404, ApiResponse.error("404", "not found"));
                else NioHttpServer.sendJson(ex, 200, ApiResponse.ok(w));
            } else if ("GET".equals(method) && path.matches("/api/v1/workers/[^/]+/readiness")) {
                String id = NioHttpServer.pathVar(ex, 4);
                var check = registry.checkReadiness(id);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(check));
            } else if ("POST".equals(method) && path.equals("/api/v1/workers")) {
                Map<String, Object> body = mapper.readValue(NioHttpServer.readBody(ex), Map.class);
                var w = new com.agentcloud.model.Worker(
                    body.get("worker_id").toString(),
                    body.getOrDefault("worker_type", "other").toString(),
                    (java.util.List<String>) body.getOrDefault("capabilities", java.util.List.of()),
                    (Map<String, Boolean>) body.getOrDefault("dependencies", Map.of()),
                    (Map<String, Object>) body.getOrDefault("metadata", Map.of()),
                    true
                );
                registry.register(w);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(w));
            } else {
                NioHttpServer.sendJson(ex, 405, ApiResponse.error("405", "method not allowed"));
            }
        } catch (Exception e) {
            log.error("WorkerHandler error", e);
            NioHttpServer.sendJson(ex, 500, ApiResponse.error("500", e.getMessage()));
        }
    }
}
