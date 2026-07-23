# 免费优先 Provider 路由方案

> 文档类型：方案计划（`*_PLAN.md`）。本文档承接 `docs/FREE_FIRST_PROVIDER_ROUTING_DESIGN.md`，聚焦第一版怎么落地，先收口元数据、路由顺序、恢复边界和用户手动窗口流程。
>
> 2026-06-18 实施状态：Phase A-C 已完成，Phase D 仅完成 HTTP/runtime surface 投影。
>
> 2026-06-30 补充状态：Phase D 已继续收口到前端读面。`/dialogue/` 的 route box 与 `/console/` 的 operator summary 现在都会把 `manual_window_required / recommended_manual_provider / manual_window_candidates / cost_route_stage / free_candidate_workers / paid_candidate_workers / fallback_reason` 翻成人话提示，不再要求操作者自己从 raw route metadata 推断“为什么免费链路断了、为什么切付费、为什么要手动窗口继续”。
>
> 2026-06-30 再补充状态：`manual_followup_instruction` 已正式进入后端 route/read surface。当前 `/api/v1/tasks/{id}/select_worker`、`/api/v1/tasks/{id}/live_flow.route_preview`、`runtime_cognition_surface.route`、`/api/v1/tasks/{id}/provider_selection.metadata` 都会稳定透出同一条手动窗口 follow-up 文案，不再只存在于 task metadata 和 Dialogue action note。
>
> 2026-06-30 三次补充状态：`manual_followup_instruction` 已继续补进 `AgentRunService` 摘要面。当前 `/api/v1/tasks/{id}/agent_run.metadata`、`live_flow.provider_selection.metadata` 也会统一透出 `free_first_routing / cost_route_stage / manual_window_required / recommended_manual_provider / manual_window_candidates / manual_followup_instruction`，不再要求 operator 在跨页面读摘要时退回 task metadata 或 route preview。

## 1. 目标

第一版方案要解决四件事：

1. 让自动路由具备“免费优先，额度耗尽后切付费”的显式语义。
2. 让 `trae`、`zcode` 这类手动窗口工具退出自动 worker 路径。
3. 让 route / recovery / UI 对 quota exhaustion 有一致口径。
4. 在不大改架构的前提下，复用现有 `Worker.metadata`、`Task.metadata`、`select_worker`、`human_gate`、`recover` 链路。

## 2. 当前实现缺口

基于当前源码，第一版缺口主要有四类。

### 2.1 Worker 元数据里没有 cost / execution class

当前 `Worker` 只有通用 `metadata`，但没有下面这些语义：

- 免费自动还是付费自动
- 是否只允许手动窗口执行
- quota 状态来源来自 provider 还是用户回报

### 2.2 `WorkerRouter` 没有免费优先阶段

当前 route 逻辑不会主动先筛 `free_auto`，再退到 `paid_auto`。只要 `selection_priority` 更高，`codex` 就可能压过 `deveco`。

### 2.3 `ControlNodeGraph` 恢复链会误把手动窗口 provider 当自动候选

当前 `appendCodingRecoveryCandidates(...)` 仍把：

- `codebuddy`
- `trae`

放入 coding recovery 列表。对于 `trae` 来说，这和“用户必须手动切窗口输入”的现实边界冲突。

### 2.4 UI 还无法明确表达“推荐手动窗口”

当前页面更擅长展示：

- 选中了哪个 worker
- 为什么 dispatch failed
- 为什么进入 human gate

但还不擅长明确表达：

- 自动 provider 免费额度没了
- 当前建议用户切到 `trae` 或 `zcode`
- 回来后应该如何回填结果并继续

## 3. 第一版方案范围

### 3.1 本轮要做

1. 给 worker 增加免费/付费/手动窗口分类 metadata。
2. 给 task 增加 `free_first` 路由偏好 metadata。
3. 在 `WorkerRouter` 中增加 cost stage 选择。
4. 在 recovery 链中剔除 `manual_window` provider。
5. 在 route trace / UI metadata 中增加手动窗口推荐字段。

