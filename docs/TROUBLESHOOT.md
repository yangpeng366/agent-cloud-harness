# Troubleshoot

## 1. Gotchas — 已知坑点

### G01: 控制动作接口处于 GET/POST 双兼容期

- **位置**: `src/main/java/com/agentcloud/server/TaskHandler.java`, `SessionHandler.java`
- **现象**: 任务和 session 控制动作已经有正式 `POST` 接口，但历史 `GET` 兼容路径仍在。
- **风险**: 外部继续调用旧 `GET` 时，仍可能被预取或缓存层误触发。
- **规避方式**: 新接入统一改用 `POST /api/v1/tasks/{id}/pause|resume|continue|escalate|handoff` 和 `POST /api/v1/sessions/{id}/pause|resume|close`；仅把 `GET` 当作过渡兼容。

### G02: 任务列表过滤键名存在双写兼容期

- **位置**: `src/main/java/com/agentcloud/server/TaskHandler.java`
- **现象**: 当前同时兼容 `?state=active` 和 `?status=active`。
- **风险**: SDK 或外部脚本可能继续分叉。
- **规避方式**: 对外文档优先统一成 `status`，`state` 仅保留为历史兼容。

### G03: tool-aware 执行器已支持多步工具链，但仍有单轮上限

- **位置**: `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java`
- **现象**: 复杂任务已经能在单轮内走“检索 -> 读取 -> 写回”这类最多 3 步工具链，但如果命中 guard 或达到上限，仍会提前收敛。
- **原因**: 当前上限是 `MAX_TOOL_ROUNDS = 3`，并带 `repeated_tool_guard`、`no_progress_guard`、`max_tool_rounds_reached` 等保护。
- **规避方式**: 把它当成“最小可观测工具链”，不要假设它已经是无限多轮 agent loop；排查时看 `/tool_trace` 和最终 `guard` 原因。

### G04: 路由结果现在受 learning memory 和 model mode 共同影响

- **位置**: `src/main/java/com/agentcloud/engine/router/WorkerRouter.java`
- **现象**: worker readiness 看起来正常，但最终没有命中你以为的“最强”或“最熟悉” worker。
- **原因**: 当前路由不再只看 capability/readiness，还会同时考虑 `model_mode`、orchestration stage、learning memory preferred worker hint，以及候选集收窄后的 `fallback_reason`。
- **规避方式**: 排查时不要只看 `assigned_worker`；同时查看 `/api/v1/tasks/{id}/select_worker`、`/runtime_context`、`/judgment_trace`、`/live_flow` 里的 route metadata。

### G05: 根路径不再是“空白页”，而是前端入口

- **位置**: `src/main/java/com/agentcloud/server/NioHttpServer.java`
- **现象**: 访问 `http://localhost:8080/` 会直接跳到 `/dialogue/`。
- **规避方式**: 调试前端问题时，明确区分 `/dialogue/` 和 `/console/` 两套页面；API 调试仍走 `/api/v1/*`。

### G06: Windows + Surefire 可能冒出 manifest classpath 假失败

- **位置**: `pom.xml`, `target/surefire-reports/*.dumpstream`
- **现象**: 在 Windows/JDK 21 下跑 Maven 测试时，偶发出现：
  - `ClassNotFoundException: com.agentcloud.runtime.TaskRuntimeContextBuilder`
  - `schema.sql not found`
- **根因**: Surefire manifest classpath 在当前工作站会触发绝对路径根盘校验，属于测试引导层噪音，不是业务类或资源真实缺失。
- **当前口径**: 仓库已经把 `-Djdk.net.URLClassPath.disableClassPathURLCheck=true` 固化进 `pom.xml` 的 `maven-surefire-plugin.argLine`，正常通过 `scripts/Test-WithJava21.ps1` 或直接 `mvn test` 不应再要求手工设置 `JDK_JAVA_OPTIONS`。
- **排查方式**:
  1. 先看 `pom.xml` 的 Surefire `argLine` 是否仍包含该 JVM 参数
  2. 再看 `target/surefire-reports/*.dumpstream` 是否仍是类路径校验噪音，而不是某个真实测试失败
  3. 只有在 `argLine` 已生效后仍复现时，才继续怀疑构建产物或资源打包问题

## 2. 常见错误场景

### 2.0.a 最近失败任务恢复入口验收

如果要验证 `thread not found`、provider runtime transient、自动 handoff 和环境阻断拒绝这些恢复合同是否仍然可用，先启动本地 harness，再运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-TaskRecoveryAcceptanceProbe.ps1 -BaseUrl http://localhost:8080
```

默认 probe 只走轻量 HTTP 合同，不触发同 worker `resume` 真实执行。需要同时覆盖 fresh-session 异步恢复触发链时，再显式加 `-IncludeResumeExecution`。

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-TaskRecoveryAcceptanceProbe.ps1 -BaseUrl http://localhost:8080 -IncludeResumeExecution
```

带 `-IncludeResumeExecution` 时，probe 通过 `recover?async=true` 验证恢复入口能立即返回 `202 accepted`，并带出 `request_id / status_url / recovery_execution_mode=fresh_session`。这条验收不等待 worker 完成；如果后续状态没有推进，再看响应里的 `status_url`、服务端日志和 provider 可用性。

长任务人工恢复时优先用异步入口，避免浏览器或脚本等待真实 worker 完成：

```powershell
curl -X POST "http://localhost:8080/api/v1/tasks/<task_id>/recover?async=true" `
  -H "Content-Type: application/json" `
  -d '{"mode":"auto","reason":"manual async recovery"}'
```

异步入口返回 `202` 只表示恢复动作已接受，后续看响应里的 `status_url`、`/tasks/{id}` 或 `/sessions/{id}/messages`。如果 provider 环境本身不可恢复，例如 `provider_auth_failed`，异步入口也应同步返回 `400`，不应进入后台。

异步恢复已落 `TaskRecoveryJob`，可以直接查最近状态：

```powershell
curl "http://localhost:8080/api/v1/tasks/<task_id>/recovery_jobs?limit=10"
```

如果响应里的 `request_id` 在这个列表里不存在，说明请求没有成功进入异步恢复入口。若 job 停在 `accepted/running`，继续看 `status_url` 与服务端日志；若为 `failed`，先看 `error_message` 和 `metadata.recovery_action`。

Console / Dialogue 手工点“自动恢复”时也应走同一条异步入口。页面任务详情会展示最近 `恢复任务`；如果看不到请求 id，先查浏览器 Network 是否请求了 `recover?async=true`，再查 `/api/v1/tasks/<task_id>/recovery_jobs?limit=5`。

前端合同可用浏览器 probe 验证，默认检查 `/dialogue/`：

```powershell
node .\scripts\recovery-job-ui-probe.js --base-url http://localhost:8080 --surface dialogue
```

也可以检查 `/console/`：

```powershell
node .\scripts\recovery-job-ui-probe.js --base-url http://localhost:8080 --surface console
```

这个 probe 会创建一个失败 task，但会在浏览器内拦截 recover/recovery_jobs 响应；验收目标是 UI 请求路径包含 `recover?async=true`，并且详情区展示 `恢复任务` 与对应请求 id，不启动真实长 worker。

### 2.0 worker 执行失败后，什么时候该自动切 worker，什么时候该停到人工确认

- **位置**: `ControlNodeGraph.continue/handoff/human_gate`, `/dialogue/`
- **现象**: 当前 task 已经创建，但执行回执里出现 worker/provider 失败，例如 thread 丢失、provider session 失效、不可读错误输出。
- **不要直接拍脑袋处理**:
  - 不是所有失败都适合自动切 worker
  - 也不是所有失败都应该让用户手工选
- **统一策略**: 参考 `docs/WORKER_FAILURE_RECOVERY_POLICY.md`
  - `worker_runtime_transient`：先同 worker 冷重试，再自动 handoff 一次
  - `task_environment_blocked`：默认不要自动切，优先 `human_gate`
  - `partial_result_or_quality_risk`：默认人工确认
- **已收口的真实坑**:
  - 如果 fallback worker 当前轮返回空输出，执行结果必须显式记成 `execution_status=empty`
  - `continue` 恢复入口必须把 `empty` 视为 `worker_runtime_transient`，否则任务会停在 `active / scheduler`
  - 如果 `ToolAwareWorkerExecutor` 走的是 `needsTool=false -> delegate fallback executor` 分支，包装结果时也必须保留 fallback 带回来的 `execution_status / evidence_refs / unfinished_items`
  - 否则空输出虽然在 `DefaultWorkerExecutor` 里已经被标成 `empty`，但经过 tool-aware 包装后又会退化成 `unknown`，最终继续落回默认 judgment `continue`
  - 恢复预算保持和文档一致：same-worker retry 1 次，auto handoff 1 次；再失败直接进 `waiting_human / human_gate`

### 2.0.b 真实 task 看起来“执行到一半停了”，但其实是 fallback worker 空跑后被旧恢复链吞掉

- **典型表现**:
  1. `/dialogue/` 第一屏只看到一段失败摘要、乱码或工具日志，没有最终结果
  2. `task.status/control_node` 还挂在 `active / scheduler`
  3. 最近事件里能看到类似 `worker_round completed ... outputLength=0`
  4. 继续点 `/continue` 后，旧实例里还会反复停在 `continue -> scheduler`
- **真实根因**:
  1. 上一轮 provider/native worker 先失败，例如 `thread not found`
  2. harness 自动 handoff 到 fallback worker
  3. fallback 当前轮又返回空输出
  4. 旧链路里 `ToolAwareWorkerExecutor` 在 `needsTool=false` 的 delegate 包装阶段把 fallback 的 `execution_status=empty` 丢回成 `unknown`
  5. `continue` 因为没看到失败 execution status，就会退回 `PromptBasedJudgmentService` 的默认 `continue`
- **当前正确行为**:
  1. 当前轮空执行状态必须保留成 `empty`
  2. `continue` 必须立刻按恢复策略处理，而不是再交给默认 judgment
  3. 恢复预算耗尽后，task 应明确进入 `waiting_human / human_gate`
- **当前验证入口**:
  1. `ToolAwareWorkerExecutorMultiStepTest.noToolDelegatePreservesEmptyExecutionStatusFromFallbackExecutor`
  2. `ControlNodeGraphActionResolutionTest.maybePlanFailureRecoveryTreatsEmptyExecutionStatusAsTransientFailure`
  3. `ControlNodeGraphOrchestrationFlowTest.recoveryFallbackEmptyOutputStopsAtHumanGateAfterRetryAndSingleHandoff`
- **快速排查**:
  1. 看后端日志是否同时出现：
     - `Worker round completed ... outputLength=0`
     - 随后没有 `[Recovery] ...`
     - 反而直接出现 `judgeExecution ... action=continue`
  2. 如果是，就优先检查：
     - `ToolAwareWorkerExecutor.delegateWithMetadata(...)`
     - 当前轮 `execution_status` 有没有在 delegate 包装后丢失
  3. fresh 修复后，隔离实例对同一 task 再触发 `/continue`，应直接返回：
     - `state=waiting_human`
     - `control_node=human_gate`

### 2.0.3 worker 明明已经自动切走了，但 `readiness`、`judgment_trace` 或页面文案还像是在用旧 worker

- **典型表现**:
  1. `task.assigned_worker` 已经变成新 worker，例如 `openclaw-native`
  2. 但 `task.metadata.assigned_worker / target_worker / preassigned_selection_reason` 仍残留旧 worker，例如 `codex`
  3. `/dialogue/`、`judgment_trace` 或 `live_flow` 里还会看到类似 `selected by task-pinned worker: ... codex`
- **当前结论**:
  1. 这不应再归类为“只是前端显示旧数据”
  2. 更准确地说，这是 task 顶层 `assigned_worker` 与 metadata 中 worker 相关字段发生了漂移
  3. 漂移存在时，恢复链和 route trace 可能继续把旧 metadata 当作 pin source，造成误导
- **当前源码收口点**:
  1. `ControlNodeGraph.schedulerNode(...)` 入口现在会先做 worker metadata 自愈
  2. 至少会同步：
     - `metadata.assigned_worker`
     - `metadata.target_worker`
     - `metadata.preassigned_selection_reason`
  3. 真实日志关键字：
     - `[Scheduler] ... normalized worker metadata assignedWorker=...`
- **排查顺序**:
  1. 先查 `GET /api/v1/tasks/{id}`，比较顶层 `assigned_worker` 与 `metadata.assigned_worker`
  2. 再查 `judgment_trace.execution_judgment.metadata.why_selected`
  3. 如果这两处仍不一致，先触发一次 `POST /api/v1/tasks/{id}/continue`
  4. 再回看 `.tmp/server-*.out.log` 是否出现 `normalized worker metadata`

### 2.0.4 `worker runtime/provider` 已经失败过，但 `/workers/{id}/readiness` 仍然显示 `ready=true`

- **典型表现**:
  1. 之前某轮执行已经出现 `thread not found / provider unavailable / failed to start / timeout`
  2. 但 `GET /api/v1/workers/{id}/readiness` 还是 `ready=true`
  3. 体感像“恢复逻辑认为坏了，readiness 却还是假健康”
- **当前结论**:
  1. 先区分“同一进程内刚发生的失败”还是“重启后的历史失败”
  2. 当前临时不可用状态是 **进程内 TTL**，不是持久化黑名单
  3. 因此：
     - 同一进程内再次查 readiness，应该看到 `runtime_available=false`
     - 进程重启后，这个状态会丢失，worker 会重新按 provider/tool 检查回到 `ready`
- **当前源码收口点**:
  1. `WorkerRegistry.checkReadiness(...)` 现在会额外返回 `runtime_available`
  2. `ControlNodeGraph` 在 auto-handoff 前会把失败 worker 标记为 temporary unavailable
  3. route / readiness / recovery 现在共用这一份状态，不再各看各的
- **当前验证入口**:
  1. `AgentProviderSupportTest.workerRegistryTemporaryUnavailabilityOverridesProviderReadiness()`
  2. `AgentProviderSupportTest.dispatchReadinessRunsProviderPreflightAndMarksWorkerTemporarilyUnavailableOnFailure()`
  3. `ControlNodeGraphActionResolutionTest.maybePlanFailureRecoveryAvoidsHotFailingProviderWhenAlternateProviderExists()`
     - 该用例同时确认 auto-handoff 会把原 worker 的 `runtime_available=false` 写进 readiness
- **排查顺序**:
  1. 先看 `.tmp/server-*.out.log` 是否出现：
     - `Worker marked temporarily unavailable`
     - `skip candidate worker=... readinessReason=temporarily unavailable`
  2. 再查 `GET /api/v1/workers/{id}/readiness`
  3. 如果刚失败但仍是 `runtime_available=true`，确认是否已经重启过 harness
  4. 如果业务上需要“跨重启保留不可用状态”，那已超出当前实现边界，应单独设计持久化降级/熔断策略

### 2.0.5 怎么确认 worker 是否真的适合自动执行，而不是只适合推荐

- **典型场景**:
  1. 新增或调整了 provider worker
  2. `/api/v1/workers/{id}/readiness` 看起来正常
  3. 但实际执行时仍可能因为命令形态、输出格式、工作区访问或恢复语义不匹配而失败
- **当前结论**:
  1. 不要只看 `ready=true`
  2. 还要看 worker 的 capability matrix metadata
  3. 这些字段现在通过 `GET /api/v1/workers` 直接可见
- **快速检查命令**:

```powershell
curl "http://localhost:8080/api/v1/workers"
```

