# API Contracts

## 1. HTTP API 清单

### 1.1 SessionHandler

| 方法 | 路径 | 用途 | 请求参数 | 响应概要 | 认证 |
|------|------|------|---------|---------|------|
| POST | `/api/v1/sessions` | 创建会话 | JSON: `title` | `Session` | 否 |
| GET | `/api/v1/sessions` | 列出全部会话 | 无 | `Session[]` | 否 |
| GET | `/api/v1/sessions/{id}` | 查询单个会话 | 路径参数 `id` | `Session` | 否 |
| GET | `/api/v1/sessions/{id}/tasks` | 列出会话下任务 | 路径参数 `id` | `Task[]` | 否 |
| GET | `/api/v1/sessions/{id}/close` | 关闭会话 | 路径参数 `id` | 已关闭 `Session` | 否 |

### 1.2 TaskHandler

| 方法 | 路径 | 用途 | 请求参数 | 响应概要 | 认证 |
|------|------|------|---------|---------|------|
| POST | `/api/v1/tasks` | 创建任务并自动进入控制图 | `title`, `task_type`, `source`, `priority`, `intent`, `goal?`, `parent_task_id?`, `session_id`, `metadata`, `auto_start?` | `Task` | 否 |
| GET | `/api/v1/tasks` | 过滤或列出最近任务 | Query: `state` 或 `status`, `task_type`, `assigned_worker` | `Task[]` | 否 |
| GET | `/api/v1/tasks/{id}` | 获取任务详情 | 路径参数 `id` | `Task` | 否 |
| POST | `/api/v1/tasks/{id}/state` | 直接更新任务状态 | JSON: `state`, `reason` | `Task` | 否 |
| GET | `/api/v1/tasks/{id}/packet` | 获取最近 resume packet | 路径参数 `id` | `ResumePacket \| null` | 否 |
| GET | `/api/v1/tasks/{id}/refresh_packet` | 重新生成并保存 resume packet | 路径参数 `id` | `ResumePacket` | 否 |
| GET | `/api/v1/tasks/{id}/select_worker` | 获取当前任务路由决策 | 路径参数 `id` | `RouteResult` | 否 |
| GET | `/api/v1/tasks/{id}/runtime_context` | 查看当前运行时上下文与 active context | 路径参数 `id` | `TaskRuntimeContext` | 否 |
| GET | `/api/v1/tasks/{id}/judgment_trace` | 查看最近一次 execution/completion judgment 诊断视图 | 路径参数 `id` | `JudgmentTraceView` | 否 |
| GET | `/api/v1/tasks/{id}/live_flow` | 聚合查看 live flow 诊断面 | Query: `limit` | `TaskLiveFlowView` | 否 |
| GET | `/api/v1/tasks/{id}/tool_trace` | 查看最近工具调用轨迹 | Query: `limit` | `ToolInvocationRecord[]` | 否 |
| GET | `/api/v1/tasks/{id}/handoff_packet` | 预览移交 packet | Query: `target_worker` | `HandoffPacketView` | 否 |
| GET | `/api/v1/tasks/{id}/pause` | 暂停任务 | 路径参数 `id` | `TaskControlResult` | 否 |
| GET | `/api/v1/tasks/{id}/resume` | 恢复任务 | 路径参数 `id` | `TaskControlResult` | 否 |
| GET | `/api/v1/tasks/{id}/continue` | 再次进入控制图 | 路径参数 `id` | `TaskControlResult` | 否 |
| GET | `/api/v1/tasks/{id}/escalate` | 升级为人工等待 | 路径参数 `id` | `TaskControlResult` | 否 |
| POST | `/api/v1/tasks/{id}/handoff` | 指定目标 worker 并移交 | JSON: `target_worker` | `HandoffResult` | 否 |

### 1.3 WorkerHandler

| 方法 | 路径 | 用途 | 请求参数 | 响应概要 | 认证 |
|------|------|------|---------|---------|------|
| GET | `/api/v1/workers` | 列出全部 worker | 无 | `Worker[]` | 否 |
| POST | `/api/v1/workers` | 动态注册 worker | `worker_id`, `worker_type`, `capabilities`, `tool_capabilities`, `tool_scope`, `dependencies`, `metadata`, `suggest_only`, `ready` | `Worker` | 否 |
| GET | `/api/v1/workers/{id}` | 查询 worker | 路径参数 `id` | `Worker` | 否 |
| GET | `/api/v1/workers/{id}/readiness` | 查询 worker readiness | 路径参数 `id` | `ReadinessCheck` | 否 |

