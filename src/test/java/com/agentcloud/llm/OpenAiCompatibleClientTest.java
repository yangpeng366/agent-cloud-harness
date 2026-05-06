package com.agentcloud.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiCompatibleClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void chatUsesChatCompletionsWithDefaultModel() throws Exception {
        try (StubServer server = new StubServer("""
            {"choices":[{"message":{"content":"{\\"summary\\":\\"ok\\"}"}}]}
            """)) {
            LlmConfig config = new LlmConfig(
                "test-key",
                server.baseUrl(),
                "gpt-5.4",
                "gpt-5.4-review",
                "chat_completions",
                30,
                1,
                321
            );

            OpenAiCompatibleClient client = new OpenAiCompatibleClient(config);
            String response = client.chat("system prompt", "user prompt");

            assertEquals("{\"summary\":\"ok\"}", response);
            assertEquals("/v1/chat/completions", server.lastPath());
            JsonNode body = server.lastRequestBodyAsJson();
            assertEquals("gpt-5.4", body.path("model").asText());
            assertEquals(321, body.path("max_tokens").asInt());
            assertEquals("system", body.path("messages").get(0).path("role").asText());
            assertEquals("system prompt", body.path("messages").get(0).path("content").asText());
            assertEquals("user", body.path("messages").get(1).path("role").asText());
            assertEquals("user prompt", body.path("messages").get(1).path("content").asText());
        }
    }

    @Test
    void reviewUsesResponsesWireApiAndReviewModel() throws Exception {
        try (StubServer server = new StubServer("""
            {"output":[{"type":"message","content":[{"type":"output_text","text":"{\\"status\\":\\"done\\"}"}]}]}
            """)) {
            LlmConfig config = new LlmConfig(
                "test-key",
                server.baseUrl(),
                "gpt-5.4",
                "gpt-5.4-review",
                "responses",
                30,
                1,
                654
            );

            OpenAiCompatibleClient client = new OpenAiCompatibleClient(config);
            String response = client.review("review system", "review user");

            assertEquals("{\"status\":\"done\"}", response);
            assertEquals("/v1/responses", server.lastPath());
            JsonNode body = server.lastRequestBodyAsJson();
            assertEquals("gpt-5.4-review", body.path("model").asText());
            assertEquals("review system", body.path("instructions").asText());
            assertEquals("review user", body.path("input").asText());
            assertEquals(654, body.path("max_output_tokens").asInt());
        }
    }

    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        private final String responseBody;
        private volatile String lastPath;
        private volatile String lastRequestBody;

        private StubServer(String responseBody) throws IOException {
            this.responseBody = responseBody.strip();
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.server.createContext("/", this::handle);
            this.server.setExecutor(Executors.newSingleThreadExecutor());
            this.server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            this.lastPath = exchange.getRequestURI().getPath();
            this.lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private String lastPath() {
            return lastPath;
        }

        private JsonNode lastRequestBodyAsJson() throws IOException {
            return MAPPER.readTree(lastRequestBody);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
