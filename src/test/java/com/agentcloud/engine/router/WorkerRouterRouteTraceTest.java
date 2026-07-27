package com.agentcloud.engine.router;

import com.agentcloud.agent.AgentProviderRegistry;
import com.agentcloud.agent.AgentProvider;
import com.agentcloud.agent.AgentProviderDescriptor;
import com.agentcloud.agent.AgentProviderStatus;
import com.agentcloud.agent.providers.CodexProvider;
import com.agentcloud.engine.LearningMemoryService;
import com.agentcloud.model.LearningMemory;
import com.agentcloud.model.Session;
import com.agentcloud.model.Task;
import com.agentcloud.model.Worker;
import com.agentcloud.store.DatabaseManager;
import com.agentcloud.store.LearningMemoryDao;
import com.agentcloud.store.SessionDao;
import com.agentcloud.store.TaskDao;
import com.agentcloud.tool.HostToolAvailability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerRouterRouteTraceTest {

    @TempDir
    Path tempDir;

    @Test
    void routeResultCarriesSelectedWorkerTierAndRole() {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(new Worker(
            "small-executor",
            "kimi",
            List.of("continuation"),
            List.of(),
            List.of(),
            Map.of("api_key", true),
            Map.of("model_tier", "small", "primary_role", "executor"),
            false,
            true
        ));
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("continuation"));

        assertEquals("small-executor", route.selectedWorker());
        assertEquals("kimi", route.selectedWorkerType());
        assertEquals("small", route.selectedModelTier());
        assertEquals("executor", route.selectedExecutionRole());
        assertEquals(route.routeReason(), route.whySelected());
        assertNull(route.fallbackReason());
    }

    @Test
    void routeResultExplainsCapabilityFallback() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("continuation"));

        assertNotNull(route.selectedWorker());
        assertEquals("ready_fallback", route.routeSource());
        assertEquals(route.routeReason(), route.whySelected());
        assertTrue(route.fallbackReason().contains("fallback to any ready worker"));
        assertTrue(route.candidateWorkers().size() >= 1);
    }

    @Test
    void defaultCodexWorkerCarriesControlledDefaultToolsAndScope() {
        WorkerRegistry registry = new WorkerRegistry();
        Worker codex = registry.get("codex");

        assertNotNull(codex);
        assertToolPresence(codex, "git");
        assertToolPresence(codex, "shell");
        assertTrue(codex.toolCapabilities().contains("patch_file"));
        assertToolPresence(codex, "powershell");
        assertToolPresence(codex, "cmd");
        assertEquals(HostToolAvailability.isWindowsHost() ? "windows" : "posix",
            codex.metadata().get("host_platform"));
        assertFalse(codex.toolScope().isEmpty());
        assertTrue(Path.of(codex.toolScope().get(0)).isAbsolute());
    }

    @Test
    void routeSkipsCapabilityMatchWhenWorkerFailsDependencyReadiness() {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(new Worker(
            "blocked-continuation",
            "codex",
            List.of("continuation"),
            List.of(),
            List.of(),
            Map.of("api_key", false),
            Map.of("model_tier", "strong", "primary_role", "executor"),
            false,
            true
        ));
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("continuation"));

        assertNotNull(route.selectedWorker());
        assertNotEquals("blocked-continuation", route.selectedWorker());
        assertEquals("ready_fallback", route.routeSource());
        assertTrue(route.fallbackReason().contains("fallback to any ready worker"));
    }

    @Test
    void orchestratedPlannerStagePrefersStrongTier() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "model_mode", "orchestrated",
            "orchestration_stage", "plan_pending"
        )));

        assertEquals("codex", route.selectedWorker());
        assertEquals("strong", route.selectedModelTier());
        assertTrue(route.routeReason().contains("model tier preference (strong)"));
    }

    @Test
    void orchestratedPlannerStageSkipsStrongProviderWhenCodexProviderIsNotReady() {
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(new CodexProvider("definitely-missing-codex-binary-for-test"))
            .register(readyProvider("kimi"));
        WorkerRegistry registry = new WorkerRegistry(providers);
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "model_mode", "orchestrated",
            "orchestration_stage", "plan_pending"
        )));

        assertEquals("kimi", route.selectedWorker());
        assertEquals("small", route.selectedModelTier());
        assertEquals("capability_match", route.routeSource());
        assertTrue(route.fallbackReason().contains("no ready worker matched preferred model tier=strong"));
        assertFalse(route.candidateWorkers().contains("codex"));
        assertFalse(route.candidateWorkers().contains("claude"));
    }

    @Test
    void routeSkipsWorkerWhenDispatchPreflightFailsEvenIfPassiveReadinessPasses() {
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(preflightProvider(
                "codex",
                true,
                false,
                "thread not found during dispatch preflight",
                "provider_runtime_transient",
                "thread not found during dispatch preflight",
                true
            ))
            .register(readyProvider("kimi"));
        WorkerRegistry registry = new WorkerRegistry(providers);
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "model_mode", "strong_only"
        )));

        assertEquals("kimi", route.selectedWorker());
        assertEquals("small", route.selectedModelTier());
        assertEquals("capability_match", route.routeSource());
        assertFalse(route.routeReason().contains("model tier preference (strong)"));
        assertTrue(route.fallbackReason().contains("dispatch readiness skipped worker(s): codex skipped"));
        assertTrue(route.fallbackReason().contains("thread not found during dispatch preflight"));
        assertTrue(route.fallbackReason().contains("no dispatch-ready worker matched preferred model tier=strong"));
        // codex profile workers (codex-openai, codex-xfyun, codex-deepseek) also share
        // providerId=codex, so they are also skipped when codex dispatch preflight fails
        assertTrue(route.dispatchSkippedWorkers().size() >= 1, "at least codex should be skipped");
        WorkerRouter.RouteSkippedWorker skipped = route.dispatchSkippedWorkers().stream()
            .filter(s -> "codex".equals(s.workerId()))
            .findFirst()
            .orElse(null);
        assertNotNull(skipped, "codex should be in skipped workers");
        assertEquals("provider_runtime_transient", skipped.providerFailureClass());
        assertEquals("thread not found during dispatch preflight", skipped.providerFailureReason());
        assertEquals(Boolean.TRUE, skipped.providerRetryable());
    }

    @Test
    void codingAutoRouteSkipsResearchOnlyGeminiEvenWhenCapabilityMatches() {
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(preflightProvider("codex", true, false, "codex unavailable"))
            .register(preflightProvider("claude", true, false, "claude unavailable"))
            .register(preflightProvider("cursor", true, false, "cursor unavailable"))
            .register(preflightProvider("copilot", true, false, "copilot unavailable"))
            .register(preflightProvider("opencode", true, false, "opencode unavailable"))
            .register(preflightProvider("deepseek", true, false, "deepseek unavailable"))
            .register(preflightProvider("kimi", true, false, "kimi unavailable"))
            .register(preflightProvider("gemini", true, true, "ready"));
        WorkerRegistry registry = new WorkerRegistry(providers);
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding"));

        assertNull(route.selectedWorker());
        assertFalse(route.candidateWorkers().contains("gemini"));
        assertTrue(route.fallbackReason().contains("auto-route task type contract for taskType=coding"));
        assertTrue(route.fallbackReason().contains("gemini skipped"));
    }

    @Test
    void codingReadyFallbackDoesNotSelectOpenClawNativeToolWorker() {
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(preflightProvider("codex", true, false, "codex unavailable"))
            .register(preflightProvider("claude", true, false, "claude unavailable"))
            .register(preflightProvider("cursor", true, false, "cursor unavailable"))
            .register(preflightProvider("copilot", true, false, "copilot unavailable"))
            .register(preflightProvider("opencode", true, false, "opencode unavailable"))
            .register(preflightProvider("deepseek", true, false, "deepseek unavailable"))
            .register(preflightProvider("kimi", true, false, "kimi unavailable"))
            .register(preflightProvider("gemini", true, false, "gemini unavailable"));
        WorkerRegistry registry = new WorkerRegistry(providers);
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding"));

        assertNull(route.selectedWorker());
        assertFalse(route.candidateWorkers().contains("openclaw-native"));
        assertTrue(route.fallbackReason().contains("dispatch readiness skipped worker(s)"));
    }

    @Test
    void dispatchReadinessStopsAfterFirstSortedReadyWorker() {
        CountingDispatchProvider codex = new CountingDispatchProvider("codex", true, true, "ready");
        CountingDispatchProvider claude = new CountingDispatchProvider("claude", true, false, "claude unavailable");
        CountingDispatchProvider cursor = new CountingDispatchProvider("cursor", true, false, "cursor unavailable");
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(codex)
            .register(claude)
            .register(cursor);
        WorkerRegistry registry = new WorkerRegistry(providers);
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding"));

        assertEquals("codex", route.selectedWorker());
        assertEquals(1, codex.dispatchCount());
        assertEquals(0, claude.dispatchCount());
        assertEquals(0, cursor.dispatchCount());
        assertTrue(route.dispatchSkippedWorkers().stream()
            .noneMatch(skipped -> "claude".equals(skipped.workerId()) || "cursor".equals(skipped.workerId())));
    }

    @Test
    void researchAutoRouteCanSelectGeminiFromResearchContract() {
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(preflightProvider("gemini", true, true, "ready"));
        WorkerRegistry registry = new WorkerRegistry(providers);
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("research"));

        assertEquals("gemini", route.selectedWorker());
        assertEquals("capability_match", route.routeSource());
        assertTrue(route.candidateWorkers().contains("gemini"));
    }

    @Test
    void researchTaskWithWorkspaceMutationBypassesPinnedOpenclawAndRoutesToCodex() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("research", Map.of(
            "task_type", "research",
            "preferred_worker", "openclaw-native",
            "intent", "阅读 docs/ARCHITECTURE.md，并把摘要写入 .tmp/rnd-summary.txt",
            "workspace_root", "D:\\gitAll\\agent-cloud-harness"
        )));

        assertEquals("coding", route.taskType());
        assertEquals("codex", route.selectedWorker());
        assertEquals("openclaw-native", route.preferredWorkerHint());
        assertNotNull(route.fallbackReason());
        assertTrue(route.fallbackReason().contains("openclaw-native"));
        assertTrue(route.fallbackReason().contains("auto_route_task_types contract"));
        assertTrue(route.fallbackReason().contains("taskType=coding"));
    }
    @Test
    void messageAutoRouteUsesToolAwareWorkerBeforeSuggestOnlyAssistants() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("message"));

        assertEquals("openclaw-native", route.selectedWorker());
        assertEquals("tool", route.selectedModelTier());
        assertTrue(route.candidateWorkers().contains("openclaw-native"));
    }

    @Test
    void pinnedWorkerDispatchFailureKeepsOriginalPreflightReason() {
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(preflightProvider("codex", true, false, "thread not found during dispatch preflight"))
            .register(readyProvider("kimi"));
        WorkerRegistry registry = new WorkerRegistry(providers);
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "preferred_worker", "codex"
        )));

        assertEquals("kimi", route.selectedWorker());
        assertEquals("codex", route.preferredWorkerHint());
        assertNotNull(route.fallbackReason());
        assertTrue(route.fallbackReason().contains("task-pinned worker 'codex' not dispatch ready"));
        assertTrue(route.fallbackReason().contains("thread not found during dispatch preflight"));
    }

    @Test
    void learningMemoryHintDispatchFailureKeepsOriginalPreflightReason() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("worker-router-dispatch-hint.db"))) {
            AgentProviderRegistry providers = new AgentProviderRegistry()
                .register(preflightProvider("codex", true, false, "thread not found during dispatch preflight"))
                .register(readyProvider("kimi"));
            WorkerRegistry registry = new WorkerRegistry(providers);
            WorkerRouter router = new WorkerRouter(registry, learningMemoryService(db, "routing:coding:codex"));

            WorkerRouter.RouteResult route = router.selectWorker(task("coding"));

            assertEquals("kimi", route.selectedWorker());
            assertEquals("codex", route.preferredWorkerHint());
            assertFalse(route.learningHintApplied());
            assertNotNull(route.fallbackReason());
            assertTrue(route.fallbackReason().contains("learning memory hint 'codex' not dispatch ready"));
            assertTrue(route.fallbackReason().contains("thread not found during dispatch preflight"));
        }
    }

    @Test
    void orchestratedExecutionStagePrefersSmallTier() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "model_mode", "orchestrated",
            "orchestration_stage", "execution_pending"
        )));

        assertEquals("kimi", route.selectedWorker());
        assertEquals("small", route.selectedModelTier());
        assertTrue(route.routeReason().contains("model tier preference (small)"));
    }

    @Test
    void learningMemoryHintIsAppliedWhenPreferredWorkerIsReadyAndCapable() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("worker-router-learning-memory.db"))) {
            WorkerRouter router = new WorkerRouter(new WorkerRegistry(), learningMemoryService(db, "routing:coding:codex"));

            WorkerRouter.RouteResult route = router.selectWorker(task("coding"));

            assertEquals("codex", route.selectedWorker());
            assertEquals("learning_memory", route.routeSource());
            assertEquals("codex", route.preferredWorkerHint());
            assertTrue(route.learningHintApplied());
            assertEquals(route.routeReason(), route.whySelected());
            assertNull(route.fallbackReason());
        }
    }

    @Test
    void localWorkspaceCodingTaskCanUseDeepseekWhenLearningHintHasWorkspaceAccess() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("worker-router-local-workspace-hint.db"))) {
            String previous = System.getProperty("agentcloud.worker.priority.config.enabled");
            System.setProperty("agentcloud.worker.priority.config.enabled", "false");
            try {
                WorkerRouter router = new WorkerRouter(new WorkerRegistry(), learningMemoryService(db, "routing:coding:deepseek"));

                WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
                    "task_type", "coding",
                    "goal", "按文档计划 D:\\gitAll\\articleeditor\\docs\\XINHUA_CNML_ADAPTER_IMPLEMENTATION_PLAN_2026-05-15.md 修改代码。",
                    "workspace_root", "D:\\gitAll\\articleeditor"
                )));

                assertEquals("deepseek", route.selectedWorker());
                assertEquals("learning_memory", route.routeSource());
                assertEquals("deepseek", route.preferredWorkerHint());
                assertTrue(route.learningHintApplied());
                assertNull(route.fallbackReason());
            } finally {
                restoreProperty("agentcloud.worker.priority.config.enabled", previous);
            }
        }
    }

    @Test
    void localWorkspaceCodingTaskRejectsLearningHintWithoutWorkspaceAccess() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("worker-router-local-workspace-hint-no-access.db"))) {
            WorkerRegistry registry = new WorkerRegistry();
            registry.register(new Worker(
                "no-local-coder",
                "native-tool",
                List.of("coding"),
                List.of(),
                List.of(),
                Map.of("api_key", true, "backend_reachable", true),
                Map.of(
                    "model_tier", "strong",
                    "primary_role", "planner_executor",
                    "selection_priority", 150,
                    "local_workspace_access", false
                ),
                false,
                true
            ));
            WorkerRouter router = new WorkerRouter(registry, learningMemoryService(db, "routing:coding:no-local-coder"));

            WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
                "task_type", "coding",
                "goal", "检查 D:\\gitAll\\articleeditor\\src 并补测试。",
                "workspace_root", "D:\\gitAll\\articleeditor"
            )));

            assertNotEquals("no-local-coder", route.selectedWorker());
            assertEquals("no-local-coder", route.preferredWorkerHint());
            assertFalse(route.learningHintApplied());
            assertNotNull(route.fallbackReason());
            assertTrue(route.fallbackReason().contains("local workspace access required"));
            assertTrue(route.fallbackReason().contains("no-local-coder skipped: local_workspace_access=false"));
            assertTrue(route.fallbackReason().contains("learning memory hint 'no-local-coder' not in current candidate set"));
        }
    }

    @Test
    void pinnedWorkerWithoutWorkspaceAccessCannotOverrideLocalWorkspaceRequirement() {
        WorkerRegistry registry = new WorkerRegistry();
        registry.register(new Worker(
            "no-local-coder",
            "native-tool",
            List.of("coding"),
            List.of(),
            List.of(),
            Map.of("api_key", true, "backend_reachable", true),
            Map.of(
                "model_tier", "strong",
                "primary_role", "planner_executor",
                "selection_priority", 150,
                "local_workspace_access", false
            ),
            false,
            true
        ));
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "preferred_worker", "no-local-coder",
            "goal", "修改 D:\\gitAll\\articleeditor\\src\\main\\java\\ArticleThirdService.java 并补测试。",
            "workspace_root", "D:\\gitAll\\articleeditor"
        )));

        assertNotEquals("no-local-coder", route.selectedWorker());
        assertEquals("no-local-coder", route.preferredWorkerHint());
        assertNotNull(route.fallbackReason());
        assertTrue(route.fallbackReason().contains("task-pinned worker 'no-local-coder' lacks local workspace access"));
        assertTrue(route.fallbackReason().contains("local workspace access required"));
    }

    @Test
    void localWorkspaceOpsTaskRejectsCandidateWithoutWorkspaceAccess() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("worker-router-ops-local-workspace.db"))) {
            WorkerRegistry registry = new WorkerRegistry();
            registry.register(new Worker(
                "no-local-ops",
                "native-tool",
                List.of("ops"),
                List.of(),
                List.of(),
                Map.of("api_key", true, "backend_reachable", true),
                Map.of(
                    "model_tier", "strong",
                    "primary_role", "planner_executor",
                    "selection_priority", 160,
                    "local_workspace_access", false
                ),
                false,
                true
            ));
            WorkerRouter router = new WorkerRouter(registry, learningMemoryService(db, "routing:ops:no-local-ops"));

            WorkerRouter.RouteResult route = router.selectWorker(task("ops", Map.of(
                "task_type", "ops",
                "goal", "在 D:\\gitAll\\agent-cloud-harness 里运行脚本并修复失败。",
                "workspace_root", "D:\\gitAll\\agent-cloud-harness"
            )));

            assertNotEquals("no-local-ops", route.selectedWorker());
            assertEquals("no-local-ops", route.preferredWorkerHint());
            assertFalse(route.learningHintApplied());
            assertNotNull(route.fallbackReason());
            assertTrue(route.fallbackReason().contains("local workspace access required"));
            assertTrue(route.fallbackReason().contains("no-local-ops skipped: local_workspace_access=false"));
        }
    }

    @Test
    void defaultWorkersDeclareObservedLocalWorkspaceAccessBoundary() {
        WorkerRegistry registry = new WorkerRegistry();

        assertEquals(Boolean.TRUE, registry.get("codex").metadata().get("local_workspace_access"));
        assertEquals(Boolean.TRUE, registry.get("deepseek").metadata().get("local_workspace_access"));
        assertEquals("native_cli_cwd", registry.get("deepseek").metadata().get("workspace_access_mode"));
    }

    @Test
    void learningMemoryHintExplainsFallbackWhenModelTierNarrowsCandidateSet() {
        try (DatabaseManager db = new DatabaseManager(tempDir.resolve("worker-router-learning-memory-fallback.db"))) {
            WorkerRouter router = new WorkerRouter(new WorkerRegistry(), learningMemoryService(db, "routing:coding:codex"));

            WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
                "task_type", "coding",
                "model_mode", "small_only"
            )));

            assertEquals("kimi", route.selectedWorker());
            assertEquals("capability_match", route.routeSource());
            assertEquals("codex", route.preferredWorkerHint());
            assertFalse(route.learningHintApplied());
            assertTrue(route.routeReason().contains("model tier preference (small)"));
            assertTrue(route.fallbackReason().contains("not in current candidate set"));
        }
    }

    @Test
    void explicitPreferredWorkerPinsRouteWhenRegisteredAndReady() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "preferred_worker", "codex"
        )));

        assertEquals("codex", route.selectedWorker());
        assertEquals("task_pinned", route.routeSource());
        assertEquals("codex", route.preferredWorkerHint());
        assertTrue(route.routeReason().contains("task-pinned worker"));
        assertNull(route.fallbackReason());
    }

    @Test
    void codexProfileRoutingCarriesSelectedAndRequestedProfileContext() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "preferred_provider_profile", "codex_openai_strong"
        )));

        assertEquals("codex-openai", route.selectedWorker());
        assertEquals("codex_profile_routing", route.routeSource());
        assertEquals("codex_openai_strong", route.selectedProviderProfile());
        assertEquals("codex_openai_strong", route.preferredProviderProfile());
        assertNull(route.workflowStage());
        assertTrue(route.whySelected().contains("preferred_provider_profile"));
    }

    @Test
    void codexProfileRoutingCarriesWorkflowStageContext() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "workflow_stage", "implement"
        )));

        assertEquals("codex-xfyun", route.selectedWorker());
        assertEquals("codex_profile_routing", route.routeSource());
        assertEquals("codex_xfyun_execute", route.selectedProviderProfile());
        assertNull(route.preferredProviderProfile());
        assertEquals("implement", route.workflowStage());
        assertTrue(route.whySelected().contains("workflow_stage=implement"));
    }

    @Test
    void pinnedCodexRouteStillCarriesProfileRoutingContext() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        Task pinnedCodexTask = task("coding", Map.of(
            "task_type", "coding",
            "preferred_provider_profile", "codex_openai_strong"
        )).withAssignedWorker("codex");

        WorkerRouter.RouteResult route = router.selectWorker(pinnedCodexTask);

        assertEquals("codex-openai", route.selectedWorker());
        assertEquals("codex_profile_routing", route.routeSource());
        assertEquals("codex_openai_strong", route.selectedProviderProfile());
        assertEquals("codex_openai_strong", route.preferredProviderProfile());
        assertNull(route.workflowStage());
    }

    @Test
    void explicitPreferredWorkerFallsBackWithReasonWhenWorkerMissing() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "preferred_worker", "missing-worker"
        )));

        assertEquals("codex", route.selectedWorker());
        assertEquals("capability_match", route.routeSource());
        assertEquals("missing-worker", route.preferredWorkerHint());
        assertNotNull(route.fallbackReason());
        assertTrue(route.fallbackReason().contains("not registered"));
    }

    @Test
    void continuationRepoModificationTaskIsRoutedUsingEffectiveCodingType() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("continuation", Map.of(
            "task_type", "continuation",
            "goal", "根据文档修改 D:\\gitAll\\articleeditor\\src\\main\\java\\ArticleThirdService.java，并补测试。"
        )));

        assertEquals("coding", route.taskType());
        assertEquals("codex", route.selectedWorker());
        assertEquals("capability_match", route.routeSource());
    }

    @Test
    void dialogueContinuationWithArticleEditorCompletionIntentRoutesToCodex() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("continuation", Map.of(
            "task_type", "continuation",
            "intent", "d:/gitAll/articleeditor-tmp/ /Editor 编辑页面 从其他页面跳过来的时候，url可能只传了editId= type accountId channelId 可能需要从稿件详情接口返回数据补全"
        )));

        assertEquals("coding", route.taskType());
        assertEquals("codex", route.selectedWorker());
        assertEquals("capability_match", route.routeSource());
        assertFalse(route.candidateWorkers().contains("openclaw-native"));
    }

    @Test
    void freeFirstRoutingPrefersDevecoBeforePaidAutoWorkers() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "provider_routing_policy", "free_first"
        )));

        assertEquals("deveco", route.selectedWorker());
        assertTrue(route.freeFirstRouting());
        assertEquals("free_auto", route.costRouteStage());
        assertTrue(route.freeCandidateWorkers().contains("deveco"));
        assertTrue(route.paidCandidateWorkers().contains("codex"));
    }

    @Test
    void freeFirstRoutingFallsBackToPaidAutoWhenFreeQuotaExhausted() {
        WorkerRegistry registry = new WorkerRegistry();
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "provider_routing_policy", "free_first",
            "user_reported_quota_state", Map.of(
                "deveco", "quota_exhausted",
                "codebuddy", "quota_exhausted"
            )
        )));

        assertEquals("codex", route.selectedWorker());
        assertTrue(route.freeFirstRouting());
        assertEquals("paid_auto", route.costRouteStage());
        assertTrue(route.fallbackReason().contains("free provider quota exhausted"));
    }

    @Test
    void freeFirstRoutingRequiresManualWindowWhenAutoProvidersUnavailableAndPaidFallbackDisabled() {
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(preflightProvider("codex", true, false, "codex unavailable"))
            .register(preflightProvider("claude", true, false, "claude unavailable"))
            .register(preflightProvider("cursor", true, false, "cursor unavailable"))
            .register(preflightProvider("copilot", true, false, "copilot unavailable"))
            .register(preflightProvider("opencode", true, false, "opencode unavailable"))
            .register(preflightProvider("deepseek", true, false, "deepseek unavailable"))
            .register(preflightProvider("kimi", true, false, "kimi unavailable"))
            .register(preflightProvider("gemini", true, false, "gemini unavailable"))
            .register(preflightProvider("reasonix", true, false, "reasonix unavailable"))
            .register(preflightProvider("codebuddy", true, false, "codebuddy unavailable"))
            .register(preflightProvider("deveco", true, false, "deveco unavailable"))
            .register(preflightProvider("trae", true, true, "ready"));
        WorkerRegistry registry = new WorkerRegistry(providers);
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "provider_routing_policy", "free_first",
            "paid_fallback_allowed", false,
            "manual_window_fallback_allowed", true,
            "manual_window_candidates", List.of("trae", "zcode")
        )));

        assertNull(route.selectedWorker());
        assertTrue(route.manualWindowRequired());
        assertEquals("manual_window_recommendation", route.costRouteStage());
        assertEquals("trae", route.recommendedManualProvider());
        assertEquals(List.of("trae", "zcode"), route.manualWindowCandidates());
        assertFalse(route.candidateWorkers().contains("trae"));
    }

    @Test
    void freeFirstRoutingSkipsExhaustedManualWindowCandidates() {
        AgentProviderRegistry providers = new AgentProviderRegistry()
            .register(preflightProvider("codex", true, false, "codex unavailable"))
            .register(preflightProvider("claude", true, false, "claude unavailable"))
            .register(preflightProvider("cursor", true, false, "cursor unavailable"))
            .register(preflightProvider("copilot", true, false, "copilot unavailable"))
            .register(preflightProvider("opencode", true, false, "opencode unavailable"))
            .register(preflightProvider("deepseek", true, false, "deepseek unavailable"))
            .register(preflightProvider("kimi", true, false, "kimi unavailable"))
            .register(preflightProvider("gemini", true, false, "gemini unavailable"))
            .register(preflightProvider("reasonix", true, false, "reasonix unavailable"))
            .register(preflightProvider("codebuddy", true, false, "codebuddy unavailable"))
            .register(preflightProvider("deveco", true, false, "deveco unavailable"))
            .register(preflightProvider("trae", true, true, "ready"));
        WorkerRegistry registry = new WorkerRegistry(providers);
        WorkerRouter router = new WorkerRouter(registry);

        WorkerRouter.RouteResult route = router.selectWorker(task("coding", Map.of(
            "task_type", "coding",
            "provider_routing_policy", "free_first",
            "paid_fallback_allowed", false,
            "manual_window_fallback_allowed", true,
            "manual_window_candidates", List.of("trae", "zcode"),
            "user_reported_quota_state", Map.of(
                "trae", "user_reported_exhausted"
            )
        )));

        assertNull(route.selectedWorker());
        assertTrue(route.manualWindowRequired());
        assertEquals("zcode", route.recommendedManualProvider());
        assertEquals(List.of("zcode"), route.manualWindowCandidates());
    }

    private Task task(String taskType) {
        return TestTasks.task(taskType);
    }

    private Task task(String taskType, Map<String, Object> metadata) {
        return TestTasks.task(taskType, metadata);
    }

    private LearningMemoryService learningMemoryService(DatabaseManager db, String hintKey) {
        SessionDao sessionDao = db.jdbi().onDemand(SessionDao.class);
        TaskDao taskDao = db.jdbi().onDemand(TaskDao.class);
        LearningMemoryDao learningMemoryDao = db.jdbi().onDemand(LearningMemoryDao.class);

        Session session = Session.create("session_route_memory", "worker router learning memory", "active");
        sessionDao.insert(session);
        taskDao.insert(new Task(
            "task_route_memory",
            session.id(),
            null,
            "routing preference seed",
            "active",
            "high",
            Instant.now(),
            Instant.now(),
            Instant.now(),
            null,
            null,
            null,
            "seed preferred worker hint",
            null,
            "codex",
            "continue",
            null,
            Map.of("task_type", "coding")
        ));
        learningMemoryDao.insert(new LearningMemory(
            "lm_route_memory",
            session.id(),
            "task_route_memory",
            null,
            "routing_preference",
            "stable_hint",
            hintKey,
            "Prefer codex for coding tasks.",
            0.9d,
            5,
            Map.of("source", "test"),
            Map.of("category", "routing_preference")
        ));
        return new LearningMemoryService(learningMemoryDao);
    }

    private void restoreProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }

    private void assertToolPresence(Worker worker, String toolCapability) {
        if (HostToolAvailability.unavailableReason(toolCapability) == null) {
            assertTrue(worker.toolCapabilities().contains(toolCapability));
        } else {
            assertFalse(worker.toolCapabilities().contains(toolCapability));
        }
    }

    private AgentProvider readyProvider(String providerId) {
        return new AgentProvider() {
            @Override
            public AgentProviderDescriptor descriptor() {
                return new AgentProviderDescriptor(
                    providerId,
                    providerId,
                    "local_cli",
                    "process",
                    List.of("chat"),
                    Map.of()
                );
            }

            @Override
            public AgentProviderStatus detect() {
                return new AgentProviderStatus(
                    providerId,
                    true,
                    "test",
                    "ready",
                    true,
                    null,
                    Instant.now(),
                    Map.of()
                );
            }
        };
    }

    private AgentProvider preflightProvider(String providerId,
                                            boolean passiveReady,
                                            boolean dispatchReady,
                                            String dispatchReason) {
        return preflightProvider(providerId, passiveReady, dispatchReady, dispatchReason, null, null, null);
    }

    private AgentProvider preflightProvider(String providerId,
                                            boolean passiveReady,
                                            boolean dispatchReady,
                                            String dispatchReason,
                                            String providerFailureClass,
                                            String providerFailureReason,
                                            Boolean providerRetryable) {
        return new AgentProvider() {
            @Override
            public AgentProviderDescriptor descriptor() {
                return new AgentProviderDescriptor(
                    providerId,
                    providerId,
                    "local_cli",
                    "process",
                    List.of("chat"),
                    Map.of()
                );
            }

            @Override
            public AgentProviderStatus detect() {
                return new AgentProviderStatus(
                    providerId,
                    true,
                    "test",
                    "ready",
                    passiveReady,
                    passiveReady ? null : "provider not ready",
                    Instant.now(),
                    Map.of()
                );
            }

            @Override
            public AgentProviderStatus dispatchPreflight() {
                Map<String, Object> metadata = new java.util.LinkedHashMap<>();
                metadata.put("source", "worker_router_dispatch_test");
                if (providerFailureClass != null) {
                    metadata.put("provider_failure_class", providerFailureClass);
                }
                if (providerFailureReason != null) {
                    metadata.put("provider_failure_reason", providerFailureReason);
                }
                if (providerRetryable != null) {
                    metadata.put("provider_retryable", providerRetryable);
                }
                return new AgentProviderStatus(
                    providerId,
                    true,
                    "test",
                    "ready",
                    dispatchReady,
                    dispatchReady ? null : dispatchReason,
                    Instant.now(),
                    metadata
                );
            }
        };
    }

    private static final class CountingDispatchProvider implements AgentProvider {
        private final String providerId;
        private final boolean passiveReady;
        private final boolean dispatchReady;
        private final String dispatchReason;
        private int dispatchCount;

        private CountingDispatchProvider(String providerId,
                                         boolean passiveReady,
                                         boolean dispatchReady,
                                         String dispatchReason) {
            this.providerId = providerId;
            this.passiveReady = passiveReady;
            this.dispatchReady = dispatchReady;
            this.dispatchReason = dispatchReason;
        }

        @Override
        public AgentProviderDescriptor descriptor() {
            return new AgentProviderDescriptor(
                providerId,
                providerId,
                "local_cli",
                "process",
                List.of("chat"),
                Map.of()
            );
        }

        @Override
        public AgentProviderStatus detect() {
            return new AgentProviderStatus(
                providerId,
                true,
                "test",
                "ready",
                passiveReady,
                passiveReady ? null : "provider not ready",
                Instant.now(),
                Map.of()
            );
        }

        @Override
        public AgentProviderStatus dispatchPreflight() {
            dispatchCount++;
            return new AgentProviderStatus(
                providerId,
                true,
                "test",
                "ready",
                dispatchReady,
                dispatchReady ? null : dispatchReason,
                Instant.now(),
                Map.of("source", "counting_dispatch_provider")
            );
        }

        private int dispatchCount() {
            return dispatchCount;
        }
    }
}
