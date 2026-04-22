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

    [GET /api/v1/tasks/{id}/pause]
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
      (buildResumePacket in memory)
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
3. `src/main/java/com/agentcloud/engine/ControlNodeGraph.java:91` — 构建 resume packet 并触发 consolidation。
4. `src/main/java/com/agentcloud/engine/ConsolidationService.java:31` — 汇总最近决策、产物、事件并写入 checkpoint。

### 2.3 恢复任务并重新调度

**触发方式**: HTTP 请求  
**涉及模块**: `server`, `engine`, `engine/router`

图: 恢复任务并重新调度

    [GET /api/v1/tasks/{id}/resume]
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

    [GET /escalate] --> (waiting_human + human_gate) --> [等待人工]

    [POST /handoff]
          |
          v
    (assigned_worker=target)
          |
          v
      (handoffNode)
          |
          v
    (consolidate handoff_before)
          |
          v
      (回到 scheduler)

**关键代码路径**:
1. `src/main/java/com/agentcloud/server/TaskHandler.java:56` — 手工升级接口。
2. `src/main/java/com/agentcloud/engine/ControlNodeGraph.java:135` — 升级后进入 `human_gate`。
3. `src/main/java/com/agentcloud/server/TaskHandler.java:64` — 移交接口读取 `target_worker`。
4. `src/main/java/com/agentcloud/engine/ControlNodeGraph.java:142` — 移交前创建 `handoff_before` consolidation。

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
| `decision_summary` | TEXT | 最近决策摘要 | 可空 |
| `artifact_summary` | TEXT | 最近产物摘要 | 可空 |
| `next_step` | TEXT | 恢复后建议动作 | 可空 |
| `payload_json` | TEXT | 结构化上下文 | 非空 |

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
| `checkpoint_type` | TEXT | `periodic/pause_before/handoff_before/session_end` | 非空 |
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
| `active` | `escalate` | `waiting_human` | 切到 `human_gate`，等待人工确认 |
| 任意 | `handoff` | 原状态不变 | 指定目标 worker 并先做 handoff consolidation |
| 任意 | `halt` | `done` | 进入 `end` 节点并停止流转 |

## 5. 关键算法与策略

### 5.1 Worker 路由策略

- **代码位置**: `src/main/java/com/agentcloud/engine/router/WorkerRouter.java`
- **用途**: 为任务选择最合适的执行 worker。
- **简要逻辑**: 先从任务 `metadata.task_type` 提取目标能力，再查找能力匹配且 `ready=true` 的 worker；如果没有匹配项，则回退到所有 ready worker；最后按 capability 精确匹配数取最大值，并保留两个 fallback worker。
- **复杂度**: 时间复杂度近似 `O(n)`，`n` 为 worker 数量。

### 5.2 Resume Packet 构建策略

- **代码位置**: `src/main/java/com/agentcloud/engine/memory/PacketBuilder.java`
- **用途**: 为暂停恢复或移交生成可继续执行的最小上下文。
- **简要逻辑**: 从当前任务最近的决策和产物中抽取摘要，构造成 `decision_summary`、`artifact_summary` 和结构化 `payload`，默认补齐 `blockers`、`key_constraints`、`next_step` 等字段。
- **复杂度**: 主要取决于最近记录条数，当前实现是固定上限查询，近似 `O(1)`。

### 5.3 Consolidation 巩固策略

- **代码位置**: `src/main/java/com/agentcloud/engine/ConsolidationService.java`
- **用途**: 在任务切换前压缩过程记忆，产出 checkpoint。
- **简要逻辑**: 依次执行 Reactivation、Selection、Compression、Abstraction、Integration 五步，从近 20 条决策、20 条产物和 50 条事件中抽取高价值信息，生成 `refinedPacket` 与 `worldModelDelta` 后写入 `checkpoints`。
- **复杂度**: 受固定条数限制，近似 `O(1)`。
