# Spec

## 1. 功能清单

| 编号 | 功能名称 | 所属模块 | 入口 | 重要程度 |
|------|---------|---------|------|---------|
| F01 | 创建会话 | Session 模块 | `SessionHandler.handle` | 重要 |
| F01A | 会话暂停/恢复/关闭 | Session 模块 | `SessionHandler.handle` / `SessionService` | 重要 |
| F01B | 会话消息流写入与查询 | Session 模块 | `SessionHandler.handle` / `SessionService.addMessage` | 重要 |
| F02 | 创建任务并自动进入控制图 | Task 模块 | `TaskHandler.handle` / `TaskService.createTask` | 核心 |
| F03 | 任务状态更新 | Task 模块 | `TaskHandler.handle` / `TaskService.updateTaskState` | 重要 |
| F04 | Worker 自动路由 | Worker Router 模块 | `ControlNodeGraph.schedulerNode` | 核心 |
| F04A | Learning Memory 辅助路由 | Router / Learning Memory 模块 | `WorkerRouter.selectWorker` | 核心 |
| F04B | 显式 worker 路由决策查询 | Task / Router 模块 | `TaskHandler.handle` / `TaskService.selectWorker` | 重要 |
| F05 | 任务暂停与续跑包刷新 | Control Graph / Memory 模块 | `TaskHandler.handle` / `TaskService.pauseTask` | 核心 |
| F06 | 人工升级与等待确认 | Control Graph 模块 | `TaskHandler.handle` / `TaskService.escalateTask` | 重要 |
| F07 | 任务移交 | Control Graph / Memory 模块 | `TaskHandler.handle` / `TaskService.handoffTask` | 重要 |
| F08 | 技能注册与就绪检查 | Skill 模块 | `SkillHandler.handle` | 辅助 |
| F09 | Checkpoint 查询 | Consolidation 模块 | `CheckpointHandler.handle` | 重要 |
| F10 | Runtime Context 查询 | Runtime 模块 | `TaskHandler.handle` / `TaskService.getRuntimeContext` | 重要 |
| F11 | Judgment Trace 查询 | Judgment 模块 | `TaskHandler.handle` / `TaskService.getJudgmentTrace` | 重要 |
| F12 | Live Flow 聚合视图 | Task / Observability 模块 | `TaskHandler.handle` / `TaskService.getLiveFlow` | 重要 |
| F13 | Tool Trace 查询 | Tool / Observability 模块 | `TaskHandler.handle` / `TaskService.getToolTrace` | 重要 |
| F14 | Experiment Run 查询 | Experiment 模块 | `TaskHandler.handle` / `TaskService.getExperimentRun` | 重要 |
| F15 | Experiment Summary 查询 | Experiment 模块 | `TaskHandler.handle` / `ExperimentMatrixService.summarizeExperiment` | 重要 |
| F16 | Experiment Matrix 批量创建与汇总 | Experiment 模块 | `ExperimentMatrixHandler.handle` | 重要 |
| F17 | Learning Memory 查询 | Learning Memory 模块 | `LearningMemoryHandler.handle` | 重要 |
| F18 | 显式 handoff packet 预览 | Task / Memory 模块 | `TaskHandler.handle` / `TaskService.getHandoffPacket` | 重要 |
| F19 | Web Console | Web 模块 | `/console/` | 辅助 |
| F20 | Dialogue 前端 | Web 模块 | `/dialogue/` | 辅助 |

## 2. 核心业务流程

### 2.1 创建任务并自动调度

**触发方式**: HTTP 请求  
**涉及模块**: `server`, `engine`, `engine/router`, `runtime`, `worker`, `judgment`, `store`