### 3.2 本轮不做

1. 不做真实余额抓取 SDK。
2. 不做 `zcode` 自动 provider 接入。
3. 不做跨程序窗口自动输入。
4. 不做复杂 quota ledger 或长期统计账本。

## 4. 元数据方案

### 4.1 Worker metadata 扩展

建议在 `WorkerRegistry` 预注册 worker 时补这些字段：

- `provider_cost_class`
- `provider_execution_mode`
- `auto_route_policy`
- `quota_signal_source`

建议映射如下：

| Worker | provider_cost_class | provider_execution_mode | auto_route_policy | quota_signal_source |
|---|---|---|---|---|
| `deveco` | `free_auto` | `auto` | `eligible` | `provider_detectable` |
| `codebuddy` | `free_auto_guarded` | `auto` | `guarded` | `provider_detectable` |
| `codex` | `paid_auto` | `auto` | `eligible` | `none` |
| `reasonix` | `paid_auto` | `auto` | `eligible` | `none` |
| `trae` | `manual_window` | `manual_window` | `manual_only` | `user_reported` |

`zcode` 第一版建议不注册内置 worker，而是在 task / route metadata 中作为手动候选项存在。

### 4.2 Task metadata 扩展

建议新增：

```json
{
  "provider_routing_policy": "free_first",
  "paid_fallback_allowed": true,
  "manual_window_fallback_allowed": true,
  "manual_window_candidates": ["trae", "zcode"],
  "user_reported_quota_state": {
    "trae": "unknown",
    "zcode": "unknown"
  }
}
```

默认口径建议：

- coding / research / reading 这类任务默认可用 `provider_routing_policy=free_first`
- 如果用户明确要求“不要切付费”，则 `paid_fallback_allowed=false`

### 4.3 Route trace metadata 扩展

建议在 `RouteResult` 或其导出 payload 上增加：

- `cost_route_stage`
- `free_candidate_workers`
- `paid_candidate_workers`
- `manual_window_candidates`
- `quota_fallback_reason`
- `manual_window_required`

## 5. 路由落地顺序

### Step 1：预过滤保持不变

先沿用当前：

- capability
- `model_mode`
- `local_workspace_access`
- `auto_route_task_types`
- dispatch readiness

这一步不让“免费优先”绕过安全与执行边界。

### Step 2：在预过滤后的候选集中做 cost-stage 分层

建议 `WorkerRouter.selectWorkerWithoutPinned(...)` 在 dispatch-ready 候选上再分成三组：

1. `free_auto` + `free_auto_guarded`
2. `paid_auto`
3. `manual_window`

第一版 route 顺序：

1. 若 `free_auto/free_auto_guarded` 组非空，先在组内选择。
2. 若免费组为空，且 `paid_fallback_allowed=true`，再选 `paid_auto`。
3. 若自动组都为空，且 `manual_window_fallback_allowed=true`，不返回自动 worker，而是带 `manual_window_required=true` 与候选列表进入上层。

### Step 3：组内排序继续复用现有能力

组内仍可继续复用：

- learning memory hint
- `selection_priority`
- dispatch readiness
- `model_tier`

也就是说：

- 免费优先决定“先在哪一组里选”
- 现有优先级决定“组内先选谁”

### Step 4：quota_exhausted 信号参与免费组淘汰

第一版建议先不改 `ReadinessCheck` 结构，只通过 metadata 扩展 route 过滤：

- 若 worker metadata 或 task metadata 明确表明该 provider `quota_exhausted=true`
- 则该 worker 从本次免费组候选中移除
- 并在 trace 中留下 `quota_fallback_reason`

后续若要收口为更正式接口，再补到 `ReadinessCheck`。

## 6. 恢复链收口

### 6.1 `manual_window` provider 移出 auto recovery

`ControlNodeGraph.appendCodingRecoveryCandidates(...)` 第一版应至少保证：

- `manual_window` provider 不进入 `auto_handoff` 候选
- `trae` 从默认 auto recovery coding 列表中移出

对当前源码，最低改动是：

