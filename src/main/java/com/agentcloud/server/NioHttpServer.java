package com.agentcloud.server;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.engine.AgentRunService;
import com.agentcloud.engine.ChatFacadeService;
import com.agentcloud.engine.ConsolidationService;
import com.agentcloud.engine.ExperimentMatrixService;
import com.agentcloud.engine.ExperimentRunService;
import com.agentcloud.engine.LearningMemoryService;
import com.agentcloud.engine.SessionService;
import com.agentcloud.engine.SkillRegistry;
import com.agentcloud.engine.TaskService;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.store.AgentActionDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

public class NioHttpServer {
    private static final Logger log = LoggerFactory.getLogger(NioHttpServer.class);
    static final String LEGACY_WRITE_ROUTE_SUNSET = "Thu, 31 Dec 2026 23:59:59 GMT";
    static final ObjectMapper SHARED_MAPPER = createSharedMapper();
    private final int port;
    private final AgentProviderRegistry agentProviderRegistry;
    private final TaskService taskService;
    private final SessionService sessionService;
    private final WorkerRegistry workerRegistry;
    private final SkillRegistry skillRegistry;
    private final ConsolidationService consolidation;
    private final LearningMemoryService learningMemoryService;
    private final ExperimentRunService experimentRunService;
    private final ExperimentMatrixService experimentMatrixService;
    private final AgentRunService agentRunService;
    private final AgentActionDao agentActionDao;
    private final ObjectMapper mapper;
    private final ClassLoader appClassLoader;
    private HttpServer server;

    public NioHttpServer(int port, TaskService taskService, SessionService sessionService,
                         WorkerRegistry workerRegistry, AgentProviderRegistry agentProviderRegistry, SkillRegistry skillRegistry,
                         ConsolidationService consolidation, LearningMemoryService learningMemoryService,
                         ExperimentRunService experimentRunService,
                         ExperimentMatrixService experimentMatrixService) {
        this(port, taskService, sessionService, workerRegistry, agentProviderRegistry, skillRegistry, consolidation,
            learningMemoryService, experimentRunService, experimentMatrixService, null, null);
    }

    public NioHttpServer(int port, TaskService taskService, SessionService sessionService,
                         WorkerRegistry workerRegistry, AgentProviderRegistry agentProviderRegistry, SkillRegistry skillRegistry,
                         ConsolidationService consolidation, LearningMemoryService learningMemoryService,
                         ExperimentRunService experimentRunService,
                         ExperimentMatrixService experimentMatrixService,
                         AgentRunService agentRunService) {
        this(port, taskService, sessionService, workerRegistry, agentProviderRegistry, skillRegistry, consolidation,
            learningMemoryService, experimentRunService, experimentMatrixService, agentRunService, null);
    }