```text
[POST /api/v1/tasks]
        |
        v
  (解析 TaskCreateRequest)
        |
        v
  (TaskService.createTask)
        |
        v
<是否提供 session_id?>
   |               |
   v               v
(自动建 Session)  (复用现有 Session)
   |               |
   +-------+-------+
           |
           v
     (写入 tasks/events)
           |
           v
   (进入 ControlNodeGraph)
           |
           v
   (scheduler: 选择 worker)
           |
           v
  (build runtime context)
           |
           v
 (execute one worker round)
           |
           +--> tool-aware path 时可能进入多步 tool round
           |        |
           |        +--> persist tool_invocations trace
           |
           v
(execution/completion judgment)
           |
           +--> RuntimeJudgmentService 补一层最小 continue/pause/handoff/escalate 判断
           |
           v
(capture learning memory / run metrics)
           |
           v
      [返回 Task JSON]
```

**关键代码路径**:
1. `src/main/java/com/agentcloud/server/TaskHandler.java` — 处理 `POST /api/v1/tasks`。
2. `src/main/java/com/agentcloud/engine/TaskService.java` — 自动补 session、写入任务与事件。
3. `src/main/java/com/agentcloud/engine/ControlNodeGraph.java` — 根据 `control_node` 进入控制节点图。
4. `src/main/java/com/agentcloud/engine/router/WorkerRouter.java` — 依据任务类型、model tier 与 learning hint 选择 worker。
5. `src/main/java/com/agentcloud/runtime/TaskRuntimeContextBuilder.java` — 构建 worker round / judgment 共用上下文。
6. `src/main/java/com/agentcloud/worker/WorkerExecutorRouter.java` — 在默认执行器和 tool-aware 执行器之间分流。
7. `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java` — 执行最多 3 步工具链，并写入工具调用 trace。
8. `src/main/java/com/agentcloud/judgment/PromptBasedJudgmentService.java` — 产出 execution/completion judgment。
9. `src/main/java/com/agentcloud/engine/RuntimeJudgmentService.java` — 基于 metadata 补一层最小状态迁移判断。

**与 hardness phase-1 的当前对齐判断**:
- 当前代码里已经存在 `WorkerExecutionResult`、`ToolInvocationRecord`、runtime context、judgment trace 与 checkpoint / packet 主线，所以这条流程不是 blueprint，而是已运行的 outer-loop 雏形。
- 当前最主要的缺口不再是“有没有多步工具执行”或“有没有 judgment 层”，而是还没有把这些能力统一压成 `WorkerExecutionEnvelope -> ToolInvocationRecord -> RuntimeFactSet -> ResumeCheckpoint -> JudgmentInput / ContinuationAction` 这样的更硬 contract 链。
#### RuntimeJudgmentService / Goal Progress 最小规则

`RuntimeJudgmentService` 当前已经消费 `Task.metadata.subgoal_status` 作为 P1/P2 最小 goal-progress decision 输入。该输入允许三种形态：

- `string[]`：例如 `['done', 'in_progress']`
- `object[]`：元素可带 `status` 或 `state`
- `object`：key 为子目标标识，value 为状态或带 `status/state` 的对象

当前状态归一规则：

| subgoal 状态 | 归一语义 | ContinuationAction |
|------|------|------|
| `done / complete / completed / accepted` | 已完成 | 全部完成时 `halt` |
| `blocked / waiting_human / human_gate` | 阻塞 | 任一阻塞时 `escalate`（控制图/页面语义为 human gate） |
| `pending / in_progress` 或其他非空状态 | 未完成 | `continue` |

优先级顺序为：task 空/暂停/等待人工状态 -> 显式 metadata 控制信号（`auto_halt / pause_requested / requires_human_confirmation`）-> `subgoal_status` -> `target_worker` handoff -> 默认 continue。

`ContinuationDecision.metadata` 至少带 `subgoal_total / subgoal_done_count / subgoal_blocked_count` 三个计数字段，后续 UI 可以用它区分“goal 已达成 / 仍在执行 / 等待人工”，但这不是任务失败判定。

