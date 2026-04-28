# Spec

## 1. 功能清单

| 编号 | 功能名称 | 所属模块 | 入口 | 重要程度 |
|------|---------|---------|------|---------|
| F01 | 创建会话 | Session 模块 | `SessionHandler.handle` | 重要 |
| F02 | 创建任务并自动进入控制图 | Task 模块 | `TaskHandler.handle` / `TaskService.createTask` | 核心 |
| F03 | 任务状态更新 | Task 模块 | `TaskHandler.handle` / `TaskService.updateTaskState` | 重要 |
| F04 | Worker 自动路由 | Worker Router 模块 | `ControlNodeGraph.schedulerNode` | 核心 |
| F05 | 任务暂停与续跑包刷新 | Control Graph / Memory 模块 | `TaskHandler.handle` / `TaskService.pauseTask` | 核心 |
| F06 | 人工升级与等待确认 | Control Graph 模块 | `TaskHandler.handle` / `TaskService.escalateTask` | 重要 |
| F07 | 任务移交 | Control Graph / Memory 模块 | `TaskHandler.handle` / `TaskService.handoffTask` | 重要 |
| F10 | 显式 worker 路由决策查询 | Task / Router 模块 | `TaskHandler.handle` / `TaskService.selectWorker` | 重要 |
| F11 | 显式 handoff packet 预览 | Task / Memory 模块 | `TaskHandler.handle` / `TaskService.getHandoffPacket` | 重要 |
| F08 | 技能注册与就绪检查 | Skill 模块 | `SkillHandler.handle` | 辅助 |
| F09 | Checkpoint 查询 | Consolidation 模块 | `CheckpointHandler.handle` | 重要 |

## 2. 核心业务流程

### 2.1 创建任务并自动调度

**触发方式**: HTTP 请求  
**涉及模块**: `server`, `engine`, `engine/router`, `store`

图: 创建任务并自动调度

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
    (自动建 Session)   (复用现有 Session)
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
        (scheduler 选择 worker)
                 |
                 v
           [返回 Task JSON]

**关键代码路径**:
1. `src/main/java/com/agentcloud/server/TaskHandler.java:31` — 处理 `POST /api/v1/tasks`。
2. `src/main/java/com/agentcloud/engine/TaskService.java:31` — 自动补 session、写入任务与事件。
3. `src/main/java/com/agentcloud/engine/ControlNodeGraph.java:33` — 根据 `control_node` 进入控制节点图。
4. `src/main/java/com/agentcloud/engine/router/WorkerRouter.java:18` — 依据任务类型选择 worker。

### 2.2 暂停任务并生成 checkpoint

**触发方式**: HTTP 请求  
**涉及模块**: `server`, `engine`, `engine/memory`, `store`

图: 暂停任务并生成 checkpoint

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
       (回到 scheduler/continue)
                |
                v
   [因 status=paused 而停止继续循环]

**关键代码路径**:
1. `src/main/java/com/agentcloud/server/TaskHandler.java:47` — 暴露 pause 接口。
2. `src/main/java/com/agentcloud/engine/ControlNodeGraph.java:128` — 将任务改成 `paused` 并跳到 `packet` 节点。
3. `src/main/java/com/agentcloud/engine/ControlNodeGraph.java` — 构建并持久化 resume packet，随后触发 consolidation。
4. `src/main/java/com/agentcloud/engine/ConsolidationService.java:31` — 汇总最近决策、产物、事件并写入 checkpoint。

### 2.3 恢复任务并重新调度

**触发方式**: HTTP 请求  
**涉及模块**: `server`, `engine`, `engine/router`

图: 恢复任务并重新调度

    [POST /api/v1/tasks/{id}/resume]
                |
                v
         (triggerResume)
                |
                v
    (status=active, node=scheduler)
                |
                v
        (schedulerNode 重新选 worker)
                |
                v
          (continueNode 继续执行)
                |
                v
             [返回 Task]

**关键代码路径**:
1. `src/main/java/com/agentcloud/server/TaskHandler.java:50` — 暴露 resume 接口。
2. `src/main/java/com/agentcloud/engine/TaskService.java:109` — 查出任务并触发恢复。
3. `src/main/java/com/agentcloud/engine/ControlNodeGraph.java:149` — 清空等待原因并回到调度节点。

### 2.4 人工升级与移交

**触发方式**: HTTP 请求  
**涉及模块**: `server`, `engine`, `engine/memory`, `store`

图: 升级与移交流程

    [POST /api/v1/tasks/{id}/escalate] --> (persist packet + checkpoint) --> (waiting_human + human_gate) --> [等待人工]

    [POST /handoff]
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

