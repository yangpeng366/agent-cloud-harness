# Goal Loop 落地方案

生成日期: 2026-06-14
分析范围: `src/main/java`, `src/main/resources/schema.sql`, `docs/`

## 1. 结论

当前仓库已经有完整的 task runtime 和 worker inner loop，但还没有独立的 goal lifecycle runtime。
现有 `goal` 主要还是任务描述文本，不是可持久化、可调度、可审计的一等对象。
因此正确方向不是重写现有 task loop，而是在其外层新增一层 goal-owned 调度与收口。

## 2. 现状对照

### 2.1 已有 inner loop

| 位置 | 现状 | 可复用点 |
|---|---|---|
| `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java` | 单轮内已经支持多步 tool round | 这是当前最接近 Codex inner loop 的实现 |
| `src/main/java/com/agentcloud/worker/ProviderCliWorkerExecutor.java` | provider-native CLI 单轮执行已封装 | 可作为 provider-backed inner loop |
| `src/main/java/com/agentcloud/judgment/PromptBasedJudgmentService.java` | execution/completion judgment 已存在 | 可升级为 goal-aware judgment 输入 |

### 2.2 已有 task-level outer loop

| 位置 | 现状 | 局限 |
|---|---|---|
| `src/main/java/com/agentcloud/engine/ControlNodeGraph.java` | `intake -> scheduler -> continue -> packet / human_gate / handoff` | 主控对象仍是 `Task.controlNode` |
| `src/main/java/com/agentcloud/engine/TaskService.java` | `continueTask / resumeTask / pauseTask / handoffTask` 已统一入口 | 续跑仍是 task 级同步递归 |
| `src/main/java/com/agentcloud/engine/TaskService.java` | `recordTaskStateProjection(...)` 已集中投影状态变化 | 可作为 goal event 的主 hook |
| `src/main/java/com/agentcloud/engine/ChatFacadeService.java` | 已能从聊天请求创建/继续 task | 但还没有 goal attach / goal resume 语义 |

### 2.3 当前缺口

| 缺口 | 现状 | 影响 |
|---|---|---|
| goal 不是一等对象 | 只有 `Task.goal` 文本字段 | 无法做 goal 级状态、预算、审计 |
| 没有 `goal_id` 主键链路 | `tasks` 表无 `goal_id` | task / subtask / handoff 不能稳定归属同一目标 |
| 没有 goal event log | 只有 task/event/packet/checkpoint | goal progress 无法回放 |
| 没有 idle-gated continuation | `shouldAutoContinueTask(...)` 仍在 task 级递归 | 容易变成“继续跑”，不是“目标持续” |
| 没有 goal budget accounting | 只有 run / experiment 层统计 | 无法在 goal 层收口 token、时长、失败恢复 |

## 3. 目标架构

图: 现状

    [Chat/API] --> [TaskService] --> [ControlNodeGraph]
                                 --> [WorkerExecutor]
                                 --> [Judgment]
                                 --> [auto continue]

图: 目标

    [Chat/API] --> [GoalService] --> [GoalRuntime] --> [TaskService]
                                           |               |
                                           v               v
                                      [Goal Events]   [Inner Loop]
                                           |               |
                                           v               v
                                      [Goal Snapshot] <-- [Task Transition]
                                           |
                                           v
                                   [MaybeContinueIfIdle]

图例: `GoalRuntime` 只管生命周期，`TaskService` 只管执行单元，`Inner Loop` 仍由 worker executor 负责。

### 3.1 职责拆分

- `GoalService`：创建、挂载、关闭、重开、supersede goal。
- `GoalRuntime`：消费 task transition，维护 goal status / phase / progress / budget。
- `GoalScheduler`：在 idle 条件满足时，决定是否发起下一轮 goal continuation。
- `TaskService`：继续保留 task 的创建、执行、暂停、恢复、移交。
- `ControlNodeGraph`：继续保留 task 级控制图，不承载 goal 生命周期规则。

### 3.2 规则

- goal owns lifecycle。
- task owns execution。
- session owns conversation thread。
- `Task.goal` 保留兼容，但不再作为生命周期主键。
- 续跑判断必须基于事实与状态，不是 prompt 里的 `keep going`。

## 4. 数据模型

### 4.1 必做变更

| 对象 | 变更 | 说明 |
|---|---|---|
| `goals` | 新增 | goal 一等对象 |
| `goal_events` | 新增 | goal 事件日志 |
| `tasks.goal_id` | 新增 | task 归属 goal 的外键链路 |
| `goals.revision` | 新增 | 用于并发控制 / CAS 写入 |

### 4.2 建议字段

