# 免费优先 Provider 路由设计

> 文档类型：技术设计（`*_DESIGN.md`）。本文档先收口“免费优先、额度耗尽切换、手动窗口 provider 单独处理”的设计边界。
>
> 2026-06-18 实现状态：第一版已落地到 `WorkerRegistry`、`WorkerRouter`、`ControlNodeGraph`、`TaskHandler`、runtime surface 导出与相关测试；本文档保留为设计基线。

## 1. 背景

当前希望补一条更贴近真实使用习惯的 provider 路由策略：

1. 优先消耗免费的 provider。
2. 免费额度耗尽后，再切到付费或高成本 provider。
3. `trae`、`zcode` 这类需要用户亲自切到外部程序窗口输入的工具，不能再当成 harness 自动 worker 对待。

这条需求和现有 `Design -> Implement -> Verify` 三段式流程并不冲突，但它关注的是更底层的 provider 选择与恢复边界。

## 2. 当前源码核对结论

以下结论来自当前仓库代码与文档，不是推测。

### 2.1 当前路由还没有“免费优先 / 额度”概念

`WorkerRouter` 当前主要按下面几类条件选 worker：

- `task_type` capability
- `model_mode` / `model_tier`
- `local_workspace_access`
- `auto_route_task_types`
- dispatch readiness
- learning memory preferred worker hint
- `selection_priority`

当前没有：

- provider 免费/付费分类
- quota / allowance / 日额度状态
- “免费耗尽后再切付费”的显式策略
- “手动窗口 provider 只推荐、不自动执行”的统一口径

### 2.2 当前相关 worker 的真实状态

| Provider / Worker | 当前源码状态 | 关键 metadata / 边界 | 设计上的第一版归类 |
|---|---|---|---|
| `deveco` | 已注册 provider + worker；已接通专属 protocol | `local_workspace_access=true`，`selection_priority=84` | `free_auto` |
| `codebuddy` | 已注册 provider + worker；已接通专属 protocol | `local_workspace_access=false`，`workspace_access_mode=executor_not_supported`，`selection_priority=86` | `free_auto_guarded` |
| `codex` | 已注册 provider + worker；app-server 执行闭环稳定 | `local_workspace_access=true`，`selection_priority=100` | `paid_auto` |
| `reasonix` | 已注册 provider + worker；native CLI | `local_workspace_access=true`，`selection_priority=88` | `paid_auto` |
| `trae` | 已注册 provider + worker，但支持矩阵仍未完全收口 | `local_workspace_access=false`，`selection_priority=85` | `manual_window` |
| `zcode` | 当前仓库中不存在注册、协议或 worker 定义 | 无源码事实 | `manual_window_external` |

这里有三个必须写实的边界：

1. `deveco` 是当前最像“免费自动 coding worker”的对象。
2. `codebuddy` 虽然已接通 protocol，但当前 metadata 仍明确表示本地工作区访问未收口，因此不能简单写成“所有 coding 任务都优先它”。
3. `zcode` 当前根本不在仓库里，不能写成“系统已有 worker”；第一版只能把它当成手动外部工具。

### 2.3 当前优先级和“免费优先”并不一致

相关 worker 的当前 `selection_priority` 是：

- `codex` = `100`
- `reasonix` = `88`
- `codebuddy` = `86`
- `trae` = `85`
- `deveco` = `84`

这说明如果只依赖当前 priority，结果更接近“强默认 provider 优先”，而不是“免费优先”。第一版不能只靠调 priority 解决，必须引入独立的 cost / execution class 语义。

### 2.4 当前恢复链还会把 `trae` 当自动候选

`ControlNodeGraph.appendCodingRecoveryCandidates(...)` 当前仍把 `codebuddy`、`trae` 放在 coding recovery 候选里。这和“`trae` 需要用户手动切窗口输入”的真实边界冲突。

因此，这条设计不只是普通 route 政策，还包含一个恢复收口要求：

