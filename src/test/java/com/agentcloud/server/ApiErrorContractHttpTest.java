package com.agentcloud.server;

import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderDescriptor;
import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.AgentProviderStatus;
import com.agentcloud.agent.providers.CodexProvider;
import com.agentcloud.agent.providers.UnsupportedAgentProvider;
import com.agentcloud.engine.ControlNodeGraph;
import com.agentcloud.engine.SessionService;
import com.agentcloud.engine.SkillRegistry;
import com.agentcloud.engine.TaskService;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.llm.LlmConfig;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.EventDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.SessionMessageDao;
import com.agentcloud.store.SkillDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.tool.HostToolAvailability;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiErrorContractHttpTest {

    @TempDir
    Path tempDir;

    @Test
    void missingTaskControlActionReturnsStable404() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("missing-task.db"))) {
            ApiCall response = fixture.postJson("/api/v1/tasks/task-missing/pause", Map.of("reason", "missing"));

            assertEquals(404, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("404", response.body().path("code").asText());
            assertEquals("not found", response.body().path("message").asText());
        }
    }

    @Test
    void missingSessionMessagesReturnsStable404() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("missing-session.db"))) {
            ApiCall response = fixture.get("/api/v1/sessions/session-missing/messages");

            assertEquals(404, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("404", response.body().path("code").asText());
            assertEquals("not found", response.body().path("message").asText());
        }
    }

    @Test
    void missingSessionMessagePostReturnsStable404() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("missing-session-post.db"))) {
            ApiCall response = fixture.postJson(
                "/api/v1/sessions/session-missing/messages",
                Map.of("role", "user", "content", "missing session")
            );

            assertEquals(404, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("404", response.body().path("code").asText());
            assertEquals("not found", response.body().path("message").asText());
        }
    }

    @Test
    void closedSessionMessagePostReturnsStable400() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("closed-session-post.db"))) {
            ApiCall create = fixture.postJson("/api/v1/sessions", Map.of("title", "closed message session"));
            String sessionId = create.body().path("data").path("id").asText();
            ApiCall close = fixture.send("POST", "/api/v1/sessions/" + sessionId + "/close", "", "application/json");
            assertEquals(200, close.statusCode());

            ApiCall response = fixture.postJson(
                "/api/v1/sessions/" + sessionId + "/messages",
                Map.of("role", "user", "content", "should fail")
            );

            assertEquals(400, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("400", response.body().path("code").asText());
            assertEquals("session is closed", response.body().path("message").asText());
        }
    }

    @Test
    void missingSessionCloseReturnsStable404() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("missing-session-close.db"))) {
            ApiCall response = fixture.send("POST", "/api/v1/sessions/session-missing/close", "", "application/json");

            assertEquals(404, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("404", response.body().path("code").asText());
            assertEquals("not found", response.body().path("message").asText());
        }
    }

    @Test
    void missingSessionPauseReturnsStable404() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("missing-session-pause.db"))) {
            ApiCall response = fixture.send("POST", "/api/v1/sessions/session-missing/pause", "", "application/json");

            assertEquals(404, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("404", response.body().path("code").asText());
            assertEquals("not found", response.body().path("message").asText());
        }
    }

    @Test
    void getSessionPauseReturnsStable405() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("session-get-pause-method.db"))) {
            ApiCall response = fixture.send("GET", "/api/v1/sessions/session-any/pause", "", "application/json");

            assertEquals(405, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("405", response.body().path("code").asText());
            assertEquals("method not allowed", response.body().path("message").asText());
        }
    }

    @Test
    void invalidJsonReturnsStable400() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("invalid-json.db"))) {
            ApiCall response = fixture.postRaw(
                "/api/v1/workers",
                "{\"worker_id\":\"broken\",",
                "application/json"
            );

            assertEquals(400, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("400", response.body().path("code").asText());
            assertEquals("invalid json body", response.body().path("message").asText());
        }
    }

    @Test
    void workerRegistrationAcceptsSupportedCommandToolCapabilitiesForCurrentHost() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("worker-tools.db"))) {
            List<String> toolCapabilities = supportedCommandToolCapabilities();
            ApiCall response = fixture.postJson("/api/v1/workers", Map.of(
                "worker_id", "command-worker",
                "worker_type", "codex",
                "capabilities", java.util.List.of("ops"),
                "tool_capabilities", toolCapabilities,
                "tool_scope", java.util.List.of(tempDir.toString())
            ));

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertTrue(response.body().path("data").path("tool_capabilities").isArray());
            assertEquals(toolCapabilities.size(), response.body().path("data").path("tool_capabilities").size());
        }
    }

    @Test
    void workerReadinessReportsToolChecksForDeclaredCommandCapabilities() throws Exception {
        List<String> toolCapabilities = supportedCommandToolCapabilities();
        Assumptions.assumeFalse(toolCapabilities.isEmpty(), "no supported command tool available on this host");
        String toolCapability = toolCapabilities.get(0);

        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("worker-readiness-tools.db"))) {
            ApiCall registration = fixture.postJson("/api/v1/workers", Map.of(
                "worker_id", "ready-worker",
                "worker_type", "codex",
                "capabilities", java.util.List.of("ops"),
                "tool_capabilities", java.util.List.of(toolCapability),
                "tool_scope", java.util.List.of(tempDir.toString())
            ));

            assertEquals(200, registration.statusCode());
            assertTrue(registration.body().path("data").path("metadata").path("host_tool_availability")
                .path(toolCapability).asBoolean());

            ApiCall readiness = fixture.get("/api/v1/workers/ready-worker/readiness");

            assertEquals(200, readiness.statusCode());
            assertTrue(readiness.body().path("success").asBoolean());
            assertTrue(readiness.body().path("data").path("ready").asBoolean());
            assertTrue(readiness.body().path("data").path("checks").path("tool:" + toolCapability).asBoolean());
            assertEquals("ready", readiness.body().path("data").path("reason").asText());
        }
    }

    @Test
    void listWorkersExposesCapabilityMatrixMetadata() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("worker-capability-matrix.db"))) {
            ApiCall response = fixture.get("/api/v1/workers");

            assertEquals(200, response.statusCode());
            JsonNode workers = response.body().path("data");
            JsonNode codex = workerById(workers, "codex");
            JsonNode kimi = workerById(workers, "kimi");
            JsonNode openclaw = workerById(workers, "openclaw-native");

            assertEquals("provider_app_server", codex.path("metadata").path("execution_backend").asText());
            assertEquals("codex app-server --listen stdio://", codex.path("metadata").path("command_shape").asText());
            assertEquals("json_rpc", codex.path("metadata").path("input_mode").asText());
            assertEquals("json_rpc_events", codex.path("metadata").path("output_mode").asText());
            assertEquals("provider_app_server_events", codex.path("metadata").path("output_contract").asText());
            assertEquals("fresh_on_recovery", codex.path("metadata").path("recovery_resume_policy").asText());
            assertTrue(codex.path("metadata").path("supports_resume").asBoolean(false));
            assertEquals("coding", codex.path("metadata").path("auto_route_task_types").get(0).asText());

            assertEquals("provider_native_cli", kimi.path("metadata").path("execution_backend").asText());
            assertEquals("kimi --print --output-format stream-json --work-dir <cwd> --prompt <prompt>",
                kimi.path("metadata").path("command_shape").asText());
            assertEquals("argv_prompt", kimi.path("metadata").path("input_mode").asText());
            assertEquals("stream_json", kimi.path("metadata").path("output_mode").asText());
            assertEquals("provider_native_cli_events", kimi.path("metadata").path("output_contract").asText());
            assertEquals("resume_if_session_id", kimi.path("metadata").path("recovery_resume_policy").asText());
            assertTrue(kimi.path("metadata").path("supports_resume").asBoolean(false));
            assertEquals("research", kimi.path("metadata").path("auto_route_task_types").get(1).asText());

            assertEquals("tool_aware", openclaw.path("metadata").path("execution_backend").asText());
            assertEquals("harness tool registry", openclaw.path("metadata").path("command_shape").asText());
            assertEquals("tool_request", openclaw.path("metadata").path("input_mode").asText());
            assertEquals("tool_result", openclaw.path("metadata").path("output_mode").asText());
            assertEquals("harness_tool_trace", openclaw.path("metadata").path("output_contract").asText());
            assertEquals("message", openclaw.path("metadata").path("auto_route_task_types").get(2).asText());
        }
    }

    @Test
    void workerReadinessIncludesProviderFailureForBuiltInCodexWorker() throws Exception {
        try (ProviderAwareWorkerHttpFixture fixture = new ProviderAwareWorkerHttpFixture(
            tempDir.resolve("worker-provider-readiness.db"),
            new AgentProviderRegistry().register(new CodexProvider("definitely-missing-codex-binary-for-test"))
        )) {
            ApiCall readiness = fixture.get("/api/v1/workers/codex/readiness");

            assertEquals(200, readiness.statusCode());
            assertFalse(readiness.body().path("data").path("ready").asBoolean(true));
            assertFalse(readiness.body().path("data").path("checks").path("provider:codex").asBoolean(true));
            assertTrue(readiness.body().path("data").path("reason").asText().contains("binary not found"));
        }
    }

    @Test
    void listWorkersProjectsRuntimeReadinessInsteadOfStaticReadyFlag() throws Exception {
        try (ProviderAwareWorkerHttpFixture fixture = new ProviderAwareWorkerHttpFixture(
            tempDir.resolve("worker-list-runtime-readiness.db"),
            new AgentProviderRegistry().register(new CodexProvider("definitely-missing-codex-binary-for-list-test"))
        )) {
            ApiCall list = fixture.get("/api/v1/workers");
            ApiCall readiness = fixture.get("/api/v1/workers/codex/readiness");

            assertEquals(200, list.statusCode());
            assertEquals(200, readiness.statusCode());
            JsonNode codex = workerById(list.body().path("data"), "codex");
            assertFalse(codex.path("ready").asBoolean(true));
            assertFalse(readiness.body().path("data").path("ready").asBoolean(true));
        }
    }

    @Test
    void workerReadinessIncludesExecutorBackendFailureForUnsupportedProviderNativeWorker() throws Exception {
        String providerId = "unsupported-cli-test";
        try (ProviderAwareWorkerHttpFixture fixture = new ProviderAwareWorkerHttpFixture(
            tempDir.resolve("worker-provider-backend-gap.db"),
            new AgentProviderRegistry().register(new StaticProvider(providerId, true, true, "ready")),
            workerRegistry -> workerRegistry.registerProviderNativeWorker(providerId, List.of("coding"), Map.of())
        )) {
            ApiCall readiness = fixture.get("/api/v1/workers/" + providerId + "/readiness");

            assertEquals(200, readiness.statusCode());
            assertFalse(readiness.body().path("data").path("ready").asBoolean(true));
            assertTrue(readiness.body().path("data").path("checks").path("provider:" + providerId).asBoolean(false));
            assertFalse(readiness.body().path("data").path("checks")
                .path("executor_backend:provider_native_cli").asBoolean(true));
            assertTrue(readiness.body().path("data").path("reason").asText().contains("executor backend not supported"));
        }
    }

    @Test
    void unsupportedDiscoveredProviderIsVisibleInAgentsButNotWorkers() throws Exception {
        AgentProviderRegistry providerRegistry = new AgentProviderRegistry().register(new UnsupportedAgentProvider(
            "unsupported_app_server",
            "Unsupported App Server",
            List.of("coding"),
            Map.of(
                "provider_discovery", true,
                "provider_protocol", "app_server_json_rpc",
                "provider_discovery_supported", false
            ),
            "app_server_json_rpc is only wired for built-in codex app-server, not dynamic provider discovery"
        ));
        try (ProviderAwareWorkerHttpFixture fixture = new ProviderAwareWorkerHttpFixture(
            tempDir.resolve("unsupported-discovered-provider.db"),
            providerRegistry,
            true
        )) {
            ApiCall agents = fixture.get("/api/v1/agents");
            ApiCall detail = fixture.get("/api/v1/agents/unsupported_app_server");
            ApiCall workers = fixture.get("/api/v1/workers");

            assertEquals(200, agents.statusCode());
            JsonNode agent = agentById(agents.body().path("data"), "unsupported_app_server");
            assertFalse(agent.path("ready").asBoolean(true));
            assertEquals("unsupported", agent.path("provider_type").asText());
            assertEquals("unsupported", agent.path("transport").asText());
            assertEquals("unsupported", agent.path("auth_status").asText());
            assertTrue(agent.path("readiness_reason").asText().contains("built-in codex app-server"));
            assertFalse(agent.path("metadata").path("provider_discovery_supported").asBoolean(true));
            assertTrue(agent.path("metadata").path("unsupported_backend").asBoolean(false));
            assertEquals("app_server_json_rpc", agent.path("metadata").path("provider_protocol").asText());

            assertEquals(200, detail.statusCode());
            assertEquals("unsupported_app_server", detail.body().path("data").path("provider_id").asText());

            assertEquals(200, workers.statusCode());
            assertFalse(hasWorker(workers.body().path("data"), "unsupported_app_server"));
        }
    }

    @Test
    void workerReadinessDispatchModeProjectsPreflightFields() throws Exception {
        try (ProviderAwareWorkerHttpFixture fixture = new ProviderAwareWorkerHttpFixture(
            tempDir.resolve("worker-provider-dispatch-preflight.db"),
            new AgentProviderRegistry().register(new PreflightProvider("codex", true, false, "fresh turn rejected"))
        )) {
            ApiCall readiness = fixture.get("/api/v1/workers/codex/readiness?mode=dispatch");

            assertEquals(200, readiness.statusCode());
            assertFalse(readiness.body().path("data").path("ready").asBoolean(true));
            assertEquals("dispatch", readiness.body().path("data").path("mode").asText());
            assertFalse(readiness.body().path("data").path("checks").path("dispatch_preflight").asBoolean(true));
            assertFalse(readiness.body().path("data").path("dispatch_preflight_ready").asBoolean(true));
            assertEquals("fresh turn rejected", readiness.body().path("data").path("dispatch_preflight_reason").asText());
            assertEquals("active_probe", readiness.body().path("data").path("dispatch_preflight_mode").asText());
            assertTrue(readiness.body().path("data").path("dispatch_preflight_active_probe").asBoolean(false));
            JsonNode metadata = readiness.body().path("data").path("dispatch_preflight_metadata");
            assertEquals("cli_help", metadata.path("dispatch_preflight_probe_kind").asText());
            assertEquals("--version", metadata.path("dispatch_preflight_probe_args").get(0).asText());
            assertEquals("direct", metadata.path("dispatch_preflight_command_shape").get(0).asText());
            assertEquals(1, metadata.path("dispatch_preflight_exit_code").asInt());
            JsonNode cliProfile = readiness.body().path("data").path("cli_profile");
            assertTrue(cliProfile.path("cli_profile_evidence_available").asBoolean(false));
            assertFalse(cliProfile.path("supports_yolo").asBoolean(true));
            assertEquals("provider_protocol_error", readiness.body().path("data").path("provider_failure_class").asText());
            assertTrue(readiness.body().path("data").path("provider_failure_reason").asText().contains("fresh turn rejected"));
            assertTrue(readiness.body().path("data").path("provider_retryable").asBoolean(false));
        }
    }

    @Test
    void agentPreflightEndpointRunsProviderDispatchPreflight() throws Exception {
        try (ProviderAwareWorkerHttpFixture fixture = new ProviderAwareWorkerHttpFixture(
            tempDir.resolve("agent-provider-preflight.db"),
            new AgentProviderRegistry().register(new PreflightProvider("codex", true, false, "fresh turn rejected")),
            true
        )) {
            ApiCall response = fixture.send("POST", "/api/v1/agents/codex/preflight", "", "application/json");

            assertEquals(200, response.statusCode());
            assertFalse(response.body().path("data").path("ready").asBoolean(true));
            assertEquals("fresh turn rejected", response.body().path("data").path("readiness_reason").asText());
            JsonNode metadata = response.body().path("data").path("metadata");
            assertEquals("dispatch_preflight_test", metadata.path("source").asText());
            assertEquals("active_probe", metadata.path("dispatch_preflight_mode").asText());
            assertEquals("cli_help", metadata.path("dispatch_preflight_probe_kind").asText());
            assertEquals("--version", metadata.path("dispatch_preflight_probe_args").get(0).asText());
            assertEquals("provider_protocol_error", metadata.path("provider_failure_class").asText());
            assertTrue(metadata.path("provider_failure_reason").asText().contains("fresh turn rejected"));
            assertTrue(metadata.path("provider_retryable").asBoolean(false));
        }
    }

    @Test
    void workerReadinessDispatchModeProjectsPassiveFallbackProbeMode() throws Exception {
        try (ProviderAwareWorkerHttpFixture fixture = new ProviderAwareWorkerHttpFixture(
            tempDir.resolve("worker-provider-dispatch-passive-preflight.db"),
            new AgentProviderRegistry().register(new StaticProvider("codex", true, true, "ready"))
        )) {
            ApiCall readiness = fixture.get("/api/v1/workers/codex/readiness?mode=dispatch");

            assertEquals(200, readiness.statusCode());
            assertTrue(readiness.body().path("data").path("ready").asBoolean(false));
            assertTrue(readiness.body().path("data").path("dispatch_preflight_ready").asBoolean(false));
            assertEquals("passive_status", readiness.body().path("data").path("dispatch_preflight_mode").asText());
            assertFalse(readiness.body().path("data").path("dispatch_preflight_active_probe").asBoolean(true));
        }
    }

    @Test
    void workerReadinessPassiveModeDoesNotRunDispatchPreflight() throws Exception {
        try (ProviderAwareWorkerHttpFixture fixture = new ProviderAwareWorkerHttpFixture(
            tempDir.resolve("worker-provider-passive-readiness.db"),
            new AgentProviderRegistry().register(new PreflightProvider("codex", true, false, "fresh turn rejected"))
        )) {
            ApiCall readiness = fixture.get("/api/v1/workers/codex/readiness");

            assertEquals(200, readiness.statusCode());
            assertTrue(readiness.body().path("data").path("ready").asBoolean(false));
            assertEquals("passive", readiness.body().path("data").path("mode").asText());
            assertTrue(readiness.body().path("data").path("dispatch_preflight_ready").isMissingNode()
                || readiness.body().path("data").path("dispatch_preflight_ready").isNull());
            assertTrue(readiness.body().path("data").path("checks").path("dispatch_preflight").isMissingNode());
        }
    }

    @Test
    void workerReadinessRejectsUnknownMode() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("worker-readiness-invalid-mode.db"))) {
            ApiCall response = fixture.get("/api/v1/workers/codex/readiness?mode=disptach");

            assertEquals(400, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("mode must be passive or dispatch", response.body().path("message").asText());
        }
    }

    @Test
    void workerRegistrationRejectsWindowsOnlyToolCapabilityOnNonWindowsHost() throws Exception {
        if (HostToolAvailability.isWindowsHost()) {
            return;
        }
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("worker-tools-nonwindows.db"))) {
            ApiCall response = fixture.postJson("/api/v1/workers", Map.of(
                "worker_id", "command-worker",
                "worker_type", "codex",
                "capabilities", java.util.List.of("ops"),
                "tool_capabilities", java.util.List.of("powershell"),
                "tool_scope", java.util.List.of(tempDir.toString())
            ));

            assertEquals(400, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("powershell is only available on Windows hosts", response.body().path("message").asText());
        }
    }

    @Test
    void workerRegistrationRejectsGitToolCapabilityWhenGitUnavailable() throws Exception {
        if (HostToolAvailability.isToolCapabilityAvailable("git")) {
            return;
        }
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("worker-tools-nogit.db"))) {
            ApiCall response = fixture.postJson("/api/v1/workers", Map.of(
                "worker_id", "command-worker",
                "worker_type", "codex",
                "capabilities", java.util.List.of("ops"),
                "tool_capabilities", java.util.List.of("git"),
                "tool_scope", java.util.List.of(tempDir.toString())
            ));

            assertEquals(400, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("git is not available on this host", response.body().path("message").asText());
        }
    }

    @Test
    void workerRegistrationAcceptsPatchFileToolCapability() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("worker-patch-tool.db"))) {
            ApiCall response = fixture.postJson("/api/v1/workers", Map.of(
                "worker_id", "patch-worker",
                "worker_type", "codex",
                "capabilities", java.util.List.of("coding"),
                "tool_capabilities", java.util.List.of("patch_file"),
                "tool_scope", java.util.List.of(tempDir.toString())
            ));

            assertEquals(200, response.statusCode());
            assertTrue(response.body().path("success").asBoolean());
            assertEquals("patch_file", response.body().path("data").path("tool_capabilities").get(0).asText());
        }
    }

    @Test
    void workerRegistrationRequiresToolScopeWhenToolCapabilitiesAreDeclared() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("worker-scope-required.db"))) {
            ApiCall response = fixture.postJson("/api/v1/workers", Map.of(
                "worker_id", "scope-missing-worker",
                "worker_type", "codex",
                "capabilities", java.util.List.of("coding"),
                "tool_capabilities", java.util.List.of("patch_file")
            ));

            assertEquals(400, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("tool_scope is required when tool_capabilities are declared",
                response.body().path("message").asText());
        }
    }

    @Test
    void skillRegistrationRequiresName() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("skill-name.db"))) {
            ApiCall response = fixture.postJson("/api/v1/skills", Map.of("description", "missing name"));

            assertEquals(400, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("400", response.body().path("code").asText());
            assertEquals("name is required", response.body().path("message").asText());
        }
    }

    @Test
    void unsupportedMethodReturnsStable405() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("method-not-allowed.db"))) {
            ApiCall response = fixture.send("DELETE", "/api/v1/workers", "", "application/json");

            assertEquals(405, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("405", response.body().path("code").asText());
            assertEquals("method not allowed", response.body().path("message").asText());
        }
    }

    @Test
    void missingExperimentRunTaskReturnsStable404() throws Exception {
        try (HttpFixture fixture = new HttpFixture(tempDir.resolve("missing-experiment-run.db"))) {
            ApiCall response = fixture.get("/api/v1/experiment_runs/task-missing");

            assertEquals(404, response.statusCode());
            assertFalse(response.body().path("success").asBoolean());
            assertEquals("404", response.body().path("code").asText());
            assertEquals("not found", response.body().path("message").asText());
        }
    }

    @Test
    void healthPayloadProjectsLlmAvailabilityWithoutLeakingApiKey() {
        Map<String, Object> payload = NioHttpServer.healthPayload(new LlmConfig(
            "sk-test-secret",
            "https://llm.example/v1",
            "gpt-test",
            "gpt-review",
            "responses",
            12,
            3,
            256
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) payload.get("llm");

        assertEquals("up", payload.get("status"));
        assertEquals(true, llm.get("available"));
        assertEquals(true, llm.get("api_key_configured"));
        assertEquals("https://llm.example/v1", llm.get("base_url"));
        assertEquals("gpt-test", llm.get("model"));
        assertEquals("gpt-review", llm.get("review_model"));
        assertEquals("responses", llm.get("wire_api"));
        assertEquals(12, llm.get("request_timeout_seconds"));
        assertEquals(3, llm.get("max_retries"));
        assertEquals(256, llm.get("max_tokens"));
        assertFalse(payload.toString().contains("sk-test-secret"));
    }

    @Test
    void healthPayloadReportsUnavailableLlmWhenApiKeyMissing() {
        Map<String, Object> payload = NioHttpServer.healthPayload(new LlmConfig(
            "",
            "https://llm.example/v1",
            "gpt-test",
            null,
            "chat_completions",
            60,
            2,
            null
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) payload.get("llm");

        assertEquals(false, llm.get("available"));
        assertEquals(false, llm.get("api_key_configured"));
        assertEquals("default", llm.get("max_tokens"));
    }

    private static final class HttpFixture implements AutoCloseable {
        private final DatabaseManager db;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final String baseUrl;

        private HttpFixture(Path dbPath) throws IOException {
            this.db = new DatabaseManager(dbPath);
            TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
            SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
            EventDao eventDao = db.jdbi().onDemand(EventDao.class);
            SessionMessageDao sessionMessageDao = db.jdbi().onDemand(SessionMessageDao.class);
            SkillDao skillDao = db.jdbi().onDemand(SkillDao.class);

            TaskService taskService = new TaskService(
                taskDao,
                sessionDao,
                eventDao,
                null,
                null,
                null,
                new ControlNodeGraph(taskDao, eventDao, sessionDao, null, null, null, null,
                    null, null, null, null, null, null),
                null,
                null,
                null,
                null,
                null
            );
            SessionService sessionService = new SessionService(sessionDao, taskDao, sessionMessageDao, eventDao);
            WorkerRegistry workerRegistry = new WorkerRegistry();
            SkillRegistry skillRegistry = new SkillRegistry(skillDao);

            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            this.server.setExecutor(executor);
            this.server.createContext("/api/v1/tasks", new TaskHandler(taskService, NioHttpServer.SHARED_MAPPER));
            this.server.createContext("/api/v1/sessions", new SessionHandler(sessionService, NioHttpServer.SHARED_MAPPER));
            this.server.createContext("/api/v1/workers", new WorkerHandler(workerRegistry, NioHttpServer.SHARED_MAPPER));
            this.server.createContext("/api/v1/skills", new SkillHandler(skillRegistry, NioHttpServer.SHARED_MAPPER));
            this.server.createContext("/api/v1/experiment_runs", new ExperimentRunHandler(taskService, null));
            this.server.start();
            this.client = HttpClient.newHttpClient();
            this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private ApiCall get(String path) throws IOException, InterruptedException {
            return send("GET", path, "", "application/json");
        }

        private ApiCall postJson(String path, Map<String, Object> body) throws IOException, InterruptedException {
            return send(
                "POST",
                path,
                NioHttpServer.SHARED_MAPPER.writeValueAsString(body),
                "application/json"
            );
        }

        private ApiCall postRaw(String path, String body, String contentType) throws IOException, InterruptedException {
            return send("POST", path, body, contentType);
        }

        private ApiCall send(String method, String path, String body, String contentType) throws IOException, InterruptedException {
            HttpRequest.BodyPublisher publisher = body == null || body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
            if (contentType != null && !contentType.isBlank()) {
                builder.header("Content-Type", contentType);
            }
            HttpRequest request = builder.method(method, publisher).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiCall(response.statusCode(), NioHttpServer.SHARED_MAPPER.readTree(response.body()));
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            db.close();
        }
    }

    private record ApiCall(int statusCode, JsonNode body) {
    }

    private JsonNode workerById(JsonNode workers, String workerId) {
        for (JsonNode worker : workers) {
            if (workerId.equals(worker.path("worker_id").asText())) {
                return worker;
            }
        }
        throw new AssertionError("worker not found: " + workerId);
    }

    private JsonNode agentById(JsonNode agents, String providerId) {
        for (JsonNode agent : agents) {
            if (providerId.equals(agent.path("provider_id").asText())) {
                return agent;
            }
        }
        throw new AssertionError("agent not found: " + providerId);
    }

    private boolean hasWorker(JsonNode workers, String workerId) {
        for (JsonNode worker : workers) {
            if (workerId.equals(worker.path("worker_id").asText())) {
                return true;
            }
        }
        return false;
    }

    private List<String> supportedCommandToolCapabilities() {
        return HostToolAvailability.supportedCommandToolCapabilities();
    }

    private final class ProviderAwareWorkerHttpFixture implements AutoCloseable {
        private final DatabaseManager db;
        private final HttpServer server;
        private final ExecutorService executor;
        private final HttpClient client;
        private final String baseUrl;

        private ProviderAwareWorkerHttpFixture(Path dbPath, AgentProviderRegistry providerRegistry) throws IOException {
            this(dbPath, providerRegistry, false, workerRegistry -> {
            });
        }

        private ProviderAwareWorkerHttpFixture(Path dbPath,
                                               AgentProviderRegistry providerRegistry,
                                               Consumer<WorkerRegistry> workerRegistryCustomizer) throws IOException {
            this(dbPath, providerRegistry, false, workerRegistryCustomizer);
        }

        private ProviderAwareWorkerHttpFixture(Path dbPath,
                                               AgentProviderRegistry providerRegistry,
                                               boolean includeAgentHandler) throws IOException {
            this(dbPath, providerRegistry, includeAgentHandler, workerRegistry -> {
            });
        }

        private ProviderAwareWorkerHttpFixture(Path dbPath,
                                               AgentProviderRegistry providerRegistry,
                                               boolean includeAgentHandler,
                                               Consumer<WorkerRegistry> workerRegistryCustomizer) throws IOException {
            this.db = new DatabaseManager(dbPath);
            WorkerRegistry workerRegistry = new WorkerRegistry(providerRegistry);
            workerRegistryCustomizer.accept(workerRegistry);
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            this.server.setExecutor(executor);
            this.server.createContext("/api/v1/workers", new WorkerHandler(workerRegistry, NioHttpServer.SHARED_MAPPER));
            if (includeAgentHandler) {
                this.server.createContext("/api/v1/agents", new AgentHandler(providerRegistry, NioHttpServer.SHARED_MAPPER));
            }
            this.server.start();
            this.client = HttpClient.newHttpClient();
            this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private ApiCall get(String path) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiCall(response.statusCode(), NioHttpServer.SHARED_MAPPER.readTree(response.body()));
        }

        private ApiCall send(String method, String path, String body, String contentType)
            throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                .header("Content-Type", contentType)
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiCall(response.statusCode(), NioHttpServer.SHARED_MAPPER.readTree(response.body()));
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            db.close();
        }
    }

    private record StaticProvider(String providerId,
                                  boolean installed,
                                  boolean ready,
                                  String reason) implements AgentProvider {
        @Override
        public AgentProviderDescriptor descriptor() {
            return new AgentProviderDescriptor(
                providerId,
                providerId,
                "local_cli",
                "process",
                java.util.List.of("chat"),
                java.util.Map.of()
            );
        }

        @Override
        public AgentProviderStatus detect() {
            return new AgentProviderStatus(
                providerId,
                installed,
                "0.0.0-test",
                "ready",
                ready,
                reason,
                null,
                java.util.Map.of("source", "test")
            );
        }
    }

    private record PreflightProvider(String providerId,
                                     boolean passiveReady,
                                     boolean dispatchReady,
                                     String dispatchReason) implements AgentProvider {
        @Override
        public AgentProviderDescriptor descriptor() {
            return new AgentProviderDescriptor(
                providerId,
                providerId,
                "local_cli",
                "process",
                java.util.List.of("chat"),
                java.util.Map.of()
            );
        }

        @Override
        public AgentProviderStatus detect() {
            return new AgentProviderStatus(
                providerId,
                true,
                "0.0.0-test",
                "ready",
                passiveReady,
                passiveReady ? null : "passive not ready",
                null,
                java.util.Map.of("source", "test")
            );
        }

        @Override
        public AgentProviderStatus dispatchPreflight() {
            return new AgentProviderStatus(
                providerId,
                true,
                "0.0.0-test",
                "ready",
                dispatchReady,
                dispatchReady ? null : dispatchReason,
                null,
                java.util.Map.ofEntries(
                    java.util.Map.entry("source", "dispatch_preflight_test"),
                    java.util.Map.entry("dispatch_preflight_mode", "active_probe"),
                    java.util.Map.entry("dispatch_preflight_probe_kind", "cli_help"),
                    java.util.Map.entry("dispatch_preflight_probe_args", java.util.List.of("--version")),
                    java.util.Map.entry("dispatch_preflight_command_shape", java.util.List.of("direct", "--version")),
                    java.util.Map.entry("dispatch_preflight_exit_code", dispatchReady ? 0 : 1),
                    java.util.Map.entry("cli_profile_evidence_available", true),
                    java.util.Map.entry("supports_yolo", false),
                    java.util.Map.entry("provider_failure_class", dispatchReady ? "" : "provider_protocol_error"),
                    java.util.Map.entry("provider_failure_reason", dispatchReady ? "" : dispatchReason),
                    java.util.Map.entry("provider_retryable", !dispatchReady)
                )
            );
        }
    }
}
