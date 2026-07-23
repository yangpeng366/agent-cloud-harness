# M01 O03 Multi-round Execution Record 2026-06-15

## 1. 用途

本文档记录 M01 `baseline_matrix_v2` 与 O03 `Experiment matrix acceptance gate` 的一次联动验证。

## 2. 基本信息

- 日期：2026-06-15
- 执行人：Codex
- 任务编号：M01 / O03
- 任务标题：baseline_matrix_v2 真实任务集 / Experiment matrix acceptance gate
- 任务类型：modify / optimize
- task_pack：project_evolution_v1
- task_case_key：M01 / O03
- task_family：modify / optimize
- task_length_bucket：medium / long
- model_mode：orchestrated
- acceptance_gate：matrix_contract / quality_and_cost
- 相关文档：
  - `PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
  - `TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
  - `MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`

## 3. 任务输入

### 3.1 原始输入

```json
{
  "m01": {
    "title": "M01 建立 baseline_matrix_v2 真实任务集",
    "task_type": "coding",
    "source": "eval",
    "priority": "high",
    "auto_start": false,
    "intent": "把 D01 D03 M02 O03 收束成 baseline_matrix_v2 候选 case",
    "goal": "让 experiment matrix 能真正列出可跑的真实工程任务",
    "metadata": {
      "task_pack": "project_evolution_v1",
      "task_case_key": "M01",
      "task_family": "modify",
      "task_length_bucket": "medium",
      "model_mode": "orchestrated",
      "acceptance_gate": "matrix_contract"
    }
  },
  "o03": {
    "title": "O03 优化 Experiment matrix acceptance gate",
    "task_type": "coding",
    "source": "eval",
    "priority": "high",
    "auto_start": false,
    "intent": "让 experiment matrix 不只创建 run，还能给出 acceptance_result",
    "goal": "增加 accepted rejected needs_followup 的最小自动判定和汇总证据",
    "metadata": {
      "task_pack": "project_evolution_v1",
      "task_case_key": "O03",
      "task_family": "optimize",
      "task_length_bucket": "long",
      "model_mode": "orchestrated",
      "acceptance_gate": "quality_and_cost"
    }
  }
}
```

### 3.2 任务目标

```text
验证 matrix 相关测试与门禁 probe 可以联动复跑。
```

### 3.3 预期验收

```text
1. ExperimentMatrixServiceTest 与 ExperimentRunServiceTest 通过。
2. 文档中保留带引号的 -Dtest=...,... 调用示例。
3. 多轮任务链里写回 matrix / acceptance gate 证据。
```

## 4. 执行过程

### Round 1

- 发现：M01 / O03 的 runbook 已经给出标准顺序，但缺少最新验证结论。
- 使用的测试 / probe：无。
- 证据：`docs/MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`
- 结论：先跑 focused regression。

### Round 2

- 发现：PowerShell 直接写裸逗号分隔 `-Dtest=ExperimentMatrixServiceTest,ExperimentRunServiceTest` 会触发参数解析错误。
- 使用的测试 / probe：裸调用失败
- 证据：`Missing argument in parameter list`
- 结论：文档示例必须保留引号。

### Round 3

- 发现：带引号的 `-MavenArgs` 调用可以稳定跑通。
- 使用的测试 / probe：`powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=ExperimentMatrixServiceTest,ExperimentRunServiceTest"`
- 证据：命令退出码 `0`
- 结论：matrix 相关 focused regression 可直接复跑。

### Round 4

- 发现：baseline matrix gate probe 也已通过。
- 使用的测试 / probe：`powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Port 8080 -Background -AutoStop` + `powershell -ExecutionPolicy Bypass -File .\scripts\Run-BaselineMatrixGateProbe.ps1 -BaseUrl http://localhost:8080 -ExperimentName baseline-gate-20260615 -ReportPath .tmp\baseline-matrix-gate-20260615.json`
- 证据：`created_run_count=9`、`summary_total_runs=9`、`catalog_case_count=9`，`checks.health_up=true`
- 结论：M01 / O03 的运行时门禁证据已补齐。

## 5. 测试与探针

### 5.1 focused tests

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=ExperimentMatrixServiceTest,ExperimentRunServiceTest"
```

结果：

```text
PASS
```

### 5.2 probes

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Port 8080 -Background -AutoStop
powershell -ExecutionPolicy Bypass -File .\scripts\Run-BaselineMatrixGateProbe.ps1 -BaseUrl http://localhost:8080 -ExperimentName baseline-gate-20260615 -ReportPath .tmp\baseline-matrix-gate-20260615.json
```

结果：

```text
PASS
created_run_count=9
summary_total_runs=9
```

## 6. 观测证据

- `experiment_run` 关键点：相关 test 已通过。
- `experiment_matrix/summary` 关键点：created_run_count=9, summary_total_runs=9, catalog_case_count=9
- `live_flow` 关键点：不适用。
- `judgment_trace` 关键点：不适用。

## 7. 代码与文档变更

- 修改文件：
  - `docs/PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
  - `docs/TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
  - `docs/README.md`
  - `docs/M01_O03_MULTI_ROUND_EXECUTION_RECORD_2026-06-15.md`
- 新增文件：
  - `docs/M01_O03_MULTI_ROUND_EXECUTION_RECORD_2026-06-15.md`
- 关键改动摘要：
  - 把 matrix 相关 focused regression 的可复用命令和调用限制写回任务链。
  - 把 baseline matrix gate probe 的 9/9 运行和 summary 聚合结果写回执行记录。

## 8. 验收结果

- `acceptance_result`：partial
- `failure_reason`：N/A
- `next_action`：继续补 O04 / 后续真实任务记录。
- `是否需要回到 D01/D03/M01/O03`：M01 / O03 这次已闭环。

## 9. 结论

```text
M01 / O03 的测试与 gate probe 已闭环：focused regression 通过，baseline matrix gate probe 生成了 9/9 的运行与 summary 证据。
```
