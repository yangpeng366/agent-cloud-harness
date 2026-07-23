# M03 Legacy GET Control Route Execution Record (2026-06-30)

## 1. 背景

M03 的目标不是继续扩展 `GET /pause|resume|continue|escalate` 这类历史写接口，而是把它们收口成：

- `POST` 是正式写路径
- `GET` 只保留兼容
- 兼容调用必须有稳定 deprecation header 与审计痕迹
- 新客户端不能再把 `GET` 当成正式入口

本轮以当前源码和 focused HTTP tests 为准，先验证现状，再补缺口。

## 2. 本轮核对范围

### 2.1 代码入口

- `src/main/java/com/agentcloud/server/TaskHandler.java`
- `src/main/java/com/agentcloud/server/SessionHandler.java`
- `src/main/java/com/agentcloud/server/NioHttpServer.java`

### 2.2 文档契约

- `docs/API_CONTRACTS.md`

### 2.3 focused tests

- `src/test/java/com/agentcloud/server/TaskHandlerControlActionHttpTest.java`
- `src/test/java/com/agentcloud/server/ControlActionHttpRouteTest.java`
- `src/test/java/com/agentcloud/server/SessionHandlerLifecycleHttpTest.java`

## 3. 现状结论

### 3.1 兼容 GET 路径仍存在，但已经带退役信号

当前 `TaskHandler` 仍保留：

- `GET /api/v1/tasks/{id}/pause`
- `GET /api/v1/tasks/{id}/resume`
- `GET /api/v1/tasks/{id}/continue`
- `GET /api/v1/tasks/{id}/escalate`

`SessionHandler` 仍保留：

- `GET /api/v1/sessions/{id}/close`

这些路径进入服务前都会调用 `NioHttpServer.markDeprecatedWriteRoute(...)`，当前会稳定补：

- `Deprecation: true`
- `Sunset: Thu, 31 Dec 2026 23:59:59 GMT`
- `Link: <same-path>; rel="alternate"; title="Use POST"`
- `Warning: 299 ... Deprecated write-via-GET route ...`
- `X-AgentCloud-Replacement-Method: POST`

### 3.2 审计痕迹已经进入消息与事件投影

当前兼容 `GET` 路径通过 `requestMetadata("GET", path, true)` 注入：

- `requested_via=http_api`
- `request_method=GET`
- `request_path=<实际 GET 路径>`
- `legacy_control_route=true`

这些字段会进入：

- `task_control_action` event
- `task_action` message
- 由控制动作触发的 `task_state_changed` / `task_state`
- `GET /sessions/{id}/close` 对应的 session lifecycle message / event

这说明 M03 的“兼容但可追踪”主策略已经在实现层成立。

### 3.3 `live_flow` 现在有直接可读的迁移审计摘要

除 raw message / event metadata 外，当前 `GET /api/v1/tasks/{id}/live_flow` 也会在 `runtime_cognition_surface` 中上浮：

- `legacy_control_audit.legacy_control_route_observed`
- `legacy_control_audit.request_method`
- `legacy_control_audit.request_path`
- `legacy_control_audit.replacement_method`
- `legacy_control_audit.latest_action`
- `legacy_control_audit.observed_at`
- `legacy_control_audit.summary`

数据源仍然是最近一条带 `legacy_control_route=true` 的 `task_control_action` event；只是现在不必再要求 operator 去翻 raw `task_action` message 或 `task_control_action` event，`live_flow` 本身就能直接解释“最近一次旧控制路由是怎么来的，以及调用方该迁到 `POST`”。

### 3.4 `/dialogue/` 与 `/console/` 现在也能直接消费这条审计摘要

当前前端不再要求 operator 先打开 raw JSON 或手翻 message/event metadata 才知道是否还存在旧控制调用：

- `/dialogue/` 的 route box 会在命中 `runtime_cognition_surface.legacy_control_audit` 时直接显示 `检测到历史 GET 控制调用`
- `/console/` 的 route box 会补同一条兼容路由提示
- `/console/` 的 operator summary 也会把这条信号上浮成 `兼容路由：...`

