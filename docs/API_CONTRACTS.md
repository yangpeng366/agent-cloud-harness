# API Contracts

## 1. HTTP API 清单

### Hardness phase-1 观测面与当前代码对齐

当前 API 不只是 CRUD 外壳，而是已经暴露出一部分 hardness phase-1 所需的观测面：

- `runtime_context` 暴露了当前 worker round / judgment round 会消费的上下文裁剪结果，其中既包含兼容旧面的 `active_context`，也包含更显式的 `mounted_context_view`
- `judgment_trace` 暴露了 execution/completion judgment 与推荐动作
- `tool_trace` 暴露了真实持久化的工具调用轨迹
- `live_flow` 聚合了 task / packet / route / runtime / judgment / checkpoint / learning memory / tool trace / experiment run
- `harness_trace` 则进一步把这些信号压成更适合 outer-loop 演进与复盘的诊断视图

因此从 hardness 方案角度看，当前 API 真实状态更接近：

- 已有 `ToolInvocationRecord` 观测面
- 已有 checkpoint / packet / runtime context 观测面
- 已有 judgment 观测面
- 但尚未显式暴露统一的 `WorkerExecutionEnvelope`、`RuntimeFactSet`、`ContinuationAction` 作为一等 API 对象

也就是说：**观测能力已经存在，但 contract 仍主要散落在多个 view / trace 接口中。**

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
| GET | `/api/v1/tasks/recoverable` | 列出最近可恢复/需人工处理的中断任务 | Query: `limit?` | `TaskRecoveryPlan[]` | 否 |
| GET | `/api/v1/tasks/{id}` | 获取任务详情 | 路径参数 `id` | `Task` | 否 |
| POST | `/api/v1/tasks/{id}/state` | 直接更新任务状态 | JSON: `state`, `reason` | `Task` | 否 |
| GET | `/api/v1/tasks/{id}/packet` | 获取最近 resume packet | 路径参数 `id` | `ResumePacket \| null` | 否 |
| GET | `/api/v1/tasks/{id}/refresh_packet` | 重新生成并保存 resume packet | 路径参数 `id` | `ResumePacket` | 否 |
| GET | `/api/v1/tasks/{id}/select_worker` | 获取当前任务路由决策 | 路径参数 `id` | `RouteResult` | 否 |
| GET | `/api/v1/tasks/{id}/runtime_context` | 查看当前运行时上下文、active context 与 mounted context 视图 | 路径参数 `id` | `TaskRuntimeContext` | 否 |
| GET | `/api/v1/tasks/{id}/judgment_trace` | 查看最近一次 execution/completion judgment 诊断视图 | 路径参数 `id` | `JudgmentTraceView` | 否 |
| GET | `/api/v1/tasks/{id}/live_flow` | 聚合查看 live flow 诊断面 | Query: `limit` | `TaskLiveFlowView` | 否 |
| GET | `/api/v1/tasks/{id}/experiment_run` | 查看该任务最新 experiment run 指标快照 | 路径参数 `id` | `ExperimentRunRecord` | 否 |
| GET | `/api/v1/tasks/{id}/experiment_summary` | 以当前任务所属 `experiment_name` 为键，查看整组 matrix 汇总与 case 对比 | 路径参数 `id` | `ExperimentMatrixSummary` | 否 |
| GET | `/api/v1/tasks/{id}/harness_trace` | 查看面向 AHE 复盘的压缩 Harness 执行轨迹 | Query: `limit` | `HarnessTraceView` | 否 |
| GET | `/api/v1/tasks/{id}/tool_trace` | 查看最近工具调用轨迹 | Query: `limit` | `ToolInvocationRecord[]` | 否 |
| GET | `/api/v1/tasks/{id}/recovery_jobs` | 查看该任务最近异步恢复 job | Query: `limit?` | `TaskRecoveryJob[]` | 否 |
| GET | `/api/v1/tasks/{id}/handoff_packet` | 预览移交 packet | Query: `target_worker` | `HandoffPacketView` | 否 |
| POST | `/api/v1/tasks/{id}/pause` | 暂停任务 | JSON: `reason?` | `TaskControlResult` | 否 |
| POST | `/api/v1/tasks/{id}/resume` | 恢复任务 | 可空 body | `TaskControlResult` | 否 |
| POST | `/api/v1/tasks/{id}/continue` | 再次进入控制图 | 可空 body | `TaskControlResult` | 否 |
| POST | `/api/v1/tasks/{id}/escalate` | 升级为人工等待 | JSON: `reason?` | `TaskControlResult` | 否 |
| POST | `/api/v1/tasks/{id}/recover` | 按恢复计划冷启动恢复或 handoff 最近失败任务 | Query: `async?`; JSON: `mode?`, `target_worker?`, `reason?`, `async?`, `wait?` | `TaskRecoveryResult` | 否 |
| GET | `/api/v1/tasks/{id}/pause` | 兼容旧客户端的暂停入口 | 路径参数 `id` | `TaskControlResult` | 否 |
| GET | `/api/v1/tasks/{id}/resume` | 兼容旧客户端的恢复入口 | 路径参数 `id` | `TaskControlResult` | 否 |
| GET | `/api/v1/tasks/{id}/continue` | 兼容旧客户端的继续入口 | 路径参数 `id` | `TaskControlResult` | 否 |
| GET | `/api/v1/tasks/{id}/escalate` | 兼容旧客户端的升级入口 | 路径参数 `id` | `TaskControlResult` | 否 |
| POST | `/api/v1/tasks/{id}/handoff` | 指定目标 worker 并移交 | JSON: `target_worker` | `HandoffResult` | 否 |

