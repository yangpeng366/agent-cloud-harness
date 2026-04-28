# API Contracts

## 1. HTTP API 清单

### 1.1 SessionHandler

| 方法 | 路径 | 用途 | 请求参数 | 响应概要 | 认证 |
|------|------|------|---------|---------|------|
| POST | `/api/v1/sessions` | 创建会话 | JSON: `title` | `Session` | 否 |
| GET | `/api/v1/sessions` | 列出全部会话 | 无 | `Session[]` | 否 |
| GET | `/api/v1/sessions/{id}` | 查询单个会话 | 路径参数 `id` | `Session` | 否 |
| GET | `/api/v1/sessions/{id}/tasks` | 列出会话下任务 | 路径参数 `id` | `Task[]` | 否 |
| GET | `/api/v1/sessions/{id}/messages` | 列出会话消息流 | Query: `limit?`, `task_id?` | `SessionMessage[]` | 否 |
| POST | `/api/v1/sessions/{id}/messages` | 在会话下追加一条消息 | JSON: `role`, `message_type`, `content`, `task_id?`, `metadata?` | `SessionMessage` | 否 |
| POST | `/api/v1/sessions/{id}/pause` | 暂停会话 | 路径参数 `id` | 已暂停 `Session` | 否 |
| POST | `/api/v1/sessions/{id}/resume` | 恢复会话 | 路径参数 `id` | 已恢复 `Session` | 否 |
| POST | `/api/v1/sessions/{id}/close` | 关闭会话 | 路径参数 `id` | 已关闭 `Session` | 否 |
| GET | `/api/v1/sessions/{id}/close` | 兼容旧客户端的关闭入口 | 路径参数 `id` | 已关闭 `Session` | 否 |

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
| GET | `/api/v1/tasks/{id}/experiment_run` | 查看该任务最新 experiment run 指标快照 | 路径参数 `id` | `ExperimentRunRecord` | 否 |
| GET | `/api/v1/tasks/{id}/experiment_summary` | 以当前任务所属 `experiment_name` 为键，查看整组 matrix 汇总与 case 对比 | 路径参数 `id` | `ExperimentMatrixSummary` | 否 |
| GET | `/api/v1/tasks/{id}/tool_trace` | 查看最近工具调用轨迹 | Query: `limit` | `ToolInvocationRecord[]` | 否 |
| GET | `/api/v1/tasks/{id}/handoff_packet` | 预览移交 packet | Query: `target_worker` | `HandoffPacketView` | 否 |
| POST | `/api/v1/tasks/{id}/pause` | 暂停任务 | JSON: `reason?` | `TaskControlResult` | 否 |
| POST | `/api/v1/tasks/{id}/resume` | 恢复任务 | 可空 body | `TaskControlResult` | 否 |
| POST | `/api/v1/tasks/{id}/continue` | 再次进入控制图 | 可空 body | `TaskControlResult` | 否 |
| POST | `/api/v1/tasks/{id}/escalate` | 升级为人工等待 | JSON: `reason?` | `TaskControlResult` | 否 |
| GET | `/api/v1/tasks/{id}/pause` | 兼容旧客户端的暂停入口 | 路径参数 `id` | `TaskControlResult` | 否 |
| GET | `/api/v1/tasks/{id}/resume` | 兼容旧客户端的恢复入口 | 路径参数 `id` | `TaskControlResult` | 否 |
| GET | `/api/v1/tasks/{id}/continue` | 兼容旧客户端的继续入口 | 路径参数 `id` | `TaskControlResult` | 否 |
| GET | `/api/v1/tasks/{id}/escalate` | 兼容旧客户端的升级入口 | 路径参数 `id` | `TaskControlResult` | 否 |
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
- `related_messages`
- `experiment_run`

该接口适合本地 live validation、回归排查和提示词调优时使用，避免在同一任务上手工拉取多个观测接口。