控制图 `continueNode` 已把同一套 `subgoal_status` 接入 `resolveAction`。当 `subgoal_status` 存在时，goal progress 判断优先于单轮 execution result 判断（验收标准 #2）：

- blocked subgoal -> `human_gate`（无论 execution 说 done/continue/checkpoint）
- all subgoals done -> `done`（无论 execution 说什么）
- open subgoals + execution done -> `checkpoint`（保存进度但不标记完成）
- open subgoals + execution continue -> `continue`（继续走原逻辑）
- 无 subgoal_status -> 保持原有行为

也就是说 goal-progress decision 已经进入 runtime loop 并优先于单轮执行结果，不再只是独立 judgment helper。

#### Goal Progress Auto-Update

`continueNode` 在 `enrichTaskFromJudgment` 后自动调用 `autoUpdateSubgoalStatus(task, executionResult)`：worker `completed` 时将当前 `in_progress`/`pending` subgoal 标为 `done`；worker `failed` 时标为 `blocked`；其他状态不触发迁移。迁移后更新 `metadata.progress_summary` 与 `metadata.progress_detail`（语义详情，引用 blocked subgoal 标题）。

回归保护：GoalProgressAutoUpdateTest 7 个场景。

#### Task 终态 partial

`finalizeCompletedTask` 在 task 到达终态时检查 `subgoal_status`：全部 done -> `done`；部分完成（有 done 但不是全部）-> `partial`；无 subgoal_status 或为空 -> `done`。`partial` 状态在 UI 展示层有独立 tone，不被误判为 done 或 failed。

回归保护：TaskPartialStatusTest 6 个场景。

#### Goal Progress pending -> in_progress 自动迁移

`autoUpdateSubgoalStatus` 在 worker executionStatus 为 `running` 时，将第一个 `pending` subgoal 迁移为 `in_progress`。这补全了 subgoal 生命周期：`pending -> in_progress -> done/blocked`。

回归保护：GoalProgressAutoUpdateTest 新增 2 个场景。


#### LLM-assisted Subgoal Update

当 worker executionStatus 为 ambiguous（非 completed/failed/running）且有 outputText 时，`autoUpdateSubgoalStatus` 调用 `LlmSubgoalJudgmentService` 通过 CCX 判断 subgoal 应为 `done` / `blocked` / `in_progress`。规则优先，LLM 只在 ambiguous 场景触发。LLM 判断结果写入 `metadata.subgoal_judgment_source = llm_fallback`，可观测可回放。

回归保护：LlmSubgoalJudgmentServiceTest 11 个场景。

#### Handoff Depth 限制

Advisory handoff 和 triggerHandoff 都会递增 `task.metadata.handoff_depth`。当 `handoff_depth >= MAX_HANDOFF_DEPTH(3)` 时，advisory handoff 被跳过，直接进入 `human_gate`，防止无限 handoff 嵌套。

回归保护：HandoffDepthLimitTest 5 个场景。

#### Worker Execution 超时保护

`schedulerNode` 中 worker 执行通过 `executeOneRoundWithTimeout` 使用 `CompletableFuture.get(120s)` 设置超时。worker hang 时抛出 RuntimeException 进入 failure recovery 路径，控制图线程不会被无限阻塞。

回归保护：WorkerExecutionTimeoutTest 3 个场景。

#### Loop Activity

每次 `continueNode` 完成后写入 `task.metadata.last_loop_tick`（ISO timestamp）。前端基于此判断 loop 活跃度：<=10s active / <=30s stall / >30s stale。

回归保护：dialogue-loop-activity-detector-plan.test.mjs 16 个场景。

#### Task 创建时的 Goal Contract 初始化

`TaskService.createTask(...)` 会先用 `ProviderTaskContractNormalizer.normalize(...)` 补齐工作区/路径等 provider contract，再调用 `ProviderTaskContractNormalizer.initializeGoalContract(meta, goal)` 初始化最小 goal contract。这里的 `goal` 取 `firstNonBlank(req.goal(), req.intent())`，因此即使上游只传了 `intent`，任务创建时也会得到可判断、可展示、可交接的最小 goal 元数据。