这一步复用了同一份前端拼接器，把 `request_method / request_path / replacement_method / latest_action` 翻成短句提示，而不是让两套页面各自拼接一份近义文案。

## 4. 本轮真实缺口

本轮开始时，M03 的实现主体已经存在，真实缺口主要在测试覆盖：

- 已有强覆盖主要集中在 `legacy GET pause`
- `legacy GET resume / continue / escalate` 缺少同等级 handler-level / route-level 回归
- `GET /sessions/{id}/close` 已有 session 侧兼容覆盖，但需要一起纳入 M03 收口结论
- 虽然 raw 审计字段已经会落进 message / event metadata，但 `live_flow` 还没有直接可读的 legacy-route 迁移摘要，operator 仍要手翻原始载荷才知道外部调用方是否还依赖旧 `GET`

另外，本轮在跑 M03 focused suite 时顺手暴露了两个 pre-existing/provider API 收口问题：

1. `LocalCliAgentProvider` 的 `cliConfig()` 与内部字段命名冲突，导致编译失败
2. `CodexAppServerWorkerExecutor` 的 `exec_json` profile 参数传递类型不一致

这两个问题不属于 M03 设计本身，但阻塞了 focused tests，因此一并做了最小收口。

## 5. 本轮修改

### 5.1 `live_flow` 读面补强

`RuntimeCognitionSurfaceView`

- 新增 `legacyControlAudit`
- 稳定字段为：
  - `legacyControlRouteObserved`
  - `requestMethod`
  - `requestPath`
  - `replacementMethod`
  - `latestAction`
  - `observedAt`
  - `summary`

`TaskService`

- `getLiveFlow(...)` 现会在 `runtime_cognition_surface` 中上浮最近一次 legacy control route 审计摘要
- 数据来自最近一条 `task_control_action` 且 `legacy_control_route=true` 的 event
- 摘要文本会明确提示该调用是 legacy `GET`，并给出迁移到 `POST` 的动作提示

这一步没有改写原始审计来源，只是把现有 raw metadata 压成 operator 可直接消费的 surface。

### 5.2 测试补强

`TaskHandlerControlActionHttpTest`

- 新增 `legacyGetResumeContinueAndEscalateStillMarkAuditMetadata`
- 覆盖 `GET resume / continue / escalate`
- 断言：
  - HTTP `200`
  - `Deprecation: true`
  - `task_action` 中存在 `request_method=GET`
  - `request_path` 为实际兼容路径
  - `legacy_control_route=true`
- 同时按真实服务契约校正 `continue` 返回的 `decision=scheduler`

`ControlActionHttpRouteTest`

- 强化 `legacyGetPauseStillWorksAndSendsDeprecationHeaders`
- 新增：
  - `legacyGetResumeStillWorksAndSendsDeprecationHeaders`
  - `legacyGetContinueStillWorksAndSendsDeprecationHeaders`
  - `legacyGetEscalateStillWorksAndSendsDeprecationHeaders`
- 统一校验 deprecation headers 与 legacy HTTP 审计字段

`TaskServiceLiveFlowViewTest`

- 新增 `getLiveFlowExposesLegacyControlRouteAuditSurface`
- 覆盖 service 侧 `live_flow.runtime_cognition_surface.legacy_control_audit`

`TaskHandlerLiveFlowHttpTest`

- 新增 `liveFlowHttpExposesLegacyControlRouteAuditSurface`
- 覆盖 HTTP JSON 输出中的 `data.runtime_cognition_surface.legacy_control_audit`

### 5.3 前端读面补强

`src/main/resources/web/dialogue/legacy-control-audit-plan.js`

- 新增共享 helper：`buildLegacyControlAuditPlan(...)`
- 统一把 legacy control audit 翻成：
  - `headline`
  - `detail`
  - `chip`
- 当前 `GET` 兼容调用的人话文案会稳定显示为：
  - `检测到历史 GET 控制调用`
  - `历史 GET 控制路由`

`src/main/resources/web/dialogue/route-box-plan.js`

- route box plan 现会直接消费 `legacyControlAudit / legacy_control_audit`
- 当审计摘要存在时，返回 `legacyControlNote`

`src/main/resources/web/dialogue/app.js`

