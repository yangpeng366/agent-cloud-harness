package com.agentcloud.server;

import com.agentcloud.engine.ExperimentMatrixService;
import com.agentcloud.model.ApiResponse;
import com.agentcloud.model.ExperimentMatrixCreateRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

class ExperimentMatrixHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(ExperimentMatrixHandler.class);

    private final ExperimentMatrixService experimentMatrixService;
    private final ObjectMapper mapper;

    ExperimentMatrixHandler(ExperimentMatrixService experimentMatrixService, ObjectMapper mapper) {
        this.experimentMatrixService = experimentMatrixService;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());

            if ("GET".equals(method) && path.equals("/api/v1/experiment_matrix/cases")) {
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(experimentMatrixService.listBaselineCases()));
                return;
            }

            if ("GET".equals(method) && path.equals("/api/v1/experiment_matrix/summary")) {
                String experimentName = params.get("experiment_name");
                if (experimentName == null || experimentName.isBlank()) {
                    NioHttpServer.sendBadRequest(ex, "experiment_name is required");
                    return;
                }
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(experimentMatrixService.summarizeExperiment(experimentName)));
                return;
            }

            if ("POST".equals(method) && path.equals("/api/v1/experiment_matrix/runs")) {
                ExperimentMatrixCreateRequest request = mapper.readValue(
                    NioHttpServer.readBody(ex),
                    ExperimentMatrixCreateRequest.class
                );
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(experimentMatrixService.createBaselineRuns(request)));
                return;
            }

            NioHttpServer.sendMethodNotAllowed(ex);
        } catch (JsonProcessingException e) {
            log.warn("ExperimentMatrixHandler invalid json: {}", e.getOriginalMessage());
            NioHttpServer.sendMalformedJson(ex);
        } catch (IllegalArgumentException e) {
            log.warn("ExperimentMatrixHandler validation error: {}", e.getMessage());
            NioHttpServer.sendIllegalArgument(ex, e);
        } catch (Exception e) {
            log.error("ExperimentMatrixHandler error", e);
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
}