| 表 | 建议字段 |
|---|---|
| `goals` | `id`, `session_id`, `parent_goal_id`, `title`, `status`, `phase`, `source_task_id`, `active_task_id`, `objective`, `success_criteria_json`, `constraints_json`, `budget_json`, `progress_json`, `outcome_summary`, `revision`, `opened_at`, `updated_at`, `closed_at`, `metadata_json` |
| `goal_events` | `id`, `goal_id`, `event_type`, `actor_type`, `actor_id`, `summary`, `payload_json`, `created_at` |

### 4.3 复用现有表

- `sessions`：继续作为 conversation thread，不改语义。
- `tasks`：继续作为执行单元。
- `resume_packets` / `checkpoints`：先把 `goal_snapshot` 放进 payload，避免一次性改表过大。
- `events`：可继续保留 task/event，同时补 goal event 投影。
- `experiment_runs` / `agent_runs`：用于 goal budget roll-up。

## 5. API 方案

### 5.1 新增 Goal API

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/v1/goals` | 创建 goal |
| GET | `/api/v1/goals/{id}` | 获取 goal |
| GET | `/api/v1/goals?session_id=&status=` | 列出 goal |
| POST | `/api/v1/goals/{id}/attach-task` | 绑定 task |
| POST | `/api/v1/goals/{id}/continue` | 触发 goal continuation |
| POST | `/api/v1/goals/{id}/pause` | 暂停 goal |
| POST | `/api/v1/goals/{id}/reopen` | 重开 goal |
| POST | `/api/v1/goals/{id}/close` | 关闭 goal |
| GET | `/api/v1/goals/{id}/live_flow` | 目标级 live flow |

### 5.2 现有 API 调整

- `TaskCreateRequest` 增加 `goal_id`，并保留 `goal` 兼容输入。
- `ChatFacadeService` 先 resolve/attach goal，再创建 task。
- `TaskService.createTask(...)` 写入 `goal_id`，并把 goal snapshot 镜像到 task metadata。
- `TaskService.continueTask(...)` 保留 task 级行为，但 goal continuation 由 `GoalRuntime` 决策。

## 6. 落地顺序

### P0: 打通主键链路

- 新增 `goals` / `goal_events` / `tasks.goal_id`。
- `TaskService.createTask(...)` 支持 goal attach。
- `ChatFacadeService` 支持从 metadata 传 goal。
- `TaskService.recordTaskStateProjection(...)` 同步发 goal event。

### P1: 让 surface 目标感知

- `RuntimeFactSet` / `TaskLiveFlowView` 加 `goal_snapshot`。
- `ResumePacket` / `HandoffPacket` 带 `goal_id`、`goal_phase`、`goal_progress_summary`。
- `GoalRuntime` 先只做状态回写，不做复杂策略。

### P2: 把 continue 升级为 goal continuation

- 引入 `MaybeContinueIfIdle`。
- 目标级续跑必须满足 idle gate、budget gate、user interrupt gate。
- `Task.goal` 退化成兼容展示字段。

## 7. 关键实现点

| 位置 | 作用 | 目标改法 |
|---|---|---|
| `TaskService.createTask(...)` | 创建 task 时注入 goal 文本 | 改成先 attach goal，再创建 task |
| `TaskService.continueTask(...)` | 触发现有控制图 | 改成 task 续跑入口，不承担 goal 调度 |
| `ControlNodeGraph.continueNode(...)` | 当前自动 continue 逻辑中心 | 拆出 goal 决策，不再把 `goal` 当继续条件 |
| `TaskService.recordTaskStateProjection(...)` | 状态投影主 hook | 这里发 `GoalEvent` 最自然 |
| `ToolAwareWorkerExecutor.executeOneRound(...)` | inner loop | 保持不动，只补 goal-aware metadata |
| `ProviderCliWorkerExecutor.executeOneRound(...)` | provider 轮次执行 | 保持不动，只补 goal-aware metadata |

## 8. 风险

- `goal` 文本和 `goal_id` 并存时容易出现双写不一致，必须以 `goal_id` 为准。
- 现在的 task 级递归 continue 不能直接变成 goal loop，否则会放大跑飞风险。
- 并发更新必须加 `revision`，否则会出现旧结果覆盖新 goal 的问题。
- goal budget 不能只靠 task metadata，必须有独立汇总口径。

## 9. 验收标准

- 能从 `/api/v1/goals` 创建、查询、绑定 task。
- 一个 goal 下可以串起多个 task，并在 live flow 里看到统一 snapshot。
- task 完成后，goal 不一定完成；goal 完成必须走独立审计。
- 用户输入能抢断 goal continuation，不会进入无条件死循环。
- 旧 task runtime 不回退，现有 worker inner loop 不被破坏。

