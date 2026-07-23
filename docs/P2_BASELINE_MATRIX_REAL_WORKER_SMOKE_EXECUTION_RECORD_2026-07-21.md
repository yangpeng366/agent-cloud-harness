# P2 Baseline Matrix Real Worker Smoke Execution Record 2026-07-21

## 1. 用途

本文档记录 2026-07-21 对 `baseline_matrix_v1` 做的第一次 provider-backed real worker smoke。重点不是再验证 HTTP summary contract，而是确认：

- `Run-BaselineMatrixRealWorkerSmoke.ps1` 已能驱动真实 worker 创建、选路、继续执行、等待终态并回收证据
- `short-001` 在 `strong_only / small_only / orchestrated` 三种 mode 下都能产出 `experiment_run` 与 trace 证据
- 当前 P2 的主要缺口已经从“没有真实 worker 证据”收敛为“已有真实 smoke，但三种 mode 都因初始化超时停在 `waiting_human / human_gate`”

## 2. 基本信息

- 日期：2026-07-21
- 执行人：Codex
- 主题：P2 baseline experiment matrix
- smoke 类型：`baseline_matrix_v1` provider-backed real worker smoke
- 基线地址：`http://localhost:18082`
- experiment_name：`baseline-real-worker-20260721-103314`
- case_keys：`short-001`
- modes：`strong_only`、`small_only`、`orchestrated`
- report 路径：`.tmp\baseline-matrix-real-worker-smoke-20260721.json`
- 相关文档：
  - `CURRENT_CAPABILITY_GAP_ASSESSMENT.md`
  - `NEXT_5_ENGINEERING_PRIORITIES.md`
  - `TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
  - `MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`
  - `O03_ACCEPTANCE_GATE_HTTP_EXECUTION_RECORD_2026-07-21.md`

## 3. 任务目标

### 3.1 目标

```text
把 O03 HTTP gate 之后的下一步真正跑起来：
1. 用真实 worker 创建并执行 baseline_matrix_v1 run。
2. 确认至少存在 terminal run 与 evaluated run，而不是只停留在未启动的 summary 占位态。
3. 为每个 mode 留下 task / route / readiness / experiment_run / trace 证据，判断当前瓶颈到底在 contract 还是在真实执行。
```

### 3.2 预期验收

```text
1. report 通过 health / created_run_count / summary_total_runs / minimum_terminal_runs / minimum_evaluated_runs 检查。
2. 每个 task_report 都能看到 select_worker、dispatch readiness、continue、terminal_wait、experiment_run。
3. 至少能明确回答每个 mode 的 selected_worker、终态、acceptance_result、failure_reason、trace 是否存在。
4. 若失败，也要能把失败收敛成下一轮可执行动作，而不是继续停留在“缺真实 evidence”。
```

## 4. 执行过程

### Round 1

- 发现：真实 worker smoke 先做了 passive readiness 快照，至少 `codex / codex-openai / codex-xfyun / codex-deepseek` 为 `ready=true`，说明当前环境具备跑 provider-backed baseline 的最小前提。
- 使用的脚本：`scripts/Run-BaselineMatrixRealWorkerSmoke.ps1`
- 证据：report 顶层 `coding_worker_passive_readiness`
- 结论：本轮问题不再是“没有任何 dispatch-ready coding worker”。

### Round 2

- 发现：`POST /api/v1/experiment_matrix/runs` 成功创建 3 个 run，`summary_total_runs=3`，说明 `short-001 x 3 mode` 的 real worker smoke 创建链已经可跑。
- 使用的脚本：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Port 18082 -Background -AutoStop`
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-BaselineMatrixRealWorkerSmoke.ps1 -BaseUrl "http://localhost:18082" -ReportPath ".tmp\baseline-matrix-real-worker-smoke-20260721.json" -CaseKeys @('short-001') -TaskPollTimeoutSec 180 -MinimumTerminalRuns 1 -MinimumEvaluatedRuns 1`
- 证据：report 顶层 `created_run_count=3`、`summary_total_runs=3`
- 结论：P2 已不再缺少真实 worker run 创建证据。

### Round 3

- 发现：三个 task 的 `POST /continue` 都返回 `The request was aborted: The operation has timed out.`，但后续 `terminal_wait.timed_out=false`，说明控制调用超时后，任务仍继续推进到了可观测终态。
- 使用的接口：`/tasks/{id}/continue` + `/tasks/{id}` 轮询
- 证据：每个 `task_report` 的 `continue_result.error` 与 `terminal_wait`
- 结论：当前最值得排查的是 worker initialize 阶段的超时，而不是 task 没有启动。

### Round 4

- 发现：三个 mode 最终都进入 `waiting_human / human_gate`，`acceptance_result=rejected`，共同失败原因为 `Worker round failed and automatic recovery budget is exhausted.`，unfinished item 一致为 `initialize: timed out waiting for response`。
- 使用的接口：`/tasks/{id}/experiment_run`、`/tasks/{id}/live_flow`、`/tasks/{id}/judgment_trace`、`/tasks/{id}/tool_trace`、`/tasks/{id}/harness_trace`
- 证据：每个 `task_report.experiment_run` 与 metadata
- 结论：已经拿到了真实 worker 失败样本，但还没有 accepted / completed 的成功样本。

### Round 5

- 发现：`.tmp\provider-runs\codex\<task_id>\run-*\events.jsonl` 里，9 次 provider run 都先收到 harness 的 JSON-RPC `initialize`，随后 Codex CLI 返回 `error: unexpected argument '--no-alt-screen' found`、`Usage: codex app-server [OPTIONS] [COMMAND]`，没有返回 initialize response。
- 根因：`CodexAppServerWorkerExecutor.buildPlan(...)` 对 app-server 启动命令仍追加 `--no-alt-screen`，而本机 Codex CLI `0.144.4` 的 `app-server` 不接受该参数；harness 因此把启动参数错误表现成 30s initialize timeout。
- 修复：app-server plan 改为 `codex app-server --listen stdio://`，exec-json plan 仍保留 `codex exec --no-alt-screen --json ...`；`WorkerRegistry.command_shape`、HTTP worker metadata 断言和 provider 参数文档同步更新。
- 回归：`powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=CodexAppServerWorkerExecutorTest,AgentProviderSupportTest,ApiErrorContractHttpTest"` 已通过。
- 结论：`initialize: timed out waiting for response` 已定位并完成最小兼容修复；还需要重新构建运行时产物后复跑 real worker smoke，确认不再卡在 app-server initialize。

