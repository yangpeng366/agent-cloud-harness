# O03 Acceptance Gate HTTP Execution Record 2026-07-21

## 1. 用途

本文档记录 O03 `Experiment matrix acceptance gate` 在 2026-07-21 的一次 HTTP gate 收口验证，重点不是再补 service 层字段，而是确认：

- `experiment_matrix/summary` 已通过 HTTP surface 暴露 O03 gate 字段
- `Run-BaselineMatrixGateProbe.ps1` 已把这些字段纳入脚本级 gate
- probe 对重复 `experiment_name` 会在创建 run 前直接失败，而不是把旧 run 结果静默累加

## 2. 基本信息

- 日期：2026-07-21
- 执行人：Codex
- 任务编号：O03
- 任务标题：Experiment matrix acceptance gate HTTP gate 收口
- 任务类型：optimize
- task_pack：project_evolution_v1
- task_case_key：O03
- task_family：optimize
- task_length_bucket：long
- model_mode：orchestrated
- acceptance_gate：quality_and_cost
- 相关文档：
  - `NEXT_5_ENGINEERING_PRIORITIES.md`
  - `CURRENT_CAPABILITY_GAP_ASSESSMENT.md`
  - `TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
  - `MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`

## 3. 任务目标

### 3.1 目标

```text
让 O03 不只停留在 ExperimentMatrixService / ExperimentRunService 的内部字段，而是形成：
1. HTTP summary contract
2. 脚本级 gate probe
3. dated execution evidence
```

### 3.2 预期验收

```text
1. HTTP summary test 能断言 acceptance / artifact quality / cost gate counts。
2. baseline matrix gate probe 会检查 mode 级 gate count map，并输出 mode_gate_rollup。
3. probe 使用重复 experiment_name 时，会在创建 run 前直接失败。
4. 有一份真实 probe report 可回看。
```

## 4. 执行过程

### Round 1

- 发现：`Run-BaselineMatrixGateProbe.ps1` 仍停留在旧口径，只验证 `created_run_count` / `summary_total_runs`，没有检查 O03 gate count map。
- 使用的测试 / probe：源码检查。
- 证据：`scripts/Run-BaselineMatrixGateProbe.ps1`
- 结论：先补 HTTP 测试，再升级 probe。

### Round 2

- 发现：`TaskHandlerExperimentSummaryHttpTest` 已覆盖 `/api/v1/experiment_matrix/summary`，但未断言 `acceptance_gate_result_counts / artifact_quality_gate_status_counts / cost_gate_status_counts / runs_with_failure_reason`。
- 使用的测试 / probe：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=TaskHandlerExperimentSummaryHttpTest"`
- 证据：命令退出码 `0`。
- 结论：HTTP surface 已有契约保护，可以继续把脚本 gate 升到相同口径。

### Round 3

- 发现：升级 probe 后，第一次真实脚本运行先暴露了 PowerShell 语法问题；修复后，又暴露了“旧 JAR 未重建导致 task metadata 缺 `baseline_cost_threshold_units`”的运行前置问题。
- 使用的测试 / probe：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Build-WithJava21.ps1 -SkipTests`
- 证据：`BUILD SUCCESS`，产物切换为 `target\agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar`。
- 结论：重新构建后，probe 使用的运行时产物已与当前源码一致。

### Round 4

- 发现：使用默认唯一 `experiment_name` 时，升级后的 probe 已能通过，并生成带 `mode_gate_rollup` 的 report。
- 使用的测试 / probe：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Port 18081 -Background -AutoStop`
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-BaselineMatrixGateProbe.ps1 -BaseUrl http://localhost:18081 -ReportPath .tmp\baseline-matrix-gate-20260721.json`
- 证据：report 中 `created_run_count=9`、`summary_total_runs=9`。
- 结论：脚本级 HTTP gate 已升级为检查 O03 gate count map，而不再只是“9/9 的 run 数量”。

### Round 5

- 发现：若显式传入已使用的 `experiment_name`，probe 现在会在创建 run 前直接报错，而不是把同名历史 run 混入 summary。
- 使用的测试 / probe：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Port 18081 -Background -AutoStop`
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-BaselineMatrixGateProbe.ps1 -BaseUrl http://localhost:18081 -ExperimentName baseline-gate-20260721-101244 -ReportPath .tmp\baseline-matrix-gate-duplicate-check.json`
- 证据：错误消息 `experiment_name already contains 9 runs: baseline-gate-20260721-101244; use a unique name or omit -ExperimentName`
- 结论：重复 experiment name 的假失败路径已被显式阻断。

