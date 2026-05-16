# GOAL_RUNTIME_LANDING_DIFF_2026-05-11

## 1. 目的

本文档把上一轮提出的“独立 Goal lifecycle runtime”方案，直接对照当前 `agent-cloud-harness` 代码现状，整理成一份可开工的落地说明。

它重点回答 4 个问题：

1. 当前代码里已经有什么，不需要从零重造
2. 当前为什么还不能算真正的 persisted goal system
3. 要新增哪些对象、表、API、hook 才能补齐 Goal 层
4. 推荐按什么顺序落地，风险最小

这不是抽象 roadmap，而是“对比当前代码，往哪改”的实施文档。

---

## 2. 一句话判断

当前仓库已经有比较完整的 **task continuity runtime**，但还没有独立的 **goal continuity runtime**。

所以它现在更像：

```text
persisted task loop
  + packet / checkpoint / judgment / runtime facts
```

而不是：

```text
persisted goal runtime
  -> goal owns lifecycle
  -> task / subtask / handoff are executions under one goal
  -> continuation / completion / budget / audit all reconcile back to goal
```

也就是说，当前实现已经很接近“加强版 continue-task harness”，但还不是 Codex 风格的 goal-native harness。

---

## 3. 当前代码现状，对照结论

## 3.1 已经具备的底座

结合当前代码，下面这些底座已经真实存在。

### A. Task 持久化与生命周期控制已经存在

现状证据：

- `src/main/resources/schema.sql`
  - `tasks`
  - `events`
  - `resume_packets`
  - `checkpoints`
  - `decisions`
  - `artifacts`
- `src/main/java/com/agentcloud/model/Task.java`
- `src/main/java/com/agentcloud/store/TaskDao.java`
- `src/main/java/com/agentcloud/engine/TaskService.java`
- `src/main/java/com/agentcloud/engine/ControlNodeGraph.java`

结论：
当前系统已经能把一个 task 当成跨轮实体来持久化、迁移状态、记录事件、生成 packet、进入 control graph。

### B. ControlNodeGraph 已经形成最小 runtime loop

现状证据：

- `ControlNodeGraph.enter(...)`
- `intake -> scheduler -> continue -> packet / human_gate / handoff`

结论：
当前 loop 的主控对象是 `Task.controlNode`，不是 goal。
这很重要，因为后面 Goal runtime 最自然的接入点，就是在这条 task 控制流之外再套一层 goal reconciliation。

### C. Resume / Handoff / Checkpoint 语义已经存在

现状证据：

- `engine/memory/PacketBuilder.java`
- `model/ResumePacket.java`
- `model/HandoffPacket.java`
- `checkpoints` 表
- `ConsolidationService`
- `TaskService.refreshResumePacket(...)`
- `TaskService.getHandoffPacket(...)`

结论：
系统已经有 continuity packet 能力。
缺的不是 packet 本身，而是 packet 还主要围绕 `task identity` 组织，而不是围绕 `goal identity` 组织。

### D. Judgment / RuntimeFact / LiveFlow 已经有观测面

现状证据：

- `RuntimeFactSet`
- `TaskLiveFlowView`
- `JudgmentTraceView`
- `RuntimeFactSet.ExecutionBoundary`
- `ExperimentRunService`

结论：
这说明 Goal 层不需要另起一整套观测系统，而应该复用现有 runtime facts / trace / experiment surface，把它们提升为“goal-aware”。

### E. 已经有一部分 budget/accounting 雏形

现状证据：

- `ExperimentRunRecord`
- `ExperimentRunService`
  - `total_cost`
  - `strong_model_cost_ratio`
  - `handoff_count`
  - `resume_count`
  - `human_gate_count`

结论：
预算/核算完全不是零基础。
但它现在是“按 task run 聚合的实验统计”，不是“按 goal 生命周期收口的运行核算”。

---

## 3.2 当前还缺什么

## 缺口 1：没有 Goal 作为独立持久化实体

当前 schema 里没有：

- `goals`
- `goal_events`
- `goal_id` 外键主线
- `goal status / goal phase / goal completion semantics`

现状：

- `tasks.goal` 只是一个文本字段
- `TaskCreateRequest.goal` 只是建 task 时的文本输入
- `Task.metadata.goal` 也可能重复存一份

结论：
现在的 `goal` 更像 task description / intent text，不是一个独立 runtime object。

## 缺口 2：Task 是当前最上层控制对象

`TaskService.createTask(...)` 当前逻辑：

- 接收 `goal` 文本
- 创建 `Task`
- 直接进入 `ControlNodeGraph`

这意味着：

