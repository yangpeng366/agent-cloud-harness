# Agent Cloud Harness — 重设计提案：更易用、兼容更多本地 Agent

> 基于实际调试中发现的问题编写。问题场景：Codex 实际完成了 11 分钟的任务（503KB 输出），但 harness 因 Codex App Server 返回 502 而标记"执行失败"，Dialogue 页面只看到"失败"而非真实产出。

---

## 1. 当前问题的根因链

```
Codex 完成任务（task_complete 事件已写入 .jsonl）
  ↓
App Server 向 harness 返回结果时 → 代理/网络层 502 Bad Gateway
  ↓
CodexAppServerWorkerExecutor 收到 502 → throw → 标记 worker_round:"失败"
  ↓
Dialogue 前端只渲染 session_messages 中的 "执行失败"
  ↓
用户看到：任务失败。实际：任务已完成，503KB 产物已落盘（artifacts 表）但不展示
```

**三个根因**：
1. **错误粒度太粗** — 把网络错误（502）和任务失败（Codex 真出错）等同处理
2. **产物展示路径断** — artifact 写入了但没有在 Dialogue 中主动渲染
3. **provider 协议无容错** — App Server 协议没有 partial-result / graceful-degradation 机制

---

## 2. 目标架构三原则

| 原则 | 含义 |
|------|------|
| **Gradual ownership** | harness 是"轻量控制面"，不替代 agent 的判断，只负责传递和观测 |
| **Output-first** | 任何执行都有产物落盘，前端始终能找到可展示的内容 |
| **Protocol as plugin** | 每个 provider 的通信协议是独立可插拔的实现，不是 switch 分支 |

---

## 3. 六项设计改进

### 🔴 P0 — 错误分类：执行结果 ≠ 通信结果

**当前**：

```java
// CodexAppServerWorkerExecutor: 任何非 2xx = failure
if (status != 200) throw new RuntimeException("unexpected status " + status);
```

**改进**：

```java
// 三分法
enum ExecutionOutcome {
    COMPLETED,      // agent 正常完成，有产物
    COMPLETED_PARTIAL, // agent 完成但通信中断，产物可能不完整
    FAILED          // agent 确实失败了
}

record WorkerExecutionResult(
    ExecutionOutcome outcome,
    String outputText,      // 始终尽力填充（>= 空串）
    String errorDetail,     // 仅在 FAILED 时有值
    List<Artifact> artifacts // 始终附带
) {}
```

**行为变化**：
- 502 → `COMPLETED_PARTIAL` + 保留已获取的任何输出片段
- session_message 的文案从 "执行失败" → "agent 完成但网络中断，部分结果已保存"
- Dialogue 前端渲染 `outputText` 而非仅显示 summary

---

### 🔴 P0 — Protocol 接口抽象，消灭 switch

**当前**：`ProviderCliWorkerExecutor` 中有 9 个 `buildXxxPlan()`、9 个 `consumeXxx()`、4 组 switch。

**改进**：

```java
interface ProviderProtocol {
    String providerId();

    /** 探测 provider 是否可用 */
    ProviderStatus detect();

    /** 把 harness 的 RuntimeContext 翻译成 provider 的调用参数 */
    ExecutionPlan buildPlan(TaskRuntimeContext ctx, ResolvedConfig config);

    /** 把 provider 的原始输出翻译成 WorkerExecutionResult */
    WorkerExecutionResult parseOutput(byte[] raw, ExecutionPlan plan);
}
```

每个 provider 一个实现类（~80-150 行），不再修改 `ProviderCliWorkerExecutor`：

| 实现类 | Provider | 协议类型 |
|--------|----------|------|
| `CursorProtocol` | cursor | native_cli_stream_json |
| `ClaudeProtocol` | claude | native_cli_stream_json |
| `DeepSeekProtocol` | deepseek | native_cli_text |
| `ReasonixProtocol` | reasonix | native_cli_text |
| `CodexAppServerProtocol` | codex | app_server_json_rpc |
| `GenericCliProtocol` | 未适配的 CLI | native_cli_text（fallback）|

`ProviderCliWorkerExecutor` 的目标形态是从 1918 行缩到 ~300 行：选 protocol → buildPlan → execute process → parseOutput。
当前落地先完成主路径 protocol registry / buildPlan / parseOutput 迁移，旧 parser 和部分 provider-specific fallback 仍保留在 executor 内，不能把本段理解为“文件已经缩到 300 行”。

---

### 🟡 P1 — 产物管线：artifact-first 渲染

**当前**：Dialogue 页面渲染的是 `session_messages`，其中 `worker_round` 消息的 `content` 是摘要文本，不是实际产出。

**改进**：

