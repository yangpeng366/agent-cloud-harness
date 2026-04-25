package com.agentcloud.server;

import com.agentcloud.engine.TaskService;
import com.agentcloud.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

class TaskHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(TaskHandler.class);
    private final TaskService svc;
    private final ObjectMapper mapper;

    TaskHandler(TaskService svc, ObjectMapper mapper) {
        this.svc = svc;
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
                Task t = svc.createTask(req);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(t));
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
                    var result = svc.pauseTask(id, "manual pause via API");
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(result));
                } else if (path.endsWith("/resume")) {
                    var result = svc.resumeTask(id);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(result));
                } else if (path.endsWith("/continue")) {
                    var result = svc.continueTask(id);
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(result));
                } else if (path.endsWith("/escalate")) {
                    var result = svc.escalateTask(id, "manual escalation via API");
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(result));
                } else {
                    Task t = svc.getTask(id);
                    if (t == null) NioHttpServer.sendJson(ex, 404, ApiResponse.error("404", "not found"));
                    else NioHttpServer.sendJson(ex, 200, ApiResponse.ok(t));
                }
            } else if ("POST".equals(method) && path.matches("/api/v1/tasks/[^/]+/handoff")) {
                String id = NioHttpServer.pathVar(ex, 4);
                Map<String, Object> body = mapper.readValue(NioHttpServer.readBody(ex), Map.class);
                String target = body.getOrDefault("target_worker", "codex").toString();
                HandoffResult result = svc.handoffTask(id, target);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(result));
            } else if ("POST".equals(method) && path.matches("/api/v1/tasks/[^/]+/state")) {
                String id = NioHttpServer.pathVar(ex, 4);
                Map<String, Object> body = mapper.readValue(NioHttpServer.readBody(ex), Map.class);
                String state = body.getOrDefault("state", "active").toString();
                String reason = body.getOrDefault("reason", "api update").toString();
                Task t = svc.updateTaskState(id, state, reason);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(t));
            } else {
                NioHttpServer.sendJson(ex, 405, ApiResponse.error("405", "method not allowed"));
            }
        } catch (Exception e) {
            log.error("TaskHandler error", e);
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
            int limit = raw == null ? 5 : Integer.parseInt(raw);
            return Math.max(1, Math.min(limit, 20));
        } catch (Exception ignored) {
            return 5;
        }
    }
}
