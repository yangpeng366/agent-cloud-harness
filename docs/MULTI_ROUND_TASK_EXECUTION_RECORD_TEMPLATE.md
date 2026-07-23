# MULTI_ROUND_TASK_EXECUTION_RECORD_TEMPLATE

## 1. 用途

本文档用于记录 `PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`、`TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md` 和 `MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md` 中的单次多轮任务执行结果。

它的目标不是写长篇总结，而是把一次完整执行最关键的证据固定下来，便于后续：

- 复跑
- 对比
- 回归
- 沉淀进 `baseline_matrix_v2`

## 2. 基本信息

- 日期：
- 执行人：
- 分支 / 提交：
- 任务编号：
- 任务标题：
- 任务类型：
- task_pack：
- task_case_key：
- task_family：
- task_length_bucket：
- model_mode：
- acceptance_gate：
- harness 端口：
- 相关文档：
  - `PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
  - `TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
  - `MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`

## 3. 任务输入

### 3.1 原始输入

```json
{}
```

### 3.2 任务目标

```text
<填写任务目标>
```

### 3.3 预期验收

```text
<填写预期验收>
```

## 4. 执行过程

### Round 1

- 发现：
- 使用的测试 / probe：
- 证据：
- 结论：

### Round 2

- 发现：
- 使用的测试 / probe：
- 证据：
- 结论：

### Round 3

- 发现：
- 使用的测试 / probe：
- 证据：
- 结论：

### Round 4

- 发现：
- 使用的测试 / probe：
- 证据：
- 结论：

### Round 5

- 发现：
- 使用的测试 / probe：
- 证据：
- 结论：

## 5. 测试与探针

### 5.1 focused tests

```powershell
<填写命令>
```

结果：

```text
<填写结果>
```

### 5.2 probes

```powershell
<填写命令>
```

结果：

```text
<填写结果>
```

### 5.3 额外回归

- `pause/resume`：
- `handoff`：
- `human gate`：

## 6. 观测证据

- `task_id`：
- `selected_worker`：
- `route_source`：
- `live_flow` 关键点：
- `judgment_trace` 关键点：
- `experiment_run` 关键点：
- `harness_trace` 关键点：
- `packet` / `checkpoint` 关键点：
- `tool_trace` 关键点：

## 7. 代码与文档变更

- 修改文件：
- 新增文件：
- 更新文档：
- 关键改动摘要：

## 8. 验收结果

- `acceptance_result`：
- `failure_reason`：
- `next_action`：
- `是否需要回到 D01/D03/M01/O03`：

## 9. 附件

- probe report：
- log 路径：
- 截图路径：
- 其他证据：

## 10. 结论

```text
<填写本次执行结论>
```
