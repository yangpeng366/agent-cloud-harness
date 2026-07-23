# MULTI_ROUND_TASK_EXECUTION_RUNBOOK

## 1. 目的

本文档用于把以下两份方案文档真正落成可执行步骤：

- `PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
- `TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`

它解决的问题不是“做什么”或“先补什么测试”，而是：

- 怎样把一个任务真正投喂给 harness
- 每一轮执行后看哪些接口
- 怎样把测试、probe、live flow 和文档记录串成一次完整执行

本文档当前优先覆盖首批 4 个任务：

- `D01` Worker priority 覆盖不生效
- `D03` Chat Facade SSE 流式兼容
- `M01` baseline_matrix_v2 真实任务集
- `O03` Experiment matrix acceptance gate

## 2. 通用前置条件

### 2.1 启动 harness

推荐先按 `LIVE_FLOW_RUNBOOK.md` 启动一个独立实例，例如 `18080`：

```powershell
.\scripts\Build-WithJava21.ps1 -SkipTests -QuietMaven

.\scripts\Run-HarnessWithJava21.ps1 `
  -Port 18080 `
  -Background `
  -StdOutPath '.tmp\multi-round-runbook.out.log' `
  -StdErrPath '.tmp\multi-round-runbook.err.log'
```

### 2.2 健康检查

```powershell
Invoke-RestMethod -Uri 'http://localhost:18080/api/v1/health'
```

成功标准：

- `status = up`

### 2.3 通用观测接口

每次执行任务后，至少保留以下接口结果：

```powershell
Invoke-RestMethod -Uri "http://localhost:18080/api/v1/tasks/{taskId}"
Invoke-RestMethod -Uri "http://localhost:18080/api/v1/tasks/{taskId}/select_worker"
Invoke-RestMethod -Uri "http://localhost:18080/api/v1/tasks/{taskId}/live_flow"
Invoke-RestMethod -Uri "http://localhost:18080/api/v1/tasks/{taskId}/judgment_trace"
Invoke-RestMethod -Uri "http://localhost:18080/api/v1/tasks/{taskId}/experiment_run"
Invoke-RestMethod -Uri "http://localhost:18080/api/v1/tasks/{taskId}/harness_trace"
```

如果任务涉及恢复或工具链，再加：

```powershell
Invoke-RestMethod -Uri "http://localhost:18080/api/v1/tasks/{taskId}/packet"
Invoke-RestMethod -Uri "http://localhost:18080/api/v1/checkpoints/{taskId}"
Invoke-RestMethod -Uri "http://localhost:18080/api/v1/tasks/{taskId}/tool_trace?limit=10"
```

## 3. 通用任务投喂模板

建议所有任务先用 `auto_start=false`，先看 `/select_worker`，再手动 `continue`。

### 3.1 通用创建模板

```powershell
$body = @{
  title = '<task title>'
  task_type = 'coding'
  source = 'eval'
  priority = 'high'
  auto_start = $false
  intent = '<task intent>'
  goal = '<task goal>'
  metadata = @{
    task_pack = 'project_evolution_v1'
    task_case_key = '<task case key>'
    task_family = '<debug|modify|optimize>'
    task_length_bucket = '<short|medium|long>'
    model_mode = 'orchestrated'
    acceptance_gate = '<gate type>'
  }
} | ConvertTo-Json -Depth 8

$created = Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:18080/api/v1/tasks' `
  -ContentType 'application/json' `
  -Body $body

$taskId = $created.data.id
```

### 3.2 通用执行模板

```powershell
Invoke-RestMethod -Uri "http://localhost:18080/api/v1/tasks/$taskId/select_worker"

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:18080/api/v1/tasks/$taskId/continue"
```

### 3.3 通用记录模板

每次执行完建议马上写一份最小记录：

```text
task_id:
task_case_key:
model_mode:
selected_worker:
route_source:
round_count:
resume_count:
handoff_count:
human_gate_count:
tests_run:
probes_run:
acceptance_result:
failure_reason:
live_flow_path:
judgment_trace_path:
experiment_run_path:
next_action:
```

若要把一次执行正式沉淀到 `docs/`，建议直接基于 `MULTI_ROUND_TASK_EXECUTION_RECORD_TEMPLATE.md` 生成记录。

## 4. D01 执行步骤

## 4.1 任务输入

```powershell
$body = @{
  title = 'D01 调试 Worker priority 覆盖不生效'
  task_type = 'coding'
  source = 'eval'
  priority = 'high'
  auto_start = $false
  intent = '复现并修复 workers.yml selection_priority 覆盖不生效的问题'
  goal = '补一个稳定回归测试，并让 WorkerRegistry 注册后的 worker metadata 保留覆盖结果'
  metadata = @{
    task_pack = 'project_evolution_v1'
    task_case_key = 'D01'
    task_family = 'debug'
    task_length_bucket = 'short'
    model_mode = 'orchestrated'
    acceptance_gate = 'regression_test'
  }
} | ConvertTo-Json -Depth 8

$created = Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:18080/api/v1/tasks' `
  -ContentType 'application/json' `
  -Body $body

$taskId = $created.data.id
```