- **关键字段**:
  1. `metadata.execution_backend`: 当前执行后端，常见值为 `provider_app_server`、`provider_native_cli`、`tool_aware`、`unsupported`
  2. `metadata.command_shape`: harness 预期使用的命令形态，例如 `codex app-server --listen stdio://`
  3. `metadata.input_mode`: prompt 输入方式，例如 `json_rpc`、`argv_prompt`、`stdin_jsonl`、`tool_request`
  4. `metadata.output_mode`: 输出解析方式，例如 `json_rpc_events`、`stream_json`、`json`、`tool_result`
  5. `metadata.output_contract`: executor 期望落库的输出合同
  6. `metadata.workspace_access_mode`: worker 如何访问本地工作区，例如 `codex_app_server_cwd`、`native_cli_cwd`、`native_cli_workspace_arg`
  7. `metadata.local_workspace_access`: 是否允许自动接需要读写本地 repo 的 coding/ops 任务
  8. `metadata.recovery_resume_policy`: 失败恢复时是否允许复用 provider session
  9. `metadata.supports_resume`: provider/native worker 是否声明支持 resume
  10. `metadata.side_effect_risk`: 自动执行风险分级
- **判断口径**:
  1. `execution_backend=unsupported` 的 worker 不能自动执行，`WorkerExecutorRouter` 会 fail fast
  2. `local_workspace_access=false` 的 worker 不应自动接带 `workspace_root / repo_path / cwd` 或本地代码路径信号的 `coding/ops` 任务
  3. `suggest_only=true` 的 worker 即使出现在列表里，也应优先理解为推荐候选，而不是默认自动执行目标
  4. 如果 `command_shape` 与本机实际 CLI 帮助不一致，应先修 provider 命令计划，不要靠重试掩盖
- **当前验证入口**:
  1. `AgentProviderSupportTest.workerRegistryEnrichesWorkerCapabilityMatrixFields()`
  2. `ApiErrorContractHttpTest.listWorkersExposesCapabilityMatrixMetadata()`
  3. `WorkerExecutorRouterProviderNativeTest.unsupportedBackendFailsFastInsteadOfFallingBackToDefault()`
  4. `WorkerRouterRouteTraceTest.pinnedWorkerWithoutWorkspaceAccessCannotOverrideLocalWorkspaceRequirement()`
  5. `WorkerRouterRouteTraceTest.localWorkspaceOpsTaskRejectsCandidateWithoutWorkspaceAccess()`

### 2.0.6 配了 `providers.yaml` / `providers.json`，但新 provider 没有按预期执行

- **典型表现**:
  1. 已在当前目录、`config/` 或 `${user.home}/.agentcloud/` 放了 `providers.yaml`
  2. 重启 harness 后，看不到对应 provider 行为变化
  3. 或 `/api/v1/workers` 能看到新 worker，但 readiness 显示 not ready
- **当前支持范围**:
  1. `ProviderProtocolDiscovery` 只动态注册 generic native CLI provider
  2. 支持 `protocol: native_cli_text|native_cli_json|native_cli_lines|native_cli_stream_json`
  3. 支持 `command` 完整命令；也支持 `binary + args`，其中 `binary` 是启动目标，task prompt 会自动追加到参数末尾
  4. 支持 `env`，也兼容旧的 `type: generic` + `command`
  5. 新 `id` 会在启动期注册到 `/api/v1/agents` 和 `/api/v1/workers`，并进入 provider-native 路由候选
  6. `native_cli_stream_json` 只是按行保留输出，不等于 Claude/Cursor 专用 stream-json parser
  7. `app_server_json_rpc`、`mcp`、未写 protocol 的自动探测当前不是 dynamic generic discovery 能力
- **快速检查**:

```yaml
providers:
  - id: trae
    protocol: native_cli_text
    binary: trae
    args: ["chat", "--mode", "agent"]
    env:
      TRAE_MODE: local
```

- **排查顺序**:
  1. 先确认配置文件位置在当前启动工作目录、`config/` 或 `${user.home}/.agentcloud/`
  2. 重启 harness；discovery 只在启动时读取配置
  3. 如果配置的是 `codex app-server`，不要期望它通过 generic discovery 生效；Codex 仍走内置 app-server executor
  4. 如果配置了全新 `id`，先确认 `/api/v1/workers` 和 `/api/v1/agents` 是否有对应条目；没有则说明 harness 没读到配置或需要重启
  5. 再查 `/api/v1/workers/{id}/readiness`；如果 `provider:<id>=false`，优先处理 binary 路径、认证或 dispatch preflight
  6. 如果任务仍没有路由到该 worker，检查 task type 是否落在配置的 `capabilities` 内，以及该 worker 是否 ready
  7. 如果需要查看 provider 输出，优先查 artifact metadata 里的 `provider_run_dir / provider_last_message_path / provider_stdout_path / provider_event_log_path`
  8. 也可通过 `GET /api/v1/tasks/{id}/provider_run_file?kind=last_message|stdout|events|metadata|prompt` 读取受控 run 文件；排查长 `events.jsonl` / `stdout.log` 时可加 `tail=true&max_lines=50` 只看尾部窗口
  9. 如果要确认文件尾部是否仍在变化，优先改用 `stream=true` 或请求头 `Accept: text/event-stream`，观察 `provider_run_file.snapshot` 和后续 `provider_run_file.update`；这条读面验证的是“尾部窗口内容变化是否能被观测到”，不是 token 级 stdout streaming
