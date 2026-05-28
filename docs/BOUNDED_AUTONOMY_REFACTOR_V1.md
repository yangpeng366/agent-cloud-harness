# BOUNDED_AUTONOMY_REFACTOR_V1

## 1. 背景

`agent-cloud-harness` 当前已经具备一条相对完整的 continuity-first runtime 主链：

- task 持久化
- control graph 驱动
- worker 执行
- execution trace / runtime facts 提炼
- resume packet / checkpoint / handoff packet
- live flow / judgment trace / console 观测

这说明项目并不是“还缺一个 agent 框架骨架”，而是已经形成了较强的 **task-centered continuity substrate**。

但从当前代码结构和运行时权力分配看，系统的主导形态仍然更接近：

- framework 主导 task flow
- LLM / worker 负责在既定骨架内执行
- UI 主要展示 trace / status / diagnostics

而下一阶段希望推进的方向是：

- framework 主要负责治理、状态承载、恢复、审计、预算与权限边界
- LLM / agent / worker 对执行推进拥有更强的结构化自主权
- 页面尽量打通用户原始需求、执行过程、关键决策与最终产物

因此，这次改造的重点不是“继续堆功能”，而是 **重排控制权结构**。

一句话概括本次改造目标：

> 将 `agent-cloud-harness` 从 task-centered continuity framework，推进为 runtime-governed, agent-driven harness。

---

## 2. 当前问题

### 2.1 `TaskService` 过重

`TaskService` 当前同时承担：

- task create / continue / resume / handoff 生命周期入口
- session current task 同步
- runtime fact assembly
- cognition surface projection
- experiment run 刷新
- agent run / recovery job 关联
- assistant progress / result message 投影

这会导致：

- service 边界不清
- 后续 Goal layer、AgentAction layer、UI work surface 继续接入时容易进一步膨胀
- runtime governance 与 execution projection 难以分离

### 2.2 `ControlNodeGraph` 权力过高

当前 control graph 已经串起：

- intake
- scheduler
- continue
- packet
- human gate
- handoff

它不仅承担治理边界，还在很大程度上预设了执行流程骨架。这意味着：

- framework 在替 agent 决定主要推进路径
- worker / LLM 的动作仍更多表现为“产出结果供框架解释”
- 扩展自主执行时容易不断把策略堆进 control graph

### 2.3 `WorkerExecutionResult` 仍偏“结果导向”

当前 `WorkerExecutionResult` 已经拥有：

- summary
- outputText
- artifactTitle / artifactContent
- suggestedNextStep
- executionStatus
- evidenceRefs
- unfinishedItems
- execution envelope metadata

但它仍主要表达“这轮执行产出了什么”，尚未正式表达：

- agent 想对 runtime 发起什么动作
- 当前是否请求更多上下文
- 是否建议 handoff / checkpoint / subtask / completion
- 哪些动作具备风险或需要审批

这限制了系统从“结果导向执行”向“动作驱动执行”演进。

### 2.4 continuity surfaces 仍主要围绕 task

当前 packet / runtime facts / live flow / judgment trace 的中心对象仍是 task identity。

这带来的问题是：

- 原始需求还没有成为一等对象
- 多任务 / 子任务 / 产物 / 决策难以在更高层统一归并
- UI 更容易沿 task timeline 展开，而不是沿 work object 展开

### 2.5 UI 仍更像 trace panel，而不是 work surface

`/console/` 与 `/dialogue/` 当前已经具备较强的可观测性价值，但主要仍用于：

- 看状态
- 看工具链
- 看 runtime trace
- 看 judgment / live flow

还没有充分形成：

- 原始需求锚点
- 当前工作对象视图
- 产物中心视图
- 用户干预入口

---

## 3. 目标状态

目标状态不是“框架放弃治理”，而是重新划分治理与执行的边界。

### 3.1 治理层（Framework / Runtime）

框架应主要负责：

- policy / permission boundary
- state surfaces 与 durable / ephemeral 边界
- event / audit / trace
- checkpoint / recovery / resume
- budget / retry / escalation boundary
- human gate / approval gate

它不应过度规定：