```
session_messages 中的 worker_round 消息保持现状（摘要）
  +
新增 /api/v1/tasks/{id}/artifacts 返回已存在的 artifacts 列表
  +
Dialogue 检测到 task 有 artifact 时，自动在消息卡片下方渲染产物预览
```

**Dialogue 产物卡片**（在 worker_round 消息下方内联）：

```
┌─────────────────────────────────────────┐
│  worker · codex  │  11 min  │  503 KB   │
│  ─────────────────────────────────       │
│  对接方正云雀,修改获取栏目接口, 栏目      │
│  获取接口需要支持 根据配置，根据用户      │
│  获取对应栏目权限（支持可配置）...        │
│  [展开完整输出] [查看 tool trace]        │
└─────────────────────────────────────────┘
```

---

### 🟡 P1 — 自动发现本地 Agent

**当前**：`BuiltinAgentProviders` 和 `WorkerRegistry` 都是硬编码的。新增一个 agent 需要改 5 处。

**改进**：引入 `~/.agentcloud/providers.yaml`：

```yaml
providers:
  - id: reasonix
    binary: reasonix
    args: ["run", "--no-config", "--no-proxy"]
    protocol: native_cli_text
    env:
      MULTICA_REASONIX_MODEL: deepseek-v4-flash

  - id: codex
    binary: codex
    protocol: app_server_json_rpc
    app_server_port: 47201

  - id: trae
    binary: trae
    args: ["chat", "--mode", "agent"]
    protocol: native_cli_text
```

**优势**：
- 新增 agent = 编辑 yaml + 重启，无需改 Java 代码
- protocol 字段选已有 protocol 实现类（native_cli_text / native_cli_stream_json / app_server_json_rpc / mcp）
- 未指定 protocol → 自动探测（先尝试 --help 看是否可用，再用 `GenericCliProtocol` 做文本解析）

当前落地边界：`native_cli_text/json/lines/stream_json` generic provider 已支持动态发现；其中 `native_cli_stream_json` 会解析通用 JSONL 事件里的 `content/text/message/result/error` 字段并归一 failed/error 事件，但仍不等同于 Claude/Cursor 这类 provider-specific JSON event parser。未写 `protocol` 但配置了 `binary` 或 `command` 的 provider 会被保守推断为 `native_cli_text`，并在 metadata 标记 `provider_protocol_inferred=true`；discovery 会执行一个短超时低副作用 startup help/probe，把 `provider_protocol_probe_*` 证据写入 metadata，但不会用该探测结果自动改协议或阻断注册。动态 provider 可通过 `dispatch_probe_args` 配置分发前真实验活命令，`/workers/{id}/readiness?mode=dispatch` 和 `POST /api/v1/agents/{id}/preflight` 会优先使用这组 probe args，而不是只能盲用默认 `--help`。`app_server_json_rpc`、`mcp` 与通用 handshake / tool bridge 仍属于后续收硬项；配置了这些未支持协议的 provider 现在会以 `unsupported` provider 出现在 `/api/v1/agents`，metadata 带 `provider_discovery_unsupported_reason`，并在可构造低副作用命令时记录 `provider_protocol_probe_mode=unsupported_startup_probe` 证据，但不会注册为 runnable worker 或路由候选，避免误分发。

---

### 🟡 P2 — 增量事件流，告别"全量等待"

**当前**：worker 执行完 → 一次性返回所有输出。11 分钟的任务，用户要等 11 分钟才能看到任何东西。

**改进**：

```
harness                                    provider process
  │                                            │
  │──── Execute(task) ────────────────────────→│
  │                                            │
  │←─── event: tool_call("read_file", ...) ───│  (实时)
  │       → 写入 tool_invocations              │
  │       → SSE 推送 Dialogue 前端             │
  │                                            │
  │←─── event: tool_result(503KB) ────────────│
  │       → 写入 artifacts                     │
  │                                            │
  │←─── event: message("正在分析...") ─────────│
  │                                            │
  │←─── event: task_complete ─────────────────│
  │       → 写入 completion_judgment           │
```

**实现方式**：`reasonix` 和 `codex` 的 provider 输出天然是流式的（NDJSON / JSONL 行），harness 在读取子进程 stdout 时**逐行消费**而非等待结束。每行 JSON 转换为 harness 内部 event，写入 DB 并可选推送 SSE。

---

### 🟢 P3 — Dialogue UI 改造为执行面板

**当前**：Dialogue 是一个"聊天视图"——消息列表 + 底部输入框。任务执行结果是消息。

**改进方向**（不推翻现有，增量叠加）：

- **消息卡片内的内联产物预览**（P1 已描述）
- **右侧 details 面板活起来**：选中 task → details 面板自动加载：
  - 最新 artifact 预览（前 20 行）
  - tool invocation 时间线（可展开）
  - 事件时间线（scheduler → worker_round → continue → judgment）
