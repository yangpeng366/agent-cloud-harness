package com.agentcloud.agent.providers;

import java.nio.file.Path;

/**
 * harness-config.yml 中 {@code judgment.shadow} 段的配置模型。
 * <p>
 * 语义（B 向分级降级降本 M1 旁路观察）：
 * <ul>
 *   <li>{@code enabled=false}（默认）→ 整套 shadow 通道不装配，既有路由/执行路径完全不变；</li>
 *   <li>{@code enabled=true && profile=record_only && router.enabled=true}
 *       → Main 注入 {@code RouterShadowHookAdapter}，每轮 execution 追加一条 DecisionEvent（JSONL），
 *         WorkerRouter 选择结果不受影响（hook 仅在 executeOneRound 之后读事实）；</li>
 *   <li>{@code profile=enforce} 当前尚未实现落点，装配行为仍是 record_only，调用方需记录告警。</li>
 * </ul>
 * 默认值全部对齐 harness-config.example.yml 的注释口径；缺省即关闭。
 */
public record JudgmentShadowConfig(
    boolean enabled,
    String profile,
    String policyVersion,
    Router router,
    Channel toolGate,
    Channel verifier,
    Stats stats
) {
    public static final String PROFILE_RECORD_ONLY = "record_only";
    public static final String PROFILE_ENFORCE = "enforce";
    public static final String DEFAULT_PROVIDER = "heuristic-shadow-v1";
    public static final Path DEFAULT_ROUTER_SINK = Path.of("logs", "judgment", "router-shadow.jsonl");
    public static final Path DEFAULT_TOOL_GATE_SINK = Path.of("logs", "judgment", "tool-gate-shadow.jsonl");
    public static final Path DEFAULT_VERIFIER_SINK = Path.of("logs", "judgment", "verifier-shadow.jsonl");

    public JudgmentShadowConfig {
        if (profile == null || profile.isBlank()) profile = PROFILE_RECORD_ONLY;
        if (policyVersion == null || policyVersion.isBlank()) policyVersion = "default";
        if (router == null) router = new Router(true, DEFAULT_PROVIDER, DEFAULT_ROUTER_SINK);
        if (toolGate == null) toolGate = new Channel(false, DEFAULT_PROVIDER, DEFAULT_TOOL_GATE_SINK);
        if (verifier == null) verifier = new Channel(false, DEFAULT_PROVIDER, DEFAULT_VERIFIER_SINK);
        if (stats == null) stats = new Stats(false, null);
    }

    /** 缺省态：整套关闭（不写账、不改路由）。 */
    public static JudgmentShadowConfig disabled() {
        return new JudgmentShadowConfig(false, PROFILE_RECORD_ONLY, "default", null, null, null, null);
    }

    /** Router 旁路是否需要装配：总开关 + 分项开关双门。 */
    public boolean routerObservable() {
        return enabled && router != null && router.enabled();
    }

    public boolean recordOnly() {
        return PROFILE_RECORD_ONLY.equalsIgnoreCase(profile);
    }

    public boolean enforceRequested() {
        return PROFILE_ENFORCE.equalsIgnoreCase(profile);
    }

    /** Router 通道：provider 名 + JSONL 落盘路径。 */
    public record Router(boolean enabled, String provider, Path path) {
        public Router {
            if (provider == null || provider.isBlank()) provider = DEFAULT_PROVIDER;
            if (path == null) path = DEFAULT_ROUTER_SINK;
        }
    }

    /** Tool Gate / Verifier 通道占位（M2/M3 接 WorkerExecutor 时消费）。 */
    public record Channel(boolean enabled, String provider, Path path) {
        public Channel {
            if (provider == null || provider.isBlank()) provider = DEFAULT_PROVIDER;
        }
    }

    /** DecisionEventStatsCli 周期回放任务（M4 Calibration）。 */
    public record Stats(boolean enabled, String cliCommand) {
    }
}