## 4.2 多轮执行

### Round 1

- 看 `/select_worker`
- 看 `/live_flow`
- 确认任务是否指向 `WorkerRegistry` / `WorkerRouter`

### Round 2

- 明确要求产出失败测试
- focused 目标：
  - `WorkerRegistryPriorityOverrideTest`
  - `WorkerRouterRouteTraceTest`
  - 如需覆盖动态 provider 注册面，再补 `WorkerRegistryDynamicProviderTest`

建议验证命令：

```powershell
.\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=WorkerRegistryPriorityOverrideTest,WorkerRegistryDynamicProviderTest,WorkerRouterRouteTraceTest"
```

### Round 3

- 修最小代码路径
- 不顺带做 provider discovery 其它改造

### Round 4

- 重新跑 focused tests
- 再看 `/live_flow` 是否留下 worker / route 证据

## 4.3 完成标准

- 至少一个 focused test 新增或变更
- `selection_priority` 覆盖结果可断言
- metadata 包含 `selection_priority_original` 与 `selection_priority_overridden`

## 5. D03 执行步骤

## 5.1 任务输入

```powershell
$body = @{
  title = 'D03 调试 Chat Facade SSE 流式兼容'
  task_type = 'coding'
  source = 'eval'
  priority = 'high'
  auto_start = $false
  intent = '验证并加固 chat completions 和 responses 的 SSE 输出契约'
  goal = '补 SSE contract 回归测试，确保 stream=true 时输出序列与 DONE 收尾稳定'
  metadata = @{
    task_pack = 'project_evolution_v1'
    task_case_key = 'D03'
    task_family = 'debug'
    task_length_bucket = 'short'
    model_mode = 'orchestrated'
    acceptance_gate = 'http_contract'
  }
} | ConvertTo-Json -Depth 8
```

## 5.2 多轮执行

### Round 1

- 阅读 `ChatFacadeHandler`
- 阅读 `ChatFacadeHandlerHttpTest`

### Round 2

- 先补 chat completions stream 断言
- 再补 responses stream 顺序断言

focused 测试：

```powershell
.\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=ChatFacadeHandlerHttpTest"
```

### Round 3

- 跑 facade acceptance probe

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-ChatFacadeAcceptanceProbe.ps1 `
  -BaseUrl http://localhost:18080
```

### Round 4

- 如前端 surface 也受影响，再跑浏览器 probe

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-DialogueBrowserAcceptanceProbe.ps1 `
  -BaseUrl http://localhost:18080 `
  -Surface both
```

## 5.3 完成标准

- `ChatFacadeHandlerHttpTest` 通过
- facade acceptance probe 成功
- 如涉及 UI，browser probe 成功

## 6. M01 执行步骤

## 6.1 任务输入

```powershell
$body = @{
  title = 'M01 建立 baseline_matrix_v2 真实任务集'
  task_type = 'coding'
  source = 'eval'
  priority = 'high'
  auto_start = $false
  intent = '把 D01 D03 M02 O03 收束成 baseline_matrix_v2 候选 case'
  goal = '在保留 baseline_matrix_v1 的同时，为 v2 增加真实仓库任务 case 和 metadata 合同'
  metadata = @{
    task_pack = 'project_evolution_v1'
    task_case_key = 'M01'
    task_family = 'modify'
    task_length_bucket = 'medium'
    model_mode = 'orchestrated'
    acceptance_gate = 'matrix_contract'
  }
} | ConvertTo-Json -Depth 8
```

## 6.2 多轮执行

### Round 1

- 明确 v2 先只纳入首批 4 个任务
- 不在第一轮扩成完整大 catalog

### Round 2

- 先补 `ExperimentMatrixServiceTest` 失败断言
- 再改 `ExperimentMatrixService`

focused 测试：

```powershell
.\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=ExperimentMatrixServiceTest"
```

### Round 3

- 跑 matrix gate probe

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-BaselineMatrixGateProbe.ps1 `
  -BaseUrl http://localhost:18080 `
  -ReportPath .tmp\baseline-v2-gate.json
