package com.agentcloud.server;

import com.agentcloud.engine.GoalService;
import com.agentcloud.model.ApiResponse;
import com.agentcloud.model.GoalCreateRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

class GoalHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(GoalHandler.class);
    private final GoalService svc;
    private final ObjectMapper mapper;

    GoalHandler(GoalService svc, ObjectMapper mapper) {
        this.svc = svc;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if ("POST".equals(method) && path.equals("/api/v1/goals")) {
                GoalCreateRequest req = mapper.readValue(NioHttpServer.readBody(ex), GoalCreateRequest.class);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(svc.createGoal(req)));
            } else if ("GET".equals(method) && path.equals("/api/v1/goals")) {
                Map<String, String> q = parseQuery(ex);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(svc.listGoals(q.get("session_id"), q.get("status"))));
            } else if ("GET".equals(method) && path.matches("/api/v1/goals/[^/]+/live_flow")) {
                String id = NioHttpServer.pathVar(ex, 4);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(svc.buildLiveFlow(id)));
            } else if ("GET".equals(method) && path.matches("/api/v1/goals/[^/]+/events")) {
                String id = NioHttpServer.pathVar(ex, 4);
                Map<String, String> q = parseQuery(ex);
                int limit = parseInt(q.get("limit"), 50);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(svc.listEvents(id, limit)));
            } else if ("POST".equals(method) && path.matches("/api/v1/goals/[^/]+/attach-task")) {
                String id = NioHttpServer.pathVar(ex, 4);
                Map<String, Object> body = readBodyMap(ex);
                String taskId = requiredString(body, "task_id");
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(svc.attachTask(id, taskId)));
            } else if ("POST".equals(method) && path.matches("/api/v1/goals/[^/]+/(pause|reopen|continue|close)")) {
                String id = NioHttpServer.pathVar(ex, 4);
                String action = NioHttpServer.pathVar(ex, 5);
                Object result = switch (action) {
                    case "pause" -> svc.pause(id);
                    case "reopen" -> svc.reopen(id);
                    case "continue" -> svc.continueGoal(id);
                    case "close" -> {
                        Map<String, Object> body = readBodyMap(ex);
                        yield svc.close(id, optionalString(body, "outcome_summary", null));
                    }
                    default -> throw new IllegalArgumentException("unsupported action: " + action);
                };
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(result));
            } else if ("GET".equals(method) && path.matches("/api/v1/goals/[^/]+")) {
                String id = NioHttpServer.pathVar(ex, 4);
                var goal = svc.getGoal(id);
                if (goal.isEmpty()) NioHttpServer.sendNotFound(ex);
                else NioHttpServer.sendJson(ex, 200, ApiResponse.ok(goal.get()));
            } else {
                NioHttpServer.sendMethodNotAllowed(ex);
            }
        } catch (JsonProcessingException e) {
            log.warn("GoalHandler invalid json: {}", e.getOriginalMessage());
            NioHttpServer.sendMalformedJson(ex);
        } catch (IllegalArgumentException e) {
            log.warn("GoalHandler validation error: {}", e.getMessage());
            NioHttpServer.sendIllegalArgument(ex, e);
        } catch (Exception e) {
            log.error("GoalHandler error", e);
            NioHttpServer.sendInternalError(ex);
        }
    }

    private Map<String, Object> readBodyMap(HttpExchange ex) throws IOException {
        String body = NioHttpServer.readBody(ex);
        if (body == null || body.isBlank()) return Map.of();
        return mapper.readValue(body, Map.class);
    }

    private Map<String, String> parseQuery(HttpExchange ex) {
        String query = ex.getRequestURI().getQuery();
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return map;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                map.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (k.equals("session_id") || k.equals("status") || k.equals("limit")) {
                    map.put(k, v);
                }
            }
        }
        return map;
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private String requiredString(Map<String, Object> body, String key) {
        String value = optionalString(body, key, null);
        if (value == null) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private String optionalString(Map<String, Object> body, String key, String defaultValue) {
        if (body == null) return defaultValue;
        Object raw = body.get(key);
        if (raw == null) return defaultValue;
        String value = raw.toString().trim();
        return value.isEmpty() ? defaultValue : value;
    }
}