- **已验证的真实 smoke**:
  1. 在临时工作目录放 `providers.yaml`，使用独立端口和临时 DB 启动 harness
  2. `/api/v1/agents` 返回 `smoke_agent`，metadata 含 `provider_discovery=true`、`configured_from=providers.yaml`
  3. `/api/v1/workers` 返回 `smoke_agent`，`ready=false`、`capabilities=["coding","research"]`
  4. `/api/v1/workers/smoke_agent/readiness` 返回 `executor_backend:provider_native_cli=true`、`provider:smoke_agent=false`、`reason=binary not found: smoke-agent-missing-binary`
  5. 该 smoke 同时覆盖 Windows PowerShell 写出的 UTF-8 BOM YAML；discovery 会剥离文件头 BOM 后解析 `providers:`

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -SkipTests -QuietMaven
node .\scripts\provider-discovery-smoke.js --port 18432 --report .\.tmp\provider-discovery-smoke\report.json
```

### 2.0.1 `/dialogue/` 里为什么“聊天”现在会直接进入 task，而不是只记一条 session note

- **当前位置**: `/dialogue/` 主 composer, `ChatFacadeService`
- **当前口径**:
  1. `/dialogue/` 的默认“聊天”现在等于 `task_auto`
  2. 如果当前上下文里已经有 task，这轮输入会作为该 task 的 continuity turn
  3. 如果当前只有 session，没有 task，这轮输入会 materialize 成新 task
- **为什么这样改**:
  1. 真实项目场景里，用户通常希望“聊天即推进任务”，而不是先收到一条“已记录到会话”的空回执
  2. `message_only` 仍保留在 façade/API 层，但不再是 `/dialogue/` 主路径

### 2.0.1.a `task_auto` 后端已经建了 task，但页面还没切到这个 task

- **典型表现**:
  1. `/v1/chat/completions` 这轮用户输入已经被写成 `task_brief`
  2. `/api/v1/sessions/{id}/tasks` 也已经出现新 task
  3. 但 `/dialogue/` 当前 hash 还停在只有 `session=`，没有及时带上新的 `task=`
- **当前结论**:
  1. 这不表示后端没 materialize task
  2. 更准确地说，这是前端没有及时追上新 task 选择
  3. 若 provider 执行链较长、请求尚未返回，这个 gap 会更明显：页面可能一直停在 session-only shell，直到 façade 响应结束
- **更合理的目标行为**:
  1. 页面先显示 `task_pending` 回执
  2. 然后在请求未结束时，短轮询同 session 的 task 列表
  3. 一旦发现新的 unseen task，就立刻把当前 selection/hash 追过去
- **排查顺序**:
  1. 先查 `sessions/{id}/messages`，确认这一轮是不是 `task_brief`
  2. 再查 `sessions/{id}/tasks`，确认新 task 是否已经存在
  3. 只有这两步都没结果时，才继续怀疑 `task_auto` 语义失效

### 2.0.1.b `继续当前任务` 看起来像生效了，但其实又新建了一条 task

- **典型表现**:
  1. 页面上已经勾了“继续当前任务”
  2. 但提交后 `sessions/{id}/tasks` 里出现的是一条新 task
  3. 原 task 的 `live_flow` / related messages 里只看到一条 `continuity_scope=session` 的 `task_brief`
- **当前结论**:
  1. 这不表示后端不会继续当前任务
  2. 更准确地说，是前端请求没有把当前 task continuity 正确绑定进 façade 请求
  3. 因此前端体感像“继续当前任务”，实际落库却仍是“materialize 一条新 task”
- **更合理的目标行为**:
  1. 只要当前已选中 task，且用户勾了“继续当前任务”
  2. 这轮输入就应该绑定到当前 task
  3. 即使页面表面仍使用 `task_required` 风格文案，也不应再 materialize 新 task
- **排查顺序**:
  1. 先查 `sessions/{id}/tasks`，确认是否真的新增了 task
  2. 再查原 task 的 `live_flow.related_messages`，确认这轮输入是不是只作为 `continuity_scope=session` 的 `task_brief`
  3. 最后再回看前端请求分流，重点看 `continueCurrentTaskId / task_id / task_mode`

### 2.0.1.c `manual-start continuity` 页面看起来像 continuity，但请求里还是 `task_auto`

> 历史回归点：这条 seam 已收口。当前若在 fresh 样本里再次出现，才应按下面步骤重新排查。

- **典型表现**:
  1. 页面已切到 `新任务`
  2. 已勾选“继续当前任务”并关闭 `auto_start`
  3. 但真实 façade request body 仍是 `metadata.task_mode=task_auto`
- **历史结论 / 正确 contract**:
  1. 这不是 probe 误判
  2. 当时的问题是前端 **continue-current task-mode regression**
  3. 正确 contract 应是：
     - `task_mode = task_required`
     - `task_id = 当前 selected task`
     - `auto_start = false`
- **排查顺序**:
  1. 先查真实请求体里的 `metadata.task_mode`
  2. 若已勾选“继续当前任务”后仍是 `task_auto`，优先检查前端 `composerTaskMode()` / `shouldContinueCurrentTask()` 分支
  3. 不要先怀疑后端 continuity contract

### 2.0.1.d `continue-current note` 在 smoke 里超时，但真实请求与落库都已经对

> 历史 smoke-driver seam：当前 `18386` fresh 样本里这条路径已重新为绿。只有后续 fresh 复现时，才继续按下面口径归类。

- **典型表现**:
  1. `scripts/dialogue-business-smoke.js` 卡在 `submit continue-current note`
  2. 页面 hash 和 detail title 看起来没有跳错
  3. 但脚本仍超时
- **历史结论 / 当前归类原则**:
  1. 这不应先归类为产品功能回退
  2. 若真实 façade request body 已经是：
     - `task_mode = task_required`
     - `task_id = 当前 selected task`
     - `auto_start = false`
  3. 且 `sessions/{id}/messages` 已出现：
     - user `task_note`
     - assistant `已记录到当前任务上下文，等待手动继续。`
  4. 那当前更准确的归类就是 **smoke-driver message convergence seam**
- **更合理的 smoke contract**:
  1. 继续当前任务时，优先验证请求体与落库消息类型
  2. 不要把“主聊天流条数变化”当成唯一成功条件
  3. 页面若保持同一 `task=` hash、同一 detail title，且 inline ack/任务消息已到位，就应视为通过

### 2.0.1.e manual-start 后 hash 已切新 task，但 detail 还停旧 task，接着 `continue-current` 又新建了一条 task

> 历史真实业务 seam：这条 selected-task/detail drift 已收口。当前若在长寿实例里再次复现，先确认是否仍在看旧 runtime。

- **典型表现**:
  1. manual-start 提交后，URL hash 已经切到新 `task=...`
  2. 但 `detailTitle` 仍显示上一条 task
  3. 再勾选“继续当前任务”提交后，又新建了一条 manual-start task
- **历史结论 / 现象解释**:
  1. 这不是单纯 smoke-driver 观察面问题
  2. 当时的问题是前端 **selected task / detail context drift**
  3. 后续这轮 `continue-current` 会被错绑到“当前 detail 仍指向的旧 task”或直接退化成新 task create path
- **真实判据**:
  1. 看 `sessions/{id}/tasks` 是否又新增 task
  2. 看 `sessions/{id}/messages` 最新两条是否变成：
     - user `task_brief`
     - assistant `task_receipt`
  3. 如果是，就说明这轮已经不是 continuity note，而是被错误走到了 manual-start create path

### 2.0.1.f `continue-current note` 已经业务正确，但 smoke 仍失败

> 历史验证口径漂移：这条已收口到当前 smoke contract。现在优先以 `task_note + chat_reply` 的 task-scoped 回执判断红绿。

- **典型表现**:
  1. 页面 inline 已出现 `已写入当前任务上下文`
  2. 当前 hash 仍保持同一 `task=...`
  3. `sessions/{id}/messages?task_id=...` 已经出现：
     - user `task_note`
     - assistant `chat_reply`
  4. 但 `scripts/dialogue-business-smoke.js` 仍报红
- **历史结论 / 当前归类原则**:
  1. 先查 smoke 是否读错了消息字段名
  2. 当前 API 主字段是 `message_type`，不要只读 `type`
  3. assistant ack 的 inline 文案也不再只会是 `已记录/已推进/已完成`
  4. `已写入当前任务上下文` 也应视为成功回执
  5. 更稳的通过标准应以 task-scoped `task_note + chat_reply` 为主，inline 只作辅助；如果回执链已经对了，就不要再让某一条 inline 文案决定整步红绿

### 2.0.1.g 长寿实例里，manual-start 明明已选中，过几秒又被旧 auto-start task 抢回去

> 历史长寿实例 seam：当前已通过短时 `selected task` stickiness 收口，并在 fresh-restart 真实 `8080` 上复验过。

- **典型表现**:
  1. `manual-start` task 刚创建后，hash / active task card / detail title 已经对齐
  2. 过几秒后，早先 `auto-start` task 的晚到 `task_progress / task_result` 刷新把 selected task 抢回去
  3. 紧接着再发 `task note attach / continue-current / follow-up`，请求会重新绑到旧 task
- **历史结论 / 当前归类原则**:
  1. 这类问题本质上是前端 **selected-task late-refresh drift**
  2. 不应先怀疑后端没落 `task_note / task_followup`
  3. 如果 API/数据库里 `task_note` 实际已经落到 `manual-start` task，而页面 selected task 还是跳回旧 task，优先查 `/dialogue/` 的 polling / task-selection 逻辑
- **排查顺序**:
  1. 查 `GET /api/v1/sessions/{id}/messages?limit=120`，确认 `task_note.task_id` 实际落到哪条 task
  2. 查 `GET /api/v1/sessions/{id}/tasks`，确认 `manual-start` task 是否仍存在
  3. 若 API 已对、页面却选回旧 task，优先检查：
     - `loadTasks()`
     - `reconcileTaskSelection(...)`
     - 5 秒 polling 刷新
  4. 当前页面已补一层短时 `selected task` stickiness；若源码已修但 `8080` 仍复现旧行为，先做 `build + fresh restart`

### 2.0.2 `/dialogue/` 里出现乱码，但 details/live_flow 也没有真正结果，应该怎么理解

- **现象**: 主聊天流里出现类似 `����: ...` 的 `task_progress / task_result`
- **不要误判**:
  1. 这通常不是前端自己编码坏了
  2. 更常见的是当前 worker/provider 返回了不可读失败输出，前端只是把摘要投影出来
- **当前 UI 原则**:
  1. 主聊天流优先显示可读失败摘要
  2. 原始失败 trace 下沉到 `details / live_flow / judgment_trace`
- **当前编码口径**:
  1. 仓库文件、HTTP、前端静态资源继续严格使用 UTF-8
  2. 只有外部进程输出（shell/cmd/powershell/git/provider-native cli）走“UTF-8 优先 + 自适应兜底”
  3. 详细策略见 `docs/TEXT_ENCODING_COMPATIBILITY_PLAN.md`

### 2.0.2.b 真实 task 页看起来只有摘要，像是没拿到 agent 返回结果

- **典型表现**:
  1. 页面已经选中了某个真实 task
  2. 用户点了“继续推进”
  3. 主聊天流里只看到一条简短 `task_progress / task_result`
  4. 体感像“没拿到 agent 返回结果”，或者必须手点刷新才出现新结果
- **先不要误判**:
  1. 这不一定是 agent 没返回结果
  2. 先查 `/api/v1/sessions/{id}/messages?task_id=...&limit=20`
  3. 如果最新 `task_progress / task_result` 的 `metadata` 里已经有：
     - `summary_preview`
     - `full_content`
     - `output_text`
     - `artifact_content`
     那就更应优先归类为 **前端结果可见性 / 活跃任务轮询 seam**
- **更合理的目标行为**:
  1. 选中 active task 时，`/dialogue/` 应对当前 task 采用更短周期的结果轮询
  2. 最新 `task_progress / task_result` 进入主聊天流时，默认仍可先显示摘要
  3. 但若后端已提供 `full_content / output_text / artifact_content`，页面必须明确给出“展开完整结果”入口，并让最新结果卡更容易被看到
  4. 若最新 worker artifact 的 `output_text / artifact_content` 为空，但 `failure_summary_readable` 已存在，后端也不应生成空壳 `full_content`；应回退拼上这条可读失败摘要
  4.1 即使当前消息还没有显式 `full_content / output_text / artifact_content`，只要已有 `failure_summary_readable`，前端也不应因此失去“展开完整结果”入口；最少应能展开出 `失败摘要`
  4.2 同样地，如果当前 message 自己的 `full_content` 只是旧的 `Worker Output / Artifact Content` 空壳，而 metadata 里已经有更可读的 `failure_summary_readable`，前端展开态也不应继续照抄旧壳；应优先展开成 `失败摘要 (+ 下一步)`
  5. 如果数据库里旧 `task_progress.full_content` 已经持久化成空壳，而当前 `live_flow.task.metadata.failure_summary_readable` 与恢复元数据更完整，前端在“当前选中的 task thread”里也不应继续盲信旧消息；应优先回退到当前 live flow / task metadata 的可读失败摘要与恢复状态
  6. 如果 `output_text / artifact_content` 本身就是长噪声或 mojibake，而 `failure_summary_readable` 已经更干净，后端也不该在 `full_content` 里继续原样拼回这两段；否则前端展开后还是会重新看到脏结果
  7. 同样地，`task.summary` 自身也不应继续保留旧的 provider/runtime 噪声；如果当前轮已经有更干净的 `failure_summary_readable`，后端更新 task 快照时应优先写回这条短失败摘要，避免第一页 task bubble、continuity summary、modal details 继续先吃旧乱码
  8. `/dialogue/` 的 `Harness` bubble 与 details/modal 也不应把 `active_context.continuity_summary` 或 `task.summary` 放在 `failure_summary_readable` 之前；只要当前 focused task metadata 已经有更干净的失败摘要，第一页就应先显示这条短摘要，再把旧 trace 继续留在 details / live_flow / artifact

### 2.0.2.c 点击“展开完整结果”后，过几秒又自动收回

- **典型表现**:
  1. 用户点击了主聊天流里 `task_progress / task_result` 卡片的“展开完整结果”
  2. 完整正文已经展开
  3. 约 5 秒后页面轮询刷新，卡片又自动回到折叠态
- **当前结论**:
  1. 这不是可接受行为
  2. 如果后端 `messages` 接口里完整结果仍在，而页面只是重新折叠，应优先归类为 **前端 message-card expanded state lost on polling seam**
  3. 这不应被误判成“agent 没返回结果”或“后端刷新时把正文清掉了”
- **更合理的目标行为**:
  1. 用户手工展开后的 message card，在同一 message id 未变化的前提下，轮询刷新后必须保持展开
  2. 只有用户手工再次收起，或消息本身被替换/移除时，页面才可以折叠
  3. 如果页面只是重新 render 同一条消息却丢了展开态，说明展开状态被做成了瞬时 DOM state，而不是绑定到 message identity
  4. 下方 active task thread 的 `展开完整结果` 也应遵守同一条原则；如果它绑定的是 `task id`，就不能再被“只保留可见 message id”的清理逻辑误删

### 2.1 服务启动失败，提示无法打开数据库

- **典型表现**: 启动阶段抛出 SQLite 或文件权限相关异常。
- **可能原因**:
  1. `${user.home}\\.agentcloud\\` 不可写
  2. `schema.sql` 未打入资源包
- **排查步骤**:
  1. 确认 `${user.home}\\.agentcloud\\` 目录存在且可写。
  2. 检查打包后的 JAR 是否包含 `schema.sql`。
  3. 查看控制台或 `server.err.log`。

### 2.2 关键迁移后 packet / checkpoint 不符合预期

- **典型表现**: `/pause`、`/escalate` 或 `/handoff` 返回成功，但 `resume_packets` / `checkpoints` 中固化内容和期望不一致。
- **可能原因**:
  1. 任务本身缺少足够的 `decision/artifact/event` 轨迹，packet 只能生成空摘要
  2. 调用的是 `handoff_packet` 预览接口，而不是正式 `/handoff`
  3. 看的是旧 packet，没有刷新当前 task 最新记录
- **排查步骤**:
  1. 先查 `resume_packets` 是否新增记录。
  2. 再查 `checkpoints` 表中的 `checkpoint_type` 是否为 `pause_before`、`escalate_before`、`handoff_before` 或 `halt_before`。
  3. 再看 `/api/v1/tasks/{id}/runtime_context` 与 `/judgment_trace` 是否已有输入轨迹。

### 2.3 Worker readiness 正常，但路由结果不符合预期

- **典型表现**: 任务分配给 fallback worker，或没有按预期命中某个 planner/executor。
- **可能原因**:
  1. 任务 `metadata.task_type` 缺失，路由回落到 `general`
  2. worker 虽 `ready=true`，但 capability 不包含目标任务类型
  3. `model_mode=small_only/strong_only/orchestrated` 收窄了候选 tier
  4. learning memory preferred worker hint 未命中当前 tier，因而触发 fallback
  5. worker 声明了 `git/shell/powershell/cmd`，但 `/readiness` 里的 `tool:<name>` 检查失败，导致它被排除在 ready 候选集之外
- **排查步骤**:
  1. 查看任务 `metadata_json` 中的 `task_type`、`model_mode`。
  2. 调用 `/api/v1/workers` 和 `/api/v1/workers/{id}/readiness`，重点看 `metadata.host_tool_availability` 与 `checks.tool:<name>`。
  3. 再查 `/api/v1/tasks/{id}/select_worker`，看 `route_source/why_selected/fallback_reason`。
  4. 如果是 orchestrated 流程，再查 `/api/v1/tasks/{id}/live_flow` 看当前 stage。

### 2.3.1 `/dialogue/` 页面里看不出当前是哪个 worker 在执行

- **典型表现**:
  1. 任务已进入 `scheduler / continue`
  2. `live_flow.route_preview`、`provider_selection` 或事件流里已经能看到 `selected_worker`
  3. 但页面主视图第一屏仍只显示 `unassigned`，或者必须展开 route drawer 才知道是谁在跑
- **当前结论**:
  1. 这属于 **worker visibility seam**，不是“没有选中 worker”
  2. active task 的主视图应该优先展示当前 route/provider worker，而不是只依赖 `task.assigned_worker`
- **排查步骤**:
  1. 先查 `/api/v1/tasks/{id}/live_flow`，确认 `route_preview.selected_worker`、`provider_selection.selected_provider` 是否已存在
  2. 再查 `tasks.assigned_worker` 和 `events` 表中的 `worker_round / node_scheduler / node_continue`
  3. 如果后端证据已存在而页面第一屏仍不可见，应优先改 `/dialogue/` 的 overview / focus line / thread bubble 展示，而不是继续追后端

### 2.3.2 task thread 里只显示 `failed`，看不到最近一轮 worker 输出

- **典型表现**:
  1. `session messages` / `live_flow.related_messages` 里已经有最近一条 `task_progress / task_result`
  2. 其中 `content / summary_preview / full_content` 已经带出了更完整的结果或失败上下文
  3. 但 task thread 里的 `Harness` bubble 仍只显示 `failed / done / active` 这类单词
- **当前结论**:
  1. 这属于 **task-thread outcome preview seam**
  2. thread 里的结果预览应优先消费最近一条 task outcome message，而不是只吃 `task.summary`
  3. 如果 `runtime_context.active_context.continuity_summary = failed`，但最近 `task_progress / task_result.content` 已经带有更完整叙述，前端不应再让 terse continuity summary 覆盖主气泡正文
- **排查步骤**:
  1. 先查 `GET /api/v1/sessions/{id}/messages?task_id={taskId}`，确认最近 task outcome message 的 `content / summary_preview / full_content`
  2. 再查 `GET /api/v1/tasks/{id}/live_flow`，确认 `related_messages` 是否已经带出同一条 message
  3. 如果消息层已有完整预览而 thread 仍只显示单词摘要，应优先修改 `/dialogue/` 的 task bubble preview 逻辑

### 2.3.3 真实任务页第一页有输出，但被历史长噪声失败摘要污染

- **典型表现**:
  1. 当前 task 的第一页已经不再只剩 `failed`
  2. 但 `thread output` 里出现大段 provider 原始报错、prompt echo、目录 listing，或者夹杂 mojibake
  3. 用户体感仍然是“不直观”“像没拿到真正结果”
- **当前结论**:
  1. 这通常不是“前端没轮询到结果”
  2. 更准确地说，是历史持久化下来的 `failure_summary_readable` 本身质量太差，而第一页又把它原样铺开
  3. 这种情况应优先归类为 **historical failure-summary noise seam**
- **更合理的目标行为**:
  1. thread 第一屏只显示短可读失败摘要，例如 `worker claude failed: thread not found (19120)`
  2. `failure_class / retry / handoff / human_gate` 继续留在 signal/recovery 行
  3. 原始长文本继续下沉到 `details / live_flow / artifact`
- **排查步骤**:
  1. 先查 `GET /api/v1/tasks/{id}/live_flow`，确认 `task.metadata.failure_summary_readable`
  2. 如果它本身就是长噪声，先区分是“新失败刚生成”还是“历史旧数据”
  3. 新失败优先查后端失败摘要清洗；历史旧数据优先查 `/dialogue/` 显示层是否已做短摘要压缩

### 2.3.4 transcript 上半区只显示 `failed`，看不出哪轮 worker 的结果

- **典型表现**:
  1. 任务 thread 下半区已经能看到 `worker claude` 和短结果预览
  2. 但上半区 `messageList` 里的最新 `task_progress` 仍只有 `failed`
  3. 用户必须点 `>` 或切到 details 才知道是哪轮 worker 跑出来的
- **当前结论**:
  1. 这属于 **message-card outcome visibility seam**
  2. `task_progress / task_result` 这种过程回执不该只当普通消息摘要卡
  3. 默认折叠态也应先露出 `worker + 当前状态/控制节点 + 短结果预览`
- **排查步骤**:
  1. 先查对应 message 的 `metadata.selected_worker / assigned_worker / summary_preview / full_content`
  2. 如果 metadata 已齐全但卡片仍只显示 `failed`，优先改 `/dialogue/` 的 `renderMessageCard / messageCardBody`
  3. 若卡片展开后正文完整、折叠态却失真，说明是前端默认摘要策略问题，不是后端没回结果

### 2.3.5 transcript 主卡已经有 worker 条带，但结果预览还是只有 `failed`

- **典型表现**:
  1. 上半区 `task_progress` 卡已经能看到 `worker claude`
  2. 但条带右侧和正文仍只有 `failed`
  3. 同一页下方 task thread 却已经能显示 `thread not found (19120)` 这类更可读摘要
- **当前结论**:
  1. 这通常说明历史 `session message.metadata` 太旧，而当前 `live_flow.task.metadata` 已经更完整
  2. 更准确地说，这是 **message-card stale metadata seam**
  3. 对“当前选中的 task”来说，transcript 主卡应允许借用当前 `live_flow.task.metadata.failure_summary_readable` 做显示层回填
  4. 这条回填不应只依赖 `selectedTaskId`；如果页面当前已经聚焦到同一条 `live_flow.task`，主卡也应允许直接借这条 focused task 的最新 outcome projection 做纠偏
- **排查步骤**:
  1. 先查 `GET /api/v1/sessions/{id}/messages?task_id=...`，看历史 message metadata
  2. 再查 `GET /api/v1/tasks/{id}/live_flow`，看 `task.metadata.failure_summary_readable`
  3. 如果两者不一致且 `live_flow` 明显更完整，优先改主卡显示层 merge / focused-outcome projection 逻辑，而不是先怀疑轮询或展开逻辑

### 2.3.6 transcript 主卡已经可读，但第一屏结果摘要过长

- **典型表现**:
  1. 主卡已经不再只显示 `failed`
  2. 但折叠态正文或 outcome strip 里把 `recovery detail + next step` 全部拼进去
  3. 用户第一眼能看懂失败原因，但视觉上仍然像一大段状态播报
- **当前结论**:
  1. 这通常不是后端问题，而是 **message-card compact-summary density seam**
  2. 主卡折叠态应优先保留 `worker + short failure/result`
  3. `failure_class / retry / handoff / human_gate / next step` 应继续留在 hint 或展开正文
- **排查步骤**:
  1. 先看主卡 `body / outcome strip / hint` 三处分别显示了什么
  2. 如果 `body` 和 `outcome strip` 已经带出短失败原因，再检查 recovery detail 是否仍被重复拼进同一行
  3. 若是，优先改 `/dialogue/` 的 compact preview 选择逻辑，而不是再动后端 message payload

### 2.3.7 第一扫看得出失败，但还是看不出“谁在执行、跑到哪一轮”

- **典型表现**:
  1. 页面已经不再只显示 `failed`
  2. 但 `worker` 只混在 badge、hint 或 overview 卡里
  3. 用户第一眼仍然觉得“像没看到真正执行面板”
- **当前结论**:
  1. 这属于 **worker execution-visibility seam**
  2. 问题不是后端没选 worker，而是第一页没有把 `worker / status / short output` 收成稳定的执行条带
  3. 更合理的目标行为是：transcript 主卡和 active task thread 默认都直接露出这三项
- **排查步骤**:
  1. 先查 `GET /api/v1/tasks/{id}/live_flow`，确认 `route_preview / provider_selection / task.metadata` 是否已经有 worker 和状态
  2. 如果后端已有，而第一页仍要靠 hint 或 details 才看得出来，优先改 `/dialogue/` 的 `renderMessageCard / renderThreadTask`
  3. 若第一页已能看到 worker，但 output 仍只有 `failed / done`，继续归到 summary-preview 选择逻辑排查

### 2.3.7a worker 已可见，但 task thread 里没有明确的 `最近输出` panel

- **典型表现**:
  1. 页面已经能看到 `worker claude` 这类执行 worker
  2. 但下半区仍只有一段 `Harness` 正文，没有独立的 `最近输出` label / short result block
  3. 用户第一眼还是难以判断“这是不是本轮 worker 的实际输出”
- **当前结论**:
  1. 这属于 **round-output visibility seam**
  2. 问题不是后端没回结果，而是第一页没有把 worker output 收成显式 output panel
  3. 更合理的目标行为是：`执行中/最近执行` 和 `最近输出` 分成两层稳定条带
  4. 如果这两层已经出现，但每层仍是一整句长文本，那还只是“信息挤出来了”，不算真正的执行面；应继续把 `worker / status / short result` 做成更清晰的 headline/detail
- **排查步骤**:
  1. 先看 `renderThreadTask` 是否已经拿到了 `assistantOutputPreview / assistantOutputFullContent`
  2. 如果数据已在，但页面仍只有普通正文，没有 output label，优先改 thread output 的结构和样式，而不是先怀疑 live_flow
  3. 若 output label 已有，但短结果仍只是 `failed / done`，再回到 failure-summary 选择逻辑继续排查

### 2.3.8 worker 条带已经有了，但 transcript 第一眼还是先看到 task brief

- **典型表现**:
  1. 下半区 active task thread 已经能看到 `最近执行 worker ...`
  2. 上半区 `task_progress` 卡本身也已经有执行条带
  3. 但 message list 顺序里，用户第一眼还是先看到 user `task brief`，最新一轮 worker 结果并没有被真正前置
- **当前结论**:
  1. 这属于 **latest-round output not pinned seam**
  2. 问题不是 message card 渲染错，而是 transcript 顶部缺一块“当前 task 最近一轮结果”的 pinned surface
  3. 更合理的目标行为是：只要当前已有 selected task，就在 transcript 顶部先钉一块 `latest round output`
- **排查步骤**:
  1. 先查 selected task 是否已经有 `task_progress / task_result`
  2. 如果有，且顶部 `messageSummary` 仍只显示 role-summary，不显示当前 task 的最新 round output，优先改 transcript summary 区
  3. 若 pinned output 已出现，但内容仍旧是 `failed / done`，再回到 outcome projection / failure summary 清洗链排查

### 2.3.9 顶部 pinned latest-round output 设计上已加，但真实页仍不出现

- **典型表现**:
  1. 右侧状态线和下方 active task thread 都已经显示当前 worker / 结果
  2. 上方 transcript 顶部理论上已有 pinned latest-round output 设计
  3. 但真实页里 `messageSummary` 仍只出现 role-summary，看不到 pinned output
- **当前结论**:
  1. 这通常不是“没有最新结果”，而是 **pinned output over-coupled to live-flow seam**
  2. 更准确地说，是顶部摘要被过度绑定到了 `live_flow.task` 完整挂载
  3. 更合理的目标行为是：只要当前 selected task 已确定，且消息里已有 `task_progress / task_result`，顶部 pinned output 就应先显示
- **排查步骤**:
  1. 先看 selected task 是否已经稳定，且对应 `task_progress / task_result` 已经在 session/related messages 里出现
  2. 如果这些都在，但 pinned output 仍为空，优先查 summary helper 是否对 `live_flow.task` 依赖过强
  3. 修正后再复看真实页，确保顶部 pinned output 能在 `live_flow` 晚到时仍先落下来

### 2.3.10 顶部 pinned latest-round output 已出现，但正文太长还重复 recovery detail

- **典型表现**:
  1. transcript 顶部已经出现 pinned `latest round output`
  2. 但 card body 同时拼了失败摘要、恢复状态、下一步
  3. footer 里又再重复一遍 `failure / retry / handoff`
- **当前结论**:
  1. 这属于 **pinned-output compact-summary seam**
  2. 不是缺数据，而是 pinned card 误用了 full preview
  3. 更合理的目标行为是：body 只留 `worker + short failure/result`，recovery detail 留在 foot
- **排查步骤**:
  1. 先看 pinned card body 是否直接吃了 full outcome preview
  2. 如果 body 已包含 `恢复状态 / 下一步` 这类长信息，优先改 pinned summary helper，换成 compact preview
  3. 再复看真实页，确认 pinned body 和 foot 不再重复

### 2.3.10a 顶部 pinned latest-round output 已有 execution/output 条带，但正文还在重复同一句短结果

- **典型表现**:
  1. pinned 区已经有 `执行中/最近执行` 条带
  2. 也已经有独立的 `最近输出` 条带
  3. 但正文仍把同一句 `worker xxx failed ...` 再重复一遍，导致第一屏像三层重复摘要
- **当前结论**:
  1. 这属于 **pinned body duplicate seam**
  2. 问题不是数据不够，而是 pinned body 没有在 output strip 已存在时主动退成 fallback
- **排查步骤**:
  1. 先看 pinned output 是否已经有独立 outcome strip
  2. 如果有，优先让正文退成补充上下文或直接隐藏
  3. 不要再继续往 pinned body 里重复同一句短失败摘要

### 2.3.11 页面能看出恢复阶段，但看不出“为什么这次 auto handoff / human_gate”

- **典型表现**:
  1. 页面已经能看到 `recovery · auto_handoff_scheduled` 或 `recovery · human_gate_required`
  2. 但看不到对应的 `failure_class`
  3. 用户知道系统做了什么，却不知道为什么这么做
- **当前结论**:
  1. 这属于 **failure-class visibility seam**
  2. 如果后端已细分 `worker_runtime_transient / task_environment_blocked / worker_backend_deterministic / partial_result_or_quality_risk`
  3. 前端第一页就应直接露出这条分类，而不是只把恢复阶段丢给用户猜
- **排查步骤**:
  1. 先查 `live_flow.task.metadata.failure_class`
  2. 如果后端已有，但第一页 pinned card / message card 只显示 `recovery_stage`，优先改 UI 可见性，不要先怀疑恢复链没生效
  3. 若 `failure_class` 为空，再回后端分类链排查

### 2.3.12 页面已经露出 failure/recovery，但还是原始枚举串

- **典型表现**:
  1. pinned output 或 `task_progress` 主卡已经能看到 `failure_class / recovery_stage`
  2. 但第一页直接显示 `worker_runtime_transient / human_gate_required`
  3. 用户虽然能看出系统判成了哪类失败，但第一眼仍然像调试字段，而不是产品态结果
- **当前结论**:
  1. 这属于 **failure-token humanization seam**
  2. 不是后端没分类，而是前端直接把原始 token 当文案用了
  3. 更合理的目标行为是：主视图显示短的人话标签，例如“临时运行失败 / 等待人工确认”；原始 token 继续留在 `live_flow / details / API`
- **排查步骤**:
  1. 先确认第一页是不是已经拿到了 `failure_class / recovery_stage`
  2. 如果拿到了但仍直接显示原始枚举串，优先改 `/dialogue/` 的 badge / recovery-detail 文案映射
  3. 若连原始枚举串都没有，再回后端 failure-class / recovery-stage 生成链排查

### 2.3.13 都显示成“等待人工确认”，但用户看不出该先修环境还是先复核结果

- **典型表现**:
  1. 页面已经把 `human_gate_required` 人话化成“等待人工确认”
  2. 但 `task_environment_blocked` 和 `partial_result_or_quality_risk` 看起来仍然一样
  3. 用户知道系统停下来了，却不知道下一步是“先修环境”还是“先看已有结果”
- **当前结论**:
  1. 这属于 **human-gate explanation seam**
  2. 问题不是恢复链没分流，而是第一页没有把 `failure_class` 转成对应的短动作解释
  3. 更合理的目标行为是：
     - `环境阻塞` -> 更像“先修环境后继续”
     - `部分结果待确认` -> 更像“先复核已有结果”
- **排查步骤**:
  1. 先查第一页 recovery line 是否同时拿到了 `failure_class` 和 `recovery_stage`
  2. 如果都拿到了却仍然只显示“等待人工确认”，优先改 `/dialogue/` recovery-detail 文案拼装
  3. 若 `failure_class` 本身缺失，再回后端 failure classification 链排查

### 2.3.14 transcript 中段出现大片空白，消息悬在上半区

- **典型表现**:
  1. 页面中间像“断了一截”
  2. 消息不多时，聊天卡停在上半区
  3. 空白主要落在消息卡下方、composer 上方
- **当前结论**:
  1. 这通常不是消息数据没回来，而是 transcript 主轴仍按顶部起排
  2. 如果 `message-list` 可滚动、消息卡能正常展开，但视觉上仍显得空，优先检查 `.message-panel__body` / `.message-stream` / `.message-list` 的主轴对齐，而不是先追后端
  3. 若页面下方同时挂着折叠态 `任务轨迹` summary，还要把它当成同一组底部栈一起看；不要只挪消息卡，最后把空白留在 `任务轨迹` 和 composer 之间
- **期望**:
  1. 当 transcript 较短时，`message summary + message list + collapsed thread drawer` 应整体贴近底部 composer
  2. 若仍有剩余空白，应优先留在消息组上方，而不是压在消息组下方
  3. 折叠态 `任务轨迹` summary 本身也应更像薄 footer strip，而不是第二块 header/panel
  4. 当前更稳的运行时验收方式不是只看截图肉眼判断，而是直接看 richer browser probe 在 `task_note_attach` seam 下附带的 `layout_metrics`
- **当前可用阈值**:
  1. `gapBetweenDrawerAndComposer <= 28`
  2. `drawerSummaryHeight <= 28`
  3. fresh `18390` 真实样本当前为：
     - `gapBetweenLastCardAndDrawer = 10`
     - `gapBetweenDrawerAndComposer = 17`
     - `drawerHeight = 23`
     - `drawerSummaryHeight = 23`

### 2.3.7 浏览器 console 里稳定出现 `/favicon.ico` 404

- **典型表现**:
  1. 页面主功能正常
  2. 但 Puppeteer / 浏览器 console 每次打开 `/dialogue/` 都报一条 `404 favicon.ico`
- **当前结论**:
  1. 这不是主业务逻辑回归
  2. 但它属于 **static-resource acceptance noise seam**
  3. 发布前和真实页复看时，应该把这种稳定噪声收掉，避免污染 console-based 验收
- **排查步骤**:
  1. 先确认 404 URL 是否就是 `/favicon.ico`
  2. 如果是，优先在页面 head 显式提供 favicon，而不是继续接受浏览器默认探测 404

### 2.3.8 真实页首屏会短暂闪出旧 `failed / idle`

- **典型表现**:
  1. URL hash 已经带了明确的 `session` 和 `task`
  2. 页面打开后前 1 秒左右，顶部先显示 `selectedStatus=idle`
  3. transcript 主卡先显示旧的 `failed`
  4. 约 3 秒后才收敛成 `worker claude failed: thread not found (19120)` 这类正确状态
- **当前结论**:
  1. 这通常不是后端没数据
  2. 更准确地说，这是 **initial selected-task live-flow ordering seam**
  3. 首轮加载时，如果主聊天流先按 `session messages` 渲染、selected task 的 `live_flow` 又还没到，就会短暂闪出旧态
- **排查步骤**:
  1. 用时间序列抓 DOM，例如 1s / 3s / 6s
  2. 如果 1s 时是 `idle/failed`，3s 后恢复正常，优先改 `/dialogue/` 首轮加载顺序
  3. 更合理的顺序是：确定 `selectedTaskId` 后，优先拉 selected task 的 `live_flow`，再渲染主聊天流与 status line

### 2.4 `/dialogue/` 里消息已写入，但页面不显示

- **典型表现**: `POST /api/v1/sessions/{id}/messages` 返回成功，但 `/dialogue/` 仍然为空。
- **可能原因**:
  1. 当前页面选中的 `session` 不是消息实际写入的那个 session
  2. 消息绑定了 `task_id`，但当前右侧查看的是另一个 task
  3. 前端 URL hash 仍锁在旧的 `session/task`
  4. 页面筛选器把目标消息过滤掉了
- **排查步骤**:
  1. 先直接调用 `GET /api/v1/sessions/{id}/messages?limit=20`，确认消息已落库。
  2. 如果看顶部消息流，先把过滤器切回 `all + all`，避免被 `assistant/system` 或 `task-only/session-only` 过滤掉。
  3. 如果看 `Related Messages`，优先调用 `GET /api/v1/tasks/{taskId}/live_flow?limit=10`，确认 `related_messages` 里是否已经带出目标消息。
  4. 若消息是 session 级普通连续聊天消息，检查 `related_messages[*].metadata.continuity_scope` 是否为 `session`；这类消息现在会并入当前 task 的 related message surface，但不会带 `task_id`。
  5. 只有在旧实例或 `live_flow.related_messages` 缺字段时，才再回退检查 `GET /api/v1/sessions/{id}/messages?task_id={taskId}`。
  6. 确认 `/dialogue/` 左侧当前选中的 session 与 API 返回的 `session_id` 一致。

### 2.4.3 `/dialogue/` 时间显示成 `01/21 22:04` 之类的明显错误日期

- **典型表现**:
  1. 后端 API 里的 `created_at / updated_at` 看起来像当前日期对应的 epoch 秒数
  2. 但聊天流、任务 rail、details 或顶部摘要仍显示成 `01/21 22:04` 一类明显错误时间
  3. `/dialogue/` 和 `/console/` 对同一条 task/session 显示出不同日期
- **真实原因**:
  1. 当前后端部分时间字段会以 epoch seconds 浮点数返回
  2. 前端如果直接 `new Date(value)`，会把 seconds 当成 milliseconds 解释
  3. 另一种常见假象是：源码里已经修了时间归一化，但真实 `8080` 仍在跑旧/坏的运行 JAR
  4. 旧版 `/console/` 的 session/task 排序也会受同一问题影响，把最近任务排错
- **排查顺序**:
  1. 先查对应 API，确认 `created_at / updated_at` 原始值
  2. 再确认 `/dialogue/app.js` 与 `/console/app.js` 的时间入口是否都走了统一归一化，而不是只修了主聊天流
  3. 如果源码看起来已经修好，但真实页面仍错，优先 fresh build + fresh restart
  4. 如果 `GET /dialogue/app.js` 都返回不了，说明已经不是“时间格式问题”，而是当前实例静态资源链本身已坏

### 2.4.1 任务已经 `done/failed`，为什么同一个 session 里还能继续聊天或继续发任务

- **典型表现**:
  1. 某个 task 已经进入 `done` 或 `failed`
  2. 但 `/dialogue/` 里仍然可以继续发 `user_note`
  3. 同一个 session 下还能继续创建 follow-up task
- **这是当前设计，不是 bug**:
  1. `task` 的终态只表示这一个工作单结束
  2. `session` 更接近 thread / conversation；只要 session 仍是 `active`，就允许继续聊天和继续发新任务
  3. 真正的阻断条件不是 `task.done/failed`，而是 `session.closed`
- **当前 contract**:
  1. `POST /api/v1/sessions/{id}/messages` 在 `session=active|paused` 且实现允许的前提下仍可写入
  2. `POST /api/v1/tasks` 只要 `session_id` 对应的 session 没有 `closed`，就仍可创建新任务
  3. 只有向 `closed session` 写 message / task 时，才会返回 `400 session is closed`
- **排查步骤**:
  1. 先看 `GET /api/v1/sessions/{id}`，确认 `status` 是否真的是 `closed`
  2. 不要只看当前 task 的 `status=done/failed` 就判断 session 应该不可继续
  3. 如果页面上主按钮被禁用，再看 `/dialogue/` composer 是否提示 `closed session`

### 2.4.2 `/dialogue/` 里“发布任务/发送消息”像点不了

- **典型表现**:
  1. composer 主按钮是灰的，或者点击后看起来没有响应
  2. 当前 URL 还停在某个旧 session 上，例如 `#session=...`
