# judgment.shadow 配置说明（B 向：运行时分级降级降本）

> 状态：M1 旁路观察（record_only）已在 Main 接通；enforce 未实现。
> 相关代码：`HarnessConfigLoader` / `JudgmentShadowConfig` / `RouterShadowHookFactory` / `Main.wireRouterShadowHook`。

## 用途

把 Router 判断作为**旁路观察**接入执行链：每轮 worker execution 追加一条 DecisionEvent（JSONL）用于后续
M4 Calibration 与 M1 降本度量，但**不改变** WorkerRouter 的 worker 选择，也不改变 WorkerExecutor 的执行路径。
默认关闭，关闭时整套 shadow 通道不装配（`routerShadowHook == null`）。

## 关键配置项（`harness-config.yml` 根级 `judgment.shadow`）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `false` | 总开关；false 时不装配任何 shadow 组件 |
| `profile` | `record_only` | `record_only` 只写账；`enforce` 未实现落点，按 record_only 运行并打 WARN |
| `policy_version` | `default` | 透传到 DecisionEvent 的策略版本标记 |
| `router.enabled` | `true` | Router 分项开关；与 `enabled` 是 AND 关系 |
| `router.provider` | `heuristic-shadow-v1` | 未知值回退 heuristic 并告警，不阻断启动 |
| `router.path` | `logs/judgment/router-shadow.jsonl` | DecisionEvent JSONL 追加路径（相对启动目录，父目录自动创建） |
| `tool_gate.*` / `verifier.*` | 各 `enabled=false` | M2/M3 占位，当前不装配 |
| `stats.*` | `enabled=false` | M4 回放 CLI 命令模板，当前不调度 |

## 生效环境

- 本地/单机：在启动目录的 `harness-config.yml` 加 `judgment.shadow.enabled: true` 即生效。
- 已上线环境：`harness-config.yml` 未写 `judgment.shadow` → 关闭态，行为与旧版本完全一致。
- 生效证据（冒烟）：`RouterShadowWiringSmokeTest` 断言注入前后 `execution_id`（含 workerId）、
  `executionStatus()`、`summary()` 不变，且 JSONL 每轮 1 行、`shadowMode=record_only`。

## 回滚方式

1. 把 `judgment.shadow.enabled` 置回 `false`（或删掉整段）后重启；
2. 无需回滚数据：`router.path` 指向的 JSONL 为 append-only 旁路账本，删除不影响主链路；
3. 紧急情况下退出码不变：hook 在 provider 异常时降级为 `balanced` + `degraded=true`，不抛异常。