- `manual_window` provider 不应继续进入 auto-handoff / auto-recovery 候选序列。

## 3. 设计目标

第一版设计目标：

1. 对支持自动执行的 provider 落一条清晰的免费优先策略。
2. 对 `trae`、`zcode` 这类手动窗口工具，落一条清晰的人工 gate 策略。
3. 把 quota 耗尽、provider 暂时不可用、workspace 不支持这几类原因拆开。
4. 不改变现有 task graph 基本结构，优先复用 `select_worker / human_gate / handoff / live_flow`。

第一版非目标：

1. 不做跨程序窗口自动输入。
2. 不伪造 `zcode` 已经接通的事实。
3. 不要求一开始就精确读取所有 provider 的剩余额度。
4. 不把 `manual_window` provider 混进自动恢复链。

## 4. 第一版 provider 分类

第一版建议把 provider 先分成三大类，再决定路由与恢复策略。

### 4.1 `free_auto`

定义：

- 当前可由 harness 自动发起执行。
- 使用成本目标上应优先于 paid provider。
- readiness / dispatch / provider failure 仍走现有自动链路。

当前可归入：

- `deveco`

### 4.2 `free_auto_guarded`

定义：

- 目标上仍属于免费自动 provider。
- 但当前存在明确执行边界，不能对所有任务无差别自动路由。

当前可归入：

- `codebuddy`

当前 guard：

- 对带本地工作区读写信号的 coding / ops 任务，仍要受 `local_workspace_access=false` 限制。
- 在 workspace 能力没收口前，更适合显式指派或用于非本地改仓场景。

### 4.3 `paid_auto`

定义：

- 当前可由 harness 自动发起执行。
- 当 `free_auto` / `free_auto_guarded` 不可用或额度耗尽时，作为自动 fallback。

当前可归入：

- `codex`
- `reasonix`

### 4.4 `manual_window`

定义：

- 需要用户切换到外部程序窗口执行。
- harness 不直接调用，也不自动 handoff 给它。
- 只在 route / recovery / UI 中作为“可推荐的手动免费选项”出现。

当前可归入：

- `trae`
- `zcode`

补充边界：

- `trae` 虽然仓库里已有 provider/worker 注册，但用户已经明确它的真实使用方式是“切窗口手动输入”，因此第一版设计按 `manual_window` 处理。
- `zcode` 当前仓库无任何注册，第一版只能作为外部手动工具目录项，不进入自动 worker 集合。

## 5. 路由策略

第一版 route policy 建议分三层：

```text
现有 capability / workspace / readiness 过滤
  ->
free_auto + free_auto_guarded 候选
  ->
paid_auto 候选
  ->
manual_window 推荐
  ->
human_gate
```

### 5.1 预过滤层沿用当前逻辑

在讨论免费/付费前，先保持当前已有过滤：

- capability
- pinned worker
- `model_mode`
- `local_workspace_access`
- `auto_route_task_types`
- dispatch readiness

也就是说，“免费优先”不能越过工作区访问边界。例如：

- 本地 Java 仓库改动任务
- 明确带 `workspace_root / cwd / repo_path`

在这种情况下，`codebuddy` 当前即使免费，也应先被 workspace gate 排除。

### 5.2 第一选择：`free_auto` / `free_auto_guarded`

若存在满足当前任务条件的免费自动候选：

- 先在 `free_auto` 中选
- 再看 `free_auto_guarded`
- 仍保留现有 dispatch readiness / preferred hint / priority 作为类内排序

第一版的实际效果大概率会是：

- coding 本地改仓任务：优先 `deveco`
- 非本地工作区依赖的轻量任务：允许 `codebuddy` 进入比较

### 5.3 第二选择：`paid_auto`

当免费自动候选出现下面任一情况时，允许切到 `paid_auto`：