**关键代码路径**:
1. `src/main/java/com/agentcloud/server/TaskHandler.java:56` — 手工升级接口。
2. `src/main/java/com/agentcloud/engine/ControlNodeGraph.java:135` — 升级后进入 `human_gate`。
3. `src/main/java/com/agentcloud/server/TaskHandler.java:64` — 移交接口读取 `target_worker`。
4. `src/main/java/com/agentcloud/engine/TaskService.java` — 可显式生成 handoff packet 预览。
5. `src/main/java/com/agentcloud/engine/ControlNodeGraph.java` — 移交前会先固化 resume packet 并创建 `handoff_before` checkpoint。

## 3. 数据模型

### 3.1 核心实体关系

图: 核心实体关系

    +-----------+      1:N      +-----------+
    | sessions  |-------------->| tasks     |
    +-----------+               +-----------+
    | id        |               | id        |
    | status    |               | session_id|
    | current_* |               | status    |
    +-----------+               | worker    |
         |                      +-----------+
         | 1:N                      | 1:N
         v                          v
    +-----------+               +-----------+
    | events    |               | decisions |
    +-----------+               +-----------+

    +---------------+    1:N    +-------------+
    | tasks         |---------->| checkpoints |
    +---------------+           +-------------+

    +-----------+      1:N      +----------------+
    | sessions  |-------------->| resume_packets |
    +-----------+               +----------------+

    图例: 以 session 和 task 为主轴，其他表围绕过程记忆展开。

### 3.2 实体详情

#### Session

- **存储位置**: `sessions`
- **对应代码**: `src/main/java/com/agentcloud/model/Session.java`
- **核心字段**:

| 字段名 | 类型 | 说明 | 约束 |
|--------|------|------|------|
| `id` | TEXT | 会话主键 | PK |
| `status` | TEXT | `active/paused/closed` | 非空 |
| `closed_at` | TEXT | 会话关闭时间 | 仅 `closed` 时非空 |
| `root_task_id` | TEXT | 根任务引用 | 可空 |
| `current_task_id` | TEXT | 当前任务引用 | 可空 |
| `summary` | TEXT | 会话摘要 | 可空 |

#### Task

- **存储位置**: `tasks`
- **对应代码**: `src/main/java/com/agentcloud/model/Task.java`
- **核心字段**:

| 字段名 | 类型 | 说明 | 约束 |
|--------|------|------|------|
| `id` | TEXT | 任务主键 | PK |
| `session_id` | TEXT | 所属会话 | FK |
| `status` | TEXT | `active/paused/waiting/done/failed` | 非空 |
| `assigned_worker` | TEXT | 被分配 worker | 可空 |
| `control_node` | TEXT | 当前控制节点 | 可空 |
| `waiting_reason` | TEXT | 等待原因 | 可空 |
| `metadata_json` | TEXT | 任务扩展元数据 | 可空 |

#### ResumePacket

- **存储位置**: `resume_packets`
- **对应代码**: `src/main/java/com/agentcloud/model/ResumePacket.java`
- **核心字段**:

| 字段名 | 类型 | 说明 | 约束 |
|--------|------|------|------|
| `packet_version` | TEXT | 包格式版本 | 非空 |
| `active_task_summary` | TEXT | 兼容旧消费方的人类摘要 | 可空 |
| `decision_summary` | TEXT | 最近决策摘要 | 可空 |
| `artifact_summary` | TEXT | 最近产物摘要 | 可空 |
| `open_questions_json` | TEXT | 顶层未决问题列表 | 可空 |
| `next_step` | TEXT | 恢复后建议动作 | 可空 |
| `payload_json` | TEXT | machine-readable first 的结构化上下文，当前固定包含 `task_identity/current_objective/current_status/current_node/assigned_worker/latest_summary/blockers/open_questions/recent_artifacts/recent_decisions/resume_hint` | 非空 |

#### HandoffPacket

- **生成位置**: `TaskService.getHandoffPacket` / `TaskService.handoffTask`
- **对应代码**: `src/main/java/com/agentcloud/model/HandoffPacket.java`
- **核心字段**:

| 字段名 | 类型 | 说明 | 约束 |
|--------|------|------|------|
| `task_identity` | OBJECT | 当前交接任务身份 | 非空 |
| `from_worker` | TEXT | 当前交出方 worker | 可空 |
| `to_worker` | TEXT | 目标接收方 worker | 可空 |
| `current_objective` | TEXT | 当前交接目标 | 可空 |
| `current_status` | TEXT | 当前任务状态 | 可空 |
| `current_node` | TEXT | 当前控制节点 | 可空 |
| `why_handoff` | TEXT | 交接原因 | 可空 |
| `what_done` | TEXT[] | 已完成工作摘要 | 非空，默认空数组 |
| `what_remaining` | TEXT[] | 剩余待做事项 | 非空，默认空数组 |
| `cautions` | TEXT[] | 风险、阻塞或注意事项 | 非空，默认空数组 |
| `resume_hint` | TEXT | 接手后最直接的恢复提示 | 可空 |
| `latest_summary` | TEXT | 最近适合交接的摘要 | 可空 |
| `handoff_summary` | TEXT | 面向人类快速浏览的交接描述 | 可空 |
| `metadata` | OBJECT | model mode / orchestration stage / planner/executor worker 等附加上下文 | 非空，默认空对象 |

