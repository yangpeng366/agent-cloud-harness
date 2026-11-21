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

### 已修缺陷①：`failed to start codex app-server: Stream Closed`（2026-11-22）

**根因**：`CodexAppServerWorkerExecutor.executeOneRound` 在 `builder.start()` 之后立即
`process.getOutputStream().close()`（注释写的是「prompt 走 CLI 参数，不需要 stdin」，但那是 exec 模式的说法）。
app-server stdio 模式下 stdin 就是 JSON-RPC 通道，`runSession()` 随后要在这个已被关闭的流上写 `initialize`，
第一次写入即抛 `IOException: Stream closed`，因此每次都在 6ms 内失败、`events.jsonl` 只有一行 `harness_send`。

**修复**：删掉这行提前关闭，stdin 交给 `runSession()` 的 try-with-resources 持有，会话结束时随 Writer 关闭（发 EOF，app-server 正常退出）。
`exec_json` 路径本来就是 `redirectInput(NUL)`，不受影响。

**实跑复验**（`.tmp/shadow-record-only-run`，port 18099，record_only 注入）：

| 观测点 | 修复前 | 修复后 |
|---|---|---|
| `provider_error` | `failed to start codex app-server: Stream Closed` | 空 |
| `provider_turn_status` / `exit_code` | 无 / 无 | `completed` / `0` |
| `duration_ms` | 6 | 37018 |
| `events.jsonl` | 258 B（只有 initialize 发送行） | 208 KB（完整 thread/turn 事件流） |
| `last_message.md` | 0 B | 12951 B 真实模型输出 |
| `provider_session_id` | 无 | `01a20030-…`（thread 建立成功） |

### token 账：`provider_token_usage`（2026-11-22 新增）

codex app-server 在每轮里推送 `thread/tokenUsage/updated`（累计 usage 快照）。`JsonRpcSession.handleNotification`
现在解析 `params.tokenUsage.total`，随运行元数据落 `metadata.json`：

- `provider_token_usage.total_tokens / input_tokens / cached_input_tokens / output_tokens / reasoning_output_tokens`
- `provider_total_tokens`（扁平字段，便于直接取数）

数据来源只取 app-server 自报的累计快照（最后一次覆盖），不做本地估算，因此可直接用于 M1 单位有效成本与 M5 判定开销占比的分子。
回归测试：`CodexAppServerWorkerExecutorTest#appServerRecordsTokenUsageLedgerFromUsageNotification`（fake app-server 按
`ThreadTokenUsageUpdatedNotification` 形状回放，断言 1234/1000/200/234/34 全套字段与 `protocol_trace` 含该方法）。

### 未完成项（blocker）

- M5 判定开销占比尚未实测：需要同一批 record_only 轮次里同时有「worker token 账」与「judgment/review LLM token 账」，
  judgment 侧目前只在 INFO 日志里打 `judgeExecution/judgeCompletion`，还没把 review 通道的 usage 写进同一账本。
- 本机 `ccx` 网关当前没有支持 `gpt-4o-mini` 的 chat 渠道（503 `没有 Chat 渠道支持模型 "gpt-4o-mini"`），
  judgment 的 review 调用拿不到真实响应；M5 测量前需要先给 ccx 配一个 review 模型渠道或改 `review_model`。

