package com.agentcloud.server;

import com.agentcloud.engine.ConsolidationService;
import com.agentcloud.engine.LearningMemoryService;
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

public class NioHttpServer {
    private static final Logger log = LoggerFactory.getLogger(NioHttpServer.class);
    static final ObjectMapper SHARED_MAPPER = createSharedMapper();
    private final int port;
    private final TaskService taskService;
    private final SessionService sessionService;
    private final WorkerRegistry workerRegistry;
    private final SkillRegistry skillRegistry;
    private final ConsolidationService consolidation;
    private final LearningMemoryService learningMemoryService;
    private final ObjectMapper mapper;
    private final ClassLoader appClassLoader;
    private HttpServer server;

    public NioHttpServer(int port, TaskService taskService, SessionService sessionService,
                         WorkerRegistry workerRegistry, SkillRegistry skillRegistry,
                         ConsolidationService consolidation, LearningMemoryService learningMemoryService) {
        this.port = port;
        this.taskService = taskService;
        this.sessionService = sessionService;
        this.workerRegistry = workerRegistry;
        this.skillRegistry = skillRegistry;
        this.consolidation = consolidation;
        this.learningMemoryService = learningMemoryService;
        this.mapper = SHARED_MAPPER;
        this.appClassLoader = NioHttpServer.class.getClassLoader();
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(command -> Thread.ofVirtual().name("agentcloud-http-", 0).start(() -> runWithAppClassLoader(command)));

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) {
                redirect(exchange, "/dialogue/");
                return;
            }
            sendJson(exchange, 404, Map.of("success", false, "code", "404", "message", "not found"));
        });
        server.createContext("/dialogue", new WebConsoleHandler("/dialogue", "web/dialogue"));
        server.createContext("/console", new WebConsoleHandler("/console", "web/console"));
        server.createContext("/api/v1/tasks", new TaskHandler(taskService, mapper));
        server.createContext("/api/v1/sessions", new SessionHandler(sessionService, mapper));
        server.createContext("/api/v1/workers", new WorkerHandler(workerRegistry, mapper));
        server.createContext("/api/v1/skills", new SkillHandler(skillRegistry, mapper));
        server.createContext("/api/v1/checkpoints", new CheckpointHandler(consolidation, mapper));
        server.createContext("/api/v1/learning_memories", new LearningMemoryHandler(learningMemoryService));
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

    private void runWithAppClassLoader(Runnable command) {
        Thread current = Thread.currentThread();
        ClassLoader original = current.getContextClassLoader();
        if (original != appClassLoader) {
            current.setContextClassLoader(appClassLoader);
        }
        try {
            command.run();
        } finally {
            if (original != appClassLoader) {
                current.setContextClassLoader(original);
            }
        }
    }

    private static ObjectMapper createSharedMapper() {
        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .setDefaultPropertyInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        warmUpJsonMapper(mapper);
        return mapper;
    }

    private static void warmUpJsonMapper(ObjectMapper mapper) {
        try {
            // 在服务启动阶段显式解析 jackson-core，避免请求首包时才触发类加载异常。
            Class.forName("com.fasterxml.jackson.core.JsonEncoding", true, NioHttpServer.class.getClassLoader());
            mapper.writeValueAsBytes(Map.of("status", "warmup"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

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

    static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }
}