- goal 不拥有任务
- task 也不会回写 goal 状态
- subtask / handoff / experiment 并不天然归属于同一个 goal identity

结论：
当前系统是 `task-first`，不是 `goal-first`。

## 缺口 3：Packet 仍然是 task-centered

`PacketBuilder` 现在产出的核心主键是：

- `task_identity`
- `task_id`
- `session_id`
- `parent_task_id`

虽然 payload 里有：

- `current_objective`
- `active_goal`

但这仍是文本级字段，不是结构化的 goal snapshot。

缺失项包括：

- `goal_id`
- `goal_snapshot`
- `goal_success_criteria`
- `goal_constraints`
- `goal_progress_summary`
- `goal_phase`

结论：
resume / handoff 能携带任务连续性，但还不能稳定携带目标连续性。

## 缺口 4：Continuation judgment 只对 task 收敛，不对 goal 收敛

现在 `continueNode(...)` 的判断结果最终驱动的是：

- 继续 task
- checkpoint
- handoff
- human gate
- done / failed

但没有一层显式逻辑去回答：

- 当前 task 完成后，goal 是否完成
- 当前 task 失败后，goal 是 blocked / failed / needs_replan / superseded
- 当前子任务结果如何累积到 goal progress

结论：
当前是 task continuation runtime，不是 goal continuation runtime。

## 缺口 5：完成审计还没有提升到 goal closure

虽然已有：

- `completionJudgment`
- `acceptanceResult`
- `evaluation_result`
- `quality note`

但这些仍然主要挂在 task / experiment run 上。

缺失的是一个显式的：

```text
goal completion audit
```

它至少需要回答：

- goal 是否满足 success criteria
- 哪些 deliverables 已完成
- 哪些 constraints 被满足或违反
- 是否允许 close / archive / reopen / fork follow-up goal

## 缺口 6：预算、成本、恢复统计没有按 goal 汇总

`ExperimentRunService` 已经会算 cost 和 handoff/resume/human_gate。

但当前问题是：

- 聚合单位主要还是单 task run
- 对多 task / subtask / orchestrated chain 的总账还不够自然

如果引入 Goal runtime，budget/accounting 最自然的归属应该是 goal：

- goal 总成本
- goal 子任务成本分布
- goal reopen 次数
- goal failover / handoff 次数
- goal 平均恢复深度

---

## 4. 当前代码最适合的接入方式

## 4.1 不要推翻 Task runtime，而是外包一层 Goal runtime

最合适的结构不是：

- 用 Goal 取代 Task

而是：

- 保留 Task 作为执行单元
- 新增 Goal 作为生命周期拥有者
- Task runtime 每次关键迁移后，回调 Goal runtime 做 reconciliation

建议抽象：

```text
GoalRuntime
  owns Goal lifecycle
  observes Task transitions
  decides goal-level status / continuation / closure / reopen / follow-up

TaskRuntime
  executes concrete work units
  emits events / packets / judgments / checkpoints
  reports result to GoalRuntime
```

## 4.2 最自然的 hook 点

### Hook A: `TaskService.createTask(...)`

如果请求里带 goal 信息，应优先：

- create or attach goal
- 再 create task
- 在 task metadata 和显式字段里写入 `goal_id`

### Hook B: `ControlNodeGraph.continueNode(...)`

当 task judgment 产出 action 后：

- 先完成 task 级迁移
- 再触发 `GoalRuntime.onTaskTransition(...)`

### Hook C: `TaskService.refreshResumePacket(...)` / `getHandoffPacket(...)`

packet builder 需要从 task-centered payload 升级到：

- task identity
- goal snapshot
- goal-scoped objective / progress / success criteria

### Hook D: `TaskService.getLiveFlow(...)` / `getJudgmentTrace(...)`

live flow / judgment trace 应新增：

- `goal`
- `goal_progress`
- `goal_status`
- `goal_budget`
- `goal_completion_audit`

这样 console 才能看到“task 在做什么”之外，也看到“它服务的 goal 现在到哪了”。

---

## 5. 推荐新增的持久化模型

## 5.1 goals

建议新增表：

```sql
CREATE TABLE goals (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  parent_goal_id TEXT,
  title TEXT NOT NULL,
  status TEXT NOT NULL,
  phase TEXT,
  priority TEXT,
  source_task_id TEXT,
  active_task_id TEXT,
  summary TEXT,
  objective TEXT,
  success_criteria_json TEXT,
  constraints_json TEXT,
  budget_json TEXT,
  progress_json TEXT,
  outcome_summary TEXT,
  opened_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  closed_at TEXT,
  metadata_json TEXT,
  FOREIGN KEY(session_id) REFERENCES sessions(id)
);
```