    public NioHttpServer(int port, TaskService taskService, SessionService sessionService,
                         WorkerRegistry workerRegistry, AgentProviderRegistry agentProviderRegistry, SkillRegistry skillRegistry,
                         ConsolidationService consolidation, LearningMemoryService learningMemoryService,
                         ExperimentRunService experimentRunService,
                         ExperimentMatrixService experimentMatrixService,
                         AgentRunService agentRunService,
                         AgentActionDao agentActionDao) {
        this.port = port;
        this.taskService = taskService;
        this.sessionService = sessionService;
        this.workerRegistry = workerRegistry;
        this.agentProviderRegistry = agentProviderRegistry;
        this.skillRegistry = skillRegistry;
        this.consolidation = consolidation;
        this.learningMemoryService = learningMemoryService;
        this.experimentRunService = experimentRunService;
        this.experimentMatrixService = experimentMatrixService;
        this.agentRunService = agentRunService;
        this.agentActionDao = agentActionDao;
        this.mapper = SHARED_MAPPER;
        this.appClassLoader = NioHttpServer.class.getClassLoader();
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(command -> Thread.ofVirtual().name("agentcloud-http-", 0).start(() -> runWithAppClassLoader(command)));
        ChatFacadeService chatFacadeService = new ChatFacadeService(sessionService, taskService);
        ChatFacadeHandler chatFacadeHandler = new ChatFacadeHandler(chatFacadeService, mapper);

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) {
                redirect(exchange, "/dialogue/");
                return;
            }
            if ("/favicon.ico".equals(path)) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            sendNotFound(exchange);
        });
        server.createContext("/dialogue", new WebConsoleHandler("/dialogue", "web/dialogue"));
        server.createContext("/console", new WebConsoleHandler("/console", "web/console"));
        server.createContext("/v1/chat/completions", chatFacadeHandler);
        server.createContext("/v1/models", chatFacadeHandler);
        server.createContext("/v1/responses", chatFacadeHandler);
        server.createContext("/api/v1/tasks", new TaskHandler(taskService, experimentMatrixService, agentProviderRegistry, mapper));
        server.createContext("/api/v1/sessions", new SessionHandler(sessionService, mapper));
        server.createContext("/api/v1/workers", new WorkerHandler(workerRegistry, mapper));
        server.createContext("/api/v1/agents", new AgentHandler(agentProviderRegistry, agentRunService, mapper));
        server.createContext("/api/v1/agent_runs", new AgentRunHandler(agentRunService));
        if (agentActionDao != null) {
            server.createContext("/api/v1/agent_actions", new AgentActionHandler(agentActionDao));
        }
        server.createContext("/api/v1/runtime_health", new RuntimeHealthHandler(agentRunService));
        server.createContext("/api/v1/skills", new SkillHandler(skillRegistry, mapper));
        server.createContext("/api/v1/checkpoints", new CheckpointHandler(consolidation, mapper));
        server.createContext("/api/v1/learning_memories", new LearningMemoryHandler(learningMemoryService));
        server.createContext("/api/v1/experiment_runs", new ExperimentRunHandler(taskService, experimentRunService));
        server.createContext("/api/v1/experiment_matrix", new ExperimentMatrixHandler(experimentMatrixService, mapper));
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
        try {
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException e) {
            if (isClientDisconnect(e)) {
                return;
            }
            throw e;
        } finally {
            ex.close();
        }
    }

    static boolean isClientDisconnect(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("broken pipe")
                    || normalized.contains("connection reset")
                    || normalized.contains("an established connection was aborted")
                    || normalized.contains("你的主机中的软件中止了一个已建立的连接")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    static void sendApiError(HttpExchange ex, int status, String code, String message) throws IOException {
        sendJson(ex, status, Map.of(
            "success", false,
            "code", code,
            "message", message
        ));
    }

    static void sendInternalError(HttpExchange ex) throws IOException {
        sendApiError(ex, 500, "500", "internal error");
    }

    static void sendBadRequest(HttpExchange ex, String message) throws IOException {
        sendApiError(ex, 400, "400", message);
    }

    static void sendMalformedJson(HttpExchange ex) throws IOException {
        sendBadRequest(ex, "invalid json body");
    }

    static void sendNotFound(HttpExchange ex) throws IOException {
        sendApiError(ex, 404, "404", "not found");
    }

    static void sendMethodNotAllowed(HttpExchange ex) throws IOException {
        sendApiError(ex, 405, "405", "method not allowed");
    }

    static void sendIllegalArgument(HttpExchange ex, IllegalArgumentException error) throws IOException {
        String message = error == null ? "bad request" : error.getMessage();
        if (isNotFoundMessage(message)) {
            sendNotFound(ex);
            return;
        }
        sendBadRequest(ex, message == null || message.isBlank() ? "bad request" : message);
    }

    static void markDeprecatedWriteRoute(HttpExchange ex, String replacementMethod, String replacementPath) {
        String normalizedMethod = replacementMethod == null || replacementMethod.isBlank() ? "POST" : replacementMethod;
        String normalizedPath = replacementPath == null || replacementPath.isBlank() ? "/" : replacementPath;
        ex.getResponseHeaders().set("Deprecation", "true");
        ex.getResponseHeaders().set("Sunset", LEGACY_WRITE_ROUTE_SUNSET);
        ex.getResponseHeaders().set("Link",
            "<" + normalizedPath + ">; rel=\"alternate\"; title=\"Use " + normalizedMethod + "\"");
        ex.getResponseHeaders().set("Warning",
            "299 agent-cloud-harness \"Deprecated write-via-GET route. Use "
                + normalizedMethod + " " + normalizedPath + "\"");
        ex.getResponseHeaders().set("X-AgentCloud-Replacement-Method", normalizedMethod);
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

    private static boolean isNotFoundMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.trim().toLowerCase(Locale.ROOT).endsWith("not found");
    }
}