当前初始化口径：

- 若 `metadata.goal` 缺失且 `goal` 非空，则补齐 `metadata.goal`
- 若 `metadata.subgoals` 缺失，则默认生成 `[goal]`
- 若 `metadata.subgoal_status` 缺失，则默认生成 `[{ title: goal, status: pending }]`
- 若 `metadata.progress_summary` 缺失，则根据当前 `subgoal_status` 生成类似 `0/1 subgoals done` 的摘要
- 若 `metadata.progress_detail` 缺失，则根据当前 `subgoal_status` 生成语义详情（引用 blocked subgoal 标题）
- 若请求中已经显式给出 `subgoals / subgoal_status / progress_summary / acceptance_criteria`，则保持原值，不被默认逻辑覆盖

这保证 `RuntimeJudgmentService`、`ControlNodeGraph`、packet/handoff 文档和 UI 状态展示从 task 创建开始就能读取同一套 goal contract，而不必等待 worker 首轮输出后再补齐。
### 2.2 暂停任务并生成 checkpoint

**触发方式**: HTTP 请求  
**涉及模块**: `server`, `engine`, `engine/memory`, `store`

```text
[POST /api/v1/tasks/{id}/pause]
            |
            v
     (triggerPause)
            |
            v
  (status=paused, node=packet)
            |
            v
       (packetNode)
            |
            v
  (buildResumePacket and persist)
            |
            v
(ConsolidationService.consolidate)
            |
            v
    [写入 checkpoints 表]
            |
            v
 [因 status=paused 而停止继续循环]
```

### 2.3 恢复任务并重新调度

**触发方式**: HTTP 请求  
**涉及模块**: `server`, `engine`, `engine/router`, `runtime`, `worker`, `judgment`

```text
[POST /api/v1/tasks/{id}/resume]
            |
            v
     (triggerResume)
            |
            v
(status=active, node=scheduler)
            |
            v
 (重新路由或复用 preassigned worker)
            |
            v
 (重新构建 runtime context)
            |
            v
   (继续执行与 judgment)
            |
            v
         [返回 Task]
```

### 2.4 人工升级与移交

**触发方式**: HTTP 请求  
**涉及模块**: `server`, `engine`, `engine/memory`, `store`

```text
[POST /api/v1/tasks/{id}/escalate]
        |
        v
(persist packet + checkpoint)
        |
        v
(waiting_human + human_gate)
        |
        v
      [等待人工]

[POST /api/v1/tasks/{id}/handoff]
        |
        v
(build handoff packet)
        |
        v
(persist packet + checkpoint)
        |
        v
(assigned_worker=target)
        |
        v
   (handoffNode)
        |
        v
   (回到 scheduler)
```

### 2.4A Advisory Handoff（模型间咨询升级）

**触发方式**: 控制图自动判断（非 HTTP 直接触发）
**涉及模块**: `engine/ControlNodeGraph, `engine/router/WorkerRegistry, `engine/memory/PacketBuilder

当 
esolveAction 输出 `escalate 且当前 worker 为 small tier 时，控制图优先尝试 advisory handoff：

``text
[continueNode: resolveAction=escalate]
        |
        v
(check current worker model_tier)
        |
   +----+----+
   |         |
 small     strong
   |         |
   v         v
(find strong-tier  (direct human_gate)
 ready worker)
   |
   +----+----+
   |         |
 found    not found
   |         |
   v         v
 advisory   human_gate
 handoff    (existing behavior)
   |
   v
(handoff_reason=advisory_consult)
(advisory_source/target worker in metadata)
   |
   v
(handoffNode -> schedulerNode)
(strong-tier worker executes one round)
   |
   v
