package com.agentcloud.server;

import com.agentcloud.engine.ChatFacadeService;
import com.agentcloud.model.openai.ChatCompletionChunkResponse;
import com.agentcloud.model.openai.ChatCompletionRequest;
import com.agentcloud.model.openai.ChatCompletionResponse;
import com.agentcloud.model.openai.ResponseCreateRequest;
import com.agentcloud.model.openai.ResponseStreamEvent;
import com.agentcloud.model.openai.ResponsesCreateResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

class ChatFacadeHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatFacadeHandler.class);
    private final ChatFacadeService service;
    private final ObjectMapper mapper;

    ChatFacadeHandler(ChatFacadeService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if ("/v1/models".equals(path)) {
                if (!"GET".equals(method)) {
                    NioHttpServer.sendMethodNotAllowed(ex);
                    return;
                }
                NioHttpServer.sendJson(ex, 200, service.listModels());
                return;
            }
            if ("/v1/chat/completions".equals(path)) {
                if (!"POST".equals(method)) {
                    NioHttpServer.sendMethodNotAllowed(ex);
                    return;
                }
                ChatCompletionRequest request = mapper.readValue(NioHttpServer.readBody(ex), ChatCompletionRequest.class);
                ChatCompletionResponse completion = service.createCompletion(request);
                if (Boolean.TRUE.equals(request.stream())) {
                    sendCompletionStream(ex, completion);
                } else {
                    NioHttpServer.sendJson(ex, 200, completion);
                }
                return;
            }
            if ("/v1/responses".equals(path)) {
                if (!"POST".equals(method)) {
                    NioHttpServer.sendMethodNotAllowed(ex);
                    return;
                }
                ResponseCreateRequest request = mapper.readValue(NioHttpServer.readBody(ex), ResponseCreateRequest.class);
                ResponsesCreateResponse response = service.createResponse(request);
                if (Boolean.TRUE.equals(request.stream())) {
                    sendResponsesStream(ex, response);
                } else {
                    NioHttpServer.sendJson(ex, 200, response);
                }
                return;
            }
            NioHttpServer.sendNotFound(ex);
        } catch (JsonProcessingException e) {
            log.warn("ChatFacadeHandler invalid json: {}", e.getOriginalMessage());
            NioHttpServer.sendMalformedJson(ex);
        } catch (IllegalArgumentException e) {
            log.warn("ChatFacadeHandler validation error: {}", e.getMessage());
            NioHttpServer.sendIllegalArgument(ex, e);
        } catch (IOException e) {
            if (NioHttpServer.isClientDisconnect(e)) {
                return;
            }
            log.error("ChatFacadeHandler I/O error", e);
            NioHttpServer.sendInternalError(ex);
        } catch (Exception e) {
            log.error("ChatFacadeHandler error", e);
            NioHttpServer.sendInternalError(ex);
        }
    }

    private void sendCompletionStream(HttpExchange ex, ChatCompletionResponse completion) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream body = ex.getResponseBody()) {
            writeSseData(body, firstChunk(completion));
            writeSseData(body, finalChunk(completion));
            body.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            body.flush();
        } catch (IOException e) {
            if (NioHttpServer.isClientDisconnect(e)) {
                return;
            }
            throw e;
        } finally {
            ex.close();
        }
    }

    private void writeSseData(OutputStream body, Object payload) throws IOException {
        byte[] json = mapper.writeValueAsBytes(payload);
        body.write("data: ".getBytes(StandardCharsets.UTF_8));
        body.write(json);
        body.write("\n\n".getBytes(StandardCharsets.UTF_8));
        body.flush();
    }

    private ChatCompletionChunkResponse firstChunk(ChatCompletionResponse completion) {
        String content = completion == null
            || completion.choices() == null
            || completion.choices().isEmpty()
            || completion.choices().get(0) == null
            || completion.choices().get(0).message() == null
            ? ""
            : completion.choices().get(0).message().content();
        return new ChatCompletionChunkResponse(
            completion.id(),
            "chat.completion.chunk",
            completion.created(),
            completion.model(),
            List.of(new ChatCompletionChunkResponse.Choice(
                0,
                new ChatCompletionChunkResponse.Delta("assistant", content),
                null
            )),
            null
        );
    }

    private ChatCompletionChunkResponse finalChunk(ChatCompletionResponse completion) {
        return new ChatCompletionChunkResponse(
            completion.id(),
            "chat.completion.chunk",
            completion.created(),
            completion.model(),
            List.of(new ChatCompletionChunkResponse.Choice(
                0,
                new ChatCompletionChunkResponse.Delta(null, null),
                "stop"
            )),
            completion.agentcloud()
        );
    }

    private void sendResponsesStream(HttpExchange ex, ResponsesCreateResponse response) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream body = ex.getResponseBody()) {
            writeSseData(body, new ResponseStreamEvent("response.created", response, null, null, null, null, null, null, null));
            writeSseData(body, new ResponseStreamEvent(
                "response.output_item.added",
                null,
                0,
                response.output() != null && !response.output().isEmpty() ? response.output().get(0).id() : null,
                null,
                response.output() != null && !response.output().isEmpty() ? response.output().get(0) : null,
                null,
                null,
                null
            ));
            writeSseData(body, new ResponseStreamEvent(
                "response.output_text.delta",
                null,
                0,
                response.output() != null && !response.output().isEmpty() ? response.output().get(0).id() : null,
                0,
                null,
                null,
                response.outputText(),
                null
            ));
            writeSseData(body, new ResponseStreamEvent(
                "response.output_text.done",
                null,
                0,
                response.output() != null && !response.output().isEmpty() ? response.output().get(0).id() : null,
                0,
                null,
                response.output() != null
                    && !response.output().isEmpty()
                    && response.output().get(0).content() != null
                    && !response.output().get(0).content().isEmpty()
                    ? response.output().get(0).content().get(0)
                    : null,
                null,
                response.outputText()
            ));
            writeSseData(body, new ResponseStreamEvent(
                "response.output_item.done",
                null,
                0,
                response.output() != null && !response.output().isEmpty() ? response.output().get(0).id() : null,
                null,
                response.output() != null && !response.output().isEmpty() ? response.output().get(0) : null,
                null,
                null,
                null
            ));
            writeSseData(body, new ResponseStreamEvent("response.completed", response, null, null, null, null, null, null, null));
            body.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            body.flush();
        } catch (IOException e) {
            if (NioHttpServer.isClientDisconnect(e)) {
                return;
            }
            throw e;
        } finally {
            ex.close();
        }
    }
}
