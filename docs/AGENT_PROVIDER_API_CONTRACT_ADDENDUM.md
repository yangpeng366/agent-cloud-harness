# API Contract 增补稿（Agent Provider / Agent Runs）

## 1. 文档目标

本文档用于补充 `agent-cloud-harness` 现有 API 契约，使系统在保留当前：
- task
- session
- worker
- packet
- checkpoint
- live flow

这些核心对象的同时，新增对：
- Agent Provider
- Agent Provider Status
- Agent Run
- Provider Selection Trace

的标准化暴露能力。

本文档默认作为以下文档的补充实现稿：
- `MULTICA_BENCHMARK_AND_BORROWING_PLAN.md`
- `AGENT_PROVIDER_TECHNICAL_DESIGN.md`
- `API_CONTRACTS.md`

---

## 2. 设计目标

### 2.1 不破坏现有 API 主体
新增 API 应与现有：
- `/api/v1/tasks`
- `/api/v1/sessions`
- `/api/v1/workers`

并存，而不是替代。

### 2.2 把 Provider 作为一等观测对象
新增 API 需要支持：
- 查看系统当前发现到哪些 Agent Provider
- 查看每个 Provider 是否安装、是否 ready、是否需要登录
- 查看每个 Provider 最近的执行 runs
- 查看某个 task 最终落到了哪个 Provider

### 2.3 machine-readable first
所有字段尽量结构化，避免把关键运行信息只放在 summary 文本中。

---

## 3. 新增 API 一览

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/v1/agents` | 列出所有 Agent Provider 及其状态 |
| GET | `/api/v1/agents/{id}` | 查看单个 Agent Provider 详情 |
| POST | `/api/v1/agents/{id}/refresh` | 刷新单个 Provider 状态 |
| GET | `/api/v1/agents/{id}/runs` | 查看单个 Provider 最近执行 runs |
| GET | `/api/v1/agent_runs/{runId}` | 查看单次 Agent Run 详情 |
| GET | `/api/v1/agent_runs/{runId}/events` | 查看单次 Run 的运行事件 |
| GET | `/api/v1/agent_runs/{runId}/artifacts` | 查看单次 Run 的产物 |
| GET | `/api/v1/tasks/{id}/provider_selection` | 查看 task 的 provider 选择结果 |
| GET | `/api/v1/tasks/{id}/agent_run` | 查看 task 关联的最新 agent run |

---

## 4. 领域对象契约

## 4.1 AgentProviderView

用于 `/api/v1/agents` 列表与 `/api/v1/agents/{id}` 详情。

```json
{
  "provider_id": "codex",
  "display_name": "Codex",
  "provider_type": "local_cli",
  "transport": "pty",
  "installed": true,
  "version": "0.9.1",
  "auth_status": "ok",
  "ready": true,
  "readiness_reason": null,
  "capabilities": ["chat", "code", "patch", "session"],
  "checked_at": "2026-04-29T10:00:00Z",
  "metadata": {
    "binary": "codex",
    "model_tier": "strong",
    "default_role": "executor"
  }
}
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `provider_id` | string | 稳定 provider 标识 |
| `display_name` | string | UI 展示名 |
| `provider_type` | string | `local_cli` / `embedded` / `remote_api` |
| `transport` | string | `pty` / `process` / `http` / `inproc` |
| `installed` | boolean | 本机是否已发现 |
| `version` | string\|null | 已探测版本 |
| `auth_status` | string | `ok` / `auth_needed` / `unknown` / `unsupported` |
| `ready` | boolean | 当前是否可接单 |
| `readiness_reason` | string\|null | 不可用原因 |
| `capabilities` | string[] | provider 支持能力 |
| `checked_at` | string | 最近一次探测时间 |
| `metadata` | object | 扩展字段 |

---

## 4.2 AgentRunView

用于 `/api/v1/agent_runs/{runId}` 和 `/api/v1/tasks/{id}/agent_run`。