(auto-resume original path after advisory)
`

Advisory handoff 与普通 handoff 的区别：
- HandoffPacket.why_handoff = advisory_consult（而非 worker_failure 或通用 handoff 文本）
- metadata.advisory_trigger = escalate_from_small_tier
- advisory round 完成后控制图自动 resume，不需要人工介入
- 如果 advisory round 也判断 `escalate，则进入 human_gate（strong-tier 已是最高可用）

### 2.5 Experiment Matrix 基线运行

**触发方式**: HTTP 请求  
**涉及模块**: `server`, `engine`, `store`

```text
[POST /api/v1/experiment_matrix/runs]
             |
             v
 (load builtin baseline cases)
             |
             v
(expand strong/small/orchestrated)
             |
             v
 (批量创建任务与 run 记录)
             |
             v
[GET /api/v1/experiment_matrix/summary]
             |
             v
 (按 mode / case 聚合指标)
```

当前 `experiment_matrix/summary` 的 mode 级聚合口径，除完成率、接受率、handoff / resume / human gate 外，还固定暴露一组 O03 acceptance gate 字段：

- `acceptance_gate_result_counts`
- `artifact_quality_gate_status_counts`
- `cost_gate_status_counts`
- `runs_with_failure_reason`
- `failure_reason_counts`

其中 baseline case 创建出来的 task metadata 也会继续携带 `baseline_cost_threshold_units` 与 `cost_gate_basis`，让未真正启动的 matrix run 也能在 summary 上区分“质量未评估”与“成本 gate 已在阈值内”。

## 3. 数据模型

### 3.1 核心实体关系

```text
+-----------+      1:N      +-----------+
| sessions  |-------------->| tasks     |
+-----------+               +-----------+
     |                            |
     | 1:N                        | 1:N
     v                            v
+----------------+         +----------------+
| session_messages|        | events         |
+----------------+         +----------------+
                                |
                                +----> decisions
                                +----> artifacts
                                +----> checkpoints
                                +----> resume_packets
                                +----> tool_invocations
                                +----> experiment_runs
                                +----> learning_memories
```

### 3.2 实体详情

#### Session

- **存储位置**: `sessions`
- **对应代码**: `src/main/java/com/agentcloud/model/Session.java`

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `id` | TEXT | 会话主键 |
| `status` | TEXT | `active/paused/closed` |
| `closed_at` | TEXT | 会话关闭时间 |
| `root_task_id` | TEXT | 根任务引用 |
| `current_task_id` | TEXT | 当前任务引用 |
| `summary` | TEXT | 会话摘要 |

#### Task

- **存储位置**: `tasks`
- **对应代码**: `src/main/java/com/agentcloud/model/Task.java`

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `id` | TEXT | 任务主键 |
| `session_id` | TEXT | 所属会话 |
| `status` | TEXT | `active/paused/waiting/done/failed` |
| `assigned_worker` | TEXT | 当前 worker |
| `control_node` | TEXT | 当前控制节点 |
| `waiting_reason` | TEXT | 等待原因 |
| `metadata_json` | TEXT | 扩展元数据 |

#### ResumePacket

- **存储位置**: `resume_packets`
- **对应代码**: `src/main/java/com/agentcloud/model/ResumePacket.java`
- **协议头**: `packet_version=1.1`、`machine_readable_first=true`

Resume packet 当前固定为 machine-readable first。除历史兼容字段外，稳定暴露以下最小协议字段（与 `API_CONTRACTS.md` 的 `ResumePacket 最小字段` 表一致）：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `task_identity` | object | `task_id/session_id/parent_task_id/title/task_type` |
| `current_objective` | TEXT | 当前连续执行目标 |
| `current_status` | TEXT | 当前任务状态 |
| `current_node` | TEXT | 当前控制节点 |
| `assigned_worker` | TEXT | 当前指派 worker |
| `latest_summary` | TEXT | 最近一版最适合续跑的摘要 |
| `next_step` | TEXT | 恢复后建议动作 |
| `blockers` | TEXT[] | 当前阻塞项 |
| `open_questions` | TEXT[] | 当前未决问题 |
| `recent_artifacts` | object[] | 最近产物引用，含 `artifact_type/title/summary/created_at` |
| `recent_decisions` | object[] | 最近决策引用，含 `decision_type/summary/rationale/created_at` |

