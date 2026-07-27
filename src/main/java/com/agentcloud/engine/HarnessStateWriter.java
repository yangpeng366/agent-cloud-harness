package com.agentcloud.engine;

import com.agentcloud.llm.LlmConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Startup probe: discovers local environment and writes harness-state.json.
 */
public final class HarnessStateWriter {
    private static final Logger log = LoggerFactory.getLogger(HarnessStateWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private HarnessStateWriter() {
    }

    public static HarnessState discover(LlmConfig llmConfig) {
        String ccxBaseUrl = llmConfig != null ? llmConfig.baseUrl() : "";
        String ccxApiKey = llmConfig != null ? llmConfig.apiKey() : "";

        boolean ccxReachable = false;
        List<String> ccxModels = List.of();
        if (ccxApiKey != null && !ccxApiKey.isBlank()) {
            try {
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ccxBaseUrl + "/models"))
                    .header("Authorization", "Bearer " + ccxApiKey)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    ccxReachable = true;
                    ccxModels = parseModelList(response.body());
                }
            } catch (Exception e) {
                log.debug("CCX probe failed: {}", e.getMessage());
            }
        }

        Map<String, HarnessState.WorkerAvailability> workers = new LinkedHashMap<>();
        String[] cliWorkers = {"codex", "claude", "pi", "kimi", "deepseek"};
        for (String workerId : cliWorkers) {
            boolean cliAvailable = isCliAvailable(workerId);
            workers.put(workerId, new HarnessState.WorkerAvailability(
                workerId, cliAvailable, Instant.now().toString()));
        }

        Map<String, HarnessState.ProviderAvailability> providers = new LinkedHashMap<>();
        providers.put("codex", new HarnessState.ProviderAvailability("codex", true, true));
        providers.put("codex-free", new HarnessState.ProviderAvailability("codex-free", ccxReachable, ccxReachable));
        providers.put("deepseek", new HarnessState.ProviderAvailability("deepseek", true, false));
        providers.put("trae", new HarnessState.ProviderAvailability("trae", true, false));

        int workerReadyCount = (int) workers.values().stream()
            .filter(HarnessState.WorkerAvailability::cliAvailable)
            .count();

        return new HarnessState(
            Instant.now(),
            ccxReachable,
            ccxModels,
            Map.of(),
            Map.copyOf(workers),
            Map.copyOf(providers),
            workerReadyCount
        );
    }

    public static void write(HarnessState state, Path path) throws IOException {
        if (state == null || path == null) {
            return;
        }
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        String json = MAPPER.writeValueAsString(state);
        Files.writeString(path, json);
        log.info("Harness state written to: {}", path);
    }

    private static List<String> parseModelList(String responseBody) {
        try {
            var root = MAPPER.readTree(responseBody);
            var data = root.get("data");
            if (data != null && data.isArray()) {
                List<String> models = new ArrayList<>();
                for (var item : data) {
                    var id = item.get("id");
                    if (id != null && !id.isNull()) {
                        models.add(id.asText());
                    }
                }
                return models;
            }
        } catch (Exception e) {
            log.debug("Failed to parse CCX model list: {}", e.getMessage());
        }
        return List.of();
    }

    private static boolean isCliAvailable(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
            }
            return exited;
        } catch (Exception e) {
            return false;
        }
    }
}