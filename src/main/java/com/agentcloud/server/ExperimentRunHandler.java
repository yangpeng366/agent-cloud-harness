package com.agentcloud.server;

import com.agentcloud.engine.ExperimentRunService;
import com.agentcloud.engine.TaskService;
import com.agentcloud.model.ApiResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

class ExperimentRunHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(ExperimentRunHandler.class);
    private final TaskService taskService;
    private final ExperimentRunService experimentRunService;

    ExperimentRunHandler(TaskService taskService, ExperimentRunService experimentRunService) {
        this.taskService = taskService;
        this.experimentRunService = experimentRunService;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());

            if ("GET".equals(method) && path.equals("/api/v1/experiment_runs")) {
                int limit = parseLimit(params.get("limit"));
                var runs = experimentRunService.listRuns(
                    params.get("experiment_name"),
                    params.get("task_case_key"),
                    params.get("task_length_bucket"),
                    params.get("model_mode"),
                    params.get("tool_execution_mode"),
                    params.get("tool_chain_termination_reason"),
                    parsePositiveInt(params.get("min_tool_chain_steps")),
                    parsePositiveInt(params.get("max_tool_chain_steps")),
                    limit
                );
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(runs));
                return;
            }

            if ("GET".equals(method) && path.startsWith("/api/v1/experiment_runs/")) {
                String taskId = NioHttpServer.pathVar(ex, 4);
                var run = taskService.getExperimentRun(taskId);
                if (run == null) {
                    NioHttpServer.sendNotFound(ex);
                } else {
                    NioHttpServer.sendJson(ex, 200, ApiResponse.ok(run));
                }
                return;
            }

            NioHttpServer.sendMethodNotAllowed(ex);
        } catch (IllegalArgumentException e) {
            log.warn("ExperimentRunHandler validation error: {}", e.getMessage());
            NioHttpServer.sendIllegalArgument(ex, e);
        } catch (Exception e) {
            log.error("ExperimentRunHandler error", e);
            NioHttpServer.sendInternalError(ex);
        }
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
            int limit = raw == null ? 50 : Integer.parseInt(raw);
            return Math.max(1, Math.min(limit, 200));
        } catch (Exception ignored) {
            return 50;
        }
    }

    private Integer parsePositiveInt(String raw) {
        try {
            int value = raw == null ? 0 : Integer.parseInt(raw);
            return value > 0 ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
