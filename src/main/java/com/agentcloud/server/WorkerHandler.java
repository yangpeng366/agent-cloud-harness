package com.agentcloud.server;

import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.ApiResponse;
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
import java.util.Set;

class WorkerHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(WorkerHandler.class);
    private static final Set<String> KNOWN_TOOL_CAPABILITIES = Set.of(
        "search_text", "read_file", "write_file", "list_files", "patch_file"
    );
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
                if (w == null) NioHttpServer.sendNotFound(ex);
                else NioHttpServer.sendJson(ex, 200, ApiResponse.ok(w));
            } else if ("GET".equals(method) && path.matches("/api/v1/workers/[^/]+/readiness")) {
                String id = NioHttpServer.pathVar(ex, 4);
                var check = registry.checkReadiness(id);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(check));
            } else if ("POST".equals(method) && path.equals("/api/v1/workers")) {
                Map<String, Object> body = mapper.readValue(NioHttpServer.readBody(ex), Map.class);
                var w = new com.agentcloud.model.Worker(
                    requiredString(body, "worker_id"),
                    optionalString(body, "worker_type", "other"),
                    stringList(body, "capabilities"),
                    validatedToolCapabilities(stringList(body, "tool_capabilities")),
                    stringList(body, "tool_scope"),
                    booleanMap(body, "dependencies"),
                    objectMap(body, "metadata"),
                    optionalBoolean(body, "suggest_only", false),
                    optionalBoolean(body, "ready", true)
                );
                registry.register(w);
                NioHttpServer.sendJson(ex, 200, ApiResponse.ok(w));
            } else {
                NioHttpServer.sendMethodNotAllowed(ex);
            }
        } catch (JsonProcessingException e) {
            log.warn("WorkerHandler invalid json: {}", e.getOriginalMessage());
            NioHttpServer.sendMalformedJson(ex);
        } catch (IllegalArgumentException e) {
            log.warn("WorkerHandler validation error: {}", e.getMessage());
            NioHttpServer.sendIllegalArgument(ex, e);
        } catch (Exception e) {
            log.error("WorkerHandler error", e);
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

    private boolean optionalBoolean(Map<String, Object> body, String key, boolean defaultValue) {
        Object raw = body.get(key);
        if (raw == null) {
            return defaultValue;
        }
        return parseBooleanValue(raw, key);
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

    private List<String> validatedToolCapabilities(List<String> toolCapabilities) {
        for (String toolCapability : toolCapabilities) {
            if (!KNOWN_TOOL_CAPABILITIES.contains(toolCapability)) {
                throw new IllegalArgumentException("unknown tool capability: " + toolCapability);
            }
        }
        return toolCapabilities;
    }
}