`GET /api/v1/tasks/{id}/select_worker` 当前返回的 `RouteResult` 除 `selected_worker` / `fallback_workers` / `route_reason` 外，还包含以下解释字段：

- `route_source`：`learning_memory` 或 `capability_match`
- `task_type`：本轮路由识别到的任务类型
- `preferred_worker_hint`：从 learning memory 读取到的 worker hint
- `learning_hint_applied`：本轮是否真的应用了 learned hint
- `candidate_workers`：进入候选集的 worker 列表

`GET /api/v1/tasks/{id}/runtime_context` 返回的 `TaskRuntimeContext.active_context` 当前会显式暴露：

- `latest_checkpoint`
- `constraints`
- `key_events`
- `key_decisions`
- `key_artifacts`
- `open_questions`
- `next_candidates`
- `risk_hints`
- `learned_hints`
- `selection_trace`
- `continuity_summary`
- `synthesized_context`

其中 `latest_checkpoint` 会把最近一次 consolidation 的 `checkpoint_type / consolidation_summary / refined_packet / world_model_delta` 一并带出，当前 active context 也会消费其中的 `key_decisions`、`key_artifacts`、`open_questions`、`next_candidates`、`repeated_failure_hints`。

`GET /api/v1/tasks/{id}/judgment_trace` 当前会聚合：

- 最近的 `execution_judgment`
- 最近的 `completion_judgment`
- 最近产出摘要 `latest_output`
- 当前推荐动作 `recommended_action`
- 当前推荐下一步 `recommended_next_step`
- 触发这些判断时可见的 `runtime_context`

`GET /api/v1/tasks/{id}/live_flow` 当前会一次性聚合：

- `task`
- `latest_packet`
- `route_preview`
- `runtime_context`
- `judgment_trace`
- `checkpoints`
- `learning_memories`
- `tool_invocations`

该接口适合本地 live validation、回归排查和提示词调优时使用，避免在同一任务上手工拉取多个观测接口。

`POST /api/v1/tasks` 当前还支持任务链字段：

- `parent_task_id`：把新任务挂到某个既有 task 之下，形成显式 follow-up / iteration chain
- 若未显式传 `session_id`，且 `parent_task_id` 有效，则新任务会继承父任务的 `session_id`
- 若同时传了 `session_id` 与 `parent_task_id`，两者必须属于同一个 session，否则请求会被拒绝
- `auto_start=false`：仅创建任务，不立即进入控制图，适合测试或外部调度器显式控制首轮启动

`GET /api/v1/tasks/{id}/tool_trace` 当前直接返回最近的 `tool_invocations`，每条记录至少包含：

- `tool_name`
- `arguments`
- `result_summary`
- `success`
- `elapsed_ms`
- `created_at`
- `metadata`

`POST /api/v1/workers` 新增的工具相关字段语义如下：

- `tool_capabilities`：当前 worker 允许调用的工具集合
- `tool_scope`：当前 worker 被允许访问的根目录集合
- `suggest_only`：若为 `true`，即使声明了能力也不会进入 tool-aware 执行路径
- `ready`：worker readiness 总开关

### 1.4 SkillHandler

| 方法 | 路径 | 用途 | 请求参数 | 响应概要 | 认证 |
|------|------|------|---------|---------|------|
| GET | `/api/v1/skills` | 列出全部 skill | 无 | `Skill[]` | 否 |
| POST | `/api/v1/skills` | 注册 skill | `id`, `name`, `description`, `capability_tags`, `input_schema`, `output_schema`, `dependencies`, `risk_level`, `version` | `Skill` | 否 |
| GET | `/api/v1/skills/{id}` | 查询 skill | 路径参数 `id` | `Skill` | 否 |
| GET | `/api/v1/skills/{id}/readiness` | 查询 skill readiness | 路径参数 `id` | `ReadinessCheck` | 否 |

### 1.5 CheckpointHandler

| 方法 | 路径 | 用途 | 请求参数 | 响应概要 | 认证 |
|------|------|------|---------|---------|------|
| GET | `/api/v1/checkpoints/{taskId}` | 查询任务 checkpoint 列表 | 路径参数 `taskId` | `Checkpoint[]` | 否 |