`GET /api/v1/tasks/{id}/experiment_summary` 会先根据该任务最新 `experiment_run.experiment_name` 自动定位实验，再返回：

- `mode_summaries`：`strong_only / small_only / orchestrated` 三种模式的完成率、接受率、route source 分布、learning hint 应用率、tool chain 指标
- `case_comparisons`：按 `task_case_key` 收口的并排对比，便于在 console 中直接看当前 case 三种模式的差异

如果任务不属于任何 experiment batch，该接口当前返回 `404 not found`。

其中 `related_messages` 的语义是“所有绑定到该 `task_id` 的 session message”，包括：

- `/dialogue/` 镜像出来的 `task_brief / task_followup`
- 后端补写的 `task_receipt / task_action / task_state`
- 任务推进后追加的 `task_progress / task_result`

它不包含未绑定 `task_id` 的 session 级普通消息。

`POST /api/v1/tasks` 当前还支持任务链字段：

- `parent_task_id`：把新任务挂到某个既有 task 之下，形成显式 follow-up / iteration chain
- 若未显式传 `session_id`，且 `parent_task_id` 有效，则新任务会继承父任务的 `session_id`
- 若同时传了 `session_id` 与 `parent_task_id`，两者必须属于同一个 session，否则请求会被拒绝
- `auto_start=false`：仅创建任务，不立即进入控制图，适合测试或外部调度器显式控制首轮启动
- `POST /api/v1/sessions` 当前会把 `requested_via / request_method / request_path` 写入 `session_created` event 和 `session_receipt` message；`POST /api/v1/sessions/{id}/pause|resume|close` 则会把同一组字段写入 `session_state_changed / session_state`
- `POST /api/v1/sessions/{id}/pause` 当前会把 `status` 更新为 `paused`，并稳定投影 `action=session_pause`
- `POST /api/v1/sessions/{id}/resume` 当前会把 `status` 更新回 `active`，并稳定投影 `action=session_resume`
- `POST /api/v1/sessions/{id}/close` 当前会投影 `action=session_close`，并同步持久化 `sessions.closed_at`
- `POST /api/v1/sessions/{id}/close` 当前只允许在该 session 下所有 task 都已进入终态后执行；若仍有 `active / paused / waiting / waiting_human` 等未完成任务，会返回 `400 session has unfinished tasks`
- `POST /api/v1/sessions/{id}/close` 现在按幂等 close 处理：对已关闭 session 重复调用时，会直接返回既有状态，不会重写第一次 `closed_at`，也不会清空已有 `current_task_id / summary`
- `POST /api/v1/sessions/{id}/pause|resume` 不提供历史 `GET` 写接口；对这类未注册 `GET` 子路径当前返回 `405 method not allowed`
- 已关闭的 session 当前不再接受新的 task 绑定，也不再允许更新 `current_task_id`；若继续向关闭中的 session 创建任务，会返回 `400 session is closed`
- `POST /api/v1/tasks` 与内部 `updateCurrentTask` 在同步 `current_task_id` 时，当前会保留 session 既有 `status/closed_at`，不会把 `paused` session 隐式拉回 `active`
- 由 `/dialogue/` 发出的任务，默认还会在对应 session 下镜像一条 `SessionMessage`
- 若已配置 `session_messages` 存储，任务创建和后续控制动作还会 best-effort 追加 `assistant/system` 回执消息
- `POST /api/v1/tasks` 当前会把 `requested_via / request_method / request_path` 一并写入 `task_created` event 和 `task_receipt` message，方便把任务发布入口纳入同一条审计/回放链
- 对于 `auto_start / resume / continue / handoff` 这类会再次推进执行链的动作，后端还会尽量根据 `summary / judgment / artifact / active_context` 追加 `assistant` 侧的 `task_progress`；若任务已进入 `done / failed`，则会写成 `task_result`
- 通过 `POST /pause / resume / continue / escalate / handoff` 触发的控制动作，当前会在 `task_control_action` event、`task_action` message，以及由这些动作触发的 `task_state_changed / task_state` 状态迁移投影中稳定补 `requested_via / request_method / request_path`
- `POST /api/v1/tasks/{id}/state` 触发的直接状态更新，当前也会把同一组 `requested_via / request_method / request_path` 写进 `task_state_changed / task_state`
- 历史兼容 `GET /pause / resume / continue / escalate` 与 `GET /sessions/{id}/close` 仍可用，但响应头会补 `Deprecation: true`、`Sunset: Thu, 31 Dec 2026 23:59:59 GMT` 与替代写接口提示，默认迁移目标是同路径 `POST`；同时生命周期投影里会额外标记 `legacy_control_route=true`
- 当前 HTTP 错误响应约定为稳定 envelope：参数校验失败或 body/query 非法返回 `400`，message 使用明确校验文案；资源不存在统一返回 `404` + `not found`
- 不支持的 HTTP 方法统一返回 `405` + `method not allowed`
- 运行时异常的 HTTP 500 当前统一返回 `{"success":false,"code":"500","message":"internal error"}`，不再直接暴露内部异常文本
- `task_action` 与 `task_control_action` 当前共享稳定生命周期字段：`action`、`action_category=task_control`、`task_status`、`control_node`、`assigned_worker?`、`reason?`
- `task_state` 与 `task_state_changed` 当前共享稳定状态迁移字段：`action=task_state_update`、`old_state`、`new_state`、`previous_state`、`current_state`、`previous_control_node?`、`current_control_node?`、`task_status`、`control_node`、`assigned_worker?`、`reason?`