## 5. 探针与命令

### 5.1 Harness

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Port 18082 -Background -AutoStop
```

### 5.2 Real worker smoke

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-BaselineMatrixRealWorkerSmoke.ps1 `
  -BaseUrl "http://localhost:18082" `
  -ReportPath ".tmp\baseline-matrix-real-worker-smoke-20260721.json" `
  -CaseKeys @('short-001') `
  -TaskPollTimeoutSec 180 `
  -MinimumTerminalRuns 1 `
  -MinimumEvaluatedRuns 1
```

### 5.3 顶层结果

```text
PASS: baseline-matrix-real-worker-smoke-20260721.json
health_up=True
created_expected_run_count=True
summary_expected_run_count=True
minimum_terminal_runs_met=True
minimum_evaluated_runs_met=True

### 5.4 focused regression

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=CodexAppServerWorkerExecutorTest,AgentProviderSupportTest,ApiErrorContractHttpTest"
```

结果：

```text
PASS
```
```

## 6. 观测证据

### 6.1 readiness 快照

- `codex|ready=True|reason=ready`
- `codex-openai|ready=True|reason=ready`
- `codex-xfyun|ready=True|reason=ready`
- `codex-deepseek|ready=True|reason=ready`
- `deepseek|ready=False|reason=binary not found: deepseek`
- `kimi|ready=False|reason=binary not found: kimi`
- `claude|ready=False|reason=binary not found: claude`

### 6.2 顶层汇总

- `experiment_name = baseline-real-worker-20260721-103314`
- `case_keys = ["short-001"]`
- `modes = ["strong_only", "small_only", "orchestrated"]`
- `created_run_count = 3`
- `summary_total_runs = 3`
- `terminal_run_count = 3`
- `evaluated_run_count = 3`

### 6.3 per-mode 结果

| mode | selected_worker | final_assigned_worker | final state | acceptance | total_cost | handoff/resume/human_gate | route_source | 备注 |
|---|---|---|---|---|---:|---|---|---|
| `strong_only` | `codex` | `codex-openai` | `waiting_human / human_gate` | `rejected` | `3` | `1 / 0 / 0` | `capability_match` | `initialize: timed out waiting for response` |
| `small_only` | `codex-openai` | `codex-xfyun` | `waiting_human / human_gate` | `rejected` | `3` | `1 / 0 / 0` | `capability_match` | `initialize: timed out waiting for response` |
| `orchestrated` | `codex-xfyun` | `codex-deepseek` | `waiting_human / human_gate` | `rejected` | `3` | `1 / 0 / 0` | `capability_match` | `initialize: timed out waiting for response` |

### 6.4 orchestrated 特有证据

- `orchestration_stage = plan_pending`
- `orchestration_closed_loop_observed = false`
- `orchestration_proof_summary = unknown_planner -> codex-deepseek -> strong_evaluator(strong) [partial_loop]`
- 说明：orchestrated mode 已能留下 planner/executor/evaluator 侧的部分闭环证据，但还没有形成完整 strong-small-strong 成功闭环。

### 6.5 trace 覆盖面

三个 task_report 都满足：

- `live_flow_observed = true`
- `judgment_trace_observed = true`
- `tool_trace_observed = true`
- `harness_trace_observed = true`

这意味着当前失败并不是“没有证据”，而是“证据已经足够说明真实执行在 initialize 阶段超时”。

## 7. 验收结果

- `acceptance_result`：partial
- `positive_result`：仓库已拿到第一份 baseline matrix provider-backed real worker smoke 证据，不再处于“只有 HTTP gate / 未启动 run”的状态
- `remaining_gap`：三个 mode 都未完成；当前仍缺 accepted/completed 样本、`medium-001 / long-001` 冒烟结果与完整 3+3+3 release gate
- `next_action`：重新构建 harness 产物后复跑 `Run-BaselineMatrixRealWorkerSmoke.ps1`，确认 `short-001` 不再因 app-server 启动参数停在 initialize；随后扩到 `medium-001 / long-001` 与完整 3+3+3。

## 8. 结论

```text
截至 2026-07-21，P2 已经不再缺“真实 worker baseline 证据”。
现在真正缺的是：重建后复跑 `short-001`，确认 app-server 参数兼容修复已把初始化超时从 P2 baseline 中移除，再把这套 smoke 扩成完整 3+3+3 的 release gate。
```