- `renderRouteBox(...)` 现会从 `runtime_cognition_surface` 读取 `legacy_control_audit`
- `/dialogue/` route box 会在 free-first/manual-window 提示之外，额外显示 legacy control note

`src/main/resources/web/console/app.js`

- `/console/` route box 现会显示 legacy control audit chip 与 note
- `buildOperatorSummaryPlan(...)` 现会把同一条提示上浮到 summary surface，显示为 `兼容路由：...`

### 5.4 测试桩补齐

`TaskHandlerControlActionHttpTest` 的 `FailingResumeControlNodeGraph` 追加：

- `triggerEscalate(...)`

这样 handler-level 兼容 `GET escalate` 不再落回默认复杂图逻辑。

### 5.5 为恢复 focused tests 进行的最小编译收口

`LocalCliAgentProvider`

- 内部 `LocalCliProviderConfig` 字段改为 `providerConfig`
- 恢复 `cliConfig()` getter
- 保留 `resolveDefaultProfile()` 对外行为

`CodexAppServerWorkerExecutor`

- 修复 `exec_json` 路径上 profile 参数追加的类型错位
- 新增 `toProfileConfig(plan)`，只做 plan -> profile 的最小适配

这些修改的目标只是恢复现有 provider 配置接口闭环，不扩展新行为。

## 6. 验证

### 6.1 focused suite

执行命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=TaskHandlerControlActionHttpTest,ControlActionHttpRouteTest,SessionHandlerLifecycleHttpTest"
```

结果：

- 2026-06-30 本机执行通过
- 退出码 `0`

补充说明：

- `TaskServiceLiveFlowViewTest` 当前 `tests="14" failures="0" errors="0"`
- `TaskHandlerLiveFlowHttpTest` 当前 `tests="22" failures="0" errors="0"`

### 6.2 前端 focused suite

执行命令：

```powershell
node --test src/test/js/dialogue-route-box-plan.test.mjs src/test/js/dialogue-product-readiness-plan.test.mjs src/test/js/console-surface-layering-plan.test.mjs
```

结果：

- 2026-06-30 本机执行通过
- `tests 22`
- `pass 22`
- `fail 0`

覆盖重点：

- `legacy_control_audit` 已从 `live_flow.runtime_cognition_surface` 接进 `/dialogue/` route box
- `/console/` route box 与 operator summary 会复用同一条 legacy control 人话提示
- 文案持有点固定在共享 helper，而不是散落在两个页面里各自拼接

### 6.3 覆盖到的关键契约

- `GET pause/resume/continue/escalate` 都会返回 deprecation headers
- `GET close session` 同样返回 deprecation headers
- `task_action` / `task_control_action` / lifecycle 投影保留 `request_method / request_path / legacy_control_route`
- `live_flow.runtime_cognition_surface.legacy_control_audit` 会把最近一次 legacy control route 使用压成可直接读的审计摘要
- `/dialogue/` 与 `/console/` 会把这条审计摘要翻成首屏可读提示，而不是要求 operator 继续翻 raw metadata
- `continue` 的返回契约保持当前真实行为：`decision=updated.controlNode()`，本轮没有把它伪装成动作名

## 7. 结论

截至 2026-06-30，M03 已具备以下收口状态：

- 代码层：`POST` 已是正式入口，`GET` 仅作兼容
- 协议层：兼容 `GET` 已稳定输出 deprecation/sunset/replacement headers
- 审计层：兼容调用会落下 `legacy_control_route=true` 等稳定 metadata
- 读面层：`live_flow` 已能直接解释最近一次 legacy route 的请求方法、路径、动作和迁移提示
- UI 层：`/dialogue/` route box、`/console/` route box 与 operator summary 都已直接消费这条迁移审计摘要
- 测试层：`pause / resume / continue / escalate / session close` 都有 focused HTTP 回归，`live_flow` 的 service + HTTP 读面也已有 focused 回归
- 前端层：legacy route 的共享文案与双页面接线已有 focused JS 回归
- 文档层：`API_CONTRACTS.md` 已明确“新接入不要再依赖 GET”

因此，M03 当前可以认为已从“只有口头退役意图”推进到“代码、测试、文档一致的退役准备态”。