建议状态：

- `active`
- `blocked`
- `waiting`
- `completed`
- `failed`
- `superseded`
- `archived`

建议 phase：

- `intake`
- `planning`
- `executing`
- `evaluating`
- `waiting`
- `closing`

## 5.2 给 tasks 增加 `goal_id`

当前 `tasks` 表最需要补的不是更多文本列，而是：

```sql
ALTER TABLE tasks ADD COLUMN goal_id TEXT;
```

这样：

- parent/child task 仍保持 task tree
- 但目标归属通过 `goal_id` 显式表达

这一步非常关键。
没有 `goal_id`，后面的 packet、subtask、experiment、audit 都只能靠 metadata 猜。

## 5.3 goal_events

建议新增：

```sql
CREATE TABLE goal_events (
  id TEXT PRIMARY KEY,
  goal_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  task_id TEXT,
  created_at TEXT NOT NULL,
  event_type TEXT NOT NULL,
  summary TEXT,
  payload_json TEXT,
  FOREIGN KEY(goal_id) REFERENCES goals(id)
);
```

用途：

- goal created
- task attached
- task completed
- replanned
- budget exceeded
- success criteria satisfied
- goal closed / reopened / superseded

## 5.4 goal_completion_audits

建议新增：

```sql
CREATE TABLE goal_completion_audits (
  id TEXT PRIMARY KEY,
  goal_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  task_id TEXT,
  created_at TEXT NOT NULL,
  audit_status TEXT NOT NULL,
  alignment_level TEXT,
  summary TEXT,
  criteria_results_json TEXT,
  unmet_items_json TEXT,
  recommended_action TEXT,
  metadata_json TEXT,
  FOREIGN KEY(goal_id) REFERENCES goals(id)
);
```

这张表的意义是把“done 不 done”从 task judgment 里拉出来，变成 goal closure 的显式依据。

## 5.5 可选：goal_budget_ledger

如果先想做轻量版，可以先不单独建表，把 budget/accounting 聚合写回 `goals.budget_json` 与 `progress_json`。

但如果要做长期演进，我更建议单独有 ledger：

```sql
CREATE TABLE goal_budget_ledger (
  id TEXT PRIMARY KEY,
  goal_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  task_id TEXT,
  created_at TEXT NOT NULL,
  entry_type TEXT NOT NULL,
  cost_units REAL,
  model_tier TEXT,
  summary TEXT,
  metadata_json TEXT,
  FOREIGN KEY(goal_id) REFERENCES goals(id)
);
```

---

## 6. 推荐新增的 Java 对象

## 6.1 model

建议新增：

- `model/Goal.java`
- `model/GoalCreateRequest.java`
- `model/GoalProgressSnapshot.java`
- `model/GoalSnapshot.java`
- `model/GoalEvent.java`
- `model/GoalCompletionAudit.java`
- `model/GoalBudgetView.java`

其中最关键的是：

### `Goal`

最小字段建议：

- `id`
- `sessionId`
- `parentGoalId`
- `title`
- `status`
- `phase`
- `priority`
- `sourceTaskId`
- `activeTaskId`
- `summary`
- `objective`
- `successCriteria`
- `constraints`
- `budget`
- `progress`
- `outcomeSummary`
- `openedAt`
- `updatedAt`
- `closedAt`
- `metadata`

### `GoalSnapshot`

这是 packet/handoff/resume 必备对象，建议包含：

- `goalId`
- `title`
- `status`
- `phase`
- `objective`
- `successCriteria`
- `constraints`
- `activeTaskId`
- `progressSummary`
- `openDeliverables`
- `budgetSummary`
- `latestAuditStatus`

---

## 6.2 store

建议新增：

- `store/GoalDao.java`
- `store/GoalEventDao.java`
- `store/GoalCompletionAuditDao.java`
- 可选：`store/GoalBudgetLedgerDao.java`

当前仓库已经广泛采用 Jdbi SQL Object，这里直接沿用即可，不需要另起 ORM。

---

## 6.3 engine

建议新增：

- `engine/GoalService.java`
- `engine/GoalRuntime.java`
- `engine/GoalRuntimeHooks.java`
- `engine/GoalCompletionAuditService.java`
- `engine/GoalBudgetAccountingService.java`

职责建议：

### `GoalService`

负责：

- create / get / list goals
- attach task to goal
- query goal snapshot
- close / reopen / supersede goal

### `GoalRuntime`

负责：

- `onTaskCreated(task)`
- `onTaskTransition(before, after, judgments, executionFacts)`
- `onTaskDone(task)`
- `reconcileGoal(goalId)`