```json
{
  "run_id": "arun_123",
  "task_id": "task_123",
  "session_id": "session_123",
  "provider_id": "codex",
  "provider_display_name": "Codex",
  "worker_role": "executor",
  "selected_worker_id": "worker_codex_executor",
  "status": "running",
  "started_at": "2026-04-29T10:01:00Z",
  "ended_at": null,
  "exit_code": null,
  "summary": "Executing task with codex provider",
  "output_preview": "Planned changes...",
  "metadata": {
    "model_tier": "strong",
    "working_directory": "D:\\gitAll\\agent-cloud-harness"
  }
}
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `run_id` | string | agent run id |
| `task_id` | string | 关联 task |
| `session_id` | string | 关联 session |
| `provider_id` | string | 执行来源 provider |
| `provider_display_name` | string | provider 展示名 |
| `worker_role` | string | `planner` / `executor` / `reviewer` 等 |
| `selected_worker_id` | string | control plane 侧选中的 worker id |
| `status` | string | `queued` / `starting` / `running` / `completed` / `failed` / `cancelled` |
| `started_at` | string | 启动时间 |
| `ended_at` | string\|null | 结束时间 |
| `exit_code` | number\|null | 进程退出码 |
| `summary` | string\|null | 简要摘要 |
| `output_preview` | string\|null | 输出预览 |
| `metadata` | object | 扩展字段 |

---

## 4.3 AgentRunEventView

用于 `/api/v1/agent_runs/{runId}/events`。

```json
[
  {
    "event_id": "arevt_1",
    "run_id": "arun_123",
    "event_type": "run.started",
    "created_at": "2026-04-29T10:01:00Z",
    "payload": {
      "provider_id": "codex"
    }
  },
  {
    "event_id": "arevt_2",
    "run_id": "arun_123",
    "event_type": "run.stdout",
    "created_at": "2026-04-29T10:01:03Z",
    "payload": {
      "text": "Planning file changes..."
    }
  }
]
```

建议事件类型：
- `run.started`
- `run.stdout`
- `run.stderr`
- `run.completed`
- `run.failed`
- `run.cancelled`
- `artifact.created`

---

## 4.4 AgentArtifactView

用于 `/api/v1/agent_runs/{runId}/artifacts`。

```json
[
  {
    "artifact_id": "aart_1",
    "run_id": "arun_123",
    "provider_id": "codex",
    "artifact_type": "diff",
    "title": "Main.java patch",
    "path": "D:\\gitAll\\agent-cloud-harness\\.tmp\\patch.diff",
    "summary": "Patch for provider integration",
    "created_at": "2026-04-29T10:03:00Z",
    "metadata": {}
  }
]
```

---

## 4.5 ProviderSelectionView

用于 `/api/v1/tasks/{id}/provider_selection`。

```json
{
  "task_id": "task_123",
  "selected_provider": "codex",
  "provider_display_name": "Codex",
  "provider_ready": true,
  "provider_auth_status": "ok",
  "provider_version": "0.9.1",
  "worker_role": "executor",
  "selected_worker_id": "worker_codex_executor",
  "selected_model_tier": "strong",
  "selection_reason": "selected by model tier preference (strong)",
  "fallback_reason": null,
  "candidate_providers": ["codex", "openclaw"],
  "metadata": {
    "route_source": "capability_match"
  }
}
```

这个对象是把现有 `RouteResult` 扩展到 provider 维度后的标准投影。

---

## 5. API 详细定义

## 5.1 GET `/api/v1/agents`

### 用途
列出当前所有已注册 Agent Provider 及其状态。

### 请求参数
无。

### 响应
`200 OK`

```json
[
  {
    "provider_id": "codex",
    "display_name": "Codex",
    "provider_type": "local_cli",
    "transport": "pty",
    "installed": true,
    "version": "0.9.1",
    "auth_status": "ok",
    "ready": true,
    "readiness_reason": null,
    "capabilities": ["chat", "code", "patch", "session"],
    "checked_at": "2026-04-29T10:00:00Z",
    "metadata": {}
  }
]
```

### 失败响应
- `500 internal error`

---

## 5.2 GET `/api/v1/agents/{id}`

### 用途
查看单个 provider 详情。

### 路径参数
- `id`: provider id

### 响应
`200 OK` -> `AgentProviderView`

### 失败响应
- `404 not found`
- `500 internal error`

---

## 5.3 POST `/api/v1/agents/{id}/refresh`

### 用途
立即刷新指定 provider 的 installed/version/auth/readiness 状态。

### 请求体
可空。

### 响应
`200 OK` -> `AgentProviderView`

### 失败响应
- `404 not found`
- `500 internal error`

---

## 5.4 POST `/api/v1/agents/{id}/preflight`

### 用途
主动执行指定 provider 的 dispatch preflight，用于调试本地 CLI / dynamic provider 的低副作用验活命令、参数兼容性和 failure classification。

### 请求体
可空。

### 响应
`200 OK` -> `AgentProviderView`

`metadata` 会包含 provider 返回的 dispatch preflight 诊断字段，例如：

- `dispatch_preflight_mode`
- `dispatch_preflight_probe_kind`
- `dispatch_preflight_probe_args`
- `dispatch_preflight_command_shape`
- `dispatch_preflight_exit_code`
- `provider_failure_class / provider_failure_reason / provider_retryable`

### 失败响应
- `404 not found`
- `500 internal error`

---

## 5.5 GET `/api/v1/agents/{id}/runs`

### 用途
查看某 provider 最近 runs。

### Query 参数
- `limit?`，默认 20
- `status?`，可选

### 响应
`200 OK`

```json
[
  {
    "run_id": "arun_123",
    "task_id": "task_123",
    "session_id": "session_123",
    "provider_id": "codex",
    "provider_display_name": "Codex",
    "worker_role": "executor",
    "selected_worker_id": "worker_codex_executor",
    "status": "completed",
    "started_at": "2026-04-29T10:01:00Z",
    "ended_at": "2026-04-29T10:05:00Z",
    "exit_code": 0,
    "summary": "Applied patch successfully",
    "output_preview": "Patch complete",
    "metadata": {}
  }
]
```

---

## 5.5 GET `/api/v1/agent_runs/{runId}`

### 用途
查看单次 provider run 详情。

### 响应
`200 OK` -> `AgentRunView`

### 失败响应
- `404 not found`

---

## 5.6 GET `/api/v1/agent_runs/{runId}/events`

### 用途
查看单次 run 的事件流。

### Query 参数
- `limit?` 默认 100

### 响应
`200 OK` -> `AgentRunEventView[]`

---

## 5.7 GET `/api/v1/agent_runs/{runId}/artifacts`

### 用途
查看单次 run 的工件。

### 响应
`200 OK` -> `AgentArtifactView[]`

---

## 5.8 GET `/api/v1/tasks/{id}/provider_selection`

### 用途
查看当前 task 的 provider 选择结果。

### 响应
`200 OK` -> `ProviderSelectionView`

### 字段来源建议
- 基础路由字段来自 `WorkerRouter.RouteResult`
- provider 状态字段来自 `AgentProviderRegistry`
- provider 解释字段来自 `AgentExecutionPlanner`

### 失败响应
- `404 not found`

---

## 5.9 GET `/api/v1/tasks/{id}/agent_run`

### 用途
查看当前 task 关联的最新 agent run。

### 响应
`200 OK` -> `AgentRunView`

### 失败响应
- `404 not found`
- 如果该 task 尚未走 provider-aware path，也可返回 `404 not found`

---

## 6. 对现有 API 的增补建议

## 6.1 增补 `GET /api/v1/tasks/{id}` 返回字段
建议在 task detail 顶层或 metadata 中补以下字段：
- `selected_provider?`
- `provider_run_id?`
- `worker_role?`
- `selected_model_tier?`

这样前端不用额外发很多请求，也能快速显示核心状态。

---

## 6.2 增补 `GET /api/v1/tasks/{id}/select_worker`
当前这个接口返回 `RouteResult`。

当前 worker readiness 已支持 `GET /api/v1/workers/{id}/readiness?mode=dispatch` 作为分发前验活入口。`mode` 只接受 `passive` 或 `dispatch`，未知取值返回 `400`，避免调用方拼写错误时绕过 provider preflight。

建议扩充如下字段：
- `selected_provider?`
- `provider_ready?`
- `provider_auth_status?`
- `provider_version?`
- `candidate_providers?`
- `provider_reason?`

如果暂时不改 `RouteResult` 本体，也可以先新增 `provider_selection` 接口，避免破坏现有客户端。

---

## 6.3 增补 `GET /api/v1/tasks/{id}/live_flow`
建议在现有聚合结构中新增：
- `provider_selection`
- `agent_run`
- `agent_run_events?`
- `agent_artifacts?`

这样 `live_flow` 仍保持“一站式聚合诊断面”定位。

---

## 7. 状态与错误约定

## 7.1 Provider 状态枚举

### `auth_status`
- `ok`
- `auth_needed`
- `unknown`
- `unsupported`

### `run_status`
- `queued`
- `starting`
- `running`
- `completed`
- `failed`
- `cancelled`

### `provider_type`
- `local_cli`
- `embedded`
- `remote_api`

### `transport`
- `pty`
- `process`
- `http`
- `inproc`

## 7.2 Discovery Probe Metadata

当 dynamic provider 未显式配置 `protocol`，但配置了 `binary` 或 `command` 时，服务会保守推断为 `native_cli_text`，并执行一次短超时 startup help/probe。该结果只用于诊断，不自动切换协议、不替代 dispatch readiness。

常见 metadata：
- `provider_protocol_inferred`: `true`
- `provider_protocol_probe_mode`: 当前为 `startup_help_probe`
- `provider_protocol_probe_command_shape`: 探测参数形态，不包含完整 binary 路径
- `provider_protocol_probe_exit_code`: 进程退出码，超时或启动失败为 `-1`
- `provider_protocol_probe_success`: 是否 exit code 为 `0`
- `provider_protocol_probe_suggested_parser`: `text` / `json` / `stream_json` / `unknown`
- `provider_protocol_probe_output_preview`: 第一条非空输出预览

---

## 7.3 错误返回
延续现有错误 envelope：

```json
{
  "success": false,
  "code": "404",
  "message": "not found"
}
```

对于 provider 相关接口，建议增加明确 message，例如：
- `provider not found`
- `agent run not found`
- `provider not installed`
- `provider auth required`

但 envelope 结构不变。

---

## 8. 与数据存储的关系

## 8.1 Phase 1
可以先采用：
- provider 列表来自内存 registry
- provider status 来自实时 detect
- agent run 来自内存 supervisor 或临时文件索引
- artifacts 通过现有 artifact 体系与 provider metadata 映射

## 8.2 Phase 2
建议新增：
- `agent_providers`
- `agent_runs`
- `agent_run_events`

这样 API 返回可稳定回放，不依赖内存状态。

---

## 9. 前端消费建议

## 9.1 Console
建议新增三个视图入口：
- Agent Inventory
- Provider Detail
- Agent Run Detail

当前 Provider Detail 已接入 operator preflight 入口：
- `运行 Preflight` 按钮调用 `POST /api/v1/agents/{id}/preflight`
- 页面会展示 `dispatch_preflight_mode`、probe args、command shape、exit code、output preview、failure class / retryable 等诊断
- 执行后同步刷新 Agent Inventory 与 worker dispatch readiness，便于判断本地 CLI 是否能接收新任务
- 对未显式配置 `protocol` 的 dynamic provider，Provider Detail 会把 startup discovery probe 单独渲染为 `startup protocol probe` 诊断块，避免只在 raw metadata 里查找 `provider_protocol_probe_*`

## 9.2 Task Detail
建议显示：
- selected provider
- provider status
- current run status
- latest provider artifact

## 9.3 Live Flow
建议把 provider 维度作为 diagnostics 的标准组成部分。

---

## 10. 第一阶段实现优先级

### Priority 1
- `GET /api/v1/agents`
- `GET /api/v1/agents/{id}`
- `POST /api/v1/agents/{id}/refresh`

### Priority 2
- `GET /api/v1/tasks/{id}/provider_selection`
- `GET /api/v1/tasks/{id}/agent_run`

### Priority 3
- `GET /api/v1/agent_runs/{runId}`
- `GET /api/v1/agent_runs/{runId}/artifacts`
- `GET /api/v1/agent_runs/{runId}/events`

---

## 11. 结论

本增补稿的核心目标是：

**让 Provider 成为一等观测对象，同时不破坏现有 task/session/worker/control-plane API 主骨架。**

这样 `agent-cloud-harness` 就能从：
- 面向 continuity 与 orchestration 的单机 harness

进一步长成：
- 具备多 Agent provider inventory、runtime status、run trace 能力的 managed agents control plane

这是当前仓库最自然的一步演进。