- **实时状态徽标**：worker_round 进行中 → 显示旋转动画 + "codex 执行中 · 已运行 3m12s"

---

## 4. 实施顺序

```
Phase A（本周 · 3-4h）
  ├── A1: 错误三分法（ExecutionOutcome 枚举）
  ├── A2: CodexAppServerWorkerExecutor 改用三分法（502 → PARTIAL）
  └── A3: Dialogue 渲染 artifact 预览卡片（复用现有 /api/v1/tasks/{id}/artifacts）

Phase B（2周 · 8-12h）
  ├── B1: ProviderProtocol 接口 + 提取 DeepSeekProtocol / ReasonixProtocol
  ├── B2: 把现有 switch 分支逐个迁移到 Protocol 实现类
  ├── B3: GenericCliProtocol（自动适配任意 CLI）
  └── B4: providers.yaml 自动发现

Phase C（3周+）
  ├── C1: 增量事件流（SSE 推送）
  ├── C2: Dialogue 实时状态徽标
  └── C3: details 面板 event/tool 时间线
```

---

## 4.1 当前落地状态（2026-06-02 复核）

| 项目 | 状态 | 证据 |
|------|------|------|
| A1: 错误三分法 | 已落地 | `ExecutionOutcome` 已加入 `WorkerExecutionResult`，`partial_timeout` 映射为 `COMPLETED_PARTIAL`。 |
| A2: Codex 502 / 通信失败 partial | 已落地 | `CodexAppServerWorkerExecutor` 在 JSON-RPC error envelope、IO/协议异常且已有足量输出时返回 `partial_timeout`，保留 `outputText` 和 provider run files。 |
| A3: artifact-first 渲染 | 已落地 | `/api/v1/tasks/{id}/artifacts` 合并任务 `artifacts` 表和 latest `agent_run` artifacts；Dialogue 的 `worker_round` 卡片会按 task 拉取并内联展示 artifact 预览。 |
| B1: ProviderProtocol 接口 | 已落地 | `ProviderProtocol`、`DeepSeekProtocol`、`ReasonixProtocol`、`GenericCliProtocol`、`ProviderProtocolRegistry` 已存在。 |
| B2: switch 迁移 | 已落地（主路径） | Claude / Cursor / DeepSeek / Reasonix / Gemini / Kimi / Copilot / OpenCode 已走 protocol build/parse 主路径；`ProviderCliWorkerExecutor` 内部旧 parser 仍保留为 fallback/反射测试兼容，后续可清理。 |
| B3: GenericCliProtocol | 已落地 | `GenericCliProtocol` 支持 text/json/lines parser，可通过 discovery 注册。 |
| B4: providers.yaml 自动发现 | 已落地（轻量版） | `ProviderProtocolDiscovery` 启动时从 `providers.yaml` / `providers.yml` / `providers.json` 发现协议配置，`Main` 已接入 discovery result 并在 worker preflight 前注册动态 provider/worker。当前 YAML parser 支持本方案里的简单 provider 列表，不是完整 YAML 规范实现；对 `native_cli_text/json/lines/stream_json` generic provider 已兼容 `protocol`、`command`、`binary`、`args`、`env`、`capabilities`、`dispatch_probe_args` 字段，JSON 配置同样支持这些别名。`command` 按完整命令执行；`binary + args` 使用 `binary` 作为启动目标并自动追加 task prompt。未写 `protocol` 但配置了 `binary` 或 `command` 时，会保守推断为 `native_cli_text`，并在 discovered provider metadata 标记 `provider_protocol_inferred=true`；同时执行短超时 startup help/probe，写入 `provider_protocol_probe_mode`、`provider_protocol_probe_command_shape`、`provider_protocol_probe_exit_code`、`provider_protocol_probe_success`、`provider_protocol_probe_suggested_parser`、`provider_protocol_probe_output_preview` 等诊断。新 `id` 会进入 `/api/v1/agents`、`/api/v1/workers` 与 provider-native 路由候选；是否 ready 仍取决于本机 binary、认证和 dispatch preflight；若配置了 `dispatch_probe_args`，分发前 readiness 和 `POST /api/v1/agents/{id}/preflight` 会优先执行这组低副作用 probe。动态 provider inventory 仍是内存态，未独立持久化。`native_cli_stream_json` 在 generic discovery 中已能解析通用 JSONL content/message/result/error 字段并标记 failed/error 事件，但仍不做 Claude/Cursor 等 provider-specific event 语义解析；`app_server_json_rpc` 仍走 Codex app-server 主链，不由 generic discovery 动态注册；`mcp` 与通用 handshake / tool bridge 仍未落地。配置了未支持协议的 provider 不再静默丢失，会以 `provider_type=unsupported` 出现在 `/api/v1/agents`；若配置了可探测 binary/command，也会记录 `provider_protocol_probe_mode=unsupported_startup_probe`，但不会进入 `/api/v1/workers` 或路由候选。 |
| C1: SSE 增量事件流 | 已落地（task event + provider run file wrapper 版） | 新增 `GET /api/v1/tasks/{id}/events?stream=true`，基于 harness `events` 表输出 SSE；Dialogue 选中 task 后用 `EventSource` 订阅，并在 worker/control/state 事件到达时增量刷新 `live_flow` 与 messages。Provider run 文件读面已支持 `tail=true&max_lines=N`，也支持 `/provider_run_file?stream=true` / `Accept: text/event-stream` 返回 `provider_run_file.snapshot/update/done` 窗口流；Dialogue / Console 的 `事件日志`、`标准输出` 按钮会优先用 EventSource 订阅该尾部窗口，便于观察 `events.jsonl` / `stdout.log` 尾部变化；当前仍不是 token 级 provider stdout SSE。 |
| C2: Dialogue 实时状态徽标 | 已落地（SSE 触发刷新 + 轮询兜底版） | Dialogue 主视图 pinned summary 与 task thread 会对 active/running 任务展示“执行中”徽标、worker、elapsed 和 control node；选中 task 后 `EventSource` 会在 worker/control/state 事件到达时触发约 250ms 的局部刷新，5s 轮询保留为兜底。 |
| C3: details event/tool 时间线 | 已落地（静态 live_flow 版） | Dialogue details 面板新增“执行时间线”，从 `live_flow` 聚合 cognition event、tool invocation、artifact、decision，按时间倒序展示。 |

