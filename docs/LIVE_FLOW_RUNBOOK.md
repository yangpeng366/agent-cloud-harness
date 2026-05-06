# Live Flow Runbook

## 1. 目标

本 runbook 用于在本机直接启动 `agent-cloud-harness`，并跑通一次带真实 LLM 调用的最小闭环，验证以下链路是否可用：

- 服务启动与健康检查
- 任务创建与控制图流转
- worker execution
- execution/completion judgment
- runtime context / active context 可观测性
- checkpoint / learning memory 回流
- `strong_only / small_only / orchestrated` 三种模式是否可比较
- `orchestrated` 是否能留下 `强规划 -> 小执行 -> 强评估` 的最小证据包

## 2. 前置条件

- JDK 21 可用
- Maven 可用
- OpenAI-compatible 环境变量已设置：
  - `OPENAI_API_KEY`
  - `OPENAI_BASE_URL`
  - `OPENAI_MODEL`
  - `OPENAI_REVIEW_MODEL`（建议指向更强的评估模型，用于 completion / execution judgment）

建议使用独立端口，避免影响已有本地服务：

- `server.port=18080`

## 3. 执行步骤

### Step 1: 用 JDK 21 打包

优先使用仓库脚本：

```powershell
.\scripts\Build-WithJava21.ps1 -SkipTests -QuietMaven
```

手工方式仍然可用：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.9+10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -DskipTests package
```

验证点：

- `target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar` 存在

### Step 2: 设置运行环境变量

```powershell
$env:OPENAI_API_KEY='<your-api-key>'
$env:OPENAI_BASE_URL='<your-base-url>'
$env:OPENAI_MODEL='<your-model>'
```

验证点：

- `OPENAI_API_KEY` 非空
- `OPENAI_BASE_URL` 指向兼容 `/chat/completions` 的服务

### Step 3: 启动服务

优先使用仓库脚本：

```powershell
.\scripts\Run-HarnessWithJava21.ps1 `
  -Port 18080 `
  -Background `
  -StdOutPath '.tmp\live-flow.out.log' `
  -StdErrPath '.tmp\live-flow.err.log'
```

手工方式仍然可用：

```powershell
New-Item -ItemType Directory -Force -Path .tmp | Out-Null
Start-Process -FilePath 'java' `
  -ArgumentList '--enable-preview','-Dserver.port=18080','-jar','target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar' `
  -WorkingDirectory (Get-Location) `
  -RedirectStandardOutput '.tmp\live-flow.out.log' `
  -RedirectStandardError '.tmp\live-flow.err.log' `
  -PassThru
```

记录：

  - `pid`

### Step 4: 健康检查

```powershell
Invoke-RestMethod -Uri 'http://localhost:18080/api/v1/health'
```

验证点：

- HTTP 200
- 返回 `status=up`

### Step 5: 创建验证任务

目标任务建议满足以下约束，便于形成近端 proof：

- 只改 1 到 3 个文件
- 验收口径清晰，最好能由测试或字段检查闭环
- 不依赖大范围重构或长链人工决策
- 优先选择“补一段 runbook / 补一段 trace metadata / 加一个 focused test”这类小真实任务

为了保留 planner 路由证据，建议本次验证显式使用 `auto_start=false`，先看 `/select_worker`，再手动 `continue`。

```powershell
$body = @{
  title = 'phase-2 live validation'
  task_type = 'coding'
  source = 'user'
  priority = 'high'
  auto_start = $false
  intent = '继续完善项目与方案文档，重点补齐 phase-2 runtime explainability 与方案收敛'
  metadata = @{
    model_mode = 'orchestrated'
    task_length_bucket = 'small_real'
    proof_story = 'near_term_closed_loop'
  }
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:18080/api/v1/tasks' `
  -ContentType 'application/json' `
  -Body $body
```

记录：

- `task_id`
- `session_id`
- `status`
- `control_node`

### Step 6: 记录 planner 路由，再触发执行

```powershell
Invoke-RestMethod -Uri 'http://localhost:18080/api/v1/tasks/{taskId}/select_worker'

Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:18080/api/v1/tasks/{taskId}/continue'
```

验证点：

- `select_worker` 在 `orchestrated + plan_pending` 下优先返回强模型 tier
- 继续执行后，任务进入真实 `route -> execute -> judge` 流程

### Step 7: 拉取关键观测面

