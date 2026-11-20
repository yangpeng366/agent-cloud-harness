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

## 实跑验证（2026-11-20，record_only 真链路）

启动方式（隔离 DB + 端口 + 独立配置，不碰仓库内 `harness-config.yml`）：

```powershell
java -Ddb.path=.tmp\shadow-record-only-run\harness.db -Dserver.port=18099 -jar target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar
# 配置：.tmp\shadow-record-only-run\harness-config.yml（仓库配置 + judgment.shadow record_only，policy_version=patrol-20261120-v1）
# 任务：POST /api/v1/tasks（chat / low / write_scope=none）
```

结果：

| 观测点 | 实测 |
|---|---|
| JSONL 落账 | 每轮 execution 追加 1 行，`kind=router`、`shadowMode=record_only` |
| workerId 不变 | `execution_id=task_9114afcb6bf644ae:codex-main:1795134422730`，与 WorkerRouter「selected by model tier preference (strong)」选择一致 |
| 路由不被改写 | live_flow 只在 envelope 追加 `routing_decision_id / routing_tier / routing_provider / routing_scores / routing_degraded`，`worker_id`、dispatch 结果不变 |
| CLI 回放 | `DecisionEventStatsCli --pretty --reasons` 输出 `totalEvents=1 / actionsByKind{router:fast:1} / degradedCount=0 / skippedLines=0` |

### 本轮修掉的缺陷：policy_version 未落账

修复前实跑：配置 `policy_version=patrol-20261120-v1`，日志也打印该值，但 JSONL 里 `policyVersion="shadow-default"`——
`RouterShadowService` 把 `decision.thresholdSetId()` 同时写进了 `policyVersion` 与 `thresholdSetId`，配置值被丢弃。
M4 Calibration 需要按策略版本分组，这条不修就只能看到默认版本。

修复点：`RouterShadowService` 增加 policyVersion 维度（空值回落 `shadow-default`）→ `RouterShadowHookFactory.createIfEnabled(..., policyVersion)` →
`Main.wireRouterShadowHook` 透传 `JudgmentShadowConfig.policyVersion()`。修复后重跑，`policyVersion=patrol-20261120-v1` 落账。

### 未完成项（blocker）

- 两轮真实执行都在 provider 启动处失败：`failed to start codex app-server: Stream Closed`（`codex-cli 0.169.0` 在 PATH 上，非缺 CLI）。
  旁路账本照常落账，但**没有真实 LLM 产出**，M1 的 token 基线与 M5 判定开销占比仍无法测量，需 provider/app-server 通道修复后补一次成功轮。