已执行验证：

```powershell
& { . .\scripts\Use-Java21.ps1 -Quiet; $mvn = & .\scripts\Resolve-MavenCommand.ps1; & $mvn -q '-Dtest=CodexAppServerWorkerExecutorTest' test }
& { . .\scripts\Use-Java21.ps1 -Quiet; $mvn = & .\scripts\Resolve-MavenCommand.ps1; & $mvn -q '-Dtest=ProviderProtocolDiscoveryTest,ProviderCliWorkerExecutorTest' test }
& { . .\scripts\Use-Java21.ps1 -Quiet; $mvn = & .\scripts\Resolve-MavenCommand.ps1; & $mvn -q -Dtest=TaskHandlerProviderSelectionHttpTest test }
& { . .\scripts\Use-Java21.ps1 -Quiet; $mvn = & .\scripts\Resolve-MavenCommand.ps1; & $mvn -q '-Dtest=ControlNodeGraphOrchestrationFlowTest#orchestratedTaskRunsPlannerThenExecutorInSingleEnter,TaskHandlerLiveFlowHttpTest#taskEventsEndpointSupportsJsonAndSseViews' test }
node --test src/test/js/dialogue-worker-round-action-plan.test.mjs # covers provider_thread_id, provider_session_id fallback, and explicit resume_provider_session_id
node --test src/test/js/dialogue-worker-round-artifact-plan.test.mjs
powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -SkipTests -QuietMaven
node .\scripts\provider-discovery-smoke.js --port 18432 --report .\.tmp\provider-discovery-smoke\report.json
node .\scripts\screenshot.js --base-url http://localhost:8080 --profile desktop --out-dir .\.tmp\dialogue-verify --report .\.tmp\dialogue-verify\screenshot-report.json
node .\scripts\dialogue-business-smoke.js --base-url http://localhost:8080 --report .\.tmp\dialogue-business-smoke-report.json
powershell -ExecutionPolicy Bypass -File .\scripts\Run-CodexPartialTimeoutSmoke.ps1
node --test src/test/js/dialogue-worker-round-action-plan.test.mjs src/test/js/dialogue-message-card-plan.test.mjs src/test/js/dialogue-execution-surface-summary-plan.test.mjs
node --test src/test/js/dialogue-transcript-layout-plan.test.mjs
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=MainConfigTest'
node .\scripts\provider-discovery-smoke.js --port 18452 --report .\.tmp\provider-discovery-smoke-18452\report.json --work-dir .\.tmp\provider-discovery-smoke-18452
node .\scripts\provider-discovery-smoke.js --port 18467 --report .tmp\provider-discovery-smoke-18467\report.json --work-dir .tmp\provider-discovery-smoke-18467
powershell -ExecutionPolicy Bypass -File .\scripts\Run-CodexPartialTimeoutSmoke.ps1 -ReportPath .tmp\codex-partial-timeout-smoke\report-20260602.json
powershell -ExecutionPolicy Bypass -File .\scripts\Run-CodexPartialTimeoutSmoke.ps1 -ReportPath .tmp\codex-partial-timeout-smoke\report-hard-limit-20260602.json
node .\scripts\dialogue-business-smoke.js --base-url http://localhost:18453 --report .tmp\dialogue-business-smoke-18453\report.json
powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18455 -Surface chat -LifecycleMode real -DebugPort 19277 -UserDataDir .tmp\edge-dialogue-browser-probe-real-18455 -ScreenshotDir .tmp\dialogue-browser-screens-18455-real-chat
powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 -BaseUrl http://localhost:18457 -Surface both -LifecycleMode real -DebugPort 19279 -UserDataDir .tmp\edge-dialogue-browser-probe-real-both-18457 -ScreenshotDir .tmp\dialogue-browser-screens-18457-real-both -NodeMaxOldSpaceMb 1024
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Port 18466 -Background -StdOutPath .tmp\harness-warmup-disabled-18466.out.log -StdErrPath .tmp\harness-warmup-disabled-18466.err.log -DisableDispatchPreflightWarmup
& { . .\scripts\Use-Java21.ps1 -Quiet; $mvn = & .\scripts\Resolve-MavenCommand.ps1; & $mvn -q '-Dtest=TaskHandlerLiveFlowHttpTest#providerRunFileHttpSupportsSseTailSnapshots' test }
& { . .\scripts\Use-Java21.ps1 -Quiet; $mvn = & .\scripts\Resolve-MavenCommand.ps1; & $mvn -q '-Dtest=TaskHandlerLiveFlowHttpTest#providerRunFileHttpSseEmitsUpdateWhenTailFileChanges+providerRunFileHttpSupportsSseTailSnapshots' test }
```

