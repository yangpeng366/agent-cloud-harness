# Codex Multi-API Profile Routing Execution Record 2026-06-30

## 1. 用途

本文档记录 `codex` 多 API / 多 profile 执行链在 2026-06-30 的一次 focused regression 收口结果，目标是把 provider/profile 主链从“代码已落地”推进到“代码、测试、文档”一致态。

## 2. 本轮范围

### 2.1 代码入口

- `src/main/java/com/agentcloud/agent/providers/LocalCliAgentProvider.java`
- `src/main/java/com/agentcloud/agent/providers/LocalCliProviderConfig.java`
- `src/main/java/com/agentcloud/worker/CodexAppServerWorkerExecutor.java`
- `src/main/java/com/agentcloud/engine/router/WorkerRegistry.java`

### 2.2 focused tests

- `src/test/java/com/agentcloud/agent/providers/LocalCliAgentProviderTest.java`
- `src/test/java/com/agentcloud/agent/providers/ProviderDefaultProfileTest.java`
- `src/test/java/com/agentcloud/agent/providers/ProviderProfileConfigTest.java`
- `src/test/java/com/agentcloud/engine/router/CodexProfileWorkerRegistryTest.java`
- `src/test/java/com/agentcloud/worker/CodexAppServerWorkerExecutorTest.java`

### 2.3 相关文档

- `docs/CODEX_MULTI_API_PROFILE_ROUTING_DESIGN.md`
- `docs/CODEX_MULTI_API_PROFILE_ROUTING_PLAN.md`
- `docs/provider/README.md`

## 3. 本轮目标

```text
把 codex 多 profile 主链补成可复查的 focused regression：既验证 provider 默认值 / worker lane / task override 三层配置能贯通，也把真实暴露出的执行器回归点锁进测试。
```

## 4. 本轮执行过程

### Round 1

- 先复核当前 worktree，确认 `ProviderProfileConfig`、`ProviderDefaultProfile`、`LocalCliProviderConfig` 扩展字段、`WorkerRegistry` 的 `codex-openai / codex-xfyun / codex-deepseek`、以及 `CodexAppServerWorkerExecutor` 的 profile 字段都已在源码中存在。
- 同时确认现有 `LocalCliAgentProviderTest` 与 `CodexAppServerWorkerExecutorTest` 还缺两类 focused 覆盖：
  - `LocalCliAgentProvider` 对 `cliConfig()` 与 `resolveDefaultProfile()` 的稳定暴露
  - `CodexAppServerWorkerExecutor` 对 worker profile / task profile 在 `app_server` 与 `exec_json` 双链上的真实传递

### Round 2

- 补充 `LocalCliAgentProviderTest`：
  - `cliConfigExposesExtendedProfileAwareProviderConfig`
  - `resolveDefaultProfileReturnsConfiguredDefaultsWithoutEnvironmentDependency`
- 补充 `CodexAppServerWorkerExecutorTest`：
  - `codexBuildPlanIncludesWorkerProfileArgsAndMetadata`
  - `execJsonCommandCarriesTaskProfileArgsAndConfigOverrides`

### Round 3

- 首轮 focused suite 编译后，新增测试暴露出两个真实实现缺口：
  1. `CodexAppServerWorkerExecutor.resolveProfile(...)` 的合并顺序写反，`provider default` 会覆盖 `worker/task` profile。
  2. `CodexAppServerWorkerExecutor.execJsonCommand(...)` 忽略了 `appendProfileArgs(...)` 的返回值，导致 `exec_json` 实际命令丢失 `-c/-m/-p` profile 参数。
- 这两个问题不是“测试太严”，而是会让已落地的 codex profile lane 在运行时失真，因此一并修复。

### Round 4

- 修复后重新跑 focused suite，确认 provider 默认值、worker metadata profile、task override profile 三层都能在最小回归面被锁住。

## 5. 本轮代码收口

### 5.1 `LocalCliAgentProvider`