当前 packet 相关接口遵循以下最小协议，默认保持 `machine-readable first`，再保留少量 human-facing summary：

`GET /api/v1/tasks/{id}/packet` 与 `GET /api/v1/tasks/{id}/refresh_packet` 返回的 `ResumePacket` 最小字段：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `task_identity` | object | `task_id/session_id/parent_task_id/title/task_type` |
| `current_objective` | string | 当前连续执行目标 |
| `current_status` | string | 当前任务状态 |
| `current_node` | string | 当前控制节点 |
| `assigned_worker` | string | 当前指派 worker |
| `latest_summary` | string | 最近一版最适合续跑的摘要 |
| `next_step` | string | 恢复后建议动作 |
| `blockers` | string[] | 当前阻塞项 |
| `open_questions` | string[] | 当前未决问题 |
| `recent_artifacts` | object[] | 最近产物引用，含 `artifact_type/title/summary/created_at` |
| `recent_decisions` | object[] | 最近决策引用，含 `decision_type/summary/rationale/created_at` |

顶层协议头当前固定包含：

- `packet_version=1.1`
- `machine_readable_first=true`

兼容性字段当前仍保留：

- `active_task_summary`
- `decision_summary`
- `artifact_summary`
- `payload.machine_readable_first=true`
- `payload.next_step` 会镜像顶层 `next_step`

`GET /api/v1/tasks/{id}/handoff_packet` 与 `POST /api/v1/tasks/{id}/handoff` 返回的 `HandoffPacket` 最小字段：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `task_identity` | object | 当前交接任务身份 |
| `from_worker` | string | 当前交出方 |
| `to_worker` | string | 目标接收方 |
| `current_objective` | string | 当前交接目标 |
| `current_status` | string | 当前任务状态 |
| `current_node` | string | 当前控制节点 |
| `why_handoff` | string | 交接原因 |
| `what_done` | string[] | 已完成工作摘要 |
| `what_remaining` | string[] | 剩余待做事项 |
| `cautions` | string[] | 风险、阻塞或注意事项 |
| `resume_hint` | string | 接手后最直接的恢复提示 |
| `latest_summary` | string | 最近一次适合交接的摘要 |
| `handoff_summary` | string | 面向人类快速浏览的交接描述 |