`TaskRecoveryPlan` 当前用于 `/tasks/recoverable` 与 `/tasks/{id}/recover` 的恢复前诊断，稳定字段至少包括：

- `recoverable` / `recommended_action` / `reason`
- `target_worker`
- `failure_class` / `provider_failure_class`
- `failure_evidence_source` / `failure_evidence`：恢复计划采用的失败分类证据来源与短证据文本，可能来自 request、task metadata、agent run metadata、agent run summary、task waiting reason、task summary 或 next step
- `recovery_stage` / `recovery_execution_mode`

当 `mode=auto` 且请求或任务 metadata 中存在不同于当前 worker 的 `target_worker / auto_handoff_target` 时，恢复入口应执行 handoff 并返回 `handoff_result`；否则才按 fresh-session `resume` 恢复。

默认恢复入口保持同步语义，HTTP 响应会等待本轮恢复动作返回。长任务或真实 provider worker 场景应使用 `POST /api/v1/tasks/{id}/recover?async=true`，或在 body 中传 `{"async":true}` / `{"wait":false}`。异步恢复会先同步校验恢复计划，不可恢复任务仍返回 `400`；可恢复任务返回 HTTP `202`，`TaskRecoveryResult.accepted=true`、`async=true`、`request_id` 和 `status_url`，后续进展通过 `status_url` 指向的 live flow 继续观察。

异步恢复还会落一条 `TaskRecoveryJob`，可通过 `GET /api/v1/tasks/{id}/recovery_jobs?limit=10` 查询。`request_id` 即 job id；状态最小集合为 `accepted / running / succeeded / failed / interrupted`。其中 `interrupted` 表示 harness 重启或进程中断时，启动 reconciler 把遗留的 `accepted/running` job 收束为已中断，并写入 `completed_at / error_message`。该接口只读，不触发恢复动作。

