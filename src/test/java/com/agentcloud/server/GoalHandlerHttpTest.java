package com.agentcloud.server;

import com.agentcloud.engine.GoalService;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.GoalDao;
import com.agentcloud.store.GoalEventDao;
import com.agentcloud.store.TaskDao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalHandlerHttpTest {
    private static final ObjectMapper MAPPER = NioHttpServer.SHARED_MAPPER;

    @TempDir
    Path tempDir;

    @Test
    void createGoalAndGetById() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("goal-http-create.db"))) {
            String body = MAPPER.writeValueAsString(Map.of(
                "title", "落地 /goal 生命周期",
                "objective", "把 goal 变成一等对象",
                "session_id", "sess_1"));
            ApiCall created = fixture.post("/api/v1/goals", body);
            assertEquals(200, created.statusCode());
            String goalId = created.body().path("data").path("id").asText();
            assertEquals("open", created.body().path("data").path("status").asText());
            assertTrue(goalId.startsWith("goal_"));

            ApiCall fetched = fixture.get("/api/v1/goals/" + goalId);
            assertEquals(200, fetched.statusCode());
            assertEquals("落地 /goal 生命周期", fetched.body().path("data").path("title").asText());
        }
    }

    @Test
    void listGoalsBySession() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("goal-http-list.db"))) {
            fixture.post("/api/v1/goals", MAPPER.writeValueAsString(Map.of("title", "g1", "session_id", "sess_1")));
            fixture.post("/api/v1/goals", MAPPER.writeValueAsString(Map.of("title", "g2", "session_id", "sess_2")));

            ApiCall list = fixture.get("/api/v1/goals?session_id=sess_1");
            assertEquals(200, list.statusCode());
            assertEquals(1, list.body().path("data").size());
            assertEquals("g1", list.body().path("data").get(0).path("title").asText());
        }
    }

    @Test
    void attachTaskLinksGoalAndTask() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("goal-http-attach.db"))) {
            String goalId = fixture.createGoal("attach goal");
            ApiCall attached = fixture.post("/api/v1/goals/" + goalId + "/attach-task",
                MAPPER.writeValueAsString(Map.of("task_id", "task_1")));
            assertEquals(200, attached.statusCode());
            assertEquals("task_1", attached.body().path("data").path("active_task_id").asText());
        }
    }

    @Test
    void lifecyclePauseReopenClose() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("goal-http-lifecycle.db"))) {
            String goalId = fixture.createGoal("lifecycle goal");

            assertEquals("paused", fixture.post("/api/v1/goals/" + goalId + "/pause", "{}").body().path("data").path("status").asText());
            assertEquals("open", fixture.post("/api/v1/goals/" + goalId + "/reopen", "{}").body().path("data").path("status").asText());
            ApiCall closed = fixture.post("/api/v1/goals/" + goalId + "/close",
                MAPPER.writeValueAsString(Map.of("outcome_summary", "delivered")));
            assertEquals(200, closed.statusCode());
            assertEquals("closed", closed.body().path("data").path("status").asText());
            assertEquals("delivered", closed.body().path("data").path("outcome_summary").asText());
        }
    }

    @Test
    void liveFlowAggregatesGoalEventsAndTasks() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("goal-http-liveflow.db"))) {
            String goalId = fixture.createGoal("liveflow goal");
            fixture.post("/api/v1/goals/" + goalId + "/attach-task", MAPPER.writeValueAsString(Map.of("task_id", "task_1")));

            ApiCall flow = fixture.get("/api/v1/goals/" + goalId + "/live_flow");
            assertEquals(200, flow.statusCode());
            assertEquals(goalId, flow.body().path("data").path("goal").path("id").asText());
            assertTrue(flow.body().path("data").path("event_count").asInt() >= 2);
            assertEquals(1, flow.body().path("data").path("task_count").asInt());
        }
    }

    @Test
    void getUnknownGoalReturns404() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("goal-http-404.db"))) {
            ApiCall res = fixture.get("/api/v1/goals/goal_does_not_exist");
            assertEquals(404, res.statusCode());
        }
    }

    private static final class HttpFixture implements AutoCloseable {
        private final DatabaseManager db;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client = HttpClient.newHttpClient();
        private final GoalService goalService;
        private final String baseUrl;

        private HttpFixture(Path dbPath) throws IOException {
            this.db = new DatabaseManager(dbPath);
            db.jdbi().onDemand(com.agentcloud.store.SessionDao.class)
                .insert(Session.create("sess_1", "goal session", "active"));
            db.jdbi().onDemand(com.agentcloud.store.SessionDao.class)
                .insert(Session.create("sess_2", "other session", "active"));
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            taskDao.insert(Task.create("task_1", "sess_1", "goal task", "active", "high"));
            GoalDao goalDao = db.jdbi().onDemand(GoalDao.class);
            GoalEventDao goalEventDao = db.jdbi().onDemand(GoalEventDao.class);
            this.goalService = new GoalService(taskDao, goalDao, goalEventDao);

            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = Executors.newSingleThreadExecutor();
            server.setExecutor(executor);
            server.createContext("/api/v1/goals", new GoalHandler(goalService, MAPPER));
            server.start();
            this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private String createGoal(String title) throws Exception {
            ApiCall created = post("/api/v1/goals",
                MAPPER.writeValueAsString(Map.of("title", title, "session_id", "sess_1", "objective", "obj")));
            assertEquals(200, created.statusCode(), "createGoal failed: " + created.body());
            return created.body().path("data").path("id").asText();
        }

        private ApiCall get(String path) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiCall(response.statusCode(), MAPPER.readTree(response.body()));
        }

        private ApiCall post(String path, String jsonBody) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiCall(response.statusCode(), MAPPER.readTree(response.body()));
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            db.close();
        }
    }

    private record ApiCall(int statusCode, JsonNode body) {}
}