# AGENT_ACTION_MODEL_DRAFT

## 1. 目的

本文档用于把 `agent-cloud-harness` 第一阶段最关键的执行接口 —— **Agent Action layer** —— 落成一份可直接指导代码实现的草案。

它回答四个问题：

1. 为什么当前 runtime 需要单独的 action contract
2. `AgentActionDraft` / `AgentAction` 应该如何建模
3. runtime 应如何对 action proposal 做 reconciliation
4. 这层对象如何与现有 `TaskService` / `ControlNodeGraph` / `WorkerExecutionResult` / `PacketBuilder` / UI 连接

这份文档不追求把 Goal layer 一并讲完，而是聚焦一个更近端、更可立即落代码的目标：

> 让 worker / agent 不再只是“返回文本结果”，而是能对 runtime 提交结构化执行动作提案。

---

## 2. 背景与动机

当前 `agent-cloud-harness` 已经具备较强的 continuity-first runtime 主链：

- task 持久化
- control graph
- worker execution
- runtime facts
- judgment trace
- live flow
- packet / checkpoint / handoff

但当前系统中很多“动作”仍然散落在不同层中：

- `ControlNodeGraph` 决定是否进入 packet / handoff / human gate / complete
- `TaskService` 决定某些 task state / projection / result 写法
- `PacketBuilder` 决定 continuity payload 中如何表达 next step
- `WorkerExecutionResult` 只能部分表达下一步建议
- judgment 只是在 task continuation / completion 层面给出判断

这带来两个问题：

### 2.1 执行层的动作意图没有正式对象

当前 worker 能表达：

- 输出文本
- artifact 文本
- suggested next step
- unfinished items

但还不能正式表达：

- 我想请求更多上下文
- 我建议生成子任务
- 我建议 handoff 给某个 worker
- 我认为现在可以 checkpoint
- 我认为当前 goal/task 已经完成
- 我认为当前阻塞需要 ask human

### 2.2 runtime 只能“猜”下一步，而不是“协调”下一步

现在 runtime 更多是在结果出来后根据：

- control node
- task status
- judgment
- metadata

去解释下一步该怎么办。

这意味着：

- 框架承担了过多执行判断
- LLM / worker 的执行自主权难以结构化扩展
- UI 难以清晰展示“agent 试图做什么”与“runtime 允许它做什么”

因此，需要一层明确的 action model，把系统推进为：

> worker 提 action proposal -> runtime 做 accept/reject/escalate -> runtime 落盘并投影 -> UI 显示动作状态

---

## 3. 设计目标

Agent Action layer 应满足以下目标：

### 3.1 明确区分 proposal 与 accepted action

要区分：

- worker / agent 提出的动作意图
- runtime 实际接受/拒绝/升级后的动作记录

也就是：

- `AgentActionDraft` = proposal
- `AgentAction` = runtime-reconciled action record

### 3.2 支持 bounded autonomy

这层不是为了“无限放权”，而是为了让执行层在治理边界内获得结构化自主权。

也就是说：

- agent 可以提动作
- runtime 不一定全收
- runtime 需要根据 policy / status / budget / approval boundary 做协调

### 3.3 支持 trace / resume / UI 复用

Action 不应该只是一瞬间的内部对象，而应成为：

- 事件留痕的一部分
- live flow / judgment trace 的一部分
- checkpoint / packet / resume continuity 的一部分
- UI 的工作对象视图的一部分

### 3.4 不推翻当前 task runtime

第一版 action model 必须可以叠加在现有：

- `TaskService`
- `ControlNodeGraph`
- `WorkerExecutionResult`
- `RuntimeFactSet`
- `PacketBuilder`

之上，而不是要求完全重写 runtime。

---

## 4. 核心对象模型

## 4.1 `AgentActionType`

建议先定义枚举，第一版支持以下动作：

- `WRITE_ARTIFACT`
- `REQUEST_CONTEXT`
- `SPAWN_SUBTASK`
- `HANDOFF`
- `CHECKPOINT`
- `MARK_COMPLETE`
- `ASK_HUMAN`
- `MARK_BLOCKED`

后续可扩展：