## 5. 测试与探针

### 5.1 focused tests

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=ExperimentMatrixServiceTest,ExperimentRunServiceTest,TaskHandlerExperimentSummaryHttpTest"
```

结果：

```text
PASS
```

### 5.2 docs audit

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-DocsIndexAudit.ps1 -FailOnViolation
```

结果：

```text
PASS
violation_count=0
```

### 5.3 probes

正向 probe：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Port 18081 -Background -AutoStop
powershell -ExecutionPolicy Bypass -File .\scripts\Run-BaselineMatrixGateProbe.ps1 -BaseUrl http://localhost:18081 -ReportPath .tmp\baseline-matrix-gate-20260721.json
```

负向 probe：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-BaselineMatrixGateProbe.ps1 -BaseUrl http://localhost:18081 -ExperimentName baseline-gate-20260721-101244 -ReportPath .tmp\baseline-matrix-gate-duplicate-check.json
```

结果：

```text
PASS: baseline-matrix-gate-20260721.json
PASS: duplicate experiment_name precheck returns explicit failure before new runs are created
```

## 6. 观测证据

### 6.1 正向 report

- report 路径：`.tmp\baseline-matrix-gate-20260721.json`
- 关键字段：
  - `experiment_name = baseline-gate-20260721-101244`
  - `created_run_count = 9`
  - `summary_total_runs = 9`
  - `checks.summary_has_acceptance_gate_counts = true`
  - `checks.summary_has_artifact_quality_gate_counts = true`
  - `checks.summary_has_cost_gate_counts = true`
  - `checks.summary_has_failure_reason_rollup = true`

### 6.2 mode gate rollup

```json
{
  "strong_only": {
    "acceptance_gate_result_counts": { "not_evaluated": 3 },
    "artifact_quality_gate_status_counts": { "not_evaluated": 3 },
    "cost_gate_status_counts": { "within_threshold": 3 },
    "runs_with_failure_reason": 0
  },
  "small_only": {
    "acceptance_gate_result_counts": { "not_evaluated": 3 },
    "artifact_quality_gate_status_counts": { "not_evaluated": 3 },
    "cost_gate_status_counts": { "within_threshold": 3 },
    "runs_with_failure_reason": 0
  },
  "orchestrated": {
    "acceptance_gate_result_counts": { "not_evaluated": 3 },
    "artifact_quality_gate_status_counts": { "not_evaluated": 3 },
    "cost_gate_status_counts": { "within_threshold": 3 },
    "runs_with_failure_reason": 0
  }
}
```

### 6.3 负向 precheck 证据

```text
experiment_name already contains 9 runs: baseline-gate-20260721-101244; use a unique name or omit -ExperimentName
```

## 7. 代码与文档变更

- 修改文件：
  - `scripts/Run-BaselineMatrixGateProbe.ps1`
  - `src/test/java/com/agentcloud/server/TaskHandlerExperimentSummaryHttpTest.java`
  - `docs/API_CONTRACTS.md`
  - `docs/SPEC.md`
  - `docs/TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
  - `docs/MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`
  - `docs/evaluation/PROGRESS.md`
  - `docs/CURRENT_CAPABILITY_GAP_ASSESSMENT.md`
  - `STATE.md`
- 新增文件：
  - `docs/O03_ACCEPTANCE_GATE_HTTP_EXECUTION_RECORD_2026-07-21.md`
- 关键改动摘要：
  - 把 O03 acceptance gate 从 service/internal contract 推进到 HTTP summary contract。
  - 把 baseline matrix gate probe 升级为检查 mode 级 gate counts 与 failure reason rollup。
  - 补了重复 `experiment_name` 的显式阻断，避免旧 run 干扰 summary gate。

## 8. 验收结果

- `acceptance_result`：partial
- `failure_reason`：真实 worker run 仍未接入，本次仍是未启动 run 的 HTTP gate / probe 验证
- `next_action`：跑一轮 provider-backed `baseline_matrix_v1`，让 `accepted / rejected / needs_followup` 不再全部处于占位态
- `是否需要回到 O03`：需要，下一轮重点应是“真实 worker 质量与成本证据”而不是再扩 summary 字段

## 9. 结论

```text
O03 的 HTTP gate 现在已经正式闭环：服务端字段、HTTP summary、probe 脚本、重复 experiment_name 预检、dated execution evidence 都已对齐。
当前缺的已不再是 contract，而是真实 worker 执行后的质量 / 成本证据。
```