关键验收点：

- Codex app-server 在已有输出后收到 `502 Bad Gateway` 类 JSON-RPC error，不再标记纯失败；返回 `partial_timeout` / `COMPLETED_PARTIAL`，并写入 last message file。
- Codex 单轮超时已按“活动超时 + 最大硬上限”执行：`turn_activity_timeout_ms` 只判断无活动卡死，`turn_max_duration_ms` / `coding_turn_max_duration_ms` 是真正硬上限，不会被更大的 activity timeout 拉长；coding / research / investigation 默认硬上限为 `900000ms`。
- ControlNodeGraph 写入 worker artifact 后，会同步追加 `worker_round` session_message；消息 metadata 指向 `artifact_id`、worker/provider、provider run files，历史 artifact 仍可由 SessionService 回填。
- 直接写入 `artifacts` 表、没有 `agent_run` 的任务，也能通过 `/api/v1/tasks/{id}/artifacts` 返回产物。
- Dialogue 不再只依赖 `session_messages.content`，`worker_round` 下方会主动展示该 task 的 artifact 预览。
- Dialogue worker_round artifact-first 渲染策略已抽成可测 plan：未加载时显示“正在加载 worker 产物”，空列表隐藏，最多内联 3 个 artifact 预览并显示剩余数量，选中 task 会打 `data-selected-task` 标记。
- Claude 已迁移为 `ClaudeProtocol` 并注册到默认 protocol registry；正常执行路径保留 stdin JSONL 输入、stream-json 输出解析、resume/model/profile 降级和 `provider_protocol_parser_used=true`。
- Cursor 已迁移为 `CursorProtocol` 并注册到默认 protocol registry；正常执行路径会清理 `stdout:` / `stderr:` 前缀、解析 stream-json assistant/result/system，并设置 `provider_protocol_parser_used=true`。
- DeepSeek protocol 当前通过 `reasonix run --model deepseek-v4-flash` 委托执行，metadata 会保留 `execution_runtime=reasonix` / `delegated_provider=deepseek`，避免“显示 deepseek、实际跑 reasonix”的诊断断层。
- `providers.yaml` / `providers.json` discovery 当前已可直接注册 native CLI generic provider；示例中的 Codex app-server 配置仍应理解为内置 Codex app-server 执行链的配置方向，不是 dynamic generic protocol registry 的已完成能力。
- Gemini 已迁移为 `GeminiProtocol` 并注册到默认 protocol registry；正常执行路径会解析 stream-json message/result/error，并设置 `provider_protocol_parser_used=true`。
- Kimi 已迁移为 `KimiProtocol` 并注册到默认 protocol registry；正常执行路径会解析 `To resume this session` 提示并保留 `provider_session_id`。
- Copilot 已迁移为 `CopilotProtocol` 并注册到默认 protocol registry；正常执行路径会保留 `provider_session_id` / `provider_active_model`，并设置 `provider_protocol_parser_used=true`。
- OpenCode 已迁移为 `OpenCodeProtocol` 并注册到默认 protocol registry；正常执行路径会设置 `provider_protocol_parser_used=true`，不再依赖 executor 内部 opencode parser 主路径。
- Task 级 SSE 事件面已接入：`/api/v1/tasks/{id}/events` 普通 GET 返回最近 harness events，`stream=true` 返回 `text/event-stream`；Dialogue 使用该流缩短“执行中/最近输出”刷新延迟。
- Provider run 文件受控读取已支持尾部窗口和 SSE 窗口流：`/api/v1/tasks/{id}/provider_run_file?kind=events&tail=true&max_lines=50` 可读取最新 `events.jsonl` 尾部行；`stream=true` 或 `Accept: text/event-stream` 会返回 `provider_run_file.snapshot/update/done`，用于观察长 `stdout.log` / `events.jsonl` 的尾部变化；Dialogue / Console 的 `事件日志`、`标准输出` 预览默认使用 `tail=true&max_lines=80`，其他文件仍保持文件头 64 KiB 的兼容行为。
- Provider run file SSE 已补回归测试覆盖真实文件变化：订阅 `stdout` 尾部窗口期间追加新行，会收到 `provider_run_file.update` 并展示最新尾部内容；这验证的是受控文件读面 update 语义，不改变其非 token-level stdout streaming 边界。
- Dialogue / Console 的 provider run 文件按钮已接入该 SSE 读面：`事件日志`、`标准输出` 优先通过 EventSource 订阅 `provider_run_file.snapshot/update/done`；关闭详情弹窗、切换任务或会话时会关闭旧订阅，避免跨任务残留连接。
- Dialogue 主视图已具备运行态徽标：选中 active/running task 后，pinned summary 与 task thread 会显示“执行中 · worker · 已运行 XmYs · 节点 scheduler/continue”，并由 task SSE 事件触发局部刷新；5s 轮询仍作为兜底，避免用户必须展开 details 才知道 Codex/worker 是否仍在跑。
- Dialogue 对 `partial_timeout` worker_round 已提供操作入口：有 `provider_thread_id / provider_session_id` 时显示“继续 Codex thread”和“手动移交”；没有 provider thread 时只显示“手动移交”，避免把部分结果压成普通失败。
- Dialogue worker_round action plan 已补回归覆盖：`provider_thread_id`、仅有 `provider_session_id`、以及显式 `resume_provider_session_id` 三种 partial timeout 元数据都会生成正确的继续 thread 请求体；没有 thread/session 时仍只显示“手动移交”。
- `scripts/Run-CodexPartialTimeoutSmoke.ps1` 已提供最小可重复验收入口：同时跑 Codex app-server 有输出通信失败、ControlNodeGraph partial_timeout 进入 human gate 且写 worker_round、provider thread continue metadata、Dialogue worker_round action plan。
- Dialogue details 面板已具备静态执行面板能力：选中 task 后可直接看到 event/tool/artifact/decision 聚合时间线，避免 worker 输出只藏在消息摘要或下沉列表里。
- 真实浏览器验证已覆盖 `/dialogue/` 默认壳层：desktop 截图断言通过，workspace/details 比例为 1080/292，未复现中间大块异常空白。
- 真实业务 smoke 已覆盖 session 创建、默认 task_auto、pinned execution surface、manual-start task、continue-current note；默认 auto task 即使仍在 `active/scheduler` 等待长 worker，也会在主视图显示“执行中 / worker codex / 节点 scheduler”，不再让用户误以为没有响应。
- Provider discovery 真实启动 smoke 已覆盖：在临时工作目录放 `providers.yaml` 后启动 harness，新 `smoke_agent` 会同时出现在 `/api/v1/agents` 和 `/api/v1/workers`；当 binary 不存在时，`/api/v1/workers` 列表里的 `ready=false` 与 `/api/v1/workers/smoke_agent/readiness` 的 `reason=binary not found: smoke-agent-missing-binary` 保持一致。该 smoke 同时验证了 PowerShell 写出的 UTF-8 BOM YAML 可被 discovery 正确解析。
- Provider discovery smoke 已扩展 unsupported 协议观测：临时 `providers.yaml` 中的 `unsupported_app_server` / `unsupported_mcp` 会出现在 `/api/v1/agents`，`ready=false` 且 metadata 带 `provider_discovery_supported=false` / `provider_discovery_unsupported_reason`；同一批 provider 不会出现在 `/api/v1/workers`。
- Unsupported dynamic provider discovery 已扩展 startup probe 证据：对声明 `app_server_json_rpc` / `mcp` 但配置了可探测 binary/command 的 provider，会写入 `provider_protocol_probe_mode=unsupported_startup_probe`、exit code、success、command shape 和 parser hint，帮助 operator 区分“协议未实现”和“本机 binary 不可用”。
- 2026-06-02 追加端到端复核：`provider-discovery-smoke` 在隔离端口 `18452` 通过，报告写入 `.tmp/provider-discovery-smoke-18452/report.json`；`Run-CodexPartialTimeoutSmoke.ps1` 通过，报告写入 `.tmp/codex-partial-timeout-smoke/report-20260602.json`；`dialogue-business-smoke` 在隔离端口 `18453`、隔离 DB、JDK21、`agentcloud.dispatch.preflight.warmup=false` 下通过，报告写入 `.tmp/dialogue-business-smoke-18453/report.json`。
- 2026-06-02 追加 provider discovery unsupported probe 端到端复核：`provider-discovery-smoke` 在隔离端口 `18467` 通过，报告 `.tmp/provider-discovery-smoke-18467/report.json` 显示 `unsupported_app_server` / `unsupported_mcp` 均进入 `/api/v1/agents`、未进入 `/api/v1/workers`，且 metadata 带 `provider_protocol_probe_mode=unsupported_startup_probe`、`--version` command shape、`exit_code=0`、`success=true`。
- 2026-06-02 追加 Codex hard-limit 复核：扩展 `Run-CodexPartialTimeoutSmoke.ps1`，纳入 `CodexAppServerWorkerExecutorTest#maxDurationRemainsHardLimitEvenWhenActivityTimeoutIsLarger`；报告 `.tmp/codex-partial-timeout-smoke/report-hard-limit-20260602.json` 显示 Java partial-timeout regression 与 Dialogue worker-round action plan 均通过。
- 2026-06-02 追加 real browser chat 复核：`Run-DialogueBrowserAcceptanceProbe.ps1 -Surface chat -LifecycleMode real` 在隔离端口 `18455` 通过，报告写入 `.tmp/dialogue-browser-real-18455/probe-output.json`；`pause` / `resume` 均走真实 `POST /api/v1/tasks/{id}/...`，`task_action` 投影为 POST，且 `hashTaskId == selectedTaskId == action target task id`。这证明 chat surface 的真实 lifecycle gate，不等于 `Surface both` 或人工 A-H 全量完成。
- 2026-06-02 追加 real browser both-surface 复核：`Run-DialogueBrowserAcceptanceProbe.ps1 -Surface both -LifecycleMode real` 在隔离端口 `18457` 通过，报告写入 `.tmp/dialogue-browser-real-both-18457/probe-output.json`；顶层 `surface=both`，`chat_surface=chat_completions`，`responses_surface=responses`，两套 surface 的 `pause` / `resume` 均为真实 POST，且 `hashTaskId == selectedTaskId == action target task id`。这闭合了自动化 richer browser lifecycle gate；人工 A-H 仍需单独确认。
- 2026-06-02 追加 Console operator preflight 入口：Provider Detail 新增 `运行 Preflight` 按钮，直接调用 `POST /api/v1/agents/{id}/preflight`，并在页面展示 `dispatch_preflight_*`、exit code、output preview、failure class / retryable 等诊断；执行后刷新 Agent Inventory 与 worker dispatch readiness。验证命令：`node --test src/test/js/console-time-normalization.test.mjs src/test/js/console-provider-window-plan.test.mjs` 与 `ApiErrorContractHttpTest#agentPreflightEndpointRunsProviderDispatchPreflight,...`。
- 2026-06-02 追加 Console browser preflight probe：扩展 `scripts/console-provider-window-probe.js`，在真实 Console 页面里选择 `codex` provider，点击 `运行 Preflight`，断言触发 `POST /api/v1/agents/codex/preflight` 且 Provider Detail 渲染 `provider preflight result`、`active_probe`、stdout preview 与 `worker dispatch probe`。隔离端口 `18461` 验证通过，报告 `.tmp/console-provider-window-preflight-probe-18461.json`，截图 `.tmp/console-provider-window-preflight-probe-18461.png`。
- 2026-06-02 追加 Console startup protocol probe 面板：Provider Detail 会把 `provider_protocol_probe_*` 渲染为独立 `startup protocol probe` 诊断块，显示 probe 成败、command shape、protocol、parser hint 和输出预览，并明确提示该探测不自动切换协议。隔离端口 `18464` browser probe 通过，报告 `.tmp/console-provider-window-protocol-probe-18464.json`，截图 `.tmp/console-provider-window-protocol-probe-18464.png`。
- 2026-06-02 追加启动 warmup 隔离开关：`Run-HarnessWithJava21.ps1 -DisableDispatchPreflightWarmup` 会注入 `-Dagentcloud.dispatch.preflight.warmup=false`，适合 Dialogue/Console 浏览器验收时避免启动阶段触发真实 provider dispatch probe；隔离端口 `18466` 日志 `.tmp/harness-warmup-disabled-18466.out.log` 已验证出现 `Worker dispatch preflight warmup skipped` 且未出现 `Worker dispatch preflight warmup completed`。