- `RETRY_WITH_WORKER`
- `REQUEST_PERMISSION`
- `REFINE_GOAL`
- `ARCHIVE_CONTEXT`
- `REOPEN_CONTEXT`

### 第一版原则

先只支持最小动作集，不要一开始把所有 orchestration 动作都做进去。

---

## 4.2 `AgentActionDraft`

`AgentActionDraft` 表示 worker / agent 在单轮执行后提出的动作提案。

建议字段：

- `actionType`
- `summary`
- `payload`
- `riskLevel`
- `requiresApproval`
- `reason`
- `confidence`

### 字段含义

#### `actionType`
动作类型，如 `CHECKPOINT` / `HANDOFF` / `MARK_COMPLETE`。

#### `summary`
一行摘要，便于 UI / trace 显示。

#### `payload`
动作专属参数。例如：

- `HANDOFF`：`to_worker`, `why`
- `SPAWN_SUBTASK`：`title`, `goal`, `task_type`
- `REQUEST_CONTEXT`：`needed_context`, `why`
- `WRITE_ARTIFACT`：`artifact_type`, `title`, `content`

#### `riskLevel`
建议枚举：

- `low`
- `medium`
- `high`
- `critical`

#### `requiresApproval`
是否建议人类或上层 supervisor 明确批准。

#### `reason`
动作理由，便于 judgment / operator 理解。

#### `confidence`
动作提案信心，可选，用于后续排序或 UI 展示。

### 设计原则

`AgentActionDraft` 是 **proposal object**，不是正式状态变更对象。

---

## 4.3 `AgentAction`

`AgentAction` 表示 runtime 对 proposal 协调后的正式动作记录。

建议字段：

- `id`
- `sessionId`
- `taskId`
- `sourceExecutionId`
- `actionType`
- `status`
- `summary`
- `payload`
- `riskLevel`
- `requiresApproval`
- `acceptedBy`
- `rejectionReason`
- `createdAt`
- `updatedAt`
- `metadata`

### `status` 建议枚举

- `proposed`
- `accepted`
- `rejected`
- `needs_approval`
- `executed`
- `superseded`

### 说明

#### `sourceExecutionId`
用于把 action 追溯回触发它的 worker execution boundary。

#### `acceptedBy`
记录是：

- runtime policy
- judgment service
- human
- supervisor worker

中的谁接受了该动作。

#### `rejectionReason`
拒绝时的原因，例如：

- policy_denied
- invalid_task_state
- missing_payload
- approval_required
- unsupported_action

### 设计原则

`AgentAction` 是 **reconciled action record**，可以被持久化、可被 UI 消费、可进入 checkpoint / packet / trace surface。

---

## 4.4 `AgentActionDecision`

建议增加一个更轻量的 reconciliation 输出对象，用于表示 runtime 对某个 draft 的处理结果。

建议字段：

- `draft`
- `decision`
- `reason`
- `acceptedAction`（可空）

其中 `decision` 枚举：

- `accept`
- `reject`
- `needs_approval`
- `rewrite`

### 为什么需要这个对象

因为 runtime 对 action 的处理不总是简单“接收或拒绝”，还可能：

- 要求审批
- 把 `MARK_COMPLETE` 改写成 `CHECKPOINT`
- 把 `HANDOFF` 降级成 `REQUEST_CONTEXT`

这个对象能更清楚表达协调过程。

---

## 5. `WorkerExecutionResult` 扩展建议

当前 `WorkerExecutionResult` 已具备 execution envelope 和 artifact/output 能力，第一版建议新增：

- `List<AgentActionDraft> proposedActions`
- `List<String> contextRequests`
- `String completionClaim`
- `String handoffTarget`
- `List<String> riskFlags`

### 含义

#### `proposedActions`
本轮 worker 明确提出的结构化动作建议。

#### `contextRequests`
如果 worker 认为缺少上下文，可单独挂这里，也可折叠为 `REQUEST_CONTEXT` action。

#### `completionClaim`
说明 worker 是否主张“当前已完成”，供 judgment / reconciler 参考。

#### `handoffTarget`
如果 worker 明确认为应移交给某个 worker，可单独挂这里，也可折叠为 `HANDOFF` action payload。

#### `riskFlags`
本轮执行识别到的风险，用于 policy / UI / operator surfacing。

