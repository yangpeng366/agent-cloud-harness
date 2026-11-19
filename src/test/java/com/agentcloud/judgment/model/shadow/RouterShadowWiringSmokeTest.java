package com.agentcloud.judgment.model.shadow;

import com.agentcloud.agent.providers.HarnessConfig;
import com.agentcloud.agent.providers.HarnessConfigLoader;
import com.agentcloud.engine.router.WorkerRegistry;
import com.agentcloud.model.Task;
import com.agentcloud.runtime.TaskRuntimeContext;
import com.agentcloud.worker.WorkerExecutionResult;
import com.agentcloud.worker.WorkerExecutor;
import com.agentcloud.worker.WorkerExecutorRouter;
import com.agentcloud.worker.RouterShadowHook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B 向 W2 冒烟：judgment.shadow 配置模型 + Main 注入点（RouterShadowHookFactory + WorkerExecutorRouter）。
 * <p>
 * 证明两件事：
 * 1) enabled=true 时每轮 execution 真的写一条 DecisionEvent 到 JSONL（写账）；
 * 2) 注入前后 worker 选择、执行状态、执行器调用完全一致（路由不变），hook 只往 envelope.metadata 追加 routing_* 字段。
 */
class RouterShadowWiringSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void judgmentSectionAbsentKeepsShadowDisabled() throws Exception {
        HarnessConfig cfg = loadConfig("""
            harness:
              workers: []
            """);

        assertFalse(cfg.judgmentShadow().enabled());
        assertFalse(cfg.judgmentShadow().routerObservable());
        assertTrue(cfg.judgmentShadow().recordOnly(), "缺省 profile = record_only");
        assertTrue(RouterShadowHookFactory
            .createIfEnabled(cfg.judgmentShadow().routerObservable(),
                cfg.judgmentShadow().router().provider(),
                cfg.judgmentShadow().router().path())
            .isEmpty(), "默认关闭时不得装配 hook");
    }

    @Test
    void recordOnlyProfileWritesLedgerAndKeepsRouting() throws Exception {
        Path ledger = tempDir.resolve("ledger").resolve("router-shadow.jsonl");
        HarnessConfig cfg = loadConfig("""
            judgment:
              shadow:
                enabled: true
                profile: record_only
                policy_version: default
                router:
                  enabled: true
                  provider: heuristic-shadow-v1
                  path: "%s"
            """.formatted(ledger.toString().replace("\\", "/")));

        assertTrue(cfg.judgmentShadow().enabled());
        assertTrue(cfg.judgmentShadow().routerObservable());
        assertTrue(cfg.judgmentShadow().recordOnly());
        assertEquals("heuristic-shadow-v1", cfg.judgmentShadow().router().provider());

        Task task = Task.create("task-smoke", "session-smoke", "deploy patch", "active", "high");
        TaskRuntimeContext ctx = new TaskRuntimeContext(
            task, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null);

        // 基线：未注入 hook
        StubExecutor stub = new StubExecutor();
        WorkerExecutorRouter baseline = newRouter(stub);
        WorkerExecutionResult before = baseline.executeOneRound(ctx, "codex-free");
        assertEquals(1, stub.calls, "基线调用一次");

        // 注入：与 Main.wireRouterShadowHook 同一路径
        Optional<RouterShadowHook> hook = RouterShadowHookFactory.createIfEnabled(
            cfg.judgmentShadow().routerObservable(),
            cfg.judgmentShadow().router().provider(),
            cfg.judgmentShadow().router().path());
        assertTrue(hook.isPresent());

        StubExecutor stub2 = new StubExecutor();
        WorkerExecutorRouter router = newRouter(stub2);
        router.setRouterShadowHook(hook.get());
        WorkerExecutionResult after = router.executeOneRound(ctx, "codex-free");

        // 路由不变：worker 选择（execution_id 编码 task:worker:epoch）/ 执行状态 / 摘要 与基线一致，执行器仍是同一个
        assertEquals(1, stub2.calls, "hook 不得重复调用执行器");
        String beforeExecId = String.valueOf(before.metadata().get("execution_id"));
        String afterExecId = String.valueOf(after.metadata().get("execution_id"));
        assertTrue(beforeExecId.startsWith("task-smoke:codex-free:"), beforeExecId);
        assertTrue(afterExecId.startsWith("task-smoke:codex-free:"), afterExecId);
        assertEquals(before.executionStatus(), after.executionStatus(), "执行状态不变");
        assertEquals(before.summary(), after.summary(), "结果主体不变");
        assertNull(before.metadata().get("routing_tier"), "基线不得有 routing_* 字段");

        // 写账：envelope.metadata 追加 5 个 routing 字段
        assertNotNull(after.metadata().get("routing_decision_id"));
        assertEquals("strong", after.metadata().get("routing_tier"), "high priority → strong");
        assertEquals("heuristic-shadow-v1", after.metadata().get("routing_provider"));

        // 写账：JSONL 一行，record_only，runId/turnId 来自 session/execution
        List<String> lines = Files.readAllLines(ledger, StandardCharsets.UTF_8);
        assertEquals(1, lines.size(), "每轮 execution 一行 DecisionEvent");
        String json = lines.get(0);
        assertTrue(json.contains("\"kind\":\"router\""), json);
        assertTrue(json.contains("\"shadowMode\":\"record_only\""), json);
        assertTrue(json.contains("\"runId\":\"session-smoke\""), json);
        assertTrue(json.contains("\"action\":\"strong\""), json);
    }

    @Test
    void routerFlagOffInsideEnabledSectionStillNoop() throws Exception {
        HarnessConfig cfg = loadConfig("""
            judgment:
              shadow:
                enabled: true
                profile: record_only
                router:
                  enabled: false
            """);

        assertTrue(cfg.judgmentShadow().enabled());
        assertFalse(cfg.judgmentShadow().routerObservable(), "router.enabled=false 时不得装配");
        assertTrue(RouterShadowHookFactory.createIfEnabled(
            cfg.judgmentShadow().routerObservable(), null, null).isEmpty());
    }

    private HarnessConfig loadConfig(String yaml) throws Exception {
        Path config = tempDir.resolve("harness-config-" + Math.abs(yaml.hashCode()) + ".yml");
        Files.writeString(config, yaml, StandardCharsets.UTF_8);
        return HarnessConfigLoader.load(List.of(config)).orElseThrow();
    }

    private static WorkerExecutorRouter newRouter(WorkerExecutor executor) {
        return new WorkerExecutorRouter(new WorkerRegistry(), executor, executor, null, null);
    }

    private static final class StubExecutor implements WorkerExecutor {
        private int calls;

        @Override
        public WorkerExecutionResult executeOneRound(TaskRuntimeContext context, String workerId) {
            calls++;
            return new WorkerExecutionResult(
                "stub summary", "stub output", false, null, null, null, "high",
                "success", List.of(), List.of(), 12, 34L, Map.of("stub", "1"));
        }
    }
}