- 没有满足 capability/workspace 的免费候选
- 免费候选 not ready
- 免费候选被 provider failure / temporary unavailable 排除
- 免费候选被识别为 `quota_exhausted`

当前 paid fallback 首选池建议为：

- `codex`
- `reasonix`

### 5.4 第三选择：`manual_window`

当自动候选都不可用，或任务明确希望继续消耗“用户可手动触发的免费额度”时：

- 不自动切到 `trae` / `zcode`
- 而是进入 `human_gate`
- 同时给出 `manual_window_candidates`

这一步的目标不是替用户操作，而是把“下一步推荐你切到哪个窗口执行、然后再回来”明确投影出来。

## 6. quota / allowance 状态模型

第一版不建议引入复杂账本，先把 quota 状态收成最小模型。

### 6.1 自动 provider 的状态

对 `free_auto` / `paid_auto`，建议支持下面几种状态：

- `available`
- `temporarily_unavailable`
- `quota_exhausted`
- `auth_blocked`
- `unsupported_for_task`
- `unknown`

其中：

- `temporarily_unavailable`、`auth_blocked` 已和当前 readiness / provider failure 链路相近。
- 第一版新增重点是 `quota_exhausted`。

### 6.2 手动窗口 provider 的状态

对 `manual_window`，第一版只能支持：

- `user_reported_available`
- `user_reported_exhausted`
- `unknown`

原因：

- harness 无法直接读取 `trae` / `zcode` 窗口里的真实余额。
- 第一版应承认这条边界，而不是假装可自动判断。

## 7. 建议的 metadata 合同

第一版优先通过现有 `Worker.metadata`、`Task.metadata`、route trace metadata 扩展，而不是先改实体结构。

### 7.1 Worker metadata

建议新增：

- `provider_cost_class`
  - `free_auto`
  - `free_auto_guarded`
  - `paid_auto`
  - `manual_window`
- `provider_execution_mode`
  - `auto`
  - `manual_window`
- `quota_signal_source`
  - `provider_detectable`
  - `user_reported`
  - `none`
- `auto_route_policy`
  - `eligible`
  - `guarded`
  - `manual_only`

第一版建议映射：

| Worker | provider_cost_class | provider_execution_mode | auto_route_policy |
|---|---|---|---|
| `deveco` | `free_auto` | `auto` | `eligible` |
| `codebuddy` | `free_auto_guarded` | `auto` | `guarded` |
| `codex` | `paid_auto` | `auto` | `eligible` |
| `reasonix` | `paid_auto` | `auto` | `eligible` |
| `trae` | `manual_window` | `manual_window` | `manual_only` |

`zcode` 当前不建议先注册成 worker；第一版只作为手动 provider 目录项出现在 task / UI metadata 里。

### 7.2 Task metadata

建议新增：

- `provider_routing_policy=free_first`
- `paid_fallback_allowed=true|false`
- `manual_window_fallback_allowed=true|false`
- `manual_window_candidates=["trae","zcode"]`
- `user_reported_quota_state={...}`

### 7.3 Route trace / selection metadata

建议新增：

- `cost_route_stage`
  - `free_auto`
  - `paid_auto`
  - `manual_window_recommendation`
- `free_candidate_workers`
- `paid_candidate_workers`
- `manual_window_candidates`
- `quota_fallback_reason`
- `manual_window_required`

这样 `/select_worker`、`live_flow`、`dialogue` 才能明确回答：

- 为什么这次没有先走免费 provider
- 为什么已经切到付费 provider
- 为什么这次只能推荐手动窗口，而不是自动切过去

## 8. 恢复与 handoff 边界

第一版恢复策略必须补一条硬边界：

- `manual_window` provider 不进入 `auto_handoff`
- `manual_window` provider 不进入 same-process 自动恢复候选

也就是说：

- 免费自动 provider quota 用尽 -> 可以自动切 `paid_auto`
- 自动 provider 全不可用 -> 进入 `human_gate`，推荐 `manual_window`
- 不能做成“auto handoff to trae / zcode”

