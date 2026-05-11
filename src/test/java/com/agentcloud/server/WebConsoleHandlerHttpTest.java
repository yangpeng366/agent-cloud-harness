package com.agentcloud.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebConsoleHandlerHttpTest {

    @Test
    void dialogueRouteServesTranscriptFirstShell() throws Exception {
        try (HttpFixture fixture = new HttpFixture()) {
            HttpResponse<String> response = fixture.get("/dialogue/");

            assertEquals(200, response.statusCode());
            assertEquals("text/html; charset=UTF-8", response.headers().firstValue("Content-Type").orElse(null));
            assertTrue(response.body().contains("Session Transcript"));
            assertTrue(response.body().contains("任务链"));
            assertTrue(response.body().contains("查看任务面板"));
            assertTrue(response.body().contains("data-composer-mode=\"auto\""));
            assertTrue(response.body().contains("data-composer-mode=\"message\""));
            assertTrue(response.body().contains("data-composer-mode=\"task\""));
            assertFalse(response.body().contains("data-composer-mode=\"followup\""));
        }
    }

    @Test
    void dialogueRouteServesAppJavascript() throws Exception {
        try (HttpFixture fixture = new HttpFixture()) {
            HttpResponse<String> response = fixture.get("/dialogue/app.js");

            assertEquals(200, response.statusCode());
            assertEquals("application/javascript; charset=UTF-8",
                response.headers().firstValue("Content-Type").orElse(null));
            assertTrue(response.body().contains("Session Transcript"));
            assertTrue(response.body().contains("messageHint"));
        }
    }

    @Test
    void dialogueRouteServesImportedJavascriptModules() throws Exception {
        try (HttpFixture fixture = new HttpFixture()) {
            HttpResponse<String> composerRequest = fixture.get("/dialogue/composer-request-plan.js");
            HttpResponse<String> facadeClient = fixture.get("/dialogue/facade-client-plan.js");
            HttpResponse<String> taskActionRender = fixture.get("/dialogue/task-action-render-plan.js");

            assertEquals(200, composerRequest.statusCode());
            assertEquals("application/javascript; charset=UTF-8",
                composerRequest.headers().firstValue("Content-Type").orElse(null));
            assertTrue(composerRequest.body().contains("buildFacadeRequest"));

            assertEquals(200, facadeClient.statusCode());
            assertEquals("application/javascript; charset=UTF-8",
                facadeClient.headers().firstValue("Content-Type").orElse(null));
            assertTrue(facadeClient.body().contains("requestFacadeCompletion"));

            assertEquals(200, taskActionRender.statusCode());
            assertEquals("application/javascript; charset=UTF-8",
                taskActionRender.headers().firstValue("Content-Type").orElse(null));
            assertTrue(taskActionRender.body().contains("renderTaskActionHtml"));
        }
    }

    private static final class HttpFixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final String baseUrl;

        private HttpFixture() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = Executors.newCachedThreadPool();
            this.server.setExecutor(executor);
            this.server.createContext("/dialogue", new WebConsoleHandler("/dialogue", "web/dialogue"));
            this.server.start();
            this.client = HttpClient.newHttpClient();
            this.baseUrl = "http://localhost:" + server.getAddress().getPort();
        }

        private HttpResponse<String> get(String path) throws IOException, InterruptedException {
            return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