1. 先把 `trae` 从硬编码 recovery 候选移出。
2. 后续再让 recovery 候选按 `auto_route_policy != manual_only` 过滤。

### 6.2 quota exhaustion 不等于 transient runtime failure

第一版建议把 `quota_exhausted` 视为：

- 不是 `worker_runtime_transient`
- 也不是 `task_environment_blocked`

更准确地说，它属于：

- `provider_quota_exhausted`

第一版可以先不新增 failure class 常量，但 route / recover 决策必须表现成：

- 不对同一个免费 provider 做 same-worker retry
- 允许直接切下一个自动 provider 组
- 若只剩 `manual_window`，进入 `human_gate`

### 6.3 recover API 的推荐行为

对 `recover` 来说，第一版要形成下面口径：

- 免费自动 provider quota 用尽 -> 推荐 paid fallback
- paid fallback 也不可用 -> 推荐手动窗口
- 不自动 recover 到 `trae/zcode`

## 7. 手动窗口流程

### 7.1 `manual_window` 不是 worker 执行，而是用户动作

当任务进入手动窗口路径时，系统应该进入：

- `waiting_human / human_gate`

并附带：

- `manual_window_candidates`
- `recommended_manual_provider`
- `manual_followup_instruction`

### 7.2 第一版推荐的用户动作合同

建议固定成下面的说明结构：

1. 推荐工具：`trae` 或 `zcode`
2. 推荐原因：免费自动额度耗尽 / 自动 provider 不可用
3. 用户动作：切到对应程序窗口输入任务
4. 返回动作：把结果回填到当前 task 或创建 implement 子任务继续

建议 metadata：

```json
{
  "manual_window_required": true,
  "recommended_manual_provider": "trae",
  "manual_window_candidates": ["trae", "zcode"],
  "manual_followup_instruction": "请切到 trae 窗口执行，并将结果回填到当前任务后继续 verify。"
}
```

### 7.3 `zcode` 的第一版口径

因为当前仓库没有 `zcode` 实现，所以第一版只能：

- 在推荐列表中出现 `zcode`
- 不能在 `/workers` 中伪装成 ready worker
- 不能进入 route 的自动候选

## 8. UI / API 方案

### 8.1 `/api/v1/tasks/{id}/select_worker`

建议新增或补充以下字段：

- `cost_route_stage`
- `free_candidate_workers`
- `paid_candidate_workers`
- `manual_window_candidates`
- `manual_window_required`
- `recommended_manual_provider`
- `quota_fallback_reason`

### 8.2 `/api/v1/tasks/{id}/live_flow`

建议在 route preview 区同步透出：

- 本轮是否走了 paid fallback
- 为什么没有命中免费 provider
- 是否已切到手动窗口推荐

### 8.3 `/dialogue/`

当 `manual_window_required=true` 时，主链路文案不应显示成：

- “已切换到 trae”

而应显示成：

- “自动 provider 当前不可继续，建议改用手动窗口免费额度”
- “推荐：trae”
- “请切到外部程序窗口执行，完成后回填结果再继续”

### 8.4 `/console/`

建议增加轻量提示：

- 当前任务 route policy 是否是 `free_first`
- 当前是否因 quota fallback 切到 paid provider
- 当前是否停在 manual window gate

## 9. 实施步骤

### Phase A：先补 metadata 和文档合同

改动面：

- `WorkerRegistry`
- `docs/AGENT_PROVIDER_TECHNICAL_DESIGN.md`
- `docs/WORKER_FAILURE_RECOVERY_POLICY.md`
- `docs/API_CONTRACTS.md`

目标：

- 先把 worker cost/execution class 写实
- 把 `trae` manual-only 口径统一到基线文档

### Phase B：补 `WorkerRouter` free-first route

改动面：

- `WorkerRouter`
- 相关 route trace / HTTP 返回测试

目标：

- 自动路由先尝试免费自动 provider
- 免费组失败后再退 paid

### Phase C：补 recovery 收口

改动面：

- `ControlNodeGraph`
- recovery contract tests