- 保留 `providerConfig` 作为内部配置持有者。
- 通过测试锁定 `cliConfig()` getter 继续对外暴露扩展后的 `LocalCliProviderConfig`。
- 通过测试锁定 `resolveDefaultProfile()` 在无环境变量时返回空默认值，而不是依赖外部环境。

### 5.2 `CodexAppServerWorkerExecutor`

- `resolveProfile(...)`
  - 从错误的覆盖链修正为 `provider default -> worker -> task`
  - 也就是稳定优先级固定为 `task > worker > provider default`
- `execJsonCommand(...)`
  - 改为真正使用 `appendProfileArgs(...)` 的返回结果
  - `exec_json` 命令因此会稳定带上：
    - `-c model_provider=...`
    - `-m ...`
    - `-p ...`

## 6. 验证

### 6.1 执行命令

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=LocalCliAgentProviderTest,CodexAppServerWorkerExecutorTest,ProviderDefaultProfileTest,ProviderProfileConfigTest,CodexProfileWorkerRegistryTest"
```

结果：

- 2026-06-30 本机执行通过
- 退出码 `0`

### 6.2 surefire 证据

- `TEST-com.agentcloud.agent.providers.LocalCliAgentProviderTest.xml`
  - `tests="5" failures="0" errors="0"`
- `TEST-com.agentcloud.agent.providers.ProviderDefaultProfileTest.xml`
  - `tests="4" failures="0" errors="0"`
- `TEST-com.agentcloud.agent.providers.ProviderProfileConfigTest.xml`
  - `tests="13" failures="0" errors="0"`
- `TEST-com.agentcloud.engine.router.CodexProfileWorkerRegistryTest.xml`
  - `tests="10" failures="0" errors="0"`
- `TEST-com.agentcloud.worker.CodexAppServerWorkerExecutorTest.xml`
  - `tests="20" failures="0" errors="0"`

## 7. 本轮锁住的关键契约

- `LocalCliAgentProvider.cliConfig()` 继续暴露扩展后的 profile-aware provider config。
- `ProviderDefaultProfile` 作为 provider 级默认 profile，可在无环境依赖时稳定返回空默认值。
- `WorkerRegistry` 中 `codex-openai` 的 worker metadata 会进入 `CodexExecutionPlan`，并反映到 `providerProfileId / modelProvider / model`。
- `task.metadata.preferred_provider_profile / provider_model_provider / provider_model / provider_cli_profile` 会进入 `exec_json` 命令实际参数，而不只是停留在计划对象里。
- `exec_json` 与 `app_server` 双链现在都受 focused regression 保护，不再允许只修一条链、另一条链悄悄偏离。

## 8. 当前剩余边界

- 本轮 focused suite 主要覆盖 provider/profile 主链的最小正确性，没有重新扩跑更大的 route/live-flow regression 组合。
- 更宽的 codex profile 路由、fallback trace、HTTP 读面行为仍以前一轮 broader suite 和现有文档为准；本轮不重复把所有更大范围验证再跑一遍。
- 因此，这份 execution record 的定位是：
  - 锁住 provider/profile 主链的最小回归面
  - 不是替代更大范围的路由或 UI 验证

## 9. 结论

```text
截至 2026-06-30，codex 多 API / 多 profile 主链已经补上 focused regression 证据。provider 默认值、worker lane、task override 三层 profile 配置都已进入最小回归面；本轮还借此发现并修复了两个真实执行器缺口：profile merge 顺序错误，以及 exec_json 丢失 profile 参数。
```

## 10. 同日追加收口：route / live-flow 读面补齐

### 10.1 背景

- 前半轮 focused regression 锁住了 provider/profile 主链，但 `/api/v1/tasks/{id}/select_worker`、`/api/v1/tasks/{id}/provider_selection`、`/api/v1/tasks/{id}/live_flow` 这些读面还没有把 codex profile 路由上下文稳定投影出来。
- 风险不是“字段少一点无所谓”，而是 route 已经从 `codex` 二层切到 `codex-openai / codex-xfyun / codex-deepseek` 时，诊断面仍然只显示通用 `codex`，会让 operator 看不出这轮到底落在哪条 profile lane。

### 10.2 追加代码范围

- `src/main/java/com/agentcloud/engine/router/WorkerRouter.java`
- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/main/java/com/agentcloud/engine/AgentRunService.java`
- `src/main/java/com/agentcloud/server/TaskHandler.java`
- `src/main/java/com/agentcloud/runtime/RuntimeFactSetAssembler.java`
- `src/main/java/com/agentcloud/runtime/RuntimeCognitionSurfaceAssembler.java`
- `src/main/java/com/agentcloud/runtime/RuntimeFactSurfaceExporter.java`
- `src/main/java/com/agentcloud/model/RuntimeCognitionSurfaceView.java`