- 任务一定如何拆分
- 一轮后一定如何推进
- 何时必须 handoff / checkpoint / ask context
- 哪个步骤必须先于哪个步骤

### 3.2 执行层（LLM / Agent / Worker）

执行层应主要负责：

- 目标理解
- 局部任务拆解
- 工具与 worker 选择建议
- 需要什么上下文
- 当前阶段该产出什么对象
- 是否建议 handoff / checkpoint / completion / block

也就是说，执行层不只是“返回文本结果”，而是“对 runtime 提出结构化执行动作”。

### 3.3 呈现层（UI / Console / Dialogue）

页面应主要打通：

- 用户原始需求
- 当前 run 状态
- 关键 decision / trace summary
- 已产出的 artifact
- 用户可介入的动作点

这会让 UI 从 chat/trace-centric 面板，逐步转成 work-object-centric 工作面。

---

## 4. 一期落地方案

本次改造建议按三个 phase 推进，而不是一次性推翻当前 task runtime。

### Phase 1：引入 Agent Action 层

目标：在不推翻现有 control graph / task runtime 的前提下，为 LLM / worker 建立正式的 runtime action contract。

#### 新增对象

建议新增：

- `AgentActionDraft`
- `AgentAction`
- `AgentActionReconciler`

#### `AgentActionDraft` 作用

表示 worker 在单轮执行后提出的“动作提案”，例如：

- `write_artifact`
- `request_context`
- `spawn_subtask`
- `handoff`
- `checkpoint`
- `mark_complete`
- `ask_human`
- `mark_blocked`

#### `AgentAction` 作用

表示 runtime 接收、拒绝或升级后的正式动作记录，用于：

- 状态迁移
- 事件留痕
- live flow 展示
- 审批 / 判断 / 恢复逻辑复用

#### `WorkerExecutionResult` 扩展

建议新增字段：

- `proposedActions`
- `contextRequests`
- `completionClaim`
- `handoffTarget`
- `riskFlags`

这会把 `WorkerExecutionResult` 从“执行结果对象”推进成“执行结果 + 动作意图对象”。

#### `AgentActionReconciler` 职责

负责：

- 读取 `WorkerExecutionResult.proposedActions`
- 根据 runtime policy / task status / control boundary 判定 accept / reject / needs_approval
- 把接受结果写回 event / artifact / task state / projection surfaces

#### 第一版建议只跑通的 action

优先只落最小动作集：

- `write_artifact`
- `checkpoint`
- `mark_complete`
- `handoff`
- `request_context`
- `spawn_subtask`

其中：

- `write_artifact` / `checkpoint` 可以直接接受
- `mark_complete` 仍需结合 judgment
- `handoff` 接入现有 handoff 流
- `request_context` 先落 event + live flow surface
- `spawn_subtask` 第一版只允许同 session child task

### Phase 2：降低框架主控权

目标：让 control graph 从“执行主控流程图”降级成“治理边界状态机”。

#### 主要方向

- 保留 intake / scheduler / human gate / handoff / packet / terminal boundary
- 逐步把“下一步怎么推进”的判断从 `ControlNodeGraph` 中抽出
- 更依赖 `WorkerExecutionResult` + `AgentActionReconciler` + judgment result

#### judgment 调整方向

当前 judgment 更偏：

- 要不要继续
- 要不要完成
- 要不要 handoff

后续应逐步升级为：

- 动作提案是否可接受
- 哪些动作需要审批
- 哪些动作应被拒绝/降级
- 哪些动作应被重写为别的 continuation action

即从 completion reviewer 逐步转向 policy-aware action reconciler。

#### `TaskService` 拆分方向

建议至少先拆出：

- `TaskProjectionService`
- `TaskContinuityService`

后续再继续拆：

- `TaskLifecycleService`
- `TaskRunAccountingService`

### Phase 3：补 Goal 层与工作面

目标：把原始需求从 task 文本字段中拉出来，并让 UI 围绕工作对象展开。

#### schema 最小扩展

新增：

- `goals`
- `goal_events`
- `tasks.goal_id`

第一版 goal 不追求复杂，只需要承载：

- 原始需求锚点
- goal status / phase
- success criteria
- constraints
- progress summary