```powershell
Invoke-RestMethod -Uri 'http://localhost:18080/api/v1/tasks/{taskId}'
Invoke-RestMethod -Uri 'http://localhost:18080/api/v1/tasks/{taskId}/live_flow'
Invoke-RestMethod -Uri 'http://localhost:18080/api/v1/tasks/{taskId}/provider_selection'
Invoke-RestMethod -Uri 'http://localhost:18080/api/v1/tasks/{taskId}/agent_run'
Invoke-RestMethod -Uri 'http://localhost:18080/api/v1/tasks/{taskId}/runtime_context'
Invoke-RestMethod -Uri 'http://localhost:18080/api/v1/tasks/{taskId}/judgment_trace'
Invoke-RestMethod -Uri 'http://localhost:18080/api/v1/tasks/{taskId}/experiment_run'
Invoke-RestMethod -Uri 'http://localhost:18080/api/v1/tasks/{taskId}/harness_trace'
Invoke-RestMethod -Uri 'http://localhost:18080/api/v1/checkpoints/{taskId}'
Invoke-RestMethod -Uri 'http://localhost:18080/api/v1/learning_memories/{taskId}'
```

验证点：

- task 已分配 worker，且状态发生流转
- `live_flow` 能一次性返回 task / route / runtime / judgment / checkpoint / learning memory
- `provider_selection` / `agent_run` 能看到 provider、worker、tier、role 的投影
- `runtime_context.active_context` 非空
- `judgment_trace` 能看到 execution/completion judgment
- `experiment_run` / `harness_trace` 能看到可复盘的轻量摘要
- checkpoint 或 learning memories 至少开始有记录

### Step 8: 检查日志与异常

```powershell
Get-Content .tmp\live-flow.out.log -Tail 100
Get-Content .tmp\live-flow.err.log -Tail 100
```

重点确认：

- LLM 请求是否成功
- worker 输出是否进入结构化解析
- judgment 是否触发 `done / partially_done / misaligned / needs_clarification`

### Step 9: 结束进程

```powershell
Stop-Process -Id <pid>
```

## 4. 预期结果

一次成功的 live flow 至少应证明：

1. 当前代码不只是“静态可编译”，而是能在真实 LLM 环境下完成一轮任务流转。
2. `live_flow`、`runtime_context`、`judgment_trace`、`learning_memories` 这几类观测面能返回可读数据。
3. 对 `orchestrated` 任务，至少能看清本轮是否形成了 `planner / executor / evaluator` 三段证据。
4. phase-2 当前剩余问题更偏策略质量与提示词稳定性，而不是主链路缺失。

## 5. 近端 Proof 证据包

如果目标是支持“small real tasks 在强模型 / 小模型编排下闭环”的近端 proof，至少应保留以下证据：

- `/tasks/{id}/select_worker`
  - `selected_model_tier=strong`
  - 原因：证明 `plan_pending` 阶段由强模型规划
- `/tasks/{id}/provider_selection` 或 `live_flow.provider_selection`
  - `selected_worker_id`
  - `selected_model_tier`
  - `worker_role`
  - `metadata.route_source`
- `/tasks/{id}/agent_run`
  - 当前轮实际执行 provider / worker / tier / status
- `/tasks/{id}/judgment_trace`
  - 有 execution judgment 与 completion judgment
  - completion judgment 最好能看到 `evaluation_result`
- `/tasks/{id}/experiment_run`
  - `model_mode`
  - `handoff_count`
  - `total_steps`
  - `acceptance_result`
  - 若已补强 evaluator trace，还应能看到 planner / executor / evaluator 摘要
- `/tasks/{id}/harness_trace`
  - 能把上述信号压成一个可复盘视图
  - `unfinished_items` / `evidence_refs` / `recommended_action` 不为空时，应能解释任务为何继续、结束或等待

## 6. 三模式最小对比

若要给 near-term proof 增加说服力，建议对同一条小真实任务各跑一遍：

- `metadata.model_mode = strong_only`
- `metadata.model_mode = small_only`
- `metadata.model_mode = orchestrated`

保持 `intent`、`task_type`、任务粒度和验收标准一致，只变 `model_mode`。最小对比时重点看：

- `provider_selection / agent_run` 中的 tier 与 role 是否变化
- `experiment_run` 中的 `total_cost / handoff_count / acceptance_result`
- `harness_trace` 是否能还原 orchestrated 任务中的 planner -> executor -> evaluator 路径

## 7. 常见失败点

- `health` 正常但任务无输出：通常是 LLM 配置错误，或兼容接口不接受当前模型。
- task 创建成功但 judgment 空白：通常是 worker 输出为空，导致 execution/completion judgment 无法形成有效信号。
- `runtime_context` 有值但 `learning_memories` 为空：说明这一轮触发条件不足，或学习记忆尚未积累到可沉淀状态。
- 结构化解析失败：先看 `.tmp\live-flow.err.log` 中是否有 LLM 非 JSON 输出，再看 `WorkerExecutionResult` 的 fallback 是否生效。