### 第一版兼容策略

第一版不要求所有 worker 都稳定输出复杂结构 JSON。

可接受三种来源：

1. worker 原生结构化输出
2. executor 内部从 structured response / metadata 推断
3. runtime fallback 从旧字段（如 `suggestedNextStep` / `unfinishedItems`）推导最小 action draft

---

## 6. Reconciliation 流程

## 6.1 基本流程

建议新增 `AgentActionReconciler`，基本流程如下：

```text
WorkerExecutionResult
    -> extract proposed action drafts
    -> validate payload / task state / policy
    -> accept / reject / needs_approval / rewrite
    -> produce AgentActionDecision list
    -> persist accepted/rejected action records
    -> trigger side effects
    -> expose to live flow / judgment trace / packet surfaces
```

---

## 6.2 建议接口

```java
AgentActionReconciliationResult reconcile(Task task, WorkerExecutionResult result);
```

其中 `AgentActionReconciliationResult` 建议包含：

- `decisions`
- `acceptedActions`
- `rejectedActions`
- `approvalNeededActions`
- `sideEffects`

---

## 6.3 Reconciler 的核心职责

### A. payload 校验

例如：

- `HANDOFF` 没有 `to_worker` -> reject
- `SPAWN_SUBTASK` 没有 `title/goal` -> reject
- `WRITE_ARTIFACT` 缺内容 -> reject 或 rewrite

### B. task state 校验

例如：

- 已 terminal 的 task 不应再 accept `SPAWN_SUBTASK`
- waiting / paused task 上某些动作需要额外检查

### C. policy 校验

例如：

- 高风险动作要求 approval
- 某些 worker 不允许直接 `MARK_COMPLETE`
- 某些 session / mode 禁止自动 handoff

### D. rewrite / downgrade

例如：

- `MARK_COMPLETE` 但 unfinished items 非空 -> rewrite 为 `CHECKPOINT`
- `HANDOFF` 目标不可用 -> rewrite 为 `REQUEST_CONTEXT` 或 `ASK_HUMAN`

### E. side effect 触发

例如：

- `WRITE_ARTIFACT` -> 写 artifact
- `CHECKPOINT` -> 创建 checkpoint / refresh packet
- `HANDOFF` -> 调现有 handoff 流
- `MARK_COMPLETE` -> 进入 completion judgment 或 terminal path

---

## 7. 第一版 action 处理规则建议

## 7.1 `WRITE_ARTIFACT`

### accept 条件

- payload 含 `artifact_type`
- 至少有 title 或 content
- task 非 terminal

### side effects

- 写入 `artifacts`
- 记录 `action_accepted`
- live flow 增加 artifact summary

### 默认策略

第一版可直接 accept。

---

## 7.2 `CHECKPOINT`

### accept 条件

- task 非 terminal
- 当前 execution 有可压缩 runtime facts 或 recent output

### side effects

- 调 `ConsolidationService`
- 创建 checkpoint
- refresh resume packet
- 写入 event / action record

### 默认策略

第一版可直接 accept。

---

## 7.3 `MARK_COMPLETE`

### accept 条件

- task 非 terminal
- 未发现强 unfinished signals
- judgment 或 policy 未明确反对

### side effects

- 触发 completion judgment
- 如果 judgment 接受，则 task -> done
- 否则可 rewrite 为 `CHECKPOINT` / `REQUEST_CONTEXT`

### 默认策略

第一版不要直接 terminal，先经过 judgment。

---

## 7.4 `HANDOFF`

### accept 条件

- payload 含 `to_worker`
- 目标 worker 当前可用或可降级可用
- task 非 terminal

### side effects

- 调现有 handoff packet / handoff state 流
- 写入 handoff event / action record

### 默认策略

如果目标不可用，则：

- `needs_approval` 或
- `rewrite -> ASK_HUMAN`

---

## 7.5 `REQUEST_CONTEXT`

### accept 条件

- payload 含 `needed_context` 或 reason 不为空

### side effects

- 写入 event
- live flow 展示缺失上下文请求
- UI 提供 intervention hint

### 默认策略

第一版先只作为 surfaced request，不自动拉取外部上下文。

---

## 7.6 `SPAWN_SUBTASK`

### accept 条件