这条边界要同时收口在：

- 普通 route
- failure recovery
- recover API
- UI 推荐动作

## 9. UI / Operator 行为建议

### 9.1 `/select_worker` / `live_flow`

需要直接看到：

- 当前路由阶段是 `free_auto` 还是 `paid_auto`
- 哪些免费候选被排除了
- 是否是 quota exhaustion 触发了 paid fallback
- 是否存在 `manual_window_candidates`

### 9.2 `/dialogue/`

当进入手动窗口路径时，主链路不要显示成“已自动切 worker”，而应显示成：

- 推荐外部工具：`trae` 或 `zcode`
- 需要用户动作：切到对应窗口输入
- 回来后怎么继续：回填结果、恢复 verify、继续 task

### 9.3 `/console/`

对 operator 更重要的是：

- 当前 provider class 分布
- 当前 route 是否已从免费切到付费
- 最近是不是因为 quota / auth / readiness 导致 paid fallback

## 10. 当前风险

| 风险 | 当前状态 | 设计口径 |
|---|---|---|
| `codebuddy` 被误当成所有 coding 任务的免费优先 worker | 当前 workspace 能力未收口 | 仅归为 `free_auto_guarded` |
| `trae` 继续被 auto-handoff 误选 | 当前 recovery 候选仍含 `trae` | 第一版要求移出自动恢复链 |
| `zcode` 被写成已接通 worker | 仓库里无任何实现 | 第一版只写 external manual provider |
| quota 状态来源不统一 | 当前无统一字段 | 第一版先收成最小 metadata 合同 |
| 用户窗口额度无法自动探测 | 真实不可观测 | 明确标记为 `user_reported` / `unknown` |

## 11. 结论

这条需求的第一版不应理解成“把所有免费工具都塞进自动路由”，而应理解成：

1. 先把自动 provider 分成免费优先与付费 fallback。
2. 再把 `trae / zcode` 这类手动窗口工具剥离出自动执行链。
3. 对 quota exhaustion 增加明确的 route / recovery / UI 语义。

按当前源码现实，第一版最稳的结论是：

- `deveco` 是免费自动主位。
- `codebuddy` 是受 guard 限制的免费自动位。
- `codex / reasonix` 是付费自动 fallback。
- `trae / zcode` 是手动窗口位，只推荐、不自动执行。

## 12. 2026-06-18 落地回写

当前仓库已按本文设计完成第一版实现：

- `WorkerRegistry` 已为内置 worker 补齐 `provider_cost_class`、`provider_execution_mode`、`auto_route_policy`、`quota_signal_source`。
- `WorkerRouter` 已支持 `provider_routing_policy=free_first`，并能输出 `free_candidate_workers`、`paid_candidate_workers`、`cost_route_stage`、`manual_window_required`、`recommended_manual_provider`、`manual_window_candidates`。
- `deveco` 现在会在免费自动池中优先于 `codebuddy` 这类 `free_auto_guarded` worker。
- `manual_only` worker 已从自动 route 候选和默认 auto-recovery 候选中剥离；`trae` 不再作为自动恢复 worker 进入 coding recovery 序列。
- 当自动 provider 不可继续且允许手动窗口回退时，任务会进入 `waiting_human / human_gate`，并在 metadata 中写入 `manual_followup_instruction`。

本轮 focused verification 证据：

- `WorkerRouterRouteTraceTest`
- `TaskHandlerControlActionHttpTest`
- `TaskHandlerLiveFlowHttpTest`
- `TaskHandlerProviderSelectionHttpTest`
- `AgentRunServiceTest`
- `PromptBasedJudgmentServiceTest`

其中 `target/surefire-reports/TEST-com.agentcloud.engine.router.WorkerRouterRouteTraceTest.xml` 当前为 `failures="0"`，可作为本设计第一版已闭环的最直接回归证据。
