# Live Flow Trace

## 2026-04-23

### 目标

- 在本机用真实 OpenAI-compatible 网关跑通 `task -> worker -> judgment -> checkpoint -> live_flow`
- 给 phase-2 runtime explainability 保留可复查的调试过程

### 已确认事实

- 服务可在 `18080` 端口正常启动
- `GET /api/v1/health` 正常
- `POST /api/v1/tasks` 已能完成单轮控制图，不再因 `packetNode()` / `handoffNode()` 递归回灌而长时间卡死
- `GET /api/v1/tasks/{id}/live_flow` 能聚合返回 task / route / runtime_context / judgment_trace / checkpoints / learning_memories

### 已确认兼容点

- 对 `https://w.ciykj.cn` 这类网关，实际可用的 chat 路径是 `/v1/chat/completions`
- 根路径 `/chat/completions` 可能返回 HTML 门户页，而不是 JSON
- 因此客户端不能假设 `OPENAI_BASE_URL` 一定已经带 `/v1`

### 已确认问题链

1. 早期版本中，`OpenAiCompatibleClient` 在第一个候选 URL 超时后会直接整次返回空串。
2. 文档类 worker round 比 judgment round 更容易超时，因为输出更长、耗时更高。
3. 当 worker round 空输出时，judgment 仍可能正常返回，因此系统会出现“有判断、无产物”的可观测状态。

### 本轮落地修复

- `OpenAiCompatibleClient`
  - 增加逐 URL、逐 attempt 的重试，而不是首个异常直接退出
  - 请求超时改为可配置：`OPENAI_TIMEOUT_SECONDS`
  - 重试次数改为可配置：`OPENAI_MAX_RETRIES`
  - 输出长度预算可配置：`OPENAI_MAX_TOKENS`
- `DefaultWorkerExecutor`
  - 收紧 worker 输出预算，优先要求紧凑、可执行结果，降低文档类任务首轮超时概率

### 当前建议运行参数

```powershell
$env:OPENAI_TIMEOUT_SECONDS='90'
$env:OPENAI_MAX_RETRIES='2'
$env:OPENAI_MAX_TOKENS='800'
```

### 下一步观察点

- worker round 是否仍以 `outputLength=0` 结束
- judgment 与 worker 是否都命中 `/v1/chat/completions`
- `recent_artifacts` 是否开始出现结构化输出
- `learning_memories` 是否从只记录 completion/context hint，逐步开始吸收真正的 worker heuristic

### 本次成功运行留痕

- 服务端口：`18080`
- 运行参数：
  - `OPENAI_BASE_URL=https://w.ciykj.cn`
  - `OPENAI_MODEL=gpt-5.4`
  - `OPENAI_TIMEOUT_SECONDS=90`
  - `OPENAI_MAX_RETRIES=2`
  - `OPENAI_MAX_TOKENS=800`
- 成功任务：
  - `task_id=task_c333e35a83df41d3`
  - `session_id=session_e16b9d45133b49c5`
  - `trace_label=live_flow_v3_retry_fix`
- 结果摘要：
  - `POST /api/v1/tasks` 在约 `42.9s` 内返回
  - `worker_round` 成功返回 `outputLength=405`
  - 生成了 `worker_artifact`
  - `completion_judgment=done`
  - 最终任务状态 `done`

### 运行中暴露的接口兼容问题

- `TaskCreateRequest` 之前不接受 `goal`
- 但 `Task` 模型与返回体实际都带 `goal`
- 因此已补齐创建接口的 `goal` 兼容，避免调用方按直觉传 `goal` 时直接触发 500

### `goal` 兼容回归结果

- 回归任务：
  - `task_id=task_e28c5ecf79cc4d71`
  - `session_id=session_578d6bdfad504185`
  - `trace_label=goal_compat_validation`
- 请求特征：
  - `POST /api/v1/tasks` 明确携带 `goal`
- 回归结果：
  - 请求成功返回 `200`
  - 任务在约 `30.8s` 内完成
  - 返回体中的 `goal` 与请求一致
  - 最终状态 `done`