- **可能原因**:
  1. 当前选中的 session 已经是 `closed`
  2. 输入为空
  3. 当前在 `follow-up` 模式，但没有有效父任务
- **当前恢复路径**:
  1. `/dialogue/` 现在会在 closed-session 场景明确显示 warning，而不再 silent fail
  2. composer 下方会直接给出 `新建会话并继续` 按钮
  3. 点击后会创建一个新 session，并保留当前输入草稿，继续发送
- **排查步骤**:
  1. 先看 composer inline warning 是否提示 `closed session`
  2. 再看左侧 session rail 当前高亮的是不是旧的已关闭 session
  3. 如果只是想继续聊，不需要 reopen 已完成 task，直接点 `新建会话并继续`

### 2.5 `/tool_trace` 为空，或 `live_flow` 中没有工具轨迹

- **典型表现**: 任务执行过后，`/api/v1/tasks/{id}/tool_trace` 返回空数组。
- **可能原因**:
  1. 任务没有命中带工具能力的 worker，而是走了 `DefaultWorkerExecutor`
  2. worker 注册时 `suggest_only=true` 或 `tool_capabilities=[]`
  3. tool planning 判定 `needs_tool=false`
  4. 在多步工具链里提早命中 guard
- **排查步骤**:
  1. 先查 `/api/v1/tasks/{id}`，确认 `assigned_worker`。
  2. 再查 `/api/v1/workers`，确认该 worker 的 `tool_capabilities`、`tool_scope`、`suggest_only`。
  3. 再查 `/api/v1/tasks/{id}/live_flow?limit=10`，确认是否已有 judgment 但没有 `tool_invocations`。
  4. 查看日志中的 tool planning / tool invocation 相关输出。