#### Skill

- **存储位置**: `skills`
- **对应代码**: `src/main/java/com/agentcloud/model/Skill.java`
- **核心字段**:

| 字段名 | 类型 | 说明 | 约束 |
|--------|------|------|------|
| `name` | TEXT | 技能名称 | 非空 |
| `capability_tags_json` | TEXT | 能力标签数组 | 可空 |
| `dependencies_json` | TEXT | 就绪依赖项 | 可空 |
| `risk_level` | TEXT | 风险等级 | 可空 |
| `ready` | INTEGER | 就绪状态 | 非空 |

#### Checkpoint

- **存储位置**: `checkpoints`
- **对应代码**: `src/main/java/com/agentcloud/model/Checkpoint.java`
- **核心字段**:

| 字段名 | 类型 | 说明 | 约束 |
|--------|------|------|------|
| `task_id` | TEXT | 所属任务 | FK |
| `checkpoint_type` | TEXT | `periodic/pause_before/escalate_before/handoff_before/halt_before/session_end` | 非空 |
| `consolidation_summary` | TEXT | 巩固摘要 | 可空 |
| `refined_packet_json` | TEXT | 精炼包 | 可空 |
| `world_model_delta_json` | TEXT | 关系增量 | 可空 |

## 4. 状态机（如有）

### 4.1 Task 状态流转

图: Task 状态机

    (创建) --> [active] --pause--> [paused] --resume--> [active]
                  |                    |
                  |                    +--continue--> [paused]
                  |
                  +--escalate--> [waiting_human]
                  |
                  +--halt--> [done]
                  |
                  +--update--> [failed]

    图例: 控制节点与业务状态分离，`control_node` 额外描述处理阶段。

| 当前状态 | 事件 | 目标状态 | 处理逻辑 |
|---------|------|---------|---------|
| `active` | `pause` | `paused` | 切到 `packet` 节点并尝试生成续跑上下文 |
| `paused` | `resume` | `active` | 清空等待原因，重新进入 `scheduler` |
| `active` | `escalate` | `waiting_human` | 先固化 packet/checkpoint，再切到 `human_gate` |
| 任意 | `handoff` | 原状态不变 | 先生成交接 packet 并固化 checkpoint，再切换 worker |
| 任意 | `halt` | `done` | 进入 `end` 节点前先固化 packet/checkpoint |

## 5. 关键算法与策略

### 5.1 Worker 路由策略

- **代码位置**: `src/main/java/com/agentcloud/engine/router/WorkerRouter.java`
- **用途**: 为任务选择最合适的执行 worker。
- **简要逻辑**: 先从任务 `metadata.task_type` 提取目标能力，再查找能力匹配且 `ready=true` 的 worker；如果没有匹配项，则回退到所有 ready worker；最后按 capability 精确匹配数取最大值，并保留两个 fallback worker。
- **复杂度**: 时间复杂度近似 `O(n)`，`n` 为 worker 数量。

### 5.2 Resume Packet 构建策略

- **代码位置**: `src/main/java/com/agentcloud/engine/memory/PacketBuilder.java`
- **用途**: 为暂停恢复或移交生成可继续执行的最小上下文。
- **简要逻辑**: 从当前任务最近的决策和产物中抽取摘要，同时固化 `task_identity/current_objective/current_status/current_node/assigned_worker/latest_summary/blockers/open_questions/recent_artifacts/recent_decisions` 等最小 machine-readable 字段；handoff 场景则额外输出 typed `HandoffPacket`，明确 `why_handoff/what_done/what_remaining/cautions/resume_hint`。
- **复杂度**: 主要取决于最近记录条数，当前实现是固定上限查询，近似 `O(1)`。

### 5.3 Consolidation 巩固策略

- **代码位置**: `src/main/java/com/agentcloud/engine/ConsolidationService.java`
- **用途**: 在任务切换前压缩过程记忆，产出 checkpoint。
- **简要逻辑**: 依次执行 Reactivation、Selection、Compression、Abstraction、Integration 五步，从近 20 条决策、20 条产物和 50 条事件中抽取高价值信息，生成 `refinedPacket` 与 `worldModelDelta` 后写入 `checkpoints`。
- **复杂度**: 受固定条数限制，近似 `O(1)`。

### 5.4 Runtime Judgment 策略

- **代码位置**: `src/main/java/com/agentcloud/engine/RuntimeJudgmentService.java`
- **用途**: 在 `continue_task` 之前判断当前任务该继续、暂停、升级、移交还是停止。
- **简要逻辑**: 当前实现是 rule-based，优先检查 `status`，再检查 `metadata.auto_halt`、`pause_requested`、`requires_human_confirmation`、`target_worker` 等信号，输出下一迁移动作。
- **复杂度**: 只检查当前任务状态和少量 metadata，近似 `O(1)`。