- payload 含 `title` 与 `goal`
- 当前 task 非 terminal
- policy 允许当前 worker 发起 child task

### side effects

- 在同一 session 下创建 child task
- relation/event/action record 落盘

### 默认策略

第一版只允许：

- 同 session
- parent_task_id = 当前 task
- 不允许深层自动 fan-out

---

## 7.7 `ASK_HUMAN`

### accept 条件

- reason 不为空

### side effects

- 写入 event
- task 可进入 waiting / human_gate 倾向
- UI 高亮 intervention needed

### 默认策略

第一版可直接 accept。

---

## 7.8 `MARK_BLOCKED`

### accept 条件

- reason 不为空

### side effects

- task waiting_reason 更新
- 写入 blocked event
- packet / live flow 暴露 blocker

### 默认策略

第一版可直接 accept，但不要自动关闭 task。

---

## 8. 与现有模块的连接点

## 8.1 `TaskService`

建议：

- 在 task continue / worker round 结束后接入 `AgentActionReconciler`
- 由 `TaskService` 负责 orchestration glue，但逐步把 action logic 外提

第一版不要让 `TaskService` 自己解释所有 action，而是调用：

- `AgentActionReconciler`
- `TaskContinuityService`（未来）
- `TaskProjectionService`（未来）

---

## 8.2 `ControlNodeGraph`

当前 graph 不必推翻，但可开始降权：

- graph 负责 boundary
- reconciler 负责 action coordination

也就是说：

- graph 决定是否进入某类边界节点
- reconciler 决定本轮动作是否有效、如何改写、如何落 side effect

---

## 8.3 `PacketBuilder`

第一版建议：

- 在 resume/handoff packet 中加入 recent accepted actions summary
- 如果存在 blocker / context request / completion claim，应进入 packet continuity payload

这样下一轮 context reconstruction 能看到：

- agent 上轮想做什么
- runtime 接受了什么
- 哪些动作被挂起或需要人工介入

---

## 8.4 `RuntimeFactSet`

建议扩展 action 相关 surface，例如：

- `recentActions`
- `actionSummary`
- `pendingApprovals`
- `openContextRequests`

让 live flow / judgment trace / operator view 能直接消费 action-level facts。

---

## 8.5 UI (`/console/`, `/dialogue/`)

第一版最小展示建议：

- Proposed Actions
- Accepted Actions
- Approval Needed
- Blockers / Context Requests

这会把 UI 从“只显示 trace”推进到“显示 agent 正在试图推动什么”。

---

## 9. 存储建议

第一版可以先不强制新建复杂表，但建议至少预留两种路径：

### 方案 A：先走事件 + metadata 轻量落地

把 action 记录先写到：

- `events`
- `decisions`
- task metadata / runtime fact surface

优点：

- 改动快
- 适合第一版验证

缺点：

- action 不是一等可查询对象
- 后续 UI / audit / analytics 会受限

### 方案 B：逐步增加 `agent_actions` 表

建议字段：

- `id`
- `session_id`
- `task_id`
- `source_execution_id`
- `action_type`
- `status`
- `summary`
- `payload_json`
- `risk_level`
- `requires_approval`
- `accepted_by`
- `rejection_reason`
- `created_at`
- `updated_at`
- `metadata_json`

建议路线：

- 第一版可先用 A
- 如果 action UI / audit / routing 迅速变重要，则尽快升级到 B

---

## 10. 验收标准

Agent Action 第一版落地后，至少应满足：

1. `WorkerExecutionResult` 能携带结构化 action proposals
2. runtime 能对最小动作集执行 accept / reject / needs_approval 协调
3. 至少以下动作能端到端跑通：
   - `WRITE_ARTIFACT`
   - `CHECKPOINT`
   - `MARK_COMPLETE`
4. live flow / console 至少能显示：
   - proposed actions
   - accepted actions
   - blockers / context requests
5. packet / resume continuity 中能带出关键动作摘要

---

## 10.1 当前落地进展（2026-05-27）

本轮已把草案中的 action contract 落成最小可运行闭环：