顶层协议头当前固定包含：

- `packet_version=1.0`
- `machine_readable_first=true`
- `metadata` 只承载扩展 trace，不替代最小字段

`GET /api/v1/checkpoints/{taskId}` 返回的 `Checkpoint.refined_packet` 当前也按同一套 continuity 语义收口。固定协议头为：

- `packet_type=checkpoint_refined_packet`
- `packet_version=1.0`
- `machine_readable_first=true`

除 consolidation 专有字段 `trigger / key_decisions / key_artifacts / key_constraints / next_candidates / repeated_failure_hints / consolidated_at` 外，`refined_packet` 还会稳定镜像以下 continuity 最小字段：

- `task_identity`
- `current_objective`
- `current_status`
- `current_node`
- `assigned_worker`
- `latest_summary`
- `next_step`
- `blockers`
- `open_questions`
- `recent_artifacts`
- `recent_decisions`

`GET /api/v1/sessions/{id}/messages` 当前支持：

- `limit`：默认 50，取值范围会被 clamp 到 `1..100`
- `task_id`：只返回与指定 task 绑定的消息
- 若 `task_id` 不属于该 session，请求会被拒绝

`POST /api/v1/sessions/{id}/messages` 当前常用字段语义：

- `role`：`/dialogue/` 手工写入时主要使用 `user`；后端任务回执会补 `assistant`、`system`
- `message_type`：页面会写入 `user_note`、`task_note`、`task_brief`、`task_followup`；后端任务回执会补 `task_receipt`、`task_action`、`task_state`、`task_progress`、`task_result`
- `task_id`：可选，把消息附着到某个 task，供 `/dialogue/` 的 Related Messages 与消息转任务草稿能力消费
- `metadata`：记录 `source_surface`、`created_via`、`mirrored_from` 等页面来源信息；后端补写的 assistant 回执还会带 `trigger`、`summary_preview`、`next_step`、`completion_status`、`judgment_action` 等执行信号

`GET /api/v1/tasks/{id}/tool_trace` 当前直接返回最近的 `tool_invocations`，每条记录至少包含：

- `tool_name`
- `arguments`
- `result_summary`

`GET /api/v1/tasks/{id}/packet` 当前已经固定为“machine-readable first”的 resume packet 视图。除了历史字段 `active_task_summary / decision_summary / artifact_summary / open_questions / next_step / payload` 外，还会稳定暴露以下最小协议字段：

- `task_identity`
- `current_objective`
- `current_status`
- `current_node`
- `assigned_worker`
- `latest_summary`
- `blockers`
- `open_questions`
- `recent_artifacts`
- `recent_decisions`

其中：

- `task_identity` 当前固定包含 `task_id / session_id / parent_task_id / title / task_type`
- `recent_artifacts` 为结构化列表，元素至少包含 `artifact_type / title / summary / created_at`
- `recent_decisions` 为结构化列表，元素至少包含 `decision_type / summary / rationale / created_at`
- `payload` 继续保留为扩展包，且会镜像 `task_identity / current_objective / next_step / blockers / recent_artifacts / recent_decisions` 等 machine-readable 字段；旧的 `active_goal / task_status / relevant_artifacts` 等兼容字段目前仍会一并输出，但不再作为主协议依赖

`GET /api/v1/tasks/{id}/handoff_packet` 与 `POST /api/v1/tasks/{id}/handoff` 返回的 `handoff_packet` 当前也已固定为 typed schema，不再是散装 `Map`。最小字段包括：

- `task_identity`
- `from_worker`
- `to_worker`
- `current_objective`
- `current_status`
- `current_node`
- `why_handoff`
- `what_done`
- `what_remaining`
- `cautions`
- `resume_hint`

此外还会附带：

- `latest_summary`
- `handoff_summary`
- `metadata`

