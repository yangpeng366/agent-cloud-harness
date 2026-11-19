package com.agentcloud.judgment.model.shadow;

import com.agentcloud.worker.RouterShadowHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Main 侧 Router 旁路装配唯一出口。
 * <p>
 * 只做「开关 → hook 实例」的映射，不持有状态：
 * <ul>
 *   <li>enabled=false（默认）→ {@link Optional#empty()}，WorkerExecutorRouter.routerShadowHook 保持 null，既有行为不变；</li>
 *   <li>enabled=true → JsonlDecisionEventSink(path) + HeuristicRouterProvider（或按 provider 名解析）
 *       + RouterShadowService + RouterShadowHookAdapter，profile 恒为 record_only（只写账，不参与路由）。</li>
 * </ul>
 * 未知 provider 名回退 heuristic 并告警（不抛异常，避免开关误配拖垮启动）。
 */
public final class RouterShadowHookFactory {

    private static final Logger log = LoggerFactory.getLogger(RouterShadowHookFactory.class);

    private RouterShadowHookFactory() {
    }

    public static Optional<RouterShadowHook> createIfEnabled(boolean enabled, String provider, Path sinkPath) {
        if (!enabled) {
            return Optional.empty();
        }
        Path target = sinkPath == null ? Path.of("logs", "judgment", "router-shadow.jsonl") : sinkPath;
        RouterProvider resolved = resolveProvider(provider);
        DecisionEventSink sink = new JsonlDecisionEventSink(target);
        RouterShadowService service = new RouterShadowService(resolved, sink);
        log.info("judgment.shadow router hook enabled. provider={} sink={} mode=record_only",
            resolved.providerRef(), target.toAbsolutePath());
        return Optional.of(new RouterShadowHookAdapter(service));
    }

    static RouterProvider resolveProvider(String provider) {
        if (provider == null || provider.isBlank() || HeuristicRouterProvider.PROVIDER_REF.equals(provider)) {
            return new HeuristicRouterProvider();
        }
        log.warn("Unknown judgment.shadow router provider '{}'; falling back to {}", provider,
            HeuristicRouterProvider.PROVIDER_REF);
        return new HeuristicRouterProvider();
    }
}