这是最关键的类。
它就是“task runtime 外面那一层 goal lifecycle runtime”。

### `GoalCompletionAuditService`

负责把当前已有：

- completion judgment
- latest packet
- runtime facts
- artifacts
- decisions

压成一份显式的 goal completion audit。

### `GoalBudgetAccountingService`

优先复用：

- `ExperimentRunService`
- `ToolInvocationRecord`
- worker artifact metadata

把 task 级成本汇总成 goal 级成本视图。

---

## 7. 现有类需要改哪些地方

## 7.1 `Task`

建议新增显式字段：

- `goalId`

原因：

- 不要把它只塞进 metadata
- 不然所有上层逻辑都要反复 parse metadata

如果短期不想改 record 签名太多，也至少要先把 DB 列、DAO、mapper 打通，然后再逐步把它从 metadata 挪成一等字段。

但我的建议是一次性改成显式字段，长期更干净。

## 7.2 `TaskCreateRequest`

建议扩成两种模式：

### 模式 A：直接挂载已有 goal

- `goalId`

### 模式 B：创建 task 时隐式创建 goal

- `goalTitle`
- `goalObjective`
- `goalSuccessCriteria`
- `goalConstraints`
- `goalBudget`

这样 task create API 才不会继续把 goal 降级成一段字符串。

## 7.3 `TaskService.createTask(...)`

当前是：

```text
resolve session
-> create Task
-> maybe autoStart
```

建议改成：

```text
resolve session
-> resolve/create goal
-> create Task with goal_id
-> GoalRuntime.onTaskCreated(task)
-> maybe autoStart
```

## 7.4 `ControlNodeGraph`

建议不要大改主 loop，只加 hook。

在这些点加 GoalRuntime hook：

- `schedulerNode(...)` 完成 worker round 后
- `continueNode(...)` 产出 judgment 后
- `packetNode(...)` 生成 packet 后
- `handoffNode(...)` 完成 handoff 后
- task 进入 `done/failed/waiting_human` 等终态或半终态时

推荐最小接口：

```java
goalRuntime.onTaskTransition(beforeTask, afterTask, executionResult, executionJudgment, completionJudgment, runtimeFacts);
```

## 7.5 `PacketBuilder`

这是 Goal 落地的关键修改点之一。

当前 packet 主要围绕 task。
建议新增：

- `goal_snapshot`
- `goal_id`
- `goal_status`
- `goal_phase`
- `goal_progress_summary`
- `goal_success_criteria`
- `goal_constraints`
- `goal_budget_summary`
- `goal_latest_audit`

### `ResumePacket`

建议 record 直接显式新增：

- `GoalSnapshot goalSnapshot`

### `HandoffPacket`

同理建议新增：

- `GoalSnapshot goalSnapshot`

这一步很值，因为后续子任务/跨 worker handoff 才真正知道“自己服务哪个 goal”。

## 7.6 `RuntimeFactSet`

建议新增 goal-aware 字段：

- `GoalSnapshot goalSnapshot`
- `GoalCompletionAudit latestGoalAudit`
- `Map<String, Object> goalBudget`

这样 live flow / judgment trace / console 能无缝显示。

## 7.7 `TaskLiveFlowView` / `JudgmentTraceView`

建议新增：

- `Goal goal`
- `GoalSnapshot goalSnapshot`
- `GoalCompletionAudit goalCompletionAudit`
- `GoalBudgetView goalBudget`

这样 UI 不只是看到 task 发生了什么，还能看到 goal 生命周期状态。

## 7.8 `ExperimentRunRecord` / `ExperimentRunService`

建议最小新增：

- `goalId`
- `goalCaseKey` 或在 metadata 写入 `goal_id`

如果一个 orchestrated chain 由多个 tasks 构成，实验评估更适合在 goal 级对账。

也就是说：

- 任务级 run 保留
- 但要能 roll up 到 goal

---

## 8. API contract 怎么改

## 8.1 新增 Goal API

建议新增：

### 创建 goal

```text
POST /api/v1/goals
```

### 查询 goal

```text
GET /api/v1/goals/{id}
```

### 列出 goals

```text
GET /api/v1/goals?status=active
```

### 查询 goal live flow

```text
GET /api/v1/goals/{id}/live_flow
```

### 查询 goal completion audit

```text
GET /api/v1/goals/{id}/completion_audit
```

### close / reopen / supersede

```text
POST /api/v1/goals/{id}/close
POST /api/v1/goals/{id}/reopen
POST /api/v1/goals/{id}/supersede
```

## 8.2 任务 API 扩展

### 任务创建

当前：

```json
{
  "title": "...",
  "goal": "..."
}
```