### 2.6 本地文档试点任务没有命中 `kimi-local-doc`

- **典型表现**: 明明注册了 `kimi-local-doc`，但任务仍然分给了内置 `doc` worker。
- **可能原因**:
  1. 任务使用的是 `task_type=doc`，而不是试点专用 `task_type=local_doc`
  2. `kimi-local-doc` 没带 `local_doc` capability
  3. worker 注册时 `ready=false`
- **排查步骤**:
  1. 查看任务创建请求中的 `task_type`
  2. 查看 `/api/v1/workers` 中 `kimi-local-doc` 的 capability 列表
  3. 对照 `docs/LOCAL_DOC_WORKER_PILOT.md`

### 2.7 `mvn package` 直接报“无效的目标发行版: 21”

- **典型表现**: Maven 在编译阶段直接失败，报错类似 `invalid target release: 21`。
- **可能原因**:
  1. `JAVA_HOME` 指向 Java 8 或其他低版本 JDK
  2. `mvn -v` 显示 Maven 正运行在非 Java 21 环境
- **解决方案**: 切换到 Java 21 后再运行构建；推荐直接用仓库脚本：

```powershell
.\scripts\Test-WithJava21.ps1
.\scripts\Build-WithJava21.ps1 -SkipTests
.\scripts\Run-HarnessWithJava21.ps1 -Port 18080 -Background
```

### 2.8 Shaded JAR 能启动，但 JSON 接口报 `BufferRecyclers`

- **典型表现**:
  1. 服务启动日志正常
  2. 但首次访问 `/api/v1/health`、`/api/v1/tasks/...` 这类 JSON 接口时失败
  3. 日志里出现 `java.lang.NoClassDefFoundError: com/fasterxml/jackson/core/util/BufferRecyclers`
- **已确认根因**:
  1. 进程环境里残留了全局 `CLASSPATH`
  2. 其值指向旧的 Java 8 运行库
- **解决方案**:

```powershell
. .\scripts\Use-Java21.ps1
.\scripts\Build-WithJava21.ps1 -SkipTests
.\scripts\Run-HarnessWithJava21.ps1 -Port 18080 -Background
```

### 2.8.1 `/api/v1/health` 正常，但 `/v1/chat/completions`、`/v1/responses`、`/v1/models` 返回 `404`

- **典型表现**:
  1. `GET /api/v1/health` 返回 `status=up`
  2. 但 `POST /v1/chat/completions`、`POST /v1/responses`、`GET /v1/models` 都返回 `404 not found`
- **已确认根因**:
  1. 启动时用了旧的非 shaded JAR，产物里缺少较新的 façade 类
  2. 进程因此能启动基础 `/api/v1/*`，但不会真正注册当前源码里的 `/v1/*` façade 路由
- **解决方案**:
  1. 优先使用仓库脚本默认值启动，它现在默认指向：
     `target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar`
  2. 如果手工启动，也显式使用 shaded JAR：

```powershell
.\scripts\Run-HarnessWithJava21.ps1 -Port 18080 -Background
# 或
java --enable-preview -jar target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar
```

- **快速验证**:
  1. 先打 `GET /v1/models`
  2. 再跑 `.\scripts\Run-ChatFacadeAcceptanceProbe.ps1 -BaseUrl http://localhost:18080`

### 2.8.1.a worker round 经常空输出，但 judgment 仍然有结果

- **典型表现**:
  1. `live_flow`、`judgment_trace` 或任务详情里能看到 execution/completion judgment
  2. 但最近一轮 worker output 很短、为空，或根本没有形成有价值 artifact
  3. 于是会出现“有判断、无产物”的观感
- **已确认根因方向**:
  1. worker round 通常比 judgment round 更容易超时，因为输出更长、预算更高
  2. 某些 OpenAI-compatible 网关根路径并不是真正的 JSON API，实际可用端点可能是 `/v1/chat/completions`
  3. 当 worker round 首先失败或空跑时，judgment 仍可能基于已有上下文返回判断结果
- **解决方案**:
  1. 先确认 `OPENAI_BASE_URL` 是否真的命中可用 JSON 端点，不要默认根路径可用
  2. 对真实 live validation，优先使用：
     - `OPENAI_TIMEOUT_SECONDS=90`
     - `OPENAI_MAX_RETRIES=2`
     - `OPENAI_MAX_TOKENS=800`
  3. 用 `GET /api/v1/tasks/{id}/live_flow`、`GET /api/v1/tasks/{id}/runtime_context`、`GET /api/v1/tasks/{id}/tool_trace` 一起判断，不要只看 judgment 是否存在
- **快速验证**:
  1. 新建一个文档类或调研类任务
  2. 检查 `worker_round` 是否产出非空 output / artifact
  3. 同时确认 judgment 和 worker 是否都在命中同一条可用模型端点

### 2.8.2 `Run-ChatFacadeAcceptanceWithLocalHarness.ps1` 跑通了，但 `.tmp` 下还看到 `chat-facade-acceptance*.log`

- **典型表现**:
  1. acceptance runner 返回成功 JSON
  2. 但 `.tmp` 目录里仍然能看到 `chat-facade-acceptance*.log`
- **当前真实结论**:
  1. 现行脚本在默认模式下已经不会为**新运行**继续留下日志文件
  2. 如果你看到固定名 `chat-facade-acceptance.out.log/.err.log`，或很早时间戳的 `chat-facade-acceptance-<port>-<time>.log`，通常是旧版本 runner 留下的历史文件
  3. 只有显式传 `-KeepServerLogs` 时，runner 才会保留本次运行的唯一日志文件，并在 JSON 结果里回传具体路径
- **处理方式**:
  1. 若只是排障结束后的目录清理，直接手动删除 `.tmp\chat-facade-acceptance*.log`
  2. 若需要保留本次运行日志用于定位问题，显式加 `-KeepServerLogs`
  3. 若怀疑现行脚本仍泄漏日志，先在空目录状态下重新跑一次，再对比新增文件，而不要把旧遗留日志误判成当前运行结果

### 2.8.3 `/dialogue/` 的 shell screenshot 或 `dialogue-business-smoke.js` 出现 flaky / 500 / 导航超时

- **典型表现**:
  1. 用隔离 DB 启动本地实例后，`scripts/screenshot.js` 可以稳定输出 desktop/narrow/responses 三张截图和 JSON report
  2. `scripts/dialogue-business-smoke.js` 可能在 `create session` 或页面导航阶段失败
  3. 某些情况下，`/dialogue/` 本身会返回 `500 console render failed`
- **当前真实结论**:
  1. 这不应直接判定成 `/dialogue/` 产品功能回归
  2. 这类问题历史上暴露过：
     - 等待条件过度依赖脆弱 DOM 文案
     - `puppeteer-core` 导航阶段偶发 `GET /dialogue/ net::ERR_ABORTED` / navigation timeout
  3. 还真实出现过另外一类非产品性故障：后台实例运行期间又重建 `target\\*.jar`，导致 `WebConsoleHandler` 在分发 `/dialogue/` 静态资源时抛 `ZipFile invalid LOC header (bad signature)`，页面因此返回 `500 console render failed`
  4. 当前已验证的收口手段是：
     - Puppeteer 打开 `/dialogue/` 改为先等 `/api/v1/health`，再显式等 shell，而不是依赖脆弱的 `networkidle2`
     - `scripts/Run-HarnessWithJava21.ps1 -Background` 改为先复制 runtime jar 到 `.tmp\\runtime-jars\\` 再启动
     - `scripts/Run-HarnessWithJava21.ps1 -Background` 现在会在端口已被占用时直接失败，避免验证误打到旧实例
     - 若 narrow profile 在 shell-only fresh 实例上仍持续红灯，不要只盯 worker/output 新条带；还要检查移动端下是否仍有 `hidden` 的 composer/context 块占布局，以及 `lede / modeHint / inline state / textarea` 是否一起把 transcript 挤瘦
  5. 在 fresh 隔离实例 `http://localhost:18328` 上，`scripts/screenshot.js` 与 `scripts/dialogue-business-smoke.js` 当前都已经通过；因此当前更准确的口径是“已有真实绿灯，但 richer acceptance 仍需单独工具链”，而不是“business smoke 仍未收口”
  6. 最近这轮 `/dialogue/` 还真实改动了壳层 HTML/CSS：左 rail 更像 recent thread list，header 更薄，details 入口更轻，顶部状态更弱化；如果只看到旧截图，不要把历史 `18268/18276/18282` 壳层样本误当成当前页面
  6. `18264` 这类失败当前更应优先判定为 build/start sequencing 风险：当 fresh 启动和本机重建并行时，后台 harness 曾真实报过 `NoClassDefFoundError: com/fasterxml/jackson/databind/PropertyNamingStrategies`
- **建议处理方式**:
  1. 先按 `STARTUP_GUIDE.md` 或 `docs/DIALOGUE_UI_VALIDATION_RUNBOOK.md` 用隔离 DB 起实例
  2. 先看 `scripts/screenshot.js` 是否通过
  3. 再跑 `scripts/dialogue-business-smoke.js`
  4. 如果出现 `/dialogue/` 返回 `500 console render failed`，优先检查服务端日志里是否有 `ZipFile invalid LOC header`；若有，先 fresh 重启实例，不要直接记成前端回归
  5. 即使本地两条 smoke 都通过，也不要把它当成 richer continuity / acceptance 的替代品

### 2.8.4 明明已经重新构建，但 `/dialogue/` 页面看起来还是旧的

- **典型表现**:
  1. 已经改了 `src/main/resources/web/dialogue/index.html|app.css|app.js`
  2. 也重新跑了 `mvn package` 或 `Build-WithJava21.ps1`
  3. 但浏览器里看到的 `/dialogue/` 还是旧样式