#### PacketBuilder 调整方向

将当前 task-centered continuity payload 逐步升级为 goal-aware continuity payload，至少补充：

- `goal_id`
- `goal_phase`
- `goal_progress_summary`
- `success_criteria`
- `constraints`

#### UI 调整方向

在 `/console/` 与 `/dialogue/` 中增加更明确的 work-surface 信息块，例如：

- Original Goal
- Current Run
- Proposed / Accepted Actions
- Artifacts
- Next Intervention

---

## 5. 具体改动点

| 模块 | 当前问题 | 改造方向 |
|---|---|---|
| `TaskService` | 过重、职责过多 | 拆分 lifecycle / projection / continuity / accounting |
| `ControlNodeGraph` | 预设过多执行流程 | 降级为 governance / boundary graph |
| `WorkerExecutionResult` | 只有结果导向，缺 action-intent | 增加 proposed actions / context / claim / risk surface |
| `schema.sql` | 没有 goal 一等对象 | 新增 `goals` / `goal_events` / `tasks.goal_id` |
| `PacketBuilder` | task-centered continuity | 逐步升级成 goal-aware continuity |
| `/console/` `/dialogue/` | trace-heavy | 增加 work-object / intervention oriented surfaces |

---

## 6. 对象模型草案

### 6.1 `AgentActionDraft`

建议字段：

- `actionType`
- `summary`
- `payload`
- `riskLevel`
- `requiresApproval`

用途：表达 worker 对 runtime 的动作提案。

### 6.2 `AgentAction`

建议字段：

- `id`
- `taskId`
- `sessionId`
- `actionType`
- `status` (`proposed`, `accepted`, `rejected`, `needs_approval`, `completed`)
- `summary`
- `payload`
- `acceptedBy`
- `createdAt`
- `updatedAt`

用途：表达 runtime 已接收的正式动作记录。

### 6.3 `WorkerExecutionResult` 新字段建议

- `List<AgentActionDraft> proposedActions`
- `List<String> contextRequests`
- `String completionClaim`
- `String handoffTarget`
- `List<String> riskFlags`

### 6.4 `AgentActionReconciler`

建议职责接口：

- `reconcile(Task task, WorkerExecutionResult result)`
- 输出一组 accepted / rejected / approval-needed actions
- 写入 event / task state / projection side effects

---

## 7. 实施顺序

### P0

- 新增 `AgentActionDraft`
- 扩展 `WorkerExecutionResult`
- 新增 `AgentActionReconciler`
- 跑通 `write_artifact` / `checkpoint` / `mark_complete`
- 在 live flow / console 中展示 proposed / accepted actions

### P0 当前落地进展（2026-05-27）

已落地的最小闭环：