其中 `metadata` 目前主要承载 `model_mode / orchestration_stage / planner_worker / executor_worker / selected_model_tier / fallback_reason` 等扩展 trace，用于跨 worker 连续性排查，但不应替代上述最小字段。

`GET /api/v1/checkpoints/{taskId}` 返回的 `Checkpoint.refined_packet` 当前也已固定为 machine-readable first，并沿用同一套 continuity 字段命名。它既可作为 consolidation 输出，也可作为后续 resume / audit / replay 的稳定输入。

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

### 1.6 LearningMemoryHandler

| 方法 | 路径 | 用途 | 请求参数 | 响应概要 | 认证 |
|------|------|------|---------|---------|------|
| GET | `/api/v1/learning_memories` | 按 `task_id` 或 `memory_type` 查询学习记忆 | Query: `task_id?`, `memory_type?`, `limit?` | `LearningMemory[]` | 否 |
| GET | `/api/v1/learning_memories/{taskId}` | 查询某个任务的学习记忆 | 路径参数 `taskId`，Query: `limit?` | `LearningMemory[]` | 否 |

### 1.7 ExperimentRunHandler

| 方法 | 路径 | 用途 | 请求参数 | 响应概要 | 认证 |
|------|------|------|---------|---------|------|
| GET | `/api/v1/experiment_runs` | 过滤查看统一 experiment run 落盘记录 | Query: `experiment_name?`, `task_case_key?`, `task_length_bucket?`, `model_mode?`, `tool_execution_mode?`, `tool_chain_termination_reason?`, `min_tool_chain_steps?`, `max_tool_chain_steps?`, `limit?` | `ExperimentRunRecord[]` | 否 |
| GET | `/api/v1/experiment_runs/{taskId}` | 查看某个任务的最新 experiment run 记录 | 路径参数 `taskId` | `ExperimentRunRecord` | 否 |

### 1.8 ExperimentMatrixHandler

| 方法 | 路径 | 用途 | 请求参数 | 响应概要 | 认证 |
|------|------|------|---------|---------|------|
| GET | `/api/v1/experiment_matrix/cases` | 查看内置 `3 short + 3 medium + 3 long` baseline case catalog | 无 | `BaselineTaskCase[]` | 否 |
| POST | `/api/v1/experiment_matrix/runs` | 按 case/mode 批量创建可比较基线 run | JSON: `experiment_name?`, `case_keys?`, `modes?`, `priority?`, `source?`, `auto_start?`, `metadata?` | `ExperimentMatrixBatch` | 否 |
| GET | `/api/v1/experiment_matrix/summary` | 按实验名聚合比较不同模式结果 | Query: `experiment_name` | `ExperimentMatrixSummary` | 否 |

`ExperimentMatrixSummary.mode_summaries[*]` 现在额外聚合以下 tool 观测字段：

- `runs_with_tool_chain_data`
- `average_tool_chain_step_count`
- `max_tool_chain_step_count`
- `tool_execution_mode_counts`
- `tool_chain_termination_reason_counts`

### 1.9 Health

| 方法 | 路径 | 用途 | 请求参数 | 响应概要 | 认证 |
|------|------|------|---------|---------|------|
| GET | `/api/v1/health` | 健康检查与版本探针 | 无 | `status/virtual_threads/version` | 否 |

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
| `session_messages` | session 级消息流 | 中 | `idx_session_messages_session_created`, `idx_session_messages_task_created` |
| `skills` | 技能注册表 | 小 | `idx_skills_ready` |
| `checkpoints` | checkpoint 历史 | 中 | `idx_checkpoints_task_created` |
| `tool_invocations` | 工具调用轨迹 | 中 | `idx_tool_invocations_task_created`, `idx_tool_invocations_session_task_created` |
| `experiment_runs` | baseline experiment 与评估指标汇总 | 中 | `idx_experiment_runs_experiment_name`, `idx_experiment_runs_case_mode`, `idx_experiment_runs_updated` |

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