- 模型层：新增 `AgentActionDraft`、`AgentAction`、`AgentActionDecision`、`AgentActionReconciliationResult`
- 执行结果层：`WorkerExecutionResult` 已携带 `proposedActions`、`contextRequests`、`completionClaim`、`handoffTarget`、`riskFlags`
- worker 输出层：默认执行器与 tool-aware 执行器已解析 `proposed_actions`、`context_requests`、`completion_claim`、`handoff_target`、`risk_flags`，并在 JSON 输出契约中声明这些可选字段
- reconciliation 层：`AgentActionReconciler.reconcile(Task, WorkerExecutionResult)` 已按最小 policy 输出 accepted / rejected / needs_approval actions
- 持久化层：已从“方案 A”升级到“方案 A + 方案 B”。每个 reconciled action 会写入 `agent_actions` 一等表，同时继续写 `events` 和 worker artifact metadata 作为兼容 trace surface
- 查询层：新增 `AgentActionDao` 与 `/api/v1/agent_actions`，支持按 `task_id`、`session_id`、`action_type`、`status` 查询，以及 `/api/v1/agent_actions/{id}` 单条读取
- side effect：`WRITE_ARTIFACT` 的 accepted action 会写入正式 `Artifact`
- side effect：`CHECKPOINT` 的 accepted action 会写入 `Checkpoint`，并保留 `source_action_id`
- side effect：`SPAWN_SUBTASK` 的 accepted action 会插入 parent-linked 子 `Task`，并写入 `task --spawns--> task` relation
- side effect：`MARK_COMPLETE` 的 accepted action 会在 `ControlNodeGraph` 当前调度流内把主 task 标记为 `done/end`
- side effect：`HANDOFF` 的 accepted action 会在 `ControlNodeGraph` 当前调度流内生成 `handoff_before` packet/consolidation、切换 assigned worker，并进入 handoff 控制节点
- side effect：`ASK_HUMAN` / `MARK_BLOCKED` 的 accepted action 会在当前调度流内进入 `waiting_human/human_gate`
- runtime surface：runtime facts、cognition surface、export payload 已暴露 proposed / accepted / rejected / approval-needed actions，以及 context requests / completion claim / handoff target / risk flags
- packet / resume continuity：`ResumePacket.payload`、`HandoffPacket.metadata`、checkpoint refined packet 已汇总 `recent_actions`、`accepted_actions`、`rejected_actions`、`approval_needed_actions`、`action_context_requests`、`action_summary`
- UI surface：`/console/` 与 `/dialogue/` 已新增 Agent Actions 面板，按任务读取 `/api/v1/agent_actions`，展示 action status、risk、approval、payload 与 rejection reason

当前 reconciliation 行为：

- 非法或缺必要 payload 的 action 标记为 `rejected`
- `risk_level=high|critical` 或 `requires_approval=true` 的 action 标记为 `needs_approval`
- 其他合法 action 标记为 `accepted`
- `context_requests` 会折叠为 `REQUEST_CONTEXT`
- `completion_claim` 会折叠为 `MARK_COMPLETE`
- `handoff_target` 会折叠为 `HANDOFF`

仍未完成：

- 暂无阻塞 P0 缺口；后续可补 action 审批/驳回写接口与 UI 操作

验证入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=PacketBuilderProtocolTest,ConsolidationServiceProtocolTest,AgentActionReconcilerTest,AgentActionHandlerHttpTest,RuntimeCognitionSurfaceActionTest,ControlNodeGraphActionResolutionTest"
node --test src/test/js/dialogue-agent-action-plan.test.mjs src/test/js/dialogue-task-action-plan.test.mjs src/test/js/dialogue-task-action-render-plan.test.mjs
```

---

## 11. 非目标

本阶段的非目标：

- 不一次性做完整 action DAG / workflow compiler
- 不要求所有 worker 一开始都原生稳定输出复杂 JSON
- 不把所有 runtime decision 都迁移到 action layer
- 不在这一阶段同时解决完整 Goal schema / lifecycle

---

## 12. 一句话收束

Agent Action layer 的本质不是“多一个模型类”，而是：

> 把系统从“worker 产出结果，框架解释结果”推进成“worker 提出动作，runtime 协调动作”。

这是 `agent-cloud-harness` 从 task-centered continuity framework 走向 runtime-governed, agent-driven harness 的第一块真正可执行接口。
