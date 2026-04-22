package com.agentcloud.server;

import com.agentcloud.engine.SkillRegistry;
import com.agentcloud.model.ApiResponse;
import com.agentcloud.model.Skill;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

class SkillHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(SkillHandler.class);
    private final SkillRegistry registry;
    private final ObjectMapper mapper;

    SkillHandler(SkillRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if ("GET".equals(method) && path.equals("/api/v1/skills")) {
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(registry.listAll()));
            } else if ("POST".equals(method) && path.equals("/api/v1/skills")) {
                Map<String, Object> body = mapper.readValue(NioHttpServer.readBody(ex), Map.class);
                Skill skill = new Skill(
                    body.getOrDefault("id", java.util.UUID.randomUUID().toString()).toString(),
                    body.get("name").toString(),
                    body.getOrDefault("description", "").toString(),
                    (List<String>) body.getOrDefault("capability_tags", List.of()),
                    (Map<String, Object>) body.getOrDefault("input_schema", Map.of()),
                    (Map<String, Object>) body.getOrDefault("output_schema", Map.of()),
                    (Map<String, Boolean>) body.getOrDefault("dependencies", Map.of()),
                    body.getOrDefault("risk_level", "medium").toString(),
                    true, true, null,
                    body.getOrDefault("version", "1.0").toString(),
                    Map.of(), null, null
                );
                registry.register(skill);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(skill));
            } else if ("GET".equals(method) && path.matches("/api/v1/skills/[^/]+")) {
                String id = NioHttpServer.pathVar(ex, 4);
                Skill s = registry.get(id);
                if (s == null) NioHttpServer.sendJson(ex, 404, ApiResponse.error("404", "not found"));
                else NioHttpServer.sendJson(ex, 200, ApiResponse.ok(s));
            } else if ("GET".equals(method) && path.matches("/api/v1/skills/[^/]+/readiness")) {
                String id = NioHttpServer.pathVar(ex, 4);
                var check = registry.checkReadiness(id);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(check));
            } else {
                NioHttpServer.sendJson(ex, 405, ApiResponse.error("405", "method not allowed"));
            }
        } catch (Exception e) {
            log.error("SkillHandler error", e);
            NioHttpServer.sendJson(ex, 500, ApiResponse.error("500", e.getMessage()));
        }
    }
}