目标：

- `manual_window` provider 不再进入 auto handoff
- quota exhaustion 走 fallback，不做 same-provider retry

### Phase D：补 `/dialogue/` 与 `/console/` 表达

改动面：

- `src/main/resources/web/dialogue/*`
- `src/main/resources/web/console/*`

目标：

- 用户能直接看懂“为什么现在要手动切窗口”
- operator 能直接看懂“为什么这轮已从免费切到付费”

## 10. focused test / 验证入口

第一版建议至少补下面几类验证。

### 10.1 路由层

- free candidate 可用时优先 `deveco`
- `codebuddy` 因 workspace gate 被排除时，不能因为“免费”而硬选
- 免费组 quota exhausted 后可退到 `codex` 或 `reasonix`
- `manual_window` provider 不进入自动 route 结果

### 10.2 恢复层

- `trae` 不再进入 auto recovery candidate
- quota exhaustion 不触发 same-worker retry
- 自动 provider 都不可用时进入 `human_gate` 并带 `manual_window_candidates`

### 10.3 HTTP / UI

- `/select_worker` 返回 `cost_route_stage` 和 `manual_window_required`
- `/live_flow` 能看出 paid fallback
- `/dialogue/` 能显示手动窗口提示，而不是伪装成自动 handoff

## 11. 风险与顺序建议

### 11.1 优先顺序

推荐顺序：

1. 先补文档和 metadata
2. 再补 route
3. 再补 recovery
4. 最后补 UI

原因：

- 先把口径写实，避免 UI 和后端对同一概念理解不一致。

### 11.2 主要风险

| 风险 | 说明 | 缓解方式 |
|---|---|---|
| 只改 priority，没改 route stage | `codex` 仍可能压过免费组 | 必须引入 cost-route 分层 |
| 把 `codebuddy` 当无条件免费自动位 | 本地工作区任务会误路由 | 保持 `free_auto_guarded` |
| `trae` 从普通 route 退了，但 recovery 还会切过去 | 用户体验仍然错乱 | recovery 同步收口 |
| `zcode` 被误写成系统已有 provider | 文档和实际不一致 | 只作为 external manual option |

## 12. 结论

第一版最重要的不是“把更多 worker 自动化”，而是把三条边界拉清：

1. `deveco / codebuddy` 属于免费优先自动链，但 `codebuddy` 仍受 guard。
2. `codex / reasonix` 属于付费自动 fallback。
3. `trae / zcode` 属于手动窗口链，只推荐，不自动执行。

按这个顺序推进后，复杂需求流可以稳定演进成：

- 自动免费先跑
- 自动付费兜底
- 实在不行再明确提示用户切到外部免费窗口工具继续

## 13. 2026-06-18 执行结果

已完成：

1. `WorkerRegistry` 为 `deveco / codebuddy / codex / reasonix / trae` 补齐免费/付费/手动窗口分类 metadata。
2. `WorkerRouter` 新增 `free_first` cost-stage 选择、quota exhaustion 过滤、manual-window recommendation 输出。
3. `ControlNodeGraph` 已在 route 无法自动继续时把手动窗口推荐收口到 `human_gate`，并写入 `manual_window_required`、`recommended_manual_provider`、`manual_followup_instruction`。
4. `/api/v1/tasks/{id}/select_worker`、`/api/v1/tasks/{id}/provider_selection`、`/api/v1/tasks/{id}/live_flow`、runtime cognition / fact surface 已透出免费优先诊断字段。
5. `trae` 已从默认 auto-recovery coding 候选中移除；`manual_only` worker 也不会再进入自动 route 候选集。

本轮未完成：

1. `zcode` 仍保持 external manual option，不注册为系统内 worker。

focused test 结果：

- `WorkerRouterRouteTraceTest` 29/29
- `TaskHandlerControlActionHttpTest` 28/28
- `TaskHandlerLiveFlowHttpTest` 20/20
- `TaskHandlerProviderSelectionHttpTest` 12/12
- `AgentRunServiceTest` 1/1
- `PromptBasedJudgmentServiceTest` 12/12