当前闭环判断：

- 业务主链路已经闭合：任务进入 scheduler 后能选 worker、写 worker artifact、追加 `worker_round` session message、通过 `/api/v1/tasks/{id}/artifacts` 暴露产物，并在 Dialogue 中以内联 artifact/worker round 方式展示。
- Codex 通信失败/超时链路已经闭合到 partial：有输出时不再直接当失败移交，而是落为 `partial_timeout` / `COMPLETED_PARTIAL`，保留 provider run files，并在 Dialogue 暴露继续 thread / 手动移交入口。
- 本地 agent 接入链路已经闭合到轻量 dynamic provider：`providers.yaml/json` 可注册 native CLI generic provider，进入 agent/worker inventory 和 provider-native 路由候选；ready 状态仍按本机 binary 与认证真实探测。Console Provider Detail 现在可以直接运行 provider preflight，减少“API 有能力但页面不可操作”的断点。
- Dialogue 执行面板链路已经闭合到 task-event/provider-run-file wrapper：选中 task 后能看到 worker/status/最近输出/执行时间线，并通过 task SSE 事件缩短刷新延迟；provider run file 读面支持 tail 窗口与 SSE snapshot/update，便于查看最近 JSONL/stdout 输出，但这仍不是 token 级 stdout streaming。
- 本地浏览器验收的运维链路已补齐到可配置启动口径：可通过 PowerShell 参数关闭启动时 dispatch preflight warmup，降低长 provider / 低内存机器对 UI 验收的干扰；运行中 readiness 和 task dispatch 的真实探测语义不变。