历史兼容字段仍保留但不再作为主协议依赖：`active_task_summary`、`decision_summary`、`artifact_summary`、`payload`（扩展包，会镜像上述 machine-readable 字段）。跨 worker 续跑时下游必须消费上述最小字段，而不是依赖散装 `payload`。

#### HandoffPacket

- **生成位置**: `TaskService.getHandoffPacket` / `TaskService.handoffTask`
- **对应代码**: `src/main/java/com/agentcloud/model/HandoffPacket.java`
- **协议头**: `packet_version=1.0`、`machine_readable_first=true`

Handoff packet 当前已固定为 typed schema，不再是散装 `Map`。稳定暴露以下最小协议字段（与 `API_CONTRACTS.md` 的 `HandoffPacket 最小字段` 表一致）：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `task_identity` | object | 当前交接任务身份 |
| `from_worker` | TEXT | 当前交出方 |
| `to_worker` | TEXT | 目标接收方 |
| `current_objective` | TEXT | 当前交接目标 |
| `current_status` | TEXT | 当前任务状态 |
| `current_node` | TEXT | 当前控制节点 |
| `why_handoff` | TEXT | 交接原因 |
| `what_done` | TEXT[] | 已完成工作摘要 |
| `what_remaining` | TEXT[] | 剩余待做事项 |
| `cautions` | TEXT[] | 风险、阻塞或注意事项 |
| `resume_hint` | TEXT | 接手后最直接的恢复提示 |
| `latest_summary` | TEXT | 最近一次适合交接的摘要 |
| `handoff_summary` | TEXT | 面向人类快速浏览的交接描述 |
| `metadata` | object | 扩展 trace（`model_mode / orchestration_stage / planner_worker / executor_worker / selected_model_tier / fallback_reason` 等），不替代上述最小字段 |

跨 worker handoff 时下游 worker 必须消费上述最小字段；`metadata` 只承载扩展 trace，不替代最小字段。handoff 与 resume 的跨 worker 稳定性由 `TaskServicePacketContractTest` 覆盖。
#### Checkpoint

- **存储位置**: `checkpoints`
- **对应代码**: `src/main/java/com/agentcloud/model/Checkpoint.java`

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `task_id` | TEXT | 所属任务 |
| `checkpoint_type` | TEXT | `pause_before/escalate_before/handoff_before/...` |
| `consolidation_summary` | TEXT | 巩固摘要 |
| `refined_packet_json` | TEXT | 精炼 packet |
| `world_model_delta_json` | TEXT | 关系增量 |

#### SessionMessage

- **存储位置**: `session_messages`
- **对应代码**: `src/main/java/com/agentcloud/model/SessionMessage.java`

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `id` | TEXT | 消息主键 |
| `session_id` | TEXT | 所属会话 |
| `task_id` | TEXT | 关联任务 |
| `role` | TEXT | `user/assistant/system/tool` 等 |
| `message_type` | TEXT | `task_receipt/task_action/task_state/task_progress/task_result/...` |
| `content` | TEXT | 人类可读内容 |
| `metadata_json` | TEXT | 投影上下文 |

#### LearningMemory

- **存储位置**: `learning_memories`
- **对应代码**: `src/main/java/com/agentcloud/model/LearningMemory.java`

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `id` | TEXT | 记忆主键 |
| `task_type` | TEXT | 适用任务类型 |
| `memory_type` | TEXT | `routing_preference/context_retention_hint` 等 |
| `memory_key` | TEXT | 记忆键 |
| `memory_value` | TEXT | 记忆值 |
| `reinforcement_count` | INTEGER | 强化次数 |
| `status` | TEXT | `candidate/reinforced/stable_hint` 等 |