```

注意：显式传 `-ExperimentName` 必须使用未复用名称；probe 现在会在创建 run 前检查同名 experiment 是否已有 run，若非 0 会直接失败，而不是把 `summary_total_runs` 累加成 18、27 这类假失败。

### Round 4

- 记录 report 路径
- 再看 `/api/v1/experiment_matrix/summary`，确认 `mode_gate_rollup` 里每个 mode 都有 `acceptance_gate_result_counts / artifact_quality_gate_status_counts / cost_gate_status_counts / runs_with_failure_reason`

## 6.3 完成标准

- `ExperimentMatrixServiceTest` 通过
- case metadata 合同完整
- gate probe 可运行并生成 report

## 7. O03 执行步骤

## 7.1 任务输入

```powershell
$body = @{
  title = 'O03 优化 Experiment matrix acceptance gate'
  task_type = 'coding'
  source = 'eval'
  priority = 'high'
  auto_start = $false
  intent = '让 experiment matrix 不只创建 run，还能给出 acceptance_result'
  goal = '增加 accepted rejected needs_followup 的最小自动判定和汇总证据'
  metadata = @{
    task_pack = 'project_evolution_v1'
    task_case_key = 'O03'
    task_family = 'optimize'
    task_length_bucket = 'long'
    model_mode = 'orchestrated'
    acceptance_gate = 'quality_and_cost'
  }
} | ConvertTo-Json -Depth 8
```

## 7.2 多轮执行

### Round 1

- 先只定义最小 acceptance 规则
- 不一次做完整自动评审系统

### Round 2

- focused 测试：
  - `ExperimentMatrixServiceTest`
  - `ExperimentRunServiceTest`

```powershell
.\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=ExperimentMatrixServiceTest,ExperimentRunServiceTest"
```

### Round 3

- 重新跑 baseline matrix gate probe
- 查看 summary 中：
  - `accepted`
  - `rejected`
  - `needs_followup`

如果要把 O03 从 HTTP gate 继续推进到真实 worker smoke，追加执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Port 18082 -Background -AutoStop
powershell -ExecutionPolicy Bypass -File .\scripts\Run-BaselineMatrixRealWorkerSmoke.ps1 `
  -BaseUrl "http://localhost:18082" `
  -ReportPath ".tmp\baseline-matrix-real-worker-smoke-20260721.json" `
  -CaseKeys @('short-001') `
  -TaskPollTimeoutSec 180 `
  -MinimumTerminalRuns 1 `
  -MinimumEvaluatedRuns 1
```

### Round 4

- 如果 acceptance 依赖长任务终态，再补：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-CodexPartialTimeoutSmoke.ps1
```

## 7.3 完成标准

- summary 可统计 acceptance 结果
- 失败原因可在 metadata 或 summary 中读到
- 至少一个真实 case 可用于 acceptance gate 演示
- 至少一份 real worker smoke report 可回看，并能读出 selected worker、终态和 failure reason

## 8. 恢复与连续性验证

如果在 D01 / D03 / M01 / O03 任一任务中需要验证恢复链，可插入：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-TaskRecoveryAcceptanceProbe.ps1 `
  -BaseUrl http://localhost:18080 `
  -ReportPath .tmp\task-recovery-acceptance-probe.json `
  -IncludeResumeExecution
```

该 probe 适合验证：

- recoverable 判定
- auto handoff
- provider auth failed 的阻断
- fresh-session async resume

## 9. 何时 pause / handoff / human gate

### 9.1 pause

以下情况建议 pause：

- 已经拿到失败证据，但修复方案还没收口
- 需要验证 packet / checkpoint 是否足够恢复
- 希望同一任务链跨轮保留 route / judgment 上下文

### 9.2 handoff

以下情况建议 handoff：

- 任务从规划转到局部执行
- 任务需要 Windows 本地探针或 browser probe
- 要主动验证跨 worker 连续性

### 9.3 human gate

以下情况不要继续自动推进：

- 变更范围超出预期
- contract 不清晰，测试无法定义通过条件
- 同类失败重复出现，暂时无法区分设计问题和实现问题

## 10. 建议沉淀的执行证据

每次 run 结束后，建议把以下内容落盘到文档或 `.tmp` 报告：

- 创建任务的原始 JSON
- `task_id`
- focused tests 命令
- probe 命令
- probe report 路径
- `/live_flow`、`/judgment_trace`、`/experiment_run` 关键字段
- 是否发生 `pause` / `handoff` / `human gate`
- 下一轮要做什么

正式记录建议命名为：

- `MULTI_ROUND_TASK_EXECUTION_RECORD_YYYY-MM-DD.md`
- 或 `TASK_<id>_MULTI_ROUND_EXECUTION_RECORD_YYYY-MM-DD.md`

## 11. 结论

当前关于“测试驱动项目，设计合理场景多轮任务和调试计划”的文档层已经拆成三层：

1. `PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
   - 定义任务集合
2. `TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
   - 定义测试驱动和调试策略
3. `MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`
   - 定义如何实际执行

这样后续不管是人工执行，还是让 harness 自己跑这些任务，都有统一的输入、步骤、观测面和验收模板。