仍未闭合或不能夸大的边界：

- `app_server_json_rpc` 和 `mcp` 还不是通用 dynamic provider protocol；Codex app-server 仍是内置 Codex 主链，不应把 `providers.yaml` 示例理解成所有 app-server provider 都已动态化。当前 discovery 只把这类配置注册为 Agent Inventory 中的 unsupported provider，不注册 runnable worker。
- 未写 `protocol` 的配置当前已做证据型 startup help/probe，并把结果写入 `provider_protocol_probe_*` metadata；unsupported `app_server_json_rpc` / `mcp` 也会在可构造命令时写入 `unsupported_startup_probe` 证据。但这些探测只用于诊断，不自动把 provider 升级为 `stream_json`、不做 provider-specific 能力识别，也不替代 dispatch readiness 的真实验活。真正的 app-server / MCP handshake 与 tool bridge 仍未落地。
- `native_cli_stream_json` 的 generic discovery 当前只覆盖通用 JSONL content/message/result/error 字段，不等同于 Claude/Cursor 等 provider-specific JSON event 语义解析。
- Provider run file SSE 当前是受控读取面的轮询式窗口流，不会从正在运行的 provider process 直接推 token；它改善 operator 观察 `stdout.log` / `events.jsonl` 的路径，但不等同于 Phase C 设想里的 provider stdout token-level streaming。
- `-DisableDispatchPreflightWarmup` 只跳过启动预热，不会禁用用户点击 Console preflight、`/workers/{id}/readiness?mode=dispatch` 或实际任务分发时的 dispatch readiness；它是验收隔离开关，不是 provider 可用性判定替代品。
- Browser richer acceptance 的自动化 `Surface both` real lifecycle 已在 2026-06-02 通过隔离端口复核；严格人工 A-H 仍未闭合。历史低内存 OOM 仍是风险，需要继续用隔离 DB、关闭启动 preflight warmup 和明确 JVM/timeout 参数跑 gate。
- 严格人工 A-H 手点仍未完成；现有 screenshot、business smoke、partial timeout smoke 和 scripted browser seam 不能替代人工 gate。
- Manual backfill 模板只作为人工逐条复核后的记录载体：默认 `evidence_mode=manual_review_required` 且 `passed=false`；scripted browser bundle 只能提供辅助上下文，不能直接把严格人工 gate 回填为通过。
- Acceptance record draft 生成器也按同一边界输出：A-H seam evidence 可以预填为上下文，但 strict manual `Passed` 仍必须由人工复核决定；token-level streaming 与完整 `/v1/responses` item/tool-call surface 仍以未勾选 gap 呈现，避免把“未验收”误读成完成项。

---

## 5. 和当前评价文档的关系

本文档是 `PROJECT_EVALUATION_AND_NEXT_PLAN.md` 的补充——评价文档侧重**现状诊断**，本文档侧重**架构重构路径**。两者对照阅读：

| 评价发现的问题 | 本文档的对应方案 |
|------|------|
| ProviderCliWorkerExecutor switch 膨胀 | **B1-B3**: Protocol 接口抽象 |
| Codex 502 → 标记失败 | **A1-A2**: 错误三分法 |
| Dialogue 不展示产物 | **A3**: artifact-first 渲染 |
| 新 provider 需改 5 处 | **B4**: providers.yaml |
| LLM 激活 / 配置零散 | 不在本文档范围（评价文档 P0-4） |
