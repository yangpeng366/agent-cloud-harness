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
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

/**
 * Startup probe: discovers local environment and writes harness-state.json.
 */
public final class HarnessStateWriter {
    private static final Logger log = LoggerFactory.getLogger(HarnessStateWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .disable(SerializationFeature.INDENT_OUTPUT)
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private HarnessStateWriter() {
    }

    public static HarnessState discover(LlmConfig llmConfig) {
        return discover(llmConfig, new com.agentcloud.agent.providers.HarnessConfig.HarnessEyesMcpConfig(
            null, false, null, false, null));
    }

    public static HarnessState discover(
        LlmConfig llmConfig,
        com.agentcloud.agent.providers.HarnessConfig.HarnessEyesMcpConfig eyesMcpConfig
    ) {
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

        HarnessState.EyesMcpStatus eyesMcp = probeEyesMcp(eyesMcpConfig);
        if (eyesMcp.available()) {
            log.info("eyes_mcp: healthy server={}/{} tools={} durationMs={}",
                eyesMcp.serverName(), eyesMcp.serverVersion(), eyesMcp.toolCount(), eyesMcp.durationMs());
        } else if (!"disabled".equals(eyesMcp.status())) {
            log.warn("eyes_mcp: unhealthy: {}", eyesMcp.error());
        }

        return new HarnessState(
            Instant.now(),
            ccxReachable,
            ccxModels,
            Map.of(),
            Map.copyOf(workers),
            Map.copyOf(providers),
            workerReadyCount,
            eyesMcp
        );
    }

    private static HarnessState.EyesMcpStatus probeEyesMcp(
        com.agentcloud.agent.providers.HarnessConfig.HarnessEyesMcpConfig config
    ) {
        if (config == null || !config.healthCheckOnStartup()) {
            return HarnessState.EyesMcpStatus.disabled();
        }

        long startedAt = System.nanoTime();
        Process process = null;
        java.nio.file.Path requestFile = null;
        try {
            requestFile = Files.createTempFile("eyes-mcp-probe", ".jsonl");
            String initialize = MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "initialize",
                "params", Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "agent-cloud-harness", "version", "0.2.0")
                )
            ));
            String initialized = MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized"
            ));
            String toolsList = MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 2,
                "method", "tools/list",
                "params", Map.of()
            ));
            requestFile = Files.createTempFile("eyes-mcp-probe", ".jsonl");
        requestFile = Files.createTempFile("eyes-mcp-probe", ".jsonl");
        Files.writeString(requestFile, initialize + "\n" + initialized + "\n" + toolsList + "\n");
            process = new ProcessBuilder(config.command())
                .redirectInput(requestFile.toFile())
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start();
            final Process eyesMcpProcess = process;
            StringBuilder stderr = new StringBuilder();
            Thread stderrReader = new Thread(() -> {
                try (java.io.BufferedReader err = new java.io.BufferedReader(
                    new java.io.InputStreamReader(eyesMcpProcess.getErrorStream(),
                        java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        stderr.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                    // best-effort stderr capture
                }
            }, "eyes-mcp-stderr");
            stderrReader.setDaemon(true);
            stderrReader.start();

            ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
            String serverName;
            String serverVersion;
            int toolCount;
            final boolean[] gotInitialize = new boolean[]{false};
            final boolean[] gotToolsList = new boolean[]{false};
            try {
                Future<EyesMcpWireResult> responseFuture = readerExecutor.submit(() -> {
                    String responseServerName = null;
                    String responseServerVersion = null;
                    int responseToolCount = 0;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        eyesMcpProcess.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            var response = MAPPER.readTree(line);
                            if (response == null || !response.has("id")) {
                                continue;
                            }
                            if (response.path("id").asInt() == 1 && response.has("result")) {
                                responseServerName = response.path("result").path("serverInfo")
                                    .path("name").asText(null);
                                responseServerVersion = response.path("result").path("serverInfo")
                                    .path("version").asText(null);
                                gotInitialize[0] = true;
                            }
                            if (response.path("id").asInt() == 2 && response.has("result")) {
                                responseToolCount = response.path("result").path("tools").size();
                                gotToolsList[0] = true;
                                return new EyesMcpWireResult(
                                    responseServerName, responseServerVersion, responseToolCount);
                            }
                        }
                    }
                    return new EyesMcpWireResult(responseServerName, responseServerVersion, responseToolCount);
                });
                EyesMcpWireResult response = responseFuture.get(
                    config.startupTimeoutSeconds(), TimeUnit.SECONDS);
                serverName = response.serverName();
                serverVersion = response.serverVersion();
                toolCount = response.toolCount();
            } catch (TimeoutException e) {
                return unhealthy(startedAt, "startup timeout after " + config.startupTimeoutSeconds() + "s");
            } finally {
                readerExecutor.shutdownNow();
            }
            if (!gotInitialize[0]) {
                return unhealthy(startedAt, stderr.toString().trim().isEmpty()
                    ? "initialize response missing"
                    : "initialize response missing; stderr=" + stderr.toString().trim());
            }
            if (!gotToolsList[0]) {
                return unhealthy(startedAt, stderr.toString().trim().isEmpty()
                    ? "tools/list response missing or empty"
                    : "tools/list response missing; stderr=" + stderr.toString().trim());
            }

            return new HarnessState.EyesMcpStatus(true, "healthy", serverName, serverVersion,
                toolCount, elapsedMs(startedAt), null, Instant.now());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unhealthy(startedAt, "probe interrupted");
        } catch (Exception e) {
            return unhealthy(startedAt, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (requestFile != null) {
                try {
                    Files.deleteIfExists(requestFile);
                } catch (IOException ignored) {
                    // temporary probe file only; startup must not fail on cleanup
                }
            }
        }
    }

    private static HarnessState.EyesMcpStatus unhealthy(long startedAt, String error) {
        return new HarnessState.EyesMcpStatus(false, "unhealthy", null, null, 0,
            elapsedMs(startedAt), error, Instant.now());
    }

    private record EyesMcpWireResult(String serverName, String serverVersion, int toolCount) {}

    private static long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
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