#### ToolInvocationRecord

- **存储位置**: `tool_invocations`
- **对应代码**: `src/main/java/com/agentcloud/model/ToolInvocationRecord.java`

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `id` | TEXT | 工具调用主键 |
| `task_id` | TEXT | 所属任务 |
| `worker_id` | TEXT | 调用工具的 worker |
| `tool_name` | TEXT | 工具名；当前内置支持 `list_files/read_file/search_text/write_file/patch_file`，命令工具 `git/shell/powershell/cmd` 按宿主机真实可执行性动态暴露，其中 `powershell/cmd` 仍仅 Windows 宿主可用 |
| `tool_round` | INTEGER | 单轮内第几步工具调用 |
| `status` | TEXT | `ok/error/skipped` 等 |
| `request_json` | TEXT | 工具请求 |
| `result_json` | TEXT | 工具结果 |

#### ExperimentRunRecord

- **存储位置**: `experiment_runs`
- **对应代码**: `src/main/java/com/agentcloud/model/ExperimentRunRecord.java`

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `id` | TEXT | run 主键 |
| `task_id` | TEXT | 关联任务 |
| `experiment_name` | TEXT | 实验名 |
| `mode` | TEXT | `strong_only/small_only/orchestrated` |
| `task_case_key` | TEXT | 基线 case 标识 |
| `accepted` | INTEGER | 是否被 judgment 接受 |
| `cost_json` | TEXT | 成本与调用统计 |
| `metadata_json` | TEXT | route / learning hint / fallback / model mode 等指标 |

## 4. 状态机

### 4.1 Task 状态流转

```text
(创建) --> [active] --pause--> [paused] --resume--> [active]
              |                    |
              |                    +--continue--> [paused]
              |
              +--escalate--> [waiting_human]
              |
              +--halt--> [done]
              |
              +--update--> [failed]
```

说明：

- `status` 描述业务态，`control_node` 描述控制图处理阶段。
- 当前控制图保留 `intake/scheduler/continue/packet/human_gate/handoff` 六个命名节点。
- `orchestrated` 模式下，同一 task 可能在 planner/judge 阶段使用 strong tier，在 execution 阶段切到 small tier。

## 5. 关键算法与策略

### 5.1 Worker 路由策略

- **代码位置**: `src/main/java/com/agentcloud/engine/router/WorkerRouter.java`
- **用途**: 为任务选择最合适的执行 worker。
- **简要逻辑**: 先从任务 `metadata.task_type` 提取目标能力，再结合 `model_mode` 收窄 model tier；之后读取 `LearningMemoryService.selectPreferredWorker(taskType)` 作为 preferred worker hint，在候选集允许时优先命中；最终输出 `selected_worker`、`route_source`、`why_selected`、`preferred_worker_hint`、`learning_hint_applied`、`fallback_reason` 等 trace。

### 5.2 Active Context / Runtime Context 构建策略

- **代码位置**: `src/main/java/com/agentcloud/runtime/ActiveContextBuilder.java`, `TaskRuntimeContextBuilder.java`
- **用途**: 为单轮 worker 执行和 judgment 构建最小但高价值的上下文。
- **简要逻辑**: 从最近事件、决策、产物、latest packet、latest checkpoint 和 learning memory 中提取摘要，裁掉低价值背景，输出 `TaskRuntimeContext`；其中 `active_context` 既服务执行 prompt，也服务 observability 接口。

### 5.3 Resume / Handoff Packet 构建策略