- 新增 `AgentActionDraft`、`AgentAction`、`AgentActionDecision`、`AgentActionReconciliationResult`
- `WorkerExecutionResult` 已扩展 `proposedActions`、`contextRequests`、`completionClaim`、`handoffTarget`、`riskFlags`，并保留旧构造器兼容
- `DefaultWorkerExecutor` / `ToolAwareWorkerExecutor` 已支持解析 worker JSON 中的 bounded-autonomy 字段，并在提示词 JSON 契约中显式声明这些可选字段
- `AgentActionReconciler` 已接入 `ControlNodeGraph.scheduler` 的 worker round 之后，对 action proposal 执行 `accepted` / `rejected` / `needs_approval` 协调
- 已新增 `agent_actions` 一等表、`AgentActionDao`、RowMapper 与 `/api/v1/agent_actions` 查询端点；动作决策会写入正式 action record，同时继续保留 `events` + worker artifact metadata 兼容投影
- `/api/v1/agent_actions` 支持按 `task_id`、`session_id`、`action_type`、`status` 查询；`/api/v1/agent_actions/{id}` 支持按 id 获取单条 action
- 已实现 `WRITE_ARTIFACT` accepted side effect：runtime 会写入正式 `Artifact`
- 已实现 `CHECKPOINT` accepted side effect：runtime 会写入 `Checkpoint`，checkpoint type 可由 payload 的 `checkpoint_type` 指定
- 已实现 `SPAWN_SUBTASK` accepted side effect：runtime 会插入 parent-linked 子 `Task`，并写入 `task --spawns--> task` relation
- 已实现主 task 流内的 `MARK_COMPLETE` accepted side effect：scheduler 同轮把 task 标记为 `done/end`，避免被旧 task 状态覆盖
- 已实现主 task 流内的 `HANDOFF` accepted side effect：scheduler 同轮生成 `handoff_before` packet/consolidation，切换 assigned worker，并进入 handoff 控制节点
- 已实现 `ASK_HUMAN` / `MARK_BLOCKED` accepted side effect：scheduler 同轮进入 `waiting_human/human_gate`
- action-only worker round 也会写入 `worker_output` artifact，保证 runtime facts / cognition surface / live flow 能拿到最新 action metadata
- `RuntimeFactSetAssembler`、`RuntimeCognitionSurfaceAssembler`、`RuntimeFactSurfaceExporter` 已投影 proposed / accepted / rejected / approval-needed actions、context requests、completion claim、handoff target、risk flags
- packet / resume continuity 已显式汇总关键 action 摘要：`ResumePacket.payload`、`HandoffPacket.metadata`、checkpoint refined packet 会写入 `recent_actions`、`accepted_actions`、`rejected_actions`、`approval_needed_actions`、`action_context_requests`、`action_summary`
- `/console/` 与 `/dialogue/` 已新增专门 Agent Actions 展示面，按任务调用 `/api/v1/agent_actions`，优先展示待审批、已拒绝、已接受 action，并保留 payload / risk / approval 信息

仍未完成的 P0/P1 缺口：

- 暂无阻塞 P0 缺口；后续可继续扩展 action 审批/驳回的写操作 API

验证入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=PacketBuilderProtocolTest,ConsolidationServiceProtocolTest,AgentActionReconcilerTest,AgentActionHandlerHttpTest,RuntimeCognitionSurfaceActionTest,ControlNodeGraphActionResolutionTest"
node --test src/test/js/dialogue-agent-action-plan.test.mjs src/test/js/dialogue-task-action-plan.test.mjs src/test/js/dialogue-task-action-render-plan.test.mjs
```

### P1

- 拆 `TaskProjectionService`
- 拆 `TaskContinuityService`
- `ControlNodeGraph` 降权
- judgment 从 completion-centric 向 action reconciliation 迁移

### P2

- schema 增加 `goals` / `goal_events` / `tasks.goal_id`
- `PacketBuilder` 增加 goal-aware 字段
- `/console/` `/dialogue/` 增强 Original Goal / Artifacts / Next Intervention 视图

---

## 8. 非目标

本次改造的非目标：

- 不推翻现有 task runtime
- 不一次性改造成完整 goal-native orchestration platform
- 不把 Java 中的流程硬编码简单迁移到 prompt 中假装自主
- 不先做复杂多 agent planner / workflow compiler
- 不以“更多功能点”替代“控制权结构重排”

---

## 9. 验收标准

第一版改造完成后，至少应满足：

1. worker 能返回结构化动作提案，而不只是文本结果
2. runtime 能对动作提案执行 accept / reject / needs_approval 的最小协调
3. live flow / console 能展示 proposed / accepted actions
4. 至少一条 `checkpoint` / `handoff` / `mark_complete` 动作链能端到端跑通
5. 页面上能同时看到：
   - 原始需求（哪怕第一版还是 task/goal 混合态）
   - 当前运行状态
   - 已产出的 artifact
   - 下一步干预点

---

## 10. 建议配套文档

为了避免主文档过胖，建议后续补两份配套文档：

1. `docs/AGENT_ACTION_MODEL_DRAFT.md`
   - 聚焦 action object、状态流转、reconciliation 规则
2. `docs/GOAL_LAYER_MINIMAL_SCHEMA_PLAN.md`
   - 聚焦 `goals` / `goal_events` / `tasks.goal_id` 的最小 schema 与迁移策略

---

## 11. 一句话收束

本次改造的核心不是“再造一个更大的框架”，而是：

> 让 framework 退到治理层，让 agent / worker 获得结构化执行推进权，让页面把需求、执行和产物重新连起来。