### 10.3 追加收口内容

- `WorkerRouter.RouteResult` / `RouteDiagnostic` 统一增加：
  - `selectedProviderProfile`
  - `preferredProviderProfile`
  - `workflowStage`
- `selectWorker(...)` 现在不只在普通 capability match 路径上附着 profile 上下文，`task_pinned` 成功分支与 pinned fallback 分支也会统一经过：
  - `applyCodexProfileRouting(...)`
  - `attachCodexProfileRouteContext(...)`
- `RuntimeFactSetAssembler.buildRoutePreview(...)` 不再丢失：
  - `selected_provider_profile`
  - `preferred_provider_profile`
  - `workflow_stage`
- `/provider_selection`、`/live_flow.route_preview`、`runtime_cognition_surface.route`、runtime fact surface export 都补齐同一组字段。

### 10.4 追加 focused tests

- `src/test/java/com/agentcloud/engine/router/WorkerRouterRouteTraceTest.java`
  - `codexProfileRoutingCarriesSelectedAndRequestedProfileContext`
  - `codexProfileRoutingCarriesWorkflowStageContext`
  - `pinnedCodexRouteStillCarriesProfileRoutingContext`
- `src/test/java/com/agentcloud/server/TaskHandlerProviderSelectionHttpTest.java`
  - `providerSelectionProjectsCodexProfileRoutingMetadata`
- `src/test/java/com/agentcloud/server/TaskHandlerLiveFlowHttpTest.java`
  - `liveFlowProjectsCodexProfileRoutingIntoRoutePreviewAndRouteSurface`

### 10.5 验证结果

执行命令：

```powershell
& .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs '-Dtest=WorkerRouterRouteTraceTest,TaskHandlerProviderSelectionHttpTest,TaskHandlerLiveFlowHttpTest'
```

结果：

- `WorkerRouterRouteTraceTest`：`tests="32" failures="0" errors="0"`
- `TaskHandlerProviderSelectionHttpTest`：`tests="13" failures="0" errors="0"`
- `TaskHandlerLiveFlowHttpTest`：本轮新增的 codex profile route/live-flow 断言已通过；同仓再复跑后，`tests="21" failures="0" errors="0"`
- 其中原先单独记作既有噪音的 `providerRunFileHttpSseEmitsUpdateWhenTailFileChanges` 已在同日收口：
  - 根因不是 `provider_run_file` SSE 合同错误，而是测试用 `Thread.sleep(350)` 假设 snapshot 会在固定时间窗内先到达
  - 现已改为等待 `provider_run_file.snapshot` 和首个 `"content":"stdout-1\\nstdout-2"` 真正到达后，再追加 `stdout-3`
  - 因此当前 `TaskHandlerLiveFlowHttpTest` broader suite 已不再被该用例阻塞

### 10.6 验证注意

- 在当前 Windows/JDK 21 环境下，Surefire 会因为 manifest classpath 的绝对路径根盘校验，出现：
  - `ClassNotFoundException: com.agentcloud.runtime.TaskRuntimeContextBuilder`
  - `schema.sql not found`