### 认证与鉴权

- **认证机制**: 本项目未发现相关内容。
- **Token 获取方式**: 本项目未发现相关内容。
- **权限模型**: 当前所有接口默认匿名可访问。

## 2. 数据库设计要点

### 2.1 SQLite — 控制平面本地状态库

- **连接配置位置**: `src/main/java/com/agentcloud/store/DatabaseManager.java`
- **ORM/驱动**: SQLite JDBC + Jdbi SQL Object
- **Migration 工具**: 未发现独立 migration 机制，启动时直接执行 `schema.sql`

**核心表/集合**:

| 表名/集合名 | 用途 | 预估规模 | 关键索引 |
|------------|------|---------|---------|
| `sessions` | 会话主表 | 小到中 | 主键 |
| `tasks` | 任务主表 | 中 | `idx_tasks_session_status_updated` |
| `decisions` | 决策轨迹 | 中 | `idx_decisions_session_task_created` |
| `artifacts` | 产物轨迹 | 中 | `idx_artifacts_session_task_created` |
| `events` | 事件流 | 中到大 | `idx_events_session_task_created` |
| `resume_packets` | 续跑包历史 | 中 | `idx_resume_packets_session_task_created` |
| `relations` | 结构化关系 | 中 | `idx_relations_source`, `idx_relations_target` |
| `skills` | 技能注册表 | 小 | `idx_skills_ready` |
| `checkpoints` | checkpoint 历史 | 中 | `idx_checkpoints_task_created` |
| `tool_invocations` | 工具调用轨迹 | 中 | `idx_tool_invocations_task_created`, `idx_tool_invocations_session_task_created` |

## 3. 缓存策略 (Redis)

本项目未发现相关内容，原因是当前实现完全基于本地 SQLite 和内存注册表，未接入 Redis。

## 4. 消息队列

本项目未发现相关内容，原因是所有控制流都在单进程同步方法调用中完成。

## 5. ElasticSearch

本项目未发现相关内容，原因是仓库中不存在 ES 客户端、索引定义或查询代码。

## 6. 第三方服务与 SDK

| 服务名称 | 用途 | SDK/协议 | 配置位置 | 备注 |
|---------|------|---------|---------|------|
| SQLite | 本地状态存储 | JDBC | `src/main/java/com/agentcloud/store/DatabaseManager.java` | DB 文件位于 `${user.home}/.agentcloud/agent_cloud.db` |
| Jdbi | DAO 层封装 | Java Library | `pom.xml` | 注解 SQL Object 风格 |
| Jackson | JSON 序列化 | Java Library | `pom.xml`, `NioHttpServer` | 输出统一为 `snake_case` |
| SLF4J + Logback | 日志记录 | Java Library | `pom.xml`, `src/main/resources/logback.xml` | 默认输出到控制台 |

## 7. 内部服务调用

| 调用方 | 被调用方 | 协议 | 接口 | 用途 |
|--------|---------|------|------|------|
| `TaskHandler` | `TaskService` | Java 方法调用 | `createTask/updateTaskState/...` | 任务 API 接入 |
| `TaskService` | `ControlNodeGraph` | Java 方法调用 | `enter/triggerPause/...` | 驱动控制节点流转 |
| `ControlNodeGraph` | `WorkerRouter` | Java 方法调用 | `selectWorker` | 自动选 worker |
| `ControlNodeGraph` | `TaskRuntimeContextBuilder` | Java 方法调用 | `build` | 组装单轮执行与判断所需上下文 |
| `ControlNodeGraph` | `WorkerExecutor` | Java 方法调用 | `executeOneRound` | 触发一轮 worker 执行 |
| `ControlNodeGraph` | `JudgmentService` | Java 方法调用 | `judgeExecution/judgeCompletion` | 对执行结果做运行时判断 |
| `TaskHandler` | `TaskService` | Java 方法调用 | `getRuntimeContext` | 暴露 working memory / active context 观测口 |
| `TaskService` | `PacketBuilder` | Java 方法调用 | `buildHandoffPacket` | 生成显式 handoff packet |
| `ControlNodeGraph` | `ConsolidationService` | Java 方法调用 | `consolidate` | 生成 checkpoint |
| `TaskService` / `SessionService` | `*Dao` | Jdbi SQL | `insert/find/list/update` | 读写持久化状态 |
