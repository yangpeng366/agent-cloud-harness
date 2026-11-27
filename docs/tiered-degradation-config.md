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

### 已修缺陷②：review 通道 503 `All upstream channels are currently unavailable`（2026-11-27）

**根因**：不是模型缺渠道。同一条 `POST /v1/responses`（相同 model/instructions/input）在 JDK `HttpClient` 默认
`HTTP_2` 下稳定 503，显式 `HTTP_1_1` 即 200。本机 CCX 网关是明文 h2c 场景，JDK 的 HTTP/2 升级请求在网关侧
路由不到上游，被回 `service_unavailable`；`PromptBasedJudgmentService` 吞掉空响应后回退
（`action=continue` / `status=partially_done`），于是判定 token 账看起来「永远缺」。

判据（`.tmp/m5-record-only-run/JdkResponsesProbe.java`，同一 token、同一 body、只改协议版本）：

```
HTTP_2   -> 503 {"error":{"message":"All upstream channels are currently unavailable"}}
HTTP_1_1 -> 200 {"id":"resp_...","model":"deepseek-v4.1-flash", ...}
```

对照排除项：`chat_completions` 本身在 ccx 上是 503 `No Chat upstream configured`（无 chat 上游），所以
wire 只能是 `responses`；请求体大小（2KB/8KB/20KB）、worker 轮次并发占用、model 选择均复现不出 503。

**修复**：`OpenAiCompatibleClient` 固定 `HttpClient.Version.HTTP_1_1`（`DEFAULT_HTTP_VERSION`），
可用 `OPENAI_HTTP_VERSION=2` 显式切回；回归测试 `OpenAiCompatibleClientTest#defaultsToHttp11ForCleartextGateways`。

### M5 首次实测（2026-11-27，record_only 真链路 + 真实 review token）

启动口径：Java 21 + 重建 shaded jar；工作目录 `.tmp/m5-record-only-run`；`judgment.shadow.profile=record_only`、
`policy_version=m5-20261127`；`OPENAI_WIRE_API=responses`、`OPENAI_MODEL=codex`、`OPENAI_REVIEW_MODEL=codex`
（ccx 实际落到 `deepseek-v4.1-flash` 上游）。任务：chat / low / `write_scope=none` / `preferred_worker=codex`。

| 观测点 | 实测 |
|---|---|
| worker 轮次 | `provider_turn_status=completed`、`exit_code=0`、`duration_ms=7674`，真实输出落 `last_message.md` |
| worker token（app-server 自报） | `total=31091`，`input=338`、`cached_input=30720`、`output=33`（含 `reasoning=25`） |
| 判定 token（`judgment_*` 落决策 metadata） | execution `835 in / 196 out`；completion `826 in / 222 out`，合计 **2079** |
| review 调用 503 | **0**（修复前同场景每轮 4 次） |
| M5（总 token 口径，judgment/(judgment+worker)） | **6.27%**（2079 / 33170）≤ 10% 门限 |
| M5（非缓存口径，剔除 `cached_input`） | **84.9%**（2079 / 2450）——单句任务上判定开销压倒执行开销 |

两条口径差别必须一起报：worker 侧的 30720 cached token 是按缓存价计费的，而判定侧的 system prompt 每轮都在变、
几乎不吃缓存。因此 M5「≤ 节省额 10%」只能在**同口径**下判：小任务用非缓存口径会直接否掉整个分级方案，
真实判定应取代表性任务的加权口径，并在 Bitable 里登记口径，禁止混算。

### 复现环境注意（本机已踩，非代码缺陷）

- `D:\repository` 与 `C:\Users\47037\.m2` 都是指向已不存在目标的**悬空 Junction**，`mvn` 直接报
  `Could not create local repository`；做法：临时 settings 指到真实目录 `D:/_m2repo`
  （`mvn -s .tmp/maven-settings.xml ...`），不要改全局 `conf/settings.xml`。
- PATH 上的 `java`（`javapath`）在本机崩溃（`-1073741819`）；构建/运行统一用
  `C:\Program Files\Java\jdk-21.0.9+10\bin\java.exe`。

### 未完成项（blocker）

- M5 目前只有 1 个单句任务样本（判定 2079 vs 执行 31091），样本不足以进 Bitable 结论；下一步要跑一批代表性任务，
  输出 `judgment_token_share` 的中位数与四分位。
- `ExperimentRunService` 的 `judgment_token_share` 只在实验跑链路聚合；record_only 旁路账目前只有决策 metadata 一份，
  尚未有判定开销的定期聚合产物（CLI 侧仍是 `DecisionEventStatsCli` 的计数口径，不含 token）。