下一步如果继续这条主题，优先顺序应是：

1. 若要继续压实“手动执行后如何回填”，下一步优先判断是否要把 human-gate 之后的用户回填动作和完成回执继续固化成稳定 contract，而不只是停在 follow-up 文案。
2. 再决定是否把 quota exhaustion 进一步收口到统一 failure class。

## 14. 2026-06-30 前端读面补充结果

这轮没有再改后端路由合同，而是把已有 free-first route metadata 收口到前端可读面。

### 14.1 `/dialogue/` route box

当前 route box 会把下面几类状态翻成人话：

- `manual_window_required=true`
  - 显示“自动链路已停下，建议切到 `<provider>` 手动继续”
  - 补充 `manual_window_candidates`
  - 明确提示“完成后把结果回填当前任务，再继续 verify 或 handoff”
- `cost_route_stage=paid_auto`
  - 显示“免费自动链路不可用，当前已回退到付费 provider”
  - 补充 `free_candidate_workers / paid_candidate_workers`
- `fallback_reason`
  - `quota exhausted` 会翻成“免费 provider 额度已耗尽”
  - `fallback to paid_auto` 会翻成“免费自动 provider 当前不可用，已切到付费自动链路”
  - `manual window required` 会翻成“当前没有可继续的自动 provider”

### 14.2 `/console/` operator summary

当前 `Summary` 首屏会复用同一套 free-first 文案逻辑：

- `manual_window_required`
  - 直接进入 operator summary 的 blocker / recovery window
- `cost_route_stage=paid_auto`
  - 首屏明确提示“免费自动链路不可用，当前已回退到付费 provider”
- 同一条提示也会进入 summary foot chips

这意味着 free-first 现在已经形成：

`WorkerRouter / live_flow.route_preview -> Dialogue route box / Console operator summary`

而不是只存在于 raw route metadata。

### 14.3 Focused verification

本轮 focused 验证命令：

```powershell
node --test src/test/js/free-first-route-plan.test.mjs src/test/js/dialogue-product-readiness-plan.test.mjs src/test/js/console-surface-layering-plan.test.mjs
```

结果：

- 13/13 通过
- 新增 `free-first-route-plan.test.mjs`
  - 锁定 `manual_window_required` 的人话 blocker
  - 锁定 `paid_auto` fallback 的人话说明
- 现有 `dialogue-product-readiness-plan.test.mjs`
  - 新增 route box free-first hint 断言
- 现有 `console-surface-layering-plan.test.mjs`
  - 新增 operator summary 复用 free-first narration 断言

## 15. 2026-06-30 task action follow-up 补充结果

这轮继续沿 free-first/manual-window 主线补了最后一层用户动作提示，但仍不扩后端 HTTP 合同。

### 15.1 Dialogue task action note

当前 `buildTaskActionPlan(task)` 已开始直接消费 task metadata 中已有的：

- `manual_window_required`
- `recommended_manual_provider`
- `manual_window_candidates`
- `manual_followup_instruction`

当任务停在 `waiting_human / human_gate` 且命中 manual window gate 时，details 面板里的 action 区现在不再只剩“自动恢复 / 恢复 / 继续 / handoff”按钮，而会先显示一条显式说明：

- `手动窗口：<provider>`
- `自动链路已停下，建议切到 <provider> 手动继续。`
- `manual_followup_instruction`
- 若有多个候选，再补 `候选：trae、zcode。`

这意味着“先去哪个窗口、做完之后怎么回填”已经不再只停留在后端 `human_gate` metadata，而是直接进入 Dialogue 的任务动作读面。

### 15.2 稳定口径

这轮仍然没有新增 route preview 字段。当前固定口径是：

- route box / console summary 继续复用已有 route metadata 做人话解释
- task action note 继续复用已有 task metadata 中的 `manual_followup_instruction`

也就是说，前端 follow-up 提示优先走“复用现有 metadata”，而不是为了这条文案再扩一个新的 API 合同字段。