- **已确认根因**:
  1. `/dialogue/` 静态资源由 `WebConsoleHandler` 从运行时 JAR 的 classpath 读取，不是直接从源码目录读取
  2. `Run-HarnessWithJava21.ps1 -Background` 会先复制运行 JAR 到 `.tmp\runtime-jars\` 再启动
  3. 所以后续重新构建，只会生成新的 `target\*.jar`，不会热更新已经跑起来的实例
  4. 如果把“重新构建”和“fresh 后台启动”并行跑，新的后台实例也可能先复制到旧的 `target\*.jar`，表现成：
     - 源码里已经有新 CSS / 新 HTML
     - 浏览器实际拿到的 `/dialogue/app.css` 仍是旧版本
     - 页面看起来像“前端改动没生效”
  5. 当前这类问题已经在 `18328` fresh 样本上被真实对照过：必须先等 build 完成，再单独启动新实例；否则像 `thread rail + details` 列宽这类 CSS 收口，可能只停留在源码里，服务端实际返回的 `app.css` 仍是旧值
- **处理方式**:
  1. 改了 `src/main/resources/web/dialogue/*` 后，按“重新构建 + 重启 fresh 实例”处理
  2. 不要指望刷新浏览器就能看到改动
  3. 如果只是改了 `scripts/screenshot.js`、`scripts/dialogue-business-smoke.js` 这类本地验证脚本，则不需要 Maven 构建；下次直接运行脚本即可
  4. 更稳的顺序是：**先等构建完成，再单独启动 fresh 实例**

### 2.8.5 工作区源码已经改了，但 `8080` 上真实页面还是旧语义

- **典型表现**:
  1. 工作区里已经把默认聊天改成“直接推进 task”
  2. 真实页面却仍然显示：
     - `已记录到当前会话。如需进入 harness 执行，请使用 task_auto 或 task_required`
  3. 或者工作区里已经把不可读失败输出改成可读摘要
  4. 真实页面里仍然直接出现 `����...` 一类 mojibake
- **已确认根因**:
  1. 当前运行中的实例仍然在吃旧的运行 JAR
  2. 或者本机构建脚本没有真正成功执行，新的前端资源根本没被重新打包
  3. 因此“源码状态”和“真实运行页面状态”会短时间分叉
- **快速判断方式**:
  1. 先看当前 `java.exe` 进程命令行，确认它加载的是哪一份 JAR
  2. 再确认 build 是否真的完成，而不是中途因为 `mvn` 缺失或 PATH 问题直接失败
  3. 再判断这是不是页面逻辑回归
- **处理方式**:
  1. 不要先继续改前端
  2. 先修好 build / fresh start 链
  3. 确认新实例真的吃到新资源后，再复验 `/dialogue/`

### 2.8.6 `Build-WithJava21.ps1` / `Test-WithJava21.ps1` 报 `mvn` 找不到

- **典型表现**:
  1. `The term 'mvn' is not recognized`
  2. 目标 JAR 虽然还在 `target/`，但它可能只是旧构建
- **风险**:
  1. 你会误以为“已经重新构建”
  2. 实际上 fresh 实例仍在吃旧语义页面
- **处理方式**:
  1. 先确认本机是否安装 Maven
  2. 若已安装但未进 PATH，优先让仓库脚本自动解析本机 Maven 可执行路径
  3. 只有在 build 真正成功之后，再起 fresh 实例验证 `/dialogue/`

- **2026-05-19 补充**:
  1. `Use-Java21.ps1`、`Build-WithJava21.ps1`、`Run-HarnessWithJava21.ps1` 的用户可见提示已改成 ASCII 前缀，例如 `[INFO]`、`[OK]`、`[WARN]`、`[ERROR]`。
  2. 这次修改只处理终端提示乱码风险，不改变 Maven 解析、Java 参数、运行 JAR 复制或端口检查逻辑。
  3. 已确认 `Use-Java21.ps1`、`Build-WithJava21.ps1`、`Run-HarnessWithJava21.ps1`、`Test-WithJava21.ps1` 文件头均不再带 UTF-8 BOM，且四个脚本不含非 ASCII 字符。
  4. 已验证 `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -Dtest=ProviderFailureClassifierTest` 成功退出。
  5. 已验证 `powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -SkipTests -QuietMaven` 成功产出 `target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar`。
  6. `Test-WithJava21.ps1` 现已兼容两种窄跑写法：
     - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -Dtest=ProviderFailureClassifierTest`
     - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=ProviderFailureClassifierTest"`
  7. 当前推荐文档示例优先写 `-MavenArgs "-Dtest=..."`，因为它更明确，也更不容易和脚本参数绑定混淆；裸 `-Dtest=...` 继续保留兼容。
  8. `Use-Java21.ps1` 在 `-Quiet` 模式下不再额外打印 `[INFO] Configuring Java 21 environment...`，避免把依赖它的 acceptance 脚本输出污染成“非纯 JSON”。

### 2.8.7 调研类真实任务自动执行失败，但证据已经收集到了

- **典型表现**:
  1. 任务目标是“本地代码 + 外部文件 + 协议文档”的调研对照
  2. `task.summary` 里只有半截计划、乱码或旧失败摘要
  3. `live_flow` / `messages` 里能看到：
     - worker 已经定位了若干真实文件
     - 也调用过本地工具
     - 但最终没有沉淀出正式结论
  4. 常见真实根因是：
     - `codex thread not found`
     - fallback worker 无有效最终输出
- **不要误判成什么**:
  1. 不要把它简单判成“这个任务完全没做”
  2. 也不要把 task summary 里的半截文本当最终结论
- **更准确的处理方式**:
  1. 先从 `/api/v1/tasks/{id}`、`/live_flow`、`/sessions/{id}/messages?task_id=...` 回收目标、失败摘要、已定位的文件入口
  2. 再直接检查本机真实证据：
     - 代码文件
     - 样例文件
     - 文档转换产物
  3. 最终把人工复核后的结论写回 `docs/`，不要只停留在 `.tmp` 或消息流
- **最近这轮真实样例**:
  1. `task_24cbb3678c684d60`
  2. 已回写文档：
     - `docs/XINHUA_CNML_RSS_ARTICLETHIRDSERVICE_ALIGNMENT_2026-05-15.md`
     - `docs/XINHUA_CNML_ADAPTER_IMPLEMENTATION_PLAN_2026-05-15.md`
  3. RSS 手册转换产物：
     - `.tmp/xinhua-rss-user-manual-v1.4.md`
- **操作原则**:
  1. 调研任务的最终交付物应优先是仓库文档
  2. 如果自动执行失败但证据链完整，优先人工补文档，不要为了“让 task 看起来自动完成”去编造最终结果
  3. 后续如果要继续自动化，应补的是更稳的调研 contract，而不是把脏 summary 继续投影到第一页

### 2.9 Windows 上 `codex` / provider CLI 明明能在 PowerShell 里找到，但任务执行时报 `CreateProcess error=2`

- **典型表现**:
  1. `/api/v1/agents/codex` 或 PowerShell `Get-Command codex -All` 看起来都能解析到 `codex`
  2. 真实任务在 `scheduler` 或首轮 planner/executor 执行时报错
  3. task summary / worker artifact 中出现 `Cannot run program "codex" ... CreateProcess error=2`
- **已确认根因**:
  1. Windows 环境里 `codex` 实际是 wrapper 脚本，如 `codex.cmd` / `codex.ps1`
  2. 旧链路里 provider detect 和真正执行没有共用同一套 launch contract
  3. PowerShell 能解析 wrapper，不代表 Java `ProcessBuilder("codex")` 也能直接启动
- **当前修复后的可观测字段**:
  1. worker execution metadata / live flow / agent run metadata 中会带：
     - `cli_binary`
     - `cli_resolved_binary`
     - `cli_launch_mode`
     - `cli_command_preview`
  2. provider detail 中会带 launch metadata：
     - `binary`
     - `configured_binary`
     - `launch_target`
     - `launch_mode`
     - `launch_available`
- **排查步骤**:
  1. 先在宿主机执行：

```powershell
Get-Command codex -All
```

  2. 再查 provider 状态：

```powershell
curl http://localhost:8080/api/v1/agents/codex
curl -X POST http://localhost:8080/api/v1/agents/codex/refresh
```

  3. 再查 worker readiness：

```powershell
curl http://localhost:8080/api/v1/workers/codex/readiness
```

  4. 如果已经执行过任务，再查：
     - `/api/v1/tasks/{id}/live_flow`
     - `/api/v1/agent_runs?task_id={id}&provider_id=codex`
  5. 确认 `cli_launch_mode` 是否为：
     - `direct`
     - `cmd_file`
     - `powershell_file`

### 2.10 `/workers/{id}/readiness` 显示 ready，但 provider 实际不可执行或未注册

- **典型表现**:
  1. worker 是内置 provider-backed worker，如 `codex` / `claude`
  2. 旧版本 `/readiness` 只看 host tool / API key / backend 标志，看起来是 `ready=true`
  3. 实际执行时 provider detect 或 CLI 启动失败
- **当前机制**:
  1. provider-backed worker 的 readiness 现在会增加 `checks["provider:<id>"]`
  2. 如果 provider detect 失败，worker readiness 会被同步拉低
  3. 如果 worker 指向 provider backend，但当前 harness 没接入该 backend，还会增加 `checks["executor_backend:<backend>"]`
  4. 如果 worker 指向 provider backend，但 provider 没注册，会返回 `provider not registered: <id>`
- **排查步骤**:
  1. 查看 `/api/v1/workers/{id}/readiness`
  2. 重点看：
     - `checks.provider:<id>`
     - `checks.executor_backend:<backend>`
     - `reason`
  3. 再对照 `/api/v1/agents/{id}` 看 provider 侧 `ready/reason/metadata`
- **说明**:
  1. 这是刻意收紧的契约
  2. 目标是避免“路由层判 ready，但执行层必失败”的 detect/readiness drift

### 2.10.1 Codex worker 显示 `thread not found (...)`，但本机 `.codex` 明明存在

- **典型表现**:
  1. Dialogue 或 task summary 显示 `worker codex failed: thread not found (27984)` 这类短线程号
  2. 本机 `C:\Users\<user>\.codex` 或 portable `codex-home` 下确实有 Codex 会话文件
  3. 甚至能在 `sessions/YYYY/MM/DD/rollout-...jsonl` 中看到对应任务 prompt 和大量命令输出
- **不要误判成什么**:
  1. 不要直接判定为 `.codex` 目录不存在
  2. 不要把括号里的短数字当成 Codex 持久化 session UUID
  3. 不要只看 worker artifact 的 `output_text`，它可能混入 Codex 命令执行输出、乱码或超长 stdout
- **当前 Codex 接入方式**:
  1. harness 的 codex worker 走 `CodexAppServerWorkerExecutor`
  2. 每轮启动 `codex app-server --listen stdio://`
  3. 通过 JSON-RPC 调用 `thread/resume` 或 `thread/start`，再调用 `turn/start`
  4. 成功或失败的结构化字段会落到 agent run / live flow 的 worker metadata
- **优先查看的结构化字段**:
  1. `execution_backend`
  2. `provider_session_id`
  3. `provider_thread_id`
  4. `resume_provider_session_id`
  5. `provider_error`
  6. `provider_turn_status`
  7. `provider_failure_class`
  8. `provider_protocol_trace`
- **本地数据入口**:
  1. harness DB：默认是 `${user.home}/.agentcloud/agent_cloud.db`，本地探针或 fresh 实例也可能使用 `.tmp/*.db`
  2. Codex home：优先查当前进程使用的 `CODEX_HOME`；没有显式环境变量时再查 `C:\Users\<user>\.codex`
  3. portable 环境常见位置：`E:\AI-Portable\codex-home`
  4. Codex 会话文件常见位置：`<codex-home>\sessions\YYYY\MM\DD\rollout-*.jsonl`
- **现场判定规则**:
  1. 如果 agent run 的 `provider_error=codex turn completion timed out`，而 output/artifact 里含 `thread not found (...)`，优先按 provider timeout 排查
  2. 如果 `provider_protocol_trace` 已包含 `thread/started`、`turn/started`、`item/commandExecution/outputDelta`，说明 harness 已经打到 Codex，不是启动前找不到 `.codex`
  3. 如果 recovery 计划显示 `recovery_execution_mode=fresh_session`，恢复轮应清掉旧 `provider_session_id / provider_thread_id / codex_thread_id / resume_provider_session_id`
- **常用命令**:

```powershell
codex --version
codex --help
codex exec --help
codex exec resume --help
codex app-server --help
codex debug app-server --help
codex debug app-server send-message-v2 --help
```

- **需要稳定获取 Codex 执行结果时的选项**:
  1. 继续使用 app-server：读取 harness 的 `agent_runs.metadata_json.worker_metadata`、`artifacts.metadata_json.latest_worker_metadata` 和 Codex `sessions/*.jsonl`
  2. 改为非交互 CLI：用 `codex exec --json -o <file>` 获取 JSONL 事件流和最后一条消息文件
  3. 恢复已存在会话：用 `codex exec resume <SESSION_ID> --json -o <file>`，其中 `<SESSION_ID>` 应是 Codex UUID，不是 `thread not found (...)` 里的短数字
- **处理建议**:
  1. 先确认当前 8080 实例是否还活着；实例不在时只能查 SQLite 与 Codex home
  2. 先用 `agent_runs` 的结构化字段定根因，再看 artifact 原文补证据
  3. 对 `thread not found / timeout / session expired` 类 transient failure，优先走 `/api/v1/tasks/{id}/recover` 触发 fresh-session 恢复，而不是手工沿用旧 thread id


### 2.10.2 Codex app-server 报 `unexpected argument '--no-alt-screen' found` 或表现成 initialize 超时

- **典型表现**:
  1. `provider_error=initialize: timed out waiting for response`
  2. `.tmp\provider-runs\codex\<task_id>\run-*\events.jsonl` 中，`harness_send initialize` 后立刻出现 `error: unexpected argument '--no-alt-screen' found`
  3. `last_message.md` 为空，`events.jsonl` 只有 CLI usage 输出，没有 JSON-RPC initialize response
- **根因**：Codex CLI `0.144.4` 的 `app-server` 不接受 `--no-alt-screen`，旧 harness 启动命令 `codex app-server --no-alt-screen --listen stdio://` 会在进入 JSON-RPC 握手前失败。
- **当前修复**：`CodexAppServerWorkerExecutor` 的 app-server plan 固定为 `codex app-server --listen stdio://`；`--no-alt-screen` 仅保留在 `codex exec --json` 的 exec-json 路径。
- **验证入口**：跑 `CodexAppServerWorkerExecutorTest,AgentProviderSupportTest,ApiErrorContractHttpTest`，再重建 shaded jar 后复跑 `Run-BaselineMatrixRealWorkerSmoke.ps1`。
### 2.11 任务已经 handoff 到别的 worker，但 live flow / judgment 仍显示旧 worker 身份

- **典型表现**:
  1. `task.assigned_worker` 已经是 `kimi` 或其他 fallback worker
  2. 但 `live_flow`、judgment metadata、worker artifact 里仍然看到上一轮 `codex` / planner 的 `selected_worker`、`selected_model_tier`
  3. 常见于 orchestrated planner round 之后的 sparse executor round
- **已确认根因**:
  1. 某些后续 round 返回的 `WorkerExecutionResult.metadata()` 很稀疏
  2. 下游 projection 在缺字段时会退回历史 metadata，造成 current-round worker identity 漂移
- **当前修复**:
  1. `ControlNodeGraph` 会在持久化前把当前 round 的 route metadata 注入到执行结果 metadata
  2. 即使执行器只返回 sparse metadata，当前 round 的 `selected_worker`、`selected_model_tier`、`execution_role`、`why_selected` 等也不会被上一轮覆盖
- **排查步骤**:
  1. 同时看：
     - `task.assigned_worker`
     - `/api/v1/tasks/{id}/live_flow`
     - `/api/v1/tasks/{id}/judgment_trace`
     - `/api/v1/agent_runs?task_id={id}`
  2. 如果仍看到旧 worker 身份，重点检查当前 round 的 `worker_metadata` 是否缺失 route fields

### 2.12 任务明明已经路由到 provider worker，但执行结果是 `empty` 或看起来卡住

- **典型表现**:
  1. `/api/v1/tasks/{id}/provider_selection` 或 task metadata 显示已经选中了 `kimi` / `hermes` / `pi` / `kiro` 这类 provider worker
  2. `agent_runs` 很快结束，`duration_ms` 很短，metadata 里却是 `worker_execution_status=empty`
  3. operator 体感上像“任务卡住了”或“找不到 codex / provider”
- **已确认根因**:
  1. 某些 suggest-only provider worker 早期没有显式 `execution_backend`
  2. `WorkerExecutorRouter` 因此把它们静默降级到 `DefaultWorkerExecutor`
  3. 如果当前宿主又没有配置通用 LLM，执行会以 `empty` 收尾，看起来像 worker 卡死
  4. 同一类问题还会出现在 provider 自己 ready，但当前 harness 根本没接入对应 executor 的场景
- **当前修复**:
  1. 内置 provider worker 现在显式声明 `execution_backend`
  2. `suggest_only=true` 不再优先于 provider-native / provider-app-server 路由
  3. 显式 provider backend 但无执行器支持时，router 会 fail fast，而不是静默退回 default LLM
  4. `readiness` 现在会额外暴露 `checks.executor_backend:<backend>`，把 `hermes` / `pi` / `kiro` 这类当前未接入执行器的 worker 直接标成 `not ready`
- **真实案例**:
  1. `task_2cd0bb782c5a4a9b` 实际 pinned 到的是 `kimi`，不是 `codex`
  2. 修复后新 run `arun_ca954de6c2aa43a9` 通过 `kimi` provider-native CLI 成功执行，耗时约 72 秒
  3. 如果 `POST /continue` 客户端超时，不代表任务没跑；先回看服务端日志和 `/api/v1/agent_runs?task_id={id}`
- **排查步骤**:
  1. 查 `/api/v1/tasks/{id}/provider_selection`
  2. 查 `/api/v1/workers/{workerId}/readiness`
  3. 查 `/api/v1/agent_runs?task_id={id}`
  4. 重点看 run metadata：
     - `execution_backend`
     - `cli_resolved_binary`
     - `cli_launch_mode`
     - `provider_output_parser`
     - `worker_execution_status`

## 3. 调试技巧

### 3.1 本地调试入口

- **日志**: 默认输出到控制台；当前工作区有 `server.out.log` 样本。
- **数据库**: 直接用 SQLite CLI 或 DB Browser 打开 `${user.home}/.agentcloud/agent_cloud.db`。
- **前端入口**:
  - `http://localhost:8080/` 会重定向到 `/dialogue/`
  - `http://localhost:8080/dialogue/` 查看 session message 与 task 回执
  - `http://localhost:8080/console/` 查看任务、route、packet、experiment 面板

### 3.2 常用调试命令

```bash
# 编译并打包
mvn package

# 启动服务
java --enable-preview -jar target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar

# 创建一个 coding 任务
curl -X POST http://localhost:8080/api/v1/tasks -H "Content-Type: application/json" -d "{\"title\":\"demo\",\"task_type\":\"coding\",\"source\":\"user\",\"priority\":\"high\",\"intent\":\"fix bug\"}"

# 查看 worker 列表
curl http://localhost:8080/api/v1/workers
```

### 3.3 关键断点位置

| 场景 | 建议断点位置 | 说明 |
|------|------------|------|
| 创建任务后为何分配到某个 worker | `src/main/java/com/agentcloud/engine/router/WorkerRouter.java` 的 `selectWorker` | 可以同时看到 tier 选择、learning hint、fallback reason |
| 单轮执行为什么停在某个阶段 | `src/main/java/com/agentcloud/engine/ControlNodeGraph.java` 的 `schedulerNode` / `continueNode` | 能看到 route、artifact、judgment、state transition |
| runtime context 为什么不完整 | `src/main/java/com/agentcloud/runtime/TaskRuntimeContextBuilder.java` 的 `build` | 可确认 active context、latest packet/checkpoint、learning hints 的拼装 |
| judgment 为什么判成 continue/pause/done | `src/main/java/com/agentcloud/judgment/PromptBasedJudgmentService.java` | 能直接看 prompt 输入与结构化输出 |
| 工具链为什么提前结束 | `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java` 的 `executeMultiToolRound` | 可看 `planner_no_additional_tool`、`repeated_tool_guard`、`no_progress_guard` |
| checkpoint / refined packet 内容异常 | `src/main/java/com/agentcloud/engine/ConsolidationService.java` | 可确认 artifact/decision/event 的抽取与 protocol payload |
| 实验汇总为什么不对 | `src/main/java/com/agentcloud/engine/ExperimentRunService.java`, `ExperimentMatrixService.java` | 可追踪 run metadata 聚合与按 mode/case 汇总 |

## 4. 配置相关注意事项

| 配置项 | 位置 | 常见错误 | 正确做法 |
|--------|------|---------|---------|
| `server.port` | JVM System Property | 忘记传导致端口冲突排查方向错误 | 明确在启动参数中覆盖 |
| `user.home` | 运行环境 | 使用受限账户导致无法创建 `.agentcloud` | 确保运行用户有写权限 |
| `schema.sql` | `src/main/resources/schema.sql` | 打包缺失导致启动失败 | 保持资源文件在主资源目录 |
| LLM 配置 | 环境变量 / 系统属性 | 本地未配置就期待 prompt judgment 生效 | 对照 `LlmConfig` 的读取项补齐 |

## 5. 性能隐患

| 编号 | 位置 | 问题描述 | 风险等级 | 建议 |
|------|------|---------|---------|------|
| P01 | `src/main/java/com/agentcloud/server/TaskHandler.java` | 列表接口仍以固定上限查询为主，缺少完整分页 | 中 | 增加分页参数和总量查询 |
| P02 | `src/main/java/com/agentcloud/engine/ConsolidationService.java` | consolidation 在 API 线程内同步执行 | 中 | 后续可异步化或限流 |
| P03 | `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java` | 多步工具链仍是单轮内固定上限 | 中 | 若要长链任务，需要更明确的多轮代理协议 |

## 6. 安全注意事项

| 编号 | 位置 | 问题描述 | 风险等级 | 建议 |
|------|------|---------|---------|------|
| S01 | `src/main/java/com/agentcloud/server` | 全部 API 无鉴权、无租户隔离 | 高 | 增加认证和最小权限控制 |
| S02 | `src/main/java/com/agentcloud/server/WorkerHandler.java`, `SkillHandler.java` | 允许外部注册 worker/skill；虽已有基础字段校验，但仍无权限边界 | 中 | 增加权限控制、白名单与更严格的字段/范围校验 |
| S03 | `src/main/java/com/agentcloud/server/*Handler.java` | 500 已统一脱敏为 `internal error`；风险主要转移到服务端日志暴露面 | 低 | 保持 HTTP 脱敏，日志只在受控环境可见 |

## 7. 运维检查清单

- [ ] 启动用户对 `${user.home}/.agentcloud/` 目录具备写权限。
- [ ] 监听端口 `8080` 或自定义 `server.port` 未被占用。
- [ ] 启动日志中已出现 `NIO HTTP Server started` 和 endpoint 列表。
- [ ] `/api/v1/health` 返回 `status=up`。
- [ ] `skills`、`workers` 基础数据符合当前运行环境预期。
- [ ] 若依赖暂停恢复流程，先验证 `checkpoints` 与 `resume_packets` 是否都按预期写入。
- [ ] 若启用了工具能力，确认 `/api/v1/tasks/{id}/tool_trace` 与 `/api/v1/tasks/{id}/live_flow` 中都能看到 `tool_invocations`。

## 8. 任务自动继续问题排查指南

### 8.1 问题现象
任务执行一轮后停在 `continue` 节点，没有自动继续到下一轮执行。

### 8.2 排查步骤

#### 步骤 1：检查任务状态
```sql
SELECT id, status, control_node, title FROM tasks WHERE id = 'task_xxx';
```
- 正常状态：`status=active`, `control_node=continue`
- 停止状态：任务停在 `continue` 节点但没有继续

#### 步骤 2：检查任务元数据中的 orchestration_stage
```sql
SELECT json_extract(metadata_json, '$.orchestration_stage') as stage FROM tasks WHERE id = 'task_xxx';
```
- `plan_pending` / `planner_active`：规划阶段任务
- `execution_xxx`：执行阶段任务

#### 步骤 3：检查 events 表确认执行流程
```sql
SELECT event_type, created_at FROM events WHERE task_id = 'task_xxx' ORDER BY created_at DESC;
```
正常流程：`task_created → node_intake → node_scheduler → worker_round → node_continue`

#### 步骤 4：检查 artifacts 查看输出内容
```sql
SELECT artifact_type, title, summary FROM artifacts WHERE task_id = 'task_xxx';
```

### 8.3 任务自动继续的条件

在 `ControlNodeGraph.shouldAutoContinueTask()` 方法中，任务自动继续需要满足以下条件：

| 条件 | 说明 | 影响 |
|------|------|------|
| `tool_aware_executor` = true | 必须是工具感知的执行器 | 非工具执行器不会自动继续 |
| `tool_execution_mode` = multi_tool_round | 必须是多工具轮模式 | 单工具轮不会自动继续 |
| `tool_chain_termination_reason` 不是 `repeated_tool_guard` / `no_progress_guard` | 工具链未因防护机制终止 | 防护触发时停止 |
| `more_declared_rounds_remain` = true | 当前轮之后还有声明的轮次 | 这是自动继续的正向信号，不应阻止继续 |
| `missing_required_current_round_write` = false | 当前轮次没有缺少必需写入 | 缺少写入时停止 |
| `grounded_output_present` = false，或 `grounded_output_present=true` 但 `more_declared_rounds_remain=true` | 尚未达到最终接地终态 | 中间轮次已有产物但后续轮次未完成时仍可继续 |
| **存在声明的后续轮次 OR LLM 返回了下一步想法 OR 有 `unfinished_items` OR 有显式 goal OR 启用了 auto_multi_round** | 积极继续策略 | 见下方核心逻辑 |
| `auto_continue_burst_count` < 限制 | 自动继续突发计数未超限 | 超限后停止 |

**核心逻辑**：任务是否继续的判断采用**积极继续策略**：
1. 如果当前轮之后还有声明的轮次（`more_declared_rounds_remain=true`），则继续
2. 如果 LLM 返回了明确的下一步想法（`suggested_next_action` 或 `next_step`），则继续
3. 如果当前轮显式留下了 `unfinished_items`，则继续
4. 如果没有下一步想法，但任务有**显式的 goal**（通过 `task.goal()` 或 metadata 中的 `goal` 字段），则继续
5. 如果没有下一步想法，但任务**启用了自动多轮模式**（`auto_multi_round=true`），则继续
6. 仅当以上条件都不满足时，任务才停止

### 8.3.3 burst 预算语义

`auto_continue_burst_count` 现在应理解为：

- **单次自动续跑链路**里的连续自动步数计数
- 不是整个任务生命周期里的永久封顶值

当前实现口径：

- `declared rounds` 长任务：budget 按 `declared_round_count` 上浮，最多到 `6`
- `auto_multi_round=true`：budget 至少放宽到 `3`
- `output_file_required=true`：budget 为 `2`
- `output_dir_required=true`：budget 为 `2`，带图像输入时为 `3`
- 手动 `resume` / 手动 `handoff` 会清掉旧的 `auto_continue_burst_count`，避免上一次自动链路把后续长任务永久锁死

### 8.3.1 显式 Goal 支持

任务可以通过以下方式设置显式 goal：

**方式 1：通过 task.goal() 字段**
```sql
UPDATE tasks SET goal = '完成项目规划文档的分析和落地' WHERE id = 'task_xxx';
```

**方式 2：通过 metadata 字段**
```sql
UPDATE tasks SET metadata_json = json_set(metadata_json, '$.goal', '完成项目规划文档的分析和落地') WHERE id = 'task_xxx';
```

### 8.3.2 自动多轮模式

可以通过以下方式启用自动多轮模式：

**方式 1：页面配置（推荐）**
1. 打开 `/dialogue/` 界面
2. 在 Composer 区域点击"参数"展开高级选项
3. 勾选"自动多轮推进"复选框
4. 发送任务

**方式 2：数据库直接设置**
```sql
UPDATE tasks SET metadata_json = json_set(metadata_json, '$.auto_multi_round', 'true') WHERE id = 'task_xxx';
```

启用后，任务会更积极地继续到下一轮，即使 LLM 没有返回明确的下一步想法。

### 8.4 日志调试

`shouldAutoContinueTask()` 方法现在会输出详细的调试日志，格式如下：
```
[AutoContinue] task=task_xxx orchestration_stage=plan_pending model_mode=orchestrated is_planning_stage=true has_output_requirement=false has_next_step=true has_goal=true auto_multi_round=false
[AutoContinue] task=task_xxx can_continue=true burst_count=0 burst_limit=2
```

可以通过日志快速定位任务不自动继续的原因：
- `rejected: tool chain terminated by guard` - 工具链被防护机制终止
- `rejected: missing required current round write` - 缺少必需的写入
- `rejected: grounded output already present` - 已有接地输出
- `rejected: no declared next round, next step, unfinished items, goal, or auto_multi_round enabled` - **没有后续轮次、没有下一步、没有 unfinished_items，也没有显式 goal/auto_multi_round**

日志中关键字段说明：
- `has_next_step` - 是否有 LLM 返回的下一步想法
- `has_goal` - 是否有显式的任务目标
- `auto_multi_round` - 是否启用了自动多轮模式
- `more_declared_rounds_remain` - 当前轮后是否还有声明轮次
- `has_unfinished_items` - 当前轮是否明确留下待完成项

### 8.5 常见问题与解决方案

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| 任务不自动继续 | 工具链被防护机制终止 | 检查 `repeated_tool_guard` 或 `no_progress_guard` 触发原因 |
| 任务不自动继续 | `declared rounds` 元数据没有正确落到当前轮 | 检查 `current_round_index/current_round_instruction/next_round_instruction` 是否连续 |
| 任务不自动继续 | 当前轮次缺少必需写入 | 检查工具执行情况 |
| 任务不自动继续 | 已有接地输出且没有后续声明轮次 | 任务已到终态或应走 completion judgment |
| 任务不自动继续 | 突发计数超限 | 手动 `resume/continue`，或检查是否应给任务显式 `declared_round_count/auto_multi_round` |
| 乱码显示 | GBK 编码内容被错误解码为 UTF-8 | ✅ 已修复：`TextDecoding.decodeExternalProcessOutput()` 增加乱码检测和 fallback |
| 内容截断 | summary 字段被截断为 280/500 字符 | ✅ 已修复：移除字符长度限制 |

### 8.6 修复记录

#### 修复 0：chat façade 代码任务判型与恢复换 worker 口径收硬
**文件**：
- `src/main/java/com/agentcloud/engine/ChatFacadeService.java`
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`

**问题现象**：
- 用户从 `/dialogue/` 发起“根据文档修改本地仓库 / 改某个 `.java` 文件 / 补测试”这类请求时，任务如果没有显式 `task_type`，旧逻辑会默认落成 `continuation`
- `WorkerRouter` 实际按 `task.metadata.task_type` 路由；`continuation` 对代码仓库改动任务过于宽泛，容易降低初始 route 质量
- 当 provider/native worker 随后报 `thread not found` 之类续跑失效时，旧恢复链可能从 `candidate_workers/fallback_workers` 里拿到 `openclaw-native` 这类非代码 worker，导致代码任务被自动切去错误 backend

**当前修复口径**：
- `ChatFacadeService` 现在会对未显式声明、且仍是通用 `continuation` 的 chat 任务做轻量判型
  - 如果用户输入明显包含：
    - 本地仓库路径，例如 `D:\gitAll\...`
    - 代码文件引用，例如 `.java/.js/.ts/.py/.xml`
    - 以及“修改/修复/实现/补测试/fix/patch/refactor”这类代码动作
  - 则自动把任务建模成 `task_type=coding`
- `ControlNodeGraph` 在 `thread not found / provider unavailable` 这类恢复链里，如果当前任务是 `coding`，会优先尝试代码 worker
  - 优先顺序会先看 `preferred_worker`
  - 然后优先 `codex / cursor / copilot / opencode / codebuddy / trae / deepseek / claude`
  - 不再让 `openclaw-native` 这类 `browser/doc/message/search` worker 抢在前面接代码仓库改动任务
- 对同一类 transient/provider failure，恢复链还会查看最近 `agent_runs`
  - 如果当前 provider 最近窗口内连续出现 `thread not found / provider unavailable / session expired / timeout`
  - `same-worker retry` 之后进入 `auto_handoff` 时，会尽量避开同 provider 的其他 worker
  - 这层只用于恢复降级，不替代正常 route 语义
- 同时，`same-worker retry` 与 `auto_handoff` 现在都应被理解成：
  - 先清掉上一轮 provider continuation 元数据
  - 再进入冷启动 retry / handoff
  - 需要被清掉的至少包括：
    - `provider_session_id`
    - `provider_thread_id`
    - `codex_thread_id`
    - `resume_provider_session_id`
- 这条行为不要只靠阅读实现判断，最好直接跑 `ControlNodeGraphActionResolutionTest` 的回归确认恢复 directive 应用后 metadata 已被清空
- 如果要确认真实下一轮不会继续复用旧 thread/session，除了 directive 级回归，还应跑控制图流转回归，检查第二轮 executor 收到的 `TaskRuntimeContext.task().metadata()` 已不再含这些 continuation 字段
- 还要额外确认执行器本身没有“偷偷续跑”：
  - `CodexAppServerWorkerExecutor` 在 `same_worker_retry_scheduled / auto_handoff_scheduled` 下不应再走 `thread/resume`
  - `ProviderCliWorkerExecutor` 在同样恢复阶段也不应默认把 `sessionId/taskId` 当成 `--resume/--session`

**适用边界**：
- 这个修复只针对“本地代码仓库改动任务”的 route/recovery 质量
- 纯文档调研、浏览器探测、搜索汇总任务仍应保留 `doc/browser/search/message` 语义，不应该被强行提成 `coding`

#### 修复 0.5：orchestrated planner 巨量失败输出不再当 delegation brief
**文件**：
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
- `src/test/java/com/agentcloud/engine/ControlNodeGraphOrchestrationFlowTest.java`

**真实触发案例**：
- `task_3809507edbbe4231`
- planner/coding worker 返回了超长噪声输出，里面带 `thread not found`
- 旧逻辑只要 planner 轮次结束，就可能继续把它当成 delegation brief，导致：
  - 巨量失败文本进入 artifact / summary
  - orchestration handoff 和 failure recovery 语义缠在一起
  - 页面看起来像“任务还在继续”，但实际只是 provider/runtime failure 在传染

**当前修复口径**：
- `ControlNodeGraph` 在 `plan_pending / planner_active` 阶段新增 `planner_delegation_gate`
- 只有 planner 输出满足“可委派 brief 最低合同”才允许继续进入 `execution_pending`
- 以下情况会直接拒绝 delegation：
  - `execution_status=failed/error/timeout/blocked/empty`
  - `failure_summary/output_text/artifact_content` 含 `thread not found / provider unavailable / failed to start / timeout`
  - 输出超大且不是紧凑 brief
  - 缺少简明 `summary + next step` 这类最小 delegation 信号

**可观测结果**：
- task metadata 会新增：
  - `planner_delegation_gate=accepted|rejected`
  - `planner_delegation_gate_reason=...`
- 被拒绝时，planner 输出不会再伪装成正常 delegation handoff
- 失败链会继续走 recovery / human_gate，而不是误导成“planner 已经成功下发给 executor”

#### 修复 0.6：route 诊断同时显示 pinned 当前态与 unpinned 恢复建议
**文件**：
- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/test/java/com/agentcloud/server/TaskHandlerControlActionHttpTest.java`
- `src/test/java/com/agentcloud/server/TaskHandlerLiveFlowHttpTest.java`

**问题现象**：
- 旧 `/api/v1/tasks/{id}/select_worker` 只返回当前 route result
- 一旦 task 已被 `assigned_worker` 钉住，页面和排障只能看到 `task_pinned`
- 看不出“如果解除 pin，系统本来会建议谁”

**当前修复口径**：
- `/select_worker` 和 `live_flow.route_preview` 现在都额外返回：
  - `current_pinned_route`
  - `recovery_unpinned_recommendation`

#### 修复 0.7：`agent_runs` 状态归一化不再把 `timeout/blocked/empty` 折成 completed
**文件**：
- `src/main/java/com/agentcloud/engine/AgentRunService.java`
- `src/test/java/com/agentcloud/server/TaskHandlerProviderSelectionHttpTest.java`

**问题现象**：
- 旧 `AgentRunService.normalizeRunStatus(...)` 会把未知或未列举状态直接压成 `completed`
- 这会污染：
  - `/api/v1/runtime_health`
  - `provider_failure_rate`
  - `provider_stats.failed_runs / last_failure_summary`
  - provider-aware recovery 对“最近 provider 是否热失败”的判断
- 典型结果是：provider 实际发生了 `timeout / blocked / empty`，但健康面板却像“最近都完成了”

**当前修复口径**：
- `agent_runs.status` 现在显式保留：
  - `completed / failed / cancelled / timeout / blocked / empty / unknown`
- 仅把这些别名折算：
  - `done/success/succeeded/ok -> completed`
  - `error -> failed`
- `recordCompletedWorkerRun(...)` 在写入 `agent_runs` 时：
  - 会保留 `WorkerExecutionResult.executionStatus`
  - `timeout / blocked / empty / unknown` 会写成对应状态
  - `last_event_type` 会统一投成 `run.failed`
- `/api/v1/runtime_health` 与 provider 统计现在把以下状态都计入 provider failure：
  - `failed`
  - `timeout`
  - `blocked`
  - `empty`
  - `unknown`

**排查提示**：
- 如果页面或日志里看到“provider 明显失败了，但 runtime health 还很绿”，优先检查：
  - `/api/v1/agent_runs?task_id={id}`
  - `/api/v1/runtime_health`
- 修复后，`recent_failures` 和 `provider_stats.last_failure_summary` 应该能看到最近一次 `timeout/blocked/empty`，而不再被误算成 completed

#### 修复 0.8：`/select_worker` 主返回体直接提示 recovery provider 避让原因
**文件**：
- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/main/java/com/agentcloud/engine/router/WorkerRouter.java`
- `src/test/java/com/agentcloud/server/TaskHandlerControlActionHttpTest.java`

**问题现象**：
- 之前只有：
  - `live_flow.route_preview.recovery_unpinned_recommendation`
  - `/provider_selection.metadata`
  才能看出“恢复视角下系统会避开哪个 provider”
- 如果 operator 只看 `/api/v1/tasks/{id}/select_worker` 顶层字段，仍然只能看到：
  - 当前 `selected_worker`
  - `route_source`
  - `current_pinned_route / recovery_unpinned_recommendation`
- 还需要继续钻子对象，排障成本偏高

**当前修复口径**：
- `/api/v1/tasks/{id}/select_worker` 主返回体现在额外提供：
  - `recovery_provider_deprioritized`
  - `recovery_deprioritized_provider`
  - `recovery_deprioritization_reason`
- 这些字段只表达“恢复视角下的 provider 风险提示”
- 不改变当前 pinned route 的 `selected_worker`

**排查提示**：
- 如果你只想快速判断“系统是否在恢复时主动绕开某个 provider”，先看：
  - `/api/v1/tasks/{id}/select_worker`
    - `recovery_provider_deprioritized`
    - `recovery_deprioritized_provider`
    - `recovery_deprioritization_reason`
- 需要更细节时，再下钻：
  - `current_pinned_route`
  - `recovery_unpinned_recommendation`

#### 修复 0.9：`/runtime_health` 直接显示 provider 恢复降级窗口
**文件**：
- `src/main/java/com/agentcloud/engine/AgentRunService.java`
- `src/test/java/com/agentcloud/server/TaskHandlerProviderSelectionHttpTest.java`

**问题现象**：
- 之前 `runtime_health` 能看到：
  - `provider_failure_rate`
  - `provider_stats.failed_runs`
  - `recent_failures`
- 但 operator 仍然要自己推断：
  - 哪个 provider 已经进入“恢复链应尽量绕开”的热失败窗口

**当前修复口径**：
- `/api/v1/runtime_health` 顶层 `metadata` 现在会额外返回：
  - `deprioritized_providers`
- `provider_stats[].metadata` 现在会额外返回：
  - `provider_deprioritized`
  - `deprioritization_reason`
- 这组字段表达的是：
  - “恢复链在当前窗口内，会不会保守地绕开这个 provider”
  - 不是 provider 永久不可用判定

**排查提示**：
- 如果你想快速判断当前恢复链是否会绕开某个 provider，先看：
  - `/api/v1/runtime_health`
    - `metadata.deprioritized_providers`
    - `provider_stats[].metadata.provider_deprioritized`
    - `provider_stats[].metadata.deprioritization_reason`
- 再结合：
  - `provider_failure_rate`
  - `recent_failures`
  - `/api/v1/tasks/{id}/select_worker`
  来判断是“普通失败”还是“已进入恢复降级窗口”

**排障用法**：
- `current_pinned_route` 用来解释“为什么现在还是这个 worker”
- `recovery_unpinned_recommendation` 用来解释“如果不再沿用旧 pin，当前系统真正建议换谁”
- 对 `task_3809507edbbe4231` 这类长代码任务，应该同时看这两块，而不是只看 `selected_worker`

#### 修复 1.0：`/console/` 与 `/dialogue/` 直接显示 provider 恢复避让原因
**文件**：
- `src/main/resources/web/console/app.js`
- `src/main/resources/web/console/app.css`
- `src/main/resources/web/dialogue/app.js`
- `src/main/resources/web/dialogue/app.css`

**问题现象**：
- API 层已经能返回：
  - `route_preview.recovery_unpinned_recommendation.provider_deprioritized`
  - `recovery_provider_deprioritized`
  - `runtime_health.metadata.deprioritized_providers`
- 但页面第一屏仍看不出：
  - 当前恢复阶段会避开谁
  - 这是普通 route 选择，还是 provider 热失败驱动的恢复降级

**当前修复口径**：
- `/console/` 的 `runtime_health` 现在会直接显示：
  - `当前恢复降级窗口：<provider...>`
  - 每个受影响 provider 行内的避让解释
- `/console/` 与 `/dialogue/` 的 route box 现在会直接显示：
  - `恢复阶段会优先避开 <provider>`
  - 如果原因是 `recent transient provider failures`，会翻成人话说明
- 这组 UI 信号只解释 recovery 视角，不改变普通 route 选择算法

**排查提示**：
- 如果 API 已有 `provider_deprioritized=true`，但页面仍看不见恢复避让说明：
  1. 先确认实例是否已更新到新静态资源
  2. 再看 `route_preview.recovery_unpinned_recommendation` 或 `runtime_health.metadata.deprioritized_providers` 是否确实非空
- 如果要做一条真实页面级回归，而不是只看 API，可直接跑：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-ConsoleProviderWindowProbe.ps1 -BaseUrl http://localhost:8080`
  - 这条 probe 会在浏览器里只覆盖 `runtime_health + live_flow/provider_selection`，并断言 `/console/` 已经把 provider 恢复降级窗口渲染出来

#### 修复 1.1：cold-start recovery 会显式投影成 `recovery_execution_mode=fresh_session`
**文件**：
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/main/resources/web/dialogue/app.js`

**问题现象**：
- 之前第一页只能看到：
  - `recovery_stage=same_worker_retry_scheduled/auto_handoff_scheduled`
  - `retry 1 / handoff 1`
- 但看不出这一轮是不是已经：
  - 清掉旧 `provider_session_id / provider_thread_id / codex_thread_id`
  - 转成 fresh session / fresh thread 的冷启动恢复

**当前修复口径**：
- cold-start recovery 现在会显式写入：
  - `recovery_execution_mode=fresh_session`
- 第一版只在以下恢复阶段投影：
  - `same_worker_retry_scheduled`
  - `auto_handoff_scheduled`
- 这条字段不是新的 `recovery_stage`，只是补充说明：
  - “本轮恢复是 fresh session，不沿用旧 continuation”

**页面口径**：
- `/dialogue/` 的消息卡恢复明细现在会直接看到：
  - `fresh session`
- route box 的 drawer chip 也会看到：
  - `recovery: fresh session`

**排查提示**：
- 如果你已经确认 recovery directive 会清 continuation metadata，但第一页还是看不出“这轮是冷启动恢复”，优先检查：
  - `task.metadata.recovery_execution_mode`
  - `sessions/{id}/messages` 最新 `task_progress/task_result.metadata.recovery_execution_mode`
  - `/dialogue/` 是否已更新到新静态资源

#### 修复 1.2：分发前 readiness 需要走 `dispatch` 验活
**文件**：
- `src/main/java/com/agentcloud/engine/router/WorkerRegistry.java`
- `src/main/java/com/agentcloud/server/WorkerHandler.java`
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`

**问题现象**：
- `/workers/{id}/readiness` 显示 `ready=true`
- 任务一发出去就出现 `thread not found / provider unavailable / failed to start / timeout`
- 长任务恢复链 auto handoff 后，下一个候选 worker 也可能立刻失败

**原因**：
- 默认 readiness 是 `passive` 模式，只检查配置、宿主工具、provider detect 和最近失败缓存。
- 这些检查不能证明 provider 现在能接受一次新的任务轮次。
- 长任务分发和恢复需要分发前可用性，否则 recovery 可能只是在多个不可用 backend 之间轮转。

**当前合同**：
- 默认入口仍是 passive：

```powershell
curl http://localhost:8080/api/v1/workers/codex/readiness
```

- 分发前验活入口使用 `mode=dispatch`：

```powershell
curl "http://localhost:8080/api/v1/workers/codex/readiness?mode=dispatch"
```

- `mode` 只接受 `passive` 或 `dispatch`。如果传入 `mode=disptach` 等未知值，接口应返回 `400`，避免误把分发前验活降级成 passive readiness。

- `dispatch` 响应会额外关注：
  - `mode=dispatch`
  - `checks.dispatch_preflight`
  - `dispatch_preflight_ready`
  - `dispatch_preflight_reason`
  - `dispatch_preflight_cached`

**排查提示**：
- 先看 passive readiness，确认不是二进制、工具、provider detect 或 unsupported backend 问题。
- 再看 dispatch readiness，确认分发前主动验活是否通过。
- 如果 dispatch 失败，worker 会被短期标记为 temporarily unavailable；路由和恢复链应跳过它。
- 如果 passive 通过但 dispatch 失败，优先处理 provider 运行态或认证态，不要只改 task prompt。
- 当前 scheduler 路由已经按 dispatch readiness 做分发门禁；如果 `/tasks/{id}/select_worker` 仍选中了问题 worker，应优先检查 route trace 的 `fallback_reason`、`candidate_workers` 与 `/workers/{id}/readiness?mode=dispatch` 是否一致。

#### 修复 1.3：最近失败任务统一恢复入口
**文件**：
- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/main/java/com/agentcloud/server/TaskHandler.java`
- `src/main/resources/web/console/app.js`

**问题现象**：
- 长任务在 `thread not found`、provider runtime failure、超大输出后进入 `waiting_human / failed / paused`。
- 页面上只能分别点 `resume / continue / handoff`，但看不出应该 cold-start 还是换 worker。
- 如果继续沿用旧 provider session/thread，恢复会反复失败。

**当前合同**：
- 查看最近可恢复任务：

```powershell
curl "http://localhost:8080/api/v1/tasks/recoverable?limit=10"
```

- 自动恢复单个任务：

```powershell
curl -X POST "http://localhost:8080/api/v1/tasks/<task_id>/recover" `
  -H "Content-Type: application/json" `
  -d '{"mode":"auto","reason":"manual recovery"}'
```

- 指定目标 worker 做 handoff 恢复：

```powershell
curl -X POST "http://localhost:8080/api/v1/tasks/<task_id>/recover" `
  -H "Content-Type: application/json" `
  -d '{"mode":"handoff","target_worker":"codex"}'
```

**排查提示**：
- `plan.recovery_execution_mode=fresh_session` 表示本轮会清掉旧 `provider_session_id / provider_thread_id / codex_thread_id`。
- `plan.recoverable=false` 且原因是认证/安装/环境问题时，应先修 provider 环境，不要盲目重试。
- 如果 `target_worker` 或 `auto_handoff_target` 存在，`recover` 会优先走 handoff。

#### 修复 1：积极的任务自动继续策略
**文件**：`src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
**修改**：
- 修改 `shouldAutoContinueTask()` 方法，采用积极继续策略
- 修正 `declared rounds` 语义：`more_declared_rounds_remain=true` 现在是继续信号，不再错误地阻止自动推进
- 支持基于 `unfinished_items` 的继续：当前轮明确留下待完成项时自动继续
- 支持基于显式 goal 的继续：当任务有明确目标时自动继续
- 支持基于 `auto_multi_round` 配置的继续：启用后更积极地推进多轮
- `grounded_output_present` 只在**没有后续声明轮次**时才阻止继续，避免中间轮已有产物却被误判终止
- 按 `declared_round_count / auto_multi_round / output requirement` 动态放宽 auto-continue burst budget
- 手动 `resume/handoff` 会清理旧的 `auto_continue_burst_count`
- 增加详细的调试日志记录，包括任务类型、阶段、下一步想法、goal 状态、auto_multi_round 状态等信息
- 核心逻辑：有后续声明轮次 OR 有下一步想法 OR 有 `unfinished_items` OR 有显式 goal OR 启用了 auto_multi_round 时继续，否则停止

#### 修复 1（前端）：页面添加自动多轮配置选项
**文件**：
- `src/main/resources/web/dialogue/index.html`
- `src/main/resources/web/dialogue/composer-plan.js`
- `src/main/resources/web/dialogue/composer-request-plan.js`
- `src/main/resources/web/dialogue/app.js`

**修改**：
- 在页面"参数"区域添加"自动多轮推进"复选框
- 在 composer-plan.js 中添加 `taskAutoMultiRound` 相关逻辑
- 在 composer-request-plan.js 中传递 `auto_multi_round` 参数到后端
- 在 app.js 中读取复选框状态并发送到后端

#### 修复 2：移除内容截断
**文件**：`src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
**修改**：移除 artifact summary 的 500 字符限制和 task summary 的 280 字符限制；移除 `ToolAwareWorkerExecutor` 和 `DefaultWorkerExecutor` 中的 fallback summary 截断

#### 修复 3：减少人工门控节点的触发概率
**文件**：`src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
**问题**：当工作器返回空输出时，被分类为 `worker_execution_failed`，直接进入人工门控节点等待人工确认
**修改**：
- 添加 `looksLikeEmptyOutputFailure()` 方法检测空输出情况
- 在 `classifyFailureClass()` 方法中优先检测空输出，将其分类为 `worker_runtime_transient`
- 空输出现在会被视为临时性错误，允许自动重试或切换到其他工作器，减少人工干预需求

#### 修复 4：乱码检测与编码 fallback
**文件**：`src/main/java/com/agentcloud/runtime/TextDecoding.java`
**修改**：增加 `looksLikeGarbage()` 和 `containsGbkGarbagePattern()` 方法，当 UTF-8 解码结果看起来像乱码时，自动尝试 GBK/GB18030 编码