- **代码位置**: `src/main/java/com/agentcloud/engine/memory/PacketBuilder.java`
- **用途**: 为暂停恢复或移交生成可继续执行的最小上下文。
- **简要逻辑**: 固化 `task_identity/current_objective/current_status/current_node/assigned_worker/latest_summary/blockers/open_questions/recent_artifacts/recent_decisions` 等 machine-readable 字段；handoff 场景则额外输出 typed `HandoffPacket`，明确 `why_handoff/what_done/what_remaining/cautions/resume_hint`。

### 5.4 Consolidation 巩固策略

- **代码位置**: `src/main/java/com/agentcloud/engine/ConsolidationService.java`
- **用途**: 在任务切换前压缩过程记忆，产出 checkpoint。
- **简要逻辑**: 按 Reactivation、Selection、Compression、Abstraction、Integration 五步，从最近事件/决策/产物中抽取高价值信息，生成 `refined_packet` 与 `world_model_delta`。

### 5.5 Prompt-based Judgment 策略

- **代码位置**: `src/main/java/com/agentcloud/judgment/PromptBasedJudgmentService.java`
- **用途**: 对当前 worker round 输出做结构化执行判断与完成判断。
- **简要逻辑**: 结合 runtime context、route metadata、artifact trace、learning hint 等信息拼装 judgment context，分别生成 execution judgment 和 completion judgment，并把结果写入 `decisions` 与 `/judgment_trace` 视图。

### 5.6 Tool-aware Execution 策略

- **代码位置**: `src/main/java/com/agentcloud/worker/ToolAwareWorkerExecutor.java`
- **用途**: 让具备工具能力的 worker 在单轮内完成有限多步工具链。
- **简要逻辑**: 执行 `planning -> invoke tool -> observe -> planning ... -> finalization`，最多 3 步；每步工具调用都会落到 `tool_invocations`，并受 `ToolPolicy` 限制访问范围；在 `repeated_tool_guard`、`no_progress_guard`、`max_tool_rounds_reached` 等条件下提前收敛。
- **当前内置工具**:
  - 文件类：`list_files`、`read_file`、`search_text`、`write_file`、`patch_file`
  - 命令类：`git`、`shell`、`powershell`、`cmd`
- **当前策略边界**:
  - `write_file` 用于整文件落稿，`patch_file` 用于精确局部改写，并且两者都可被视为 grounded write
  - `git` 当前只开放只读检查型子命令
  - `git/shell/powershell/cmd` 都会先做宿主机真实可执行性探测；探测失败时，内置 `codex` 不会默认宣称该能力，服务端也不会接受对应 capability 的动态注册
  - `shell`、`powershell`、`cmd` 受 `cwd`、超时、输出上限和危险命令片段拦截约束
  - `powershell/cmd` 除了要在 Windows 宿主上运行，还要求对应可执行文件真实存在
  - 命令工具是“受控执行”而非强隔离沙箱，因此仍然只适合本地或受控部署环境

### 5.7 Runtime Judgment 策略

- **代码位置**: `src/main/java/com/agentcloud/engine/RuntimeJudgmentService.java`
- **用途**: 在 control node 切换前做最小规则式迁移动作判断。
- **简要逻辑**: 优先检查 `status`，再检查 `metadata.auto_halt`、`pause_requested`、`requires_human_confirmation`、`target_worker` 等信号，输出下一迁移动作。

### 5.8 Experiment Matrix 汇总策略

- **代码位置**: `src/main/java/com/agentcloud/engine/ExperimentRunService.java`, `ExperimentMatrixService.java`
- **用途**: 让 `strong_only`、`small_only`、`orchestrated` 三种模式能针对同一批 case 做可比较评估。
- **简要逻辑**: `ExperimentMatrixService` 负责展开 baseline cases 并批量创建 run；`ExperimentRunService` 负责记录每个 task 的 route、learning hint、恢复次数、成本、accepted 等指标；summary 阶段按 mode 与 case 聚合完成率、接受率、cost、learning hint 命中情况。