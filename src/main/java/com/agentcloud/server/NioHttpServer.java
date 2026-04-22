package com.agentcloud.server;

import com.agentcloud.engine.ConsolidationService;
import com.agentcloud.engine.SessionService;
import com.agentcloud.engine.SkillRegistry;
import com.agentcloud.engine.TaskService;
import com.agentcloud.engine.router.WorkerRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

public class NioHttpServer {
    private static final Logger log = LoggerFactory.getLogger(NioHttpServer.class);
    private final int port;
    private final TaskService taskService;
    private final SessionService sessionService;
    private final WorkerRegistry workerRegistry;
    private final SkillRegistry skillRegistry;
    private final ConsolidationService consolidation;
    private final ObjectMapper mapper;
    private HttpServer server;

    public NioHttpServer(int port, TaskService taskService, SessionService sessionService,
                         WorkerRegistry workerRegistry, SkillRegistry skillRegistry, ConsolidationService consolidation) {
        this.port = port;
        this.taskService = taskService;
        this.sessionService = sessionService;
        this.workerRegistry = workerRegistry;
        this.skillRegistry = skillRegistry;
        this.consolidation = consolidation;
        this.mapper = SHARED_MAPPER;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/api/v1/tasks", new TaskHandler(taskService, mapper));
        server.createContext("/api/v1/sessions", new SessionHandler(sessionService, mapper));
        server.createContext("/api/v1/workers", new WorkerHandler(workerRegistry, mapper));
        server.createContext("/api/v1/skills", new SkillHandler(skillRegistry, mapper));
        server.createContext("/api/v1/checkpoints", new CheckpointHandler(consolidation, mapper));
        server.createContext("/api/v1/health", exchange -> {
            sendJson(exchange, 200, Map.of("status", "up", "virtual_threads", true, "version", "0.2.0"));
        });

        server.start();
        log.info("NIO HTTP Server started on port {} (virtual threads enabled)", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("Server stopped");
        }
    }

    static final ObjectMapper SHARED_MAPPER = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
        .setDefaultPropertyInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = SHARED_MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    static String pathVar(HttpExchange ex, int idx) {
        String path = ex.getRequestURI().getPath();
        String[] parts = path.split("/");
        return parts.length > idx ? parts[idx] : "";
    }
}
