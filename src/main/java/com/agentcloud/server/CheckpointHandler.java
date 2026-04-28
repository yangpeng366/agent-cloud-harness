package com.agentcloud.server;

import com.agentcloud.engine.ConsolidationService;
import com.agentcloud.model.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

class CheckpointHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(CheckpointHandler.class);
    private final ConsolidationService consolidation;
    private final ObjectMapper mapper;

    CheckpointHandler(ConsolidationService consolidation, ObjectMapper mapper) {
        this.consolidation = consolidation;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            // GET /api/v1/checkpoints/{taskId}
            if ("GET".equals(method) && path.matches("/api/v1/checkpoints/[^/]+")) {
                String taskId = NioHttpServer.pathVar(ex, 4);
                var list = consolidation.listByTask(taskId, 20);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(list));
            } else {
                NioHttpServer.sendMethodNotAllowed(ex);
            }
        } catch (Exception e) {
            log.error("CheckpointHandler error", e);
            NioHttpServer.sendInternalError(ex);
        }
    }
}