### 15.3 Focused verification

本轮 focused 验证命令：

```powershell
node --test src/test/js/dialogue-task-action-plan.test.mjs src/test/js/dialogue-task-action-render-plan.test.mjs src/test/js/dialogue-product-readiness-plan.test.mjs
```

结果：

- 新增 task-action plan 回归：锁定 manual window note 的 metadata 投影
- 新增 task-action render 回归：锁定 note DOM 渲染
- 现有 product-readiness 回归：锁定 `app.js` 已把 `noteHtml` 挂进任务动作区

## 16. 2026-06-30 后端 route/read surface 补充结果

这轮继续沿 free-first/manual-window 主线补齐后端读面，不再让 `manual_followup_instruction` 只停留在 task metadata 和前端 note。

### 16.1 当前已补齐的读面

- raw `/api/v1/tasks/{id}/select_worker`
- `/api/v1/tasks/{id}/live_flow.route_preview`
- `runtime_cognition_surface.route`
- `/api/v1/tasks/{id}/provider_selection.metadata`

现在这些 surface 都会稳定带出：

- `manual_window_required`
- `recommended_manual_provider`
- `manual_window_candidates`
- `manual_followup_instruction`

### 16.2 实现口径

- `manual_followup_instruction` 现在由 `WorkerRouter.RouteResult` 统一派生，避免 route preview、provider selection 和 `human_gate` 写回文案漂移。
- `ControlNodeGraph.applyManualWindowGate(...)` 已改成复用同一条 follow-up 文案来源。
- Dialogue task action note 仍直接消费 task metadata，但后端 route/read surface 已与它共享同一条手动窗口 follow-up 文案。

### 16.3 Focused verification

本轮 focused 验证结果：

- `TaskHandlerLiveFlowHttpTest` 21/21
- `TaskHandlerProviderSelectionHttpTest` 14/14
- `TaskHandlerControlActionHttpTest` 31/31

## 17. 2026-06-30 AgentRun 执行摘要面补充结果

这轮继续沿 free-first/manual-window 主线补齐执行摘要面，不再让 `manual_followup_instruction` 只停留在 route/read surface。

### 17.1 当前已补齐的摘要面

- `/api/v1/tasks/{id}/agent_run.metadata`
- `live_flow.provider_selection.metadata`
- `AgentRunService.recordWorkerRun(...)` 持久化的最新 `agent_run` 记录

这些面现在统一透出：

- `free_first_routing`
- `free_candidate_workers`
- `paid_candidate_workers`
- `cost_route_stage`
- `manual_window_required`
- `recommended_manual_provider`
- `manual_window_candidates`
- `manual_followup_instruction`

### 17.2 实现口径

- `AgentRunService.providerSelection(...)` 现在直接复用 `route.manualFollowupInstruction()`，因此 `/provider_selection` 与 `live_flow.provider_selection` 不再缺这条字段。
- `AgentRunService.recordWorkerRun(...)` 现在会把 free-first/manual-window 路由摘要一起写入 `agent_run.metadata`，避免 `/agent_run` 只剩 `candidate_workers / fallback_reason` 这类半截 route trace。
- `manual_followup_instruction` 仍保持单一来源：`WorkerRouter.RouteResult` 派生；执行摘要面只做投影，不单独拼新文案。

### 17.3 Focused verification

本轮 focused 验证结果：

- `AgentRunServiceTest` 2/2
- `TaskHandlerProviderSelectionHttpTest` 15/15
- `TaskHandlerLiveFlowHttpTest` 21/21

当前结论：

- `manual_followup_instruction` 已从 task metadata、route/read surface 继续推进到 `AgentRunService` 执行摘要面。
- operator 现在无论看 `/select_worker`、`/provider_selection`、`/live_flow` 还是 `/agent_run`，都能看到同一条手动窗口 follow-up 文案和同组 free-first/manual-window 诊断字段。
- 当前剩余边界主要只剩 `zcode` 仍是 external manual option，以及 quota exhaustion 是否继续上升为统一 failure class。
