package com.agentcloud.server;

import com.agentcloud.engine.SkillRegistry;
import com.agentcloud.model.ApiResponse;
import com.agentcloud.model.Skill;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
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
                    optionalString(body, "id", java.util.UUID.randomUUID().toString()),
                    requiredString(body, "name"),
                    optionalString(body, "description", ""),
                    stringList(body, "capability_tags"),
                    objectMap(body, "input_schema"),
                    objectMap(body, "output_schema"),
                    booleanMap(body, "dependencies"),
                    optionalString(body, "risk_level", "medium"),
                    true, true, null,
                    optionalString(body, "version", "1.0"),
                    objectMap(body, "metadata"),
                    null, null
                );
                registry.register(skill);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(skill));
            } else if ("GET".equals(method) && path.matches("/api/v1/skills/[^/]+")) {
                String id = NioHttpServer.pathVar(ex, 4);
                Skill s = registry.get(id);
                if (s == null) NioHttpServer.sendNotFound(ex);
                else NioHttpServer.sendJson(ex, 200, ApiResponse.ok(s));
            } else if ("GET".equals(method) && path.matches("/api/v1/skills/[^/]+/readiness")) {
                String id = NioHttpServer.pathVar(ex, 4);
                var check = registry.checkReadiness(id);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(check));
            } else {
                NioHttpServer.sendMethodNotAllowed(ex);
            }
        } catch (JsonProcessingException e) {
            log.warn("SkillHandler invalid json: {}", e.getOriginalMessage());
            NioHttpServer.sendMalformedJson(ex);
        } catch (IllegalArgumentException e) {
            log.warn("SkillHandler validation error: {}", e.getMessage());
            NioHttpServer.sendIllegalArgument(ex, e);
        } catch (Exception e) {
            log.error("SkillHandler error", e);
            NioHttpServer.sendInternalError(ex);
        }
    }

    private String requiredString(Map<String, Object> body, String key) {
        String value = optionalString(body, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private String optionalString(Map<String, Object> body, String key, String defaultValue) {
        Object raw = body.get(key);
        if (raw == null) {
            return defaultValue;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? defaultValue : value;
    }

    private List<String> stringList(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        return list.stream()
            .filter(value -> value != null)
            .map(Object::toString)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }

    private Map<String, Object> objectMap(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            values.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return values;
    }

    private Map<String, Boolean> booleanMap(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            values.put(String.valueOf(entry.getKey()), parseBooleanValue(entry.getValue(), key));
        }
        return values;
    }

    private boolean parseBooleanValue(Object raw, String key) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String value) {
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
        }
        throw new IllegalArgumentException(key + " must be boolean");
    }
}
