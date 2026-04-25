package com.agentcloud.server;

import com.agentcloud.engine.SessionService;
import com.agentcloud.model.SessionMessageCreateRequest;
import com.agentcloud.model.ApiResponse;
import com.agentcloud.model.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

class SessionHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(SessionHandler.class);
    private final SessionService svc;
    private final ObjectMapper mapper;

    SessionHandler(SessionService svc, ObjectMapper mapper) {
        this.svc = svc;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();

            if ("POST".equals(method) && path.equals("/api/v1/sessions")) {
                Map<String, Object> body = mapper.readValue(NioHttpServer.readBody(ex), Map.class);
                String title = body.getOrDefault("title", "untitled").toString();
                Session s = svc.createSession(title);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(s));
            } else if ("GET".equals(method) && path.equals("/api/v1/sessions")) {
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(svc.listSessions()));
            } else if ("POST".equals(method) && path.matches("/api/v1/sessions/[^/]+/messages")) {
                String id = NioHttpServer.pathVar(ex, 4);
                SessionMessageCreateRequest req = mapper.readValue(NioHttpServer.readBody(ex), SessionMessageCreateRequest.class);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(svc.addMessage(id, req)));
            } else if ("GET".equals(method) && path.startsWith("/api/v1/sessions/")) {
                String id = NioHttpServer.pathVar(ex, 4);
                if (path.endsWith("/tasks")) {
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(svc.getSessionTasks(id)));
                } else if (path.endsWith("/messages")) {
                    Map<String, String> params = parseQuery(query);
                    int limit = parseLimit(params.get("limit"));
                    String taskId = params.get("task_id");
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(svc.listMessages(id, limit, taskId)));
                } else if (path.endsWith("/close")) {
                    Session s = svc.closeSession(id);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(s));
                } else {
                    Session s = svc.getSession(id);
                    if (s == null) NioHttpServer.sendJson(ex, 404, ApiResponse.error("404", "not found"));
                    else NioHttpServer.sendJson(ex, 200, ApiResponse.ok(s));
                }
            } else {
                NioHttpServer.sendJson(ex, 405, ApiResponse.error("405", "method not allowed"));
            }
        } catch (IllegalArgumentException e) {
            log.warn("SessionHandler validation error: {}", e.getMessage());
            NioHttpServer.sendJson(ex, 400, ApiResponse.error("400", e.getMessage()));
        } catch (Exception e) {
            log.error("SessionHandler error", e);
            NioHttpServer.sendJson(ex, 500, ApiResponse.error("500", e.getMessage()));
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
            int limit = raw == null ? 50 : Integer.parseInt(raw);
            return Math.max(1, Math.min(limit, 100));
        } catch (Exception ignored) {
            return 50;
        }
    }
}