建议升级为：

```json
{
  "title": "实现 mounted context prompt path",
  "session_id": "sess_xxx",
  "goal_id": "goal_xxx",
  "metadata": {
    "task_type": "coding"
  }
}
```

或者：

```json
{
  "title": "实现 mounted context prompt path",
  "session_id": "sess_xxx",
  "goal": {
    "title": "完成 mounted context phase 2A",
    "objective": "让 mounted context 成为默认 prompt rendering 主路径",
    "success_criteria": [
      "DefaultWorkerExecutor 走 mounted renderer",
      "ToolAwareWorkerExecutor 走 mounted renderer",
      "测试通过"
    ],
    "constraints": [
      "兼容旧 prompt mode",
      "不破坏当前 live flow"
    ],
    "budget": {
      "max_strong_rounds": 5
    }
  }
}
```

这比单个 `goal` 字符串稳定得多。

## 8.3 packet / live flow 响应扩展

所有这些响应都建议变成 goal-aware：

- `/api/v1/tasks/{id}/packet`
- `/api/v1/tasks/{id}/handoff_packet`
- `/api/v1/tasks/{id}/live_flow`
- `/api/v1/tasks/{id}/judgment_trace`

---

## 9. 推荐的落地顺序

## P0

### P0-1. 建立 Goal 持久化主模型

先做：

- `goals`
- `goal_events`
- `tasks.goal_id`
- `Goal` / `GoalDao`
- `GoalService`

这是整个 Goal runtime 的主键层。

### P0-2. 让 Task create / subtask create 能挂 goal

改：

- `TaskCreateRequest`
- `TaskService.createTask(...)`
- 相关 handler

目标：
新任务创建时不再丢失 goal identity。

### P0-3. GoalRuntime hook 接入 `ControlNodeGraph`

最小先接：

- task created
- task state transitioned
- task done / failed / waiting_human / handoff

先只做状态回写，不急着做复杂策略。

---

## P1

### P1-1. packet / handoff / runtime facts 全链路加 `goal_snapshot`

改：

- `PacketBuilder`
- `ResumePacket`
- `HandoffPacket`
- `RuntimeFactSet`
- `TaskLiveFlowView`
- `JudgmentTraceView`

目标：
先让 continuity surfaces 都看到同一个 goal。

### P1-2. completion audit 升级成 goal completion audit

新增：

- `GoalCompletionAudit`
- `GoalCompletionAuditService`

目标：
把 task done 和 goal done 分开。

### P1-3. budget/accounting roll-up 到 goal

先复用现有：

- `ExperimentRunService`
- tool trace
- worker metadata

做出 goal 级 budget summary，不需要一开始就做复杂账本。

---

## P2

### P2-1. subtask / orchestrated mode / experiment 统一 `goal_id`

当前 orchestrated mode 已经有不少 metadata。
下一步是统一把：

- parent task
- subtask
- experiment run
- handoff target

都显式系到一个 `goal_id`。

### P2-2. auto continue 升级成 goal-aware continuation gating

现在 continue 更多是 task-loop 决策。
后续可升级成：

- task can continue?
- goal should continue?
- goal should replan?
- goal should fork follow-up task?
- goal should close?

这一步才是从 continue-task harness 走向 persisted goal system 的关键升级。

---

## 10. 最小可开工类清单

如果要直接开始改，我建议第一批先只新增这些：

### schema

- `goals`
- `goal_events`
- `tasks.goal_id`

### model

- `Goal`
- `GoalSnapshot`
- `GoalEvent`

### store

- `GoalDao`
- `GoalEventDao`

### engine

- `GoalService`
- `GoalRuntime`

### patch existing

- `Task`
- `TaskDao`
- `TaskCreateRequest`
- `TaskService`
- `ControlNodeGraph`
- `PacketBuilder`
- `ResumePacket`
- `HandoffPacket`

这一批做完，就已经从“goal 只是文本”迈到“goal 是持久化主对象”了。

---

## 11. 最后的判断

对照当前代码，我的结论还是这一句：

> 这个仓库不缺 continuity 基础，也不缺 task runtime；真正缺的是一层独立的 Goal lifecycle runtime，把现有 Task / Packet / Judgment / Checkpoint / Experiment surface 统一收编到 goal identity 之下。

所以正确做法不是大拆当前架构，而是：

- 保留现有 task runtime 作为执行引擎
- 新增独立 goal runtime 作为上层生命周期控制面
- 先补 `goal_id + goals + goal hook + goal_snapshot`
- 再补 completion audit / budget / continuation gating

这样改，结构最稳，也最接近你想要的 persisted goal system。