本地 live API 验收可运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-TaskRecoveryAcceptanceProbe.ps1 -BaseUrl http://localhost:8080
```

默认 probe 覆盖 recoverable 列表、auto handoff 和环境阻断拒绝；若要让 probe 额外验证 fresh-session 异步恢复触发链，可加 `-IncludeResumeExecution`。

`-IncludeResumeExecution` 当前使用 `recover?async=true`，验收重点是 HTTP `202`、`accepted=true`、`async=true`、`request_id`、`status_url` 和 `recovery_execution_mode=fresh_session`。它不等待 worker 完成，后续进展通过 `status_url` 继续观察。

### 1.2A Chat Facade

| 方法 | 路径 | 用途 | 请求参数 | 响应概要 | 认证 |
|------|------|------|---------|---------|------|
| POST | `/v1/chat/completions` | OpenAI-compatible chat façade；把 chat turn 映射到 session message + 可选 task materialization | JSON: `model`, `messages`, `stream?`, `metadata?` | `chat.completion` JSON + `agentcloud` continuity 扩展块 | 否 |
| POST | `/v1/responses` | 最小 OpenAI-compatible Responses façade；复用同一套 session/task continuity contract | JSON: `model`, `input`, `instructions?`, `stream?`, `metadata?` | `response` JSON + `agentcloud` continuity 扩展块 | 否 |
| GET | `/v1/models` | 返回 façade 级模型列表 | 无 | `list` + `model[]` | 否 |

`POST /v1/chat/completions` 当前是 **最小兼容实现**，只保证：

- 非 streaming JSON completion
- 最小 `stream=true` SSE completion
- 文本 `messages`
- 单个最终 `assistant` reply
- continuity 扩展块里显式返回 `session_id / task_id / task_status / control_node / reply_type / reply_source / live_flow_path / packet_path`

当前稳定支持的 façade model：

- `agentcloud-default`
- `agentcloud-strong`
- `agentcloud-fast`

当前 `metadata.task_mode` 支持：

- `message_only`
- `task_auto`
- `task_required`

`POST /v1/responses` 当前也是 **最小兼容实现**，只保证：

- 文本 `input`
- 可选 `instructions`
- 复用与 `/v1/chat/completions` 相同的 `metadata.task_mode / task_id / session_id / auto_start` continuity 语义
- 非 streaming `response` JSON
- 最小 `stream=true` SSE event 流

当前 `input` 支持的最小形态：

- 直接传字符串
- 传数组，其中 user item 的 `content` 为：
  - 字符串
  - 或 `[{ "type": "input_text", "text": "..." }]`

当前 `response` 输出形态：

- `object=response`
- `status=completed`
- `output[0].type=message`
- `output[0].content[0].type=output_text`
- 顶层 `output_text`
- 同样附带 `agentcloud.session_id / task_id / task_status / control_node / reply_type / reply_source / live_flow_path / packet_path`

当前 `/v1/responses?stream=true` 最小 SSE 事件面：

- `response.created`
- `response.output_item.added`
- `response.output_text.delta`
- `response.output_text.done`
- `response.completed`
- `data: [DONE]`

它仍然不是 token 级增量流；`response.output_text.delta` 当前承载的是最终完整文本，而不是逐 token 追加。

当前执行语义：

- `message_only`
  - 未提供 `task_id` 时，只向 session 追加一条 user turn，不物化新 task
  - 显式提供 `task_id` 时，把本轮输入记录为该 task 的 `task_note` continuity turn，并返回 `chat_reply / session_ack`；这条路径不会自动推进控制图
- `task_auto`
  - 若显式提供 `task_id`，则把本轮输入作为该 task 的 continuity turn；当 `auto_start=true` 时复用现有 `TaskService.continueTask(...)`，当 `auto_start=false` 时仅记录一条 `task_note` 并返回等待手动继续的 assistant ack
  - 否则优先复用 session 当前未终态 task；若找到 active task，语义同上，同样受 `auto_start` 控制；若没有 active task，则自动新建一个 task 并进入 harness
- `task_required`
  - 一定物化为 task（或显式 `task_id` continuation），再复用现有 `TaskService` / `ControlNodeGraph`
  - 当显式提供 `task_id` 时，这条路径同样遵守 `auto_start`：`false` 只记录 continuity turn，不自动推进控制图
  - 当新建 task 且 `auto_start=false` 时，assistant reply 会优先复用 `task_receipt` 的 manual-start 文案，而不是伪装成已经执行过一轮的 `task_progress`
  - 对已有 task 的 manual continuity（显式 `task_id` 或 `task_auto` 复用 active task）当前仍返回 `chat_reply / session_ack`；只有“新建但未启动”的 task materialization 才返回 `task_receipt / task_receipt`

当前 materialization/backfill 语义：

- façade 在真正 `createTask(...)` 之前，会先写一条 user turn：
  - 普通新任务写 `task_brief`
  - follow-up 新任务写 `task_followup`
- 当 task / child task 成功 materialize 后，这条 staging user turn 会被回填 `task_id`
- 因此 `task_brief / task_followup` 最终是 task-bound continuity message，而不是永久停留在 task-free session note
- 当前这条合同已有 HTTP 回归覆盖：
  - `ChatFacadeHandlerHttpTest.chatFacadeAcceptanceFlowCoversMessageTaskNoteAndManualFollowupInOneSession()`

当前 `agentcloud.reply_type / reply_source` 约定：

- `chat_reply / session_ack`
  - façade 只记录 session message 或 task note，没有推进执行链时返回
- `task_receipt / task_receipt`
  - 新建 manual-start task 后返回
- `task_progress / task_progress`
  - 自动推进执行链后，优先基于 `task_progress` 返回
- `task_result / task_result`
  - 任务已进入终态且已生成 `task_result` 时返回
- `task_action / task_action`
  - 若 façade 最终命中的是 task control action 回执，则按该类型返回
- `task_state / task_state`
  - 若 façade 最终命中的是状态迁移回执，则按该类型返回

当前 `/dialogue/` 对这组字段的消费约定：

- `chat_reply / session_ack`
  - 只显示“已记录”类 toast / composer inline，不额外打 transcript latest reply badge
- `task_receipt / task_receipt`
  - 显示“任务已记录”类 toast / composer inline，并给当前作用域下最新一条 assistant/system task reply 打 `latest receipt`
- `task_progress / task_progress`
  - 显示“任务已推进”类 toast / composer inline，并给当前作用域下最新一条 assistant/system task reply 打 `latest progress`
- `task_result / task_result`
  - 显示“任务已完成”类 toast / composer inline，并给当前作用域下最新一条 assistant/system task reply 打 `latest result`

当前已知限制：

- `stream=true` 当前仅支持最小 SSE 包装：
  - 先输出一条 `chat.completion.chunk`，其中 `delta.role=assistant`，`delta.content` 直接承载最终完整文本
  - 再输出一条 `finish_reason=stop` 的终止 chunk，并携带完整 `agentcloud` continuity 扩展块
  - 最后输出 `data: [DONE]`
- 当前不是 token 级增量流，也不保证中途 progress event / tool-call delta
- `/v1/responses` 当前也只是最小 façade，不支持完整 Responses item/type surface、tool-call streaming、multimodal
- façade 只做外层 chat 包装，不替代 `/api/v1/tasks/*` 诊断面

### 1.3 WorkerHandler

| 方法 | 路径 | 用途 | 请求参数 | 响应概要 | 认证 |
|------|------|------|---------|---------|------|
| GET | `/api/v1/workers` | 列出全部 worker | 无 | `Worker[]` | 否 |
| POST | `/api/v1/workers` | 动态注册 worker | `worker_id`, `worker_type`, `capabilities`, `tool_capabilities`, `tool_scope`, `dependencies`, `metadata`, `suggest_only`, `ready` | `Worker` | 否 |
| GET | `/api/v1/workers/{id}` | 查询 worker | 路径参数 `id` | `Worker` | 否 |
| GET | `/api/v1/workers/{id}/readiness` | 查询 worker readiness | 路径参数 `id`；Query `mode=passive|dispatch`，默认 `passive` | `ReadinessCheck` | 否 |

`GET /api/v1/tasks/{id}/select_worker` 当前返回的 `RouteResult` 除 `selected_worker` / `fallback_workers` / `route_reason` 外，还包含以下解释字段：

- `route_source`：`task_pinned`、`learning_memory`、`capability_match`、`ready_fallback` 或 `none`
- `task_type`：本轮路由识别到的任务类型
- `preferred_worker_hint`：从 learning memory 读取到的 worker hint
- `learning_hint_applied`：本轮是否真的应用了 learned hint
- `candidate_workers`：进入候选集的 worker 列表
- `fallback_reason`：未按首选 worker / tier / hint 命中的原因；若分发前 readiness 跳过候选 worker，会包含对应 worker 与原因
- `current_pinned_route`：当任务当前已有 assigned worker 时，说明是否继续固定到该 worker
- `recovery_unpinned_recommendation`：恢复链建议解绑当前 worker 时的解释信息

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

同一个 `TaskRuntimeContext` 现在还会携带 `mounted_context_view`，用于把 runtime surface 进一步整理成更稳定的 panel/object 结构。当前稳定字段至少包括：

- `task_id`
- `panels`
- `selection_trace`

其中 `panels` 会按固定 panel 名称输出：

- `pinned`
- `active`
- `ancestor`
- `sibling`
- `evidence`
- `index`
- `archive_handles`

每个 panel 下的 `objects` 至少包含：

- `type`
- `path`
- `parent_path`
- `title`
- `summary`
- `content_preview`
- `retention_state`
- `metadata`

当前 mounted context 的消费面分成两类：

- 始终消费的 runtime / policy 面：
  `TaskRuntimeContextBuilder` 会稳定构建 `mounted_context_view`，`LearningMemoryService` 的 `context_retention_hint` 候选提取与 retention evidence 也会优先读取它
- 受 rollout seam 控制的 prompt 面：
  `DefaultWorkerExecutor` 的 execution prompt、`ToolAwareWorkerExecutor` 的 planning / finalization prompt、`PromptBasedJudgmentService` 的 execution / completion judgment prompt

当前 prompt rollout 通过 task metadata 控制，识别键依次为：

- `prompt_rendering_mode`
- `mounted_context_mode`（兼容别名）
- `prompt_mode`（continuity-safe 兼容别名；适用于 resume/handoff / experiment replay 仅保留 canonical prompt 指标字段的场景）

当前稳定模式值为：

- `active_context_only`：默认模式，仅注入既有 `Active Context`，不渲染 mounted prompt
- `mounted_context_shadow`：计算 mounted prompt 摘要并把模式 / panel count / selection trace count 写入 worker metadata，但不注入 prompt
- `mounted_context_primary`：把 mounted prompt 摘要注入 execution / planning / judgment prompt，同时保留 `Active Context` 兼容面

当前 learning memory 的 phase-2 语义也已经有了比较明确的分层：

- `routing_preference`：由运行期 handoff judgment 强化，并直接反哺 `WorkerRouter.selectWorker()`
- `context_retention_hint`：由 checkpoint / completion signal 强化，并直接回流 `TaskRuntimeContextBuilder -> ActiveContextBuilder`
- `completion_pattern`：记录非 `done` 完成态与 alignment 证据，当前主要通过 `/learning_memories` 与 `live_flow` 暴露，供人工审计与后续策略收敛
- `worker_heuristic`：记录低置信度 worker 输出与 follow-up 信号，当前主要通过 `/learning_memories` 与 `live_flow` 暴露，供人工审计与后续策略收敛

`GET /api/v1/tasks/{id}/judgment_trace` 当前会聚合：

- 最近的 `execution_judgment`
- 最近的 `completion_judgment`
- 最近产出摘要 `latest_output`
- 当前推荐动作 `recommended_action`
- 当前推荐下一步 `recommended_next_step`
- 触发这些判断时可见的 `runtime_context`
- 同轮执行边界 `execution_boundary`
- 可直接复用到诊断 UI 的 `runtime_facts`（含 route preview、tool summary、prompt mode、mounted-context rollout 信号、candidate workers、evidence refs、unfinished items）

`GET /api/v1/tasks/{id}/live_flow` 当前会一次性聚合：

- `task`
- `latest_packet`
- `route_preview`
- `runtime_context`
- `judgment_trace`
- `runtime_facts`
- `runtime_cognition_surface`
- `runtime_cognition_timeline`
- `execution_boundary`
- `checkpoints`
- `learning_memories`
- `tool_invocations`
- `related_messages`
- `experiment_run`

该接口适合本地 live validation、回归排查和提示词调优时使用，避免在同一任务上手工拉取多个观测接口。

其中 `runtime_cognition_timeline` 用来把同一任务最近一轮 route / execution / judgment 的认知边界，以及 pause / resume / handoff / checkpoint / resume packet 这些 continuity boundary 按时间线显式展开，当前 entry 主要包含：

- `stage` / `label` / `occurred_at`
- `continuity_action` / `checkpoint_type` / `reason` / `target_worker`
- `worker_id` / `route_source` / `prompt_mode` / `execution_status`
- `tool_invocation_count`
- `mounted_context_rendered` / `mounted_context_injected` / `mounted_context_panel_count`
- `mounted_context_rendered_object_count` / `mounted_context_hidden_object_count`
- `mounted_context_rendered_selection_trace_count` / `mounted_context_hidden_selection_trace_count`
- `mounted_context_budget_truncated`
- `aligned_with_previous_prompt_mode`
- `candidate_workers`
- `evidence_refs`
- `unfinished_items`
- `summary`

其中 `runtime_cognition_surface` / `judgment_trace.runtime_cognition_surface` 当前也会稳定暴露 mounted-context prompt budget 与 provider 执行诊断，用于对齐 worker 与 judgment 的 working-memory 消耗面，并让 details/open 面板不用回退解析 raw artifact metadata。主要包括：

- `mounted_render_used`
- `mounted_context_panel_count`
- `mounted_context_non_empty_panel_count`
- `mounted_context_selection_trace_count`
- `mounted_pinned_count`
- `mounted_active_count`
- `mounted_ancestor_count`
- `mounted_sibling_count`
- `mounted_evidence_count`
- `mounted_index_count`
- `mounted_archive_count`
- `execution.worker_id / execution.execution_status / execution.execution_backend`
- `execution.provider_id / execution.provider_session_id / execution.provider_thread_id / execution.resume_provider_session_id`
- `execution.provider_turn_status / execution.provider_timeout_kind / execution.provider_abort_reason`
- `execution.provider_activity_timeout_ms / execution.provider_turn_max_duration_ms`
- `execution.provider_failure_class / execution.provider_failure_reason / execution.provider_retryable`
- `execution.partial_output_chars / execution.partial_timeout_min_output_chars`
- `execution.provider_run_dir / execution.provider_*_path`

它的目的不是替代 `judgment_trace.runtime_facts`，而是把 shared runtime cognition seam 在单任务观测面里做成可直接阅读的 timeline，便于定位 route、execution、execution judgment、completion judgment 之间的认知漂移。

`GET /api/v1/tasks/{id}/experiment_summary` 会先根据该任务最新 `experiment_run.experiment_name` 自动定位实验，再返回：

- `mode_summaries`：`strong_only / small_only / orchestrated` 三种模式的完成率、接受率、route source 分布、learning hint 应用率、tool chain 指标
- `case_comparisons`：按 `task_case_key` 收口的并排对比，便于在 console 中直接看当前 case 三种模式的差异

如果任务不属于任何 experiment batch，该接口当前返回 `404 not found`。

其中 `related_messages` 的语义现在更接近“当前 task 的 related message surface”，默认按时间窗口聚合两类消息：

- `/dialogue/` 镜像出来的 `task_brief / task_followup`
- 后端补写的 `task_receipt / task_action / task_state`
- 任务推进后追加的 `task_progress / task_result`
- 同一 session 下未绑定 `task_id` 的普通连续聊天消息（例如 `user_note`），用于保留 follow-up 前后的 continuity hint

其中：

- 绑定当前 `task_id` 的消息会补 `metadata.continuity_scope=task`
- 未绑定 `task_id` 但被并入当前 task 读面的 session 级消息会补 `metadata.continuity_scope=session`

这个字段的目的，是让 `/dialogue/`、`live_flow` 和后续 continuity 读面能区分“task-bound receipt”与“session-level continuity hint”，而不需要再猜测来源。

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
- `payload.prompt_rendering_mode`
- `payload.mounted_context_mode`
- `payload.prompt_mode`

其中 packet continuity 字段当前约定为：

- `prompt_rendering_mode`：主 rollout 键
- `mounted_context_mode`：兼容别名
- `prompt_mode`：canonical continuity-safe alias，适用于 resume/handoff/replay 只保留统一 prompt 指标字段的场景

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

当前 `HandoffPacket.metadata` 也会稳定镜像 mounted-context continuity 字段：

- `prompt_rendering_mode`
- `mounted_context_mode`
- `prompt_mode`

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
- `prompt_rendering_mode`
- `mounted_context_mode`
- `prompt_mode`

`GET /api/v1/sessions/{id}/messages` 当前支持：

- `limit`：默认 50，取值范围会被 clamp 到 `1..100`
- `task_id`：只返回与指定 task 绑定的消息
- 若 `task_id` 不属于该 session，请求会被拒绝

`POST /api/v1/sessions/{id}/messages` 当前常用字段语义：

- `role`：`/dialogue/` 手工写入时主要使用 `user`；后端任务回执会补 `assistant`、`system`
- `message_type`：页面会写入 `user_note`、`task_note`、`task_brief`、`task_followup`；后端任务回执会补 `task_receipt`、`task_action`、`task_state`、`task_progress`、`task_result`、`worker_round`
- `task_id`：可选，把消息附着到某个 task，供 `/dialogue/` 的 Related Messages 与消息转任务草稿能力消费
- `metadata`：记录 `source_surface`、`created_via`、`mirrored_from` 等页面来源信息；后端补写的 assistant/system 回执还会带 `trigger`、`summary_preview`、`next_step`、`completion_status`、`judgment_action`、`action_label`、`route_source`、`tool_chain_*`、`provider_*`、`partial_*` 等执行信号

当前与 `/dialogue/` 直接相关的 message metadata 约定已经进一步收口为：

- `task_progress / task_result`
  - `summary_preview`：前端优先展示的可读摘要
  - `next_step`：下一步提示
  - `model_mode / task_type / assigned_worker / route_source / preferred_worker_hint / learning_hint_applied`
  - `tool_execution_mode / tool_chain_step_count / tool_chain_termination_reason / tool_chain_trace_summary / tool_chain_tools`
- `worker_round`
  - 由 `ControlNodeGraph` 写入 worker artifact 后同步追加，存量 artifact 会在读取 session messages 时 best-effort backfill
  - `content` 是压缩后的本轮 worker 输出摘要，不是 provider 原始长日志
  - `metadata.artifact_id / artifact_type` 指向 worker artifact
  - `metadata.execution_status / selected_worker / execution_backend / provider_id`
  - `metadata.provider_thread_id / resume_provider_session_id / provider_timeout_kind / provider_abort_reason`
  - `metadata.partial_output_chars / partial_timeout_min_output_chars`
  - `metadata.provider_run_dir / provider_last_message_path / provider_event_log_path / provider_stdout_path / provider_run_metadata_path`
  - 当 `execution_status=partial_timeout` 时，前端必须展示部分结果；若有 `provider_thread_id / provider_session_id`，提供继续原 provider thread 入口，否则只提供手动移交入口，不能把它压成普通失败
- `task_action`
  - `action`：机器可判定的控制动作键
  - `action_label`：面向消息层的稳定可读标签，例如 `已暂停 / 已恢复执行 / 已继续推进`
- `task_state`
  - `previous_state / current_state`
  - `task_status / control_node`
  - `reason?`

也就是说，`GET /api/v1/sessions/{id}/messages` 现在已经足够支持 `/dialogue/` 直接把消息渲染成 thread-style lifecycle reply，而不需要额外回查一轮 `live_flow` 才知道当前状态或下一步。

`GET /api/v1/tasks/{id}/tool_trace` 当前返回的是已经真实落库的 `ToolInvocationRecord[]`，字段至少包括：

- `id`
- `session_id`
- `task_id`
- `worker_id`
- `execution_id`
- `tool_name`
- `arguments`
- `result_summary`
- `status`
- `success`
- `elapsed_ms`
- `touched_paths`
- `created_at`
- `metadata`

从 hardness phase-1 方案视角看，这意味着：

- `ToolInvocationRecord` 已经不是 blueprint，而是现有 API 和持久化对象
- `execution_id / status / touched_paths` 已经是稳定 HTTP 字段、SQLite 字段和 runtime fact 输入，不应在后续重构里退化成只存在于 `metadata` 的弱字段
- `RuntimeFactSetAssembler` 已使用这些字段推导 execution boundary，experiment evidence 会记录 `tool_execution_ids` 与 `tool_trace_path`
- `/dialogue/` 与 `/console/` 的 details tool trace 摘要已优先显示 `status / execution_id / touched_paths`，再补充 `result_summary`
- 后续不需要重新设计另一套 tool trace 对象；继续沿 `ToolInvocationRecord` 扩展消费面即可

`GET /api/v1/tasks/{id}/harness_trace` 返回面向 AHE / Harness 演进复盘的压缩视图，聚合 live flow、tool trace、judgment trace、agent run 相关信息。当前稳定字段至少包含：

- `task_id`
- `task_status`
- `control_node`
- `assigned_worker`
- `execution_status`
- `evidence_refs`
- `unfinished_items`
- `recommended_action`
- `recommended_next_step`
- `route_preview`
- `experiment_run`
- `agent_run`
- `execution_judgment`
- `completion_judgment`
- `tool_invocations`
- `agent_run_events`
- `agent_artifacts`
- `harness_metadata`

其中 `recommended_action` / `recommended_next_step` 对齐 `JudgmentTraceView` 的推荐动作命名。`harness_metadata` 会合并 experiment run 的轻量元数据，并补充 `tool_invocation_count / agent_run_event_count / agent_artifact_count`。若轻量运行环境未注入 `runtime_context_builder`，judgment 字段可为空，但接口仍应返回 200 与可用的 trace 数据。

`GET /api/v1/tasks/{id}/tool_trace` 当前直接返回最近的 `tool_invocations`，每条记录至少包含：

- `tool_name`
- `execution_id`
- `status`
- `success`
- `touched_paths`
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
- 当前只要声明了任意 `tool_capabilities`，就必须同时提供至少一个 `tool_scope`

当前服务端接受的内置 `tool_capabilities` 名称包括：

- 文件类：`list_files`、`read_file`、`search_text`、`write_file`、`patch_file`
- 命令类：`git`、`shell`、`powershell`、`cmd`

这些工具的当前约束边界是：

- `tool_scope` 仍然是所有文件类工具的根目录边界
- `write_file` 适合整文件写入，`patch_file` 适合基于 `old_text/new_text` 的锚定式局部改写
- `git` 仅允许受控只读子命令
- `git/shell/powershell/cmd` 都会先做宿主机真实可执行性探测；若不可用，服务端不会接受对应 capability 注册，内置 `codex` worker 也不会默认宣称该能力
- `shell`、`powershell`、`cmd` 会同时受工作目录约束、超时、输出长度上限与危险命令片段拦截
- `powershell/cmd` 仍然只在 Windows 宿主可注册；即使是 Windows，也还要求对应可执行文件真实存在
- 这批命令工具属于“受控本地命令”，不是强沙箱；当前定位仍然是本地或受控环境 harness
- `GET /api/v1/workers/{id}/readiness` 的 `checks` 现在同时包含依赖项、`tool:<name>` 形式的宿主工具检查，以及 provider-backed worker 的 `provider:<id>` / `executor_backend:<backend>` 检查；若某项命令工具不可用或当前 harness 没接入对应 provider executor，`reason` 会直接返回稳定原因文案
- `GET /api/v1/workers/{id}/readiness?mode=dispatch` 会执行分发前 readiness。该模式会在 passive readiness 通过后追加 `checks.dispatch_preflight`，并返回 `mode=dispatch`、`dispatch_preflight_ready`、`dispatch_preflight_reason`、`dispatch_preflight_cached`、`dispatch_preflight_mode`、`dispatch_preflight_active_probe`、`dispatch_preflight_metadata`。它用于真正分发任务前确认 worker/provider 当前可接受新轮次；默认 `/readiness` 仍是 `mode=passive`，不主动启动 provider。`dispatch_preflight_active_probe=false` 只表示本次结果不是主动测试轮次，是否阻断仍以 `ready` 和严格模式配置为准。`dispatch_preflight_metadata` 当前用于回传被探测的本地 CLI 命令形态，例如 `launch_target`、`launch_mode`、`dispatch_preflight_probe_kind`、`dispatch_preflight_probe_args`、`dispatch_preflight_command_shape`、`dispatch_preflight_exit_code`。`mode` 只接受 `passive` 或 `dispatch`；未知取值返回 `400`，避免拼写错误被静默降级为 passive。
- `GET /api/v1/workers` 与 `POST /api/v1/workers` 返回的 `Worker.metadata.host_tool_availability` 会回填该 worker 已声明命令工具的宿主探测结果

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

`LearningMemory` 当前稳定字段包括：

- `memory_type`
- `state`
- `hint_key`
- `summary`
- `confidence_score`
- `reinforcement_count`
- `evidence`
- `metadata`

其中 `memory_type` 当前至少包括：

- `routing_preference`
- `context_retention_hint`
- `completion_pattern`
- `worker_heuristic`

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

`BaselineTaskCase` 当前不只是任务标题清单。每个内置 case 都会返回：

- `case_key`
- `title`
- `task_type`
- `task_length_bucket`
- `intent`
- `goal`
- `workspace_preconditions`
- `acceptance_criteria`
- `expected_artifacts`
- `recovery_policy`
- `metadata`

`POST /api/v1/experiment_matrix/runs` 创建 task 时，会把 case 合同同步写入 task metadata：

- `baseline_workspace_preconditions`
- `baseline_acceptance_criteria`
- `baseline_expected_artifacts`
- `baseline_recovery_policy`

`ExperimentMatrixSummary.mode_summaries[*]` 现在额外聚合以下 tool 观测字段：

- `runs_with_tool_chain_data`
- `average_tool_chain_step_count`
- `max_tool_chain_step_count`
- `tool_execution_mode_counts`
- `tool_chain_termination_reason_counts`

同一结构也会暴露 strong-to-small orchestration 的最小闭环证据，用于判断
`orchestrated` 是否真的形成“强规划 / 小执行 / 强验收”路径，而不只是普通单 worker 执行：

- `runs_with_strong_planner_evidence`
- `runs_with_small_executor_evidence`
- `runs_with_strong_evaluator_evidence`
- `runs_with_strong_small_strong_loop`
- `evaluator_model_tier_counts`

这些字段只统计已经进入 `experiment_run.metadata` 的运行时证据，不依赖人工备注：
`planner_worker/planner_model_tier` 来自 orchestrated planner 阶段，
`executor_worker/executor_model_tier` 来自 execution 阶段，
`evaluator_model_tier` 来自 completion judgment。若某一段证据缺失，对应计数不会被推断为成功。

同一结构当前也会补充 mounted-context rollout 对比字段，便于直接比较
`active_context_only / mounted_context_shadow / mounted_context_primary` 三种 prompt mode：

- `runs_with_prompt_mode_data`
- `prompt_mode_counts`
- `runs_with_mounted_context_rendered`
- `runs_with_mounted_render_used`
- `runs_with_mounted_context_injected`
- `mounted_context_rendered_rate`
- `mounted_render_used_rate`
- `mounted_context_injected_rate`
- `average_mounted_context_panel_count`
- `average_mounted_context_active_count`
- `average_mounted_context_evidence_count`
- `runs_with_mounted_context_budget_data`
- `runs_with_mounted_context_budget_truncated`
- `mounted_context_budget_truncated_rate`
- `average_mounted_context_rendered_object_count`
- `average_mounted_context_hidden_object_count`
- `average_mounted_context_rendered_selection_trace_count`
- `average_mounted_context_hidden_selection_trace_count`
- `runs_with_execution_judgment_prompt_mode_data`
- `execution_judgment_prompt_mode_counts`
- `runs_with_execution_judgment_mounted_context_rendered`
- `runs_with_execution_judgment_mounted_render_used`
- `runs_with_execution_judgment_mounted_context_injected`
- `execution_judgment_mounted_context_rendered_rate`
- `execution_judgment_mounted_render_used_rate`
- `execution_judgment_mounted_context_injected_rate`
- `average_execution_judgment_mounted_context_active_count`
- `average_execution_judgment_mounted_context_evidence_count`
- `runs_with_execution_judgment_mounted_context_budget_data`
- `runs_with_execution_judgment_mounted_context_budget_truncated`
- `execution_judgment_mounted_context_budget_truncated_rate`
- `average_execution_judgment_mounted_context_rendered_object_count`
- `average_execution_judgment_mounted_context_hidden_object_count`
- `average_execution_judgment_mounted_context_rendered_selection_trace_count`
- `average_execution_judgment_mounted_context_hidden_selection_trace_count`
- `runs_with_completion_judgment_prompt_mode_data`
- `completion_judgment_prompt_mode_counts`
- `runs_with_completion_judgment_mounted_context_rendered`
- `runs_with_completion_judgment_mounted_render_used`
- `runs_with_completion_judgment_mounted_context_injected`
- `completion_judgment_mounted_context_rendered_rate`
- `completion_judgment_mounted_render_used_rate`
- `completion_judgment_mounted_context_injected_rate`
- `average_completion_judgment_mounted_context_active_count`
- `average_completion_judgment_mounted_context_evidence_count`
- `runs_with_completion_judgment_mounted_context_budget_data`
- `runs_with_completion_judgment_mounted_context_budget_truncated`
- `completion_judgment_mounted_context_budget_truncated_rate`
- `average_completion_judgment_mounted_context_rendered_object_count`
- `average_completion_judgment_mounted_context_hidden_object_count`
- `average_completion_judgment_mounted_context_rendered_selection_trace_count`
- `average_completion_judgment_mounted_context_hidden_selection_trace_count`

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