- 这不是本轮业务改动引入的真实回归。当前已经把
  `-Djdk.net.URLClassPath.disableClassPathURLCheck=true`
  固化进 `pom.xml` 的 `maven-surefire-plugin.argLine`，不再要求每次手工设置 `JDK_JAVA_OPTIONS`。
- 因此本轮 execution record 的结论是：
  - codex profile route 读面扩展已收口
  - Windows/Surefire manifest classpath 噪音已转成仓库内固定测试口径
  - `provider_run_file` SSE tail update 回归也已单独收口，不再作为 broader suite 的剩余失败项

### 10.7 同日追加：主程序星火 glm5.1 配置与 targeted rerun

- 为避免“route/live-flow 断言通过”与“主程序实际 API 配置可用”之间存在空档，又追加做了一轮主程序配置 smoke。
- 主程序当前仍通过 `LlmConfig` 读取环境变量：
  - `OPENAI_API_KEY`
  - `OPENAI_BASE_URL`
  - `OPENAI_MODEL`
  - `OPENAI_REVIEW_MODEL`
  - `OPENAI_WIRE_API`
- 使用如下实测组合启动独立实例后，`/api/v1/health` 已返回 `llm.available=true`，且 `base_url/model/review_model/wire_api` 与配置一致：

```text
OPENAI_BASE_URL=https://maas-coding-api.cn-huabei-1.xf-yun.com/v2
OPENAI_MODEL=xopglm51
OPENAI_REVIEW_MODEL=xopglm51
OPENAI_WIRE_API=chat_completions
```

- 另外直接对 `https://maas-coding-api.cn-huabei-1.xf-yun.com/v2/chat/completions` 发最小请求，已收到 `pong`，说明当前 `OpenAiCompatibleClient` 的 OpenAI-compatible `chat_completions` 调用模式可直连星火 glm5.1。
- 同日又分别干净重跑：
  - `TaskHandlerLiveFlowHttpTest`：`tests="21" failures="0" errors="0"`
  - `TaskHandlerProviderSelectionHttpTest`：`tests="13" failures="0" errors="0"`
  - `ProviderDefaultProfileTest`：`tests="4" failures="0" errors="0"`
  - `ProviderProfileConfigTest`：`tests="13" failures="0" errors="0"`
  - `CodexProfileWorkerRegistryTest`：`tests="10" failures="0" errors="0"`
  - `OpenAiCompatibleClientTest`：`tests="5" failures="0" errors="0"`
  - `ChatFacadeHandlerHttpTest`：`tests="23" failures="0" errors="0"`
  - `CodexAppServerWorkerExecutorTest`：`tests="20" failures="0" errors="0"`

### 10.8 当前可对外说明的边界

- `/dialogue/` 与 `/v1/chat/completions` 走的是 harness 自己的 `ChatFacadeService`，它把 OpenAI-compatible 请求翻译成 session/task 操作，不是把用户消息直接代理到外部星火。
- 因此“Dialogue 页面能工作”与“外部星火 API 已可用”是两条不同链路；本轮已经分别用：
  - `ChatFacadeHandlerHttpTest`
  - 主程序 `/api/v1/health`
  - 直连星火 `/chat/completions` smoke
  做了拆分验证。
- 另一个仍存在但不阻塞本轮切星火的实现边界：
  - `ProviderProfileConfig.fromWorkerMetadata(...) / fromTaskMetadata(...)` 目前只解析 `provider_profile_id / provider_model_provider / provider_model / provider_cli_profile`
  - `configOverrides` 仍未从 task/worker metadata 透传
  - `LocalCliProviderConfig.resolveDefaultProfile()` 目前也只给 provider default 提供 `model_provider / profile / config_json`，默认 `model` 字段仍为空
- 这些边界不影响当前把“主程序 LLM client”切到星火 glm5.1，但会限制后续更细粒度的 task-level config override 能力。
