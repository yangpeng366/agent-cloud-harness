# D01 Worker Priority Override Execution Record 2026-06-15

## 1. 用途

本文档记录 D01 `Worker priority 覆盖不生效` 的一次 focused regression 执行结果，用于把脚本兼容、测试回归和文档入口串成一条可复跑证据链。

## 2. 基本信息

- 日期：2026-06-15
- 执行人：Codex
- 任务编号：D01
- 任务标题：Worker priority 覆盖不生效
- 任务类型：debug
- task_pack：project_evolution_v1
- task_case_key：D01
- task_family：debug
- task_length_bucket：short
- model_mode：orchestrated
- acceptance_gate：regression_test
- 相关文档：
  - `PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
  - `TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
  - `MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`

## 3. 任务输入

### 3.1 原始输入

```json
{
  "title": "D01 调试 Worker priority 覆盖不生效",
  "task_type": "coding",
  "source": "eval",
  "priority": "high",
  "auto_start": false,
  "intent": "复现并修复 workers.yml selection_priority 覆盖不生效的问题",
  "goal": "补一个稳定回归测试，并让 WorkerRegistry 注册后的 worker metadata 保留覆盖结果",
  "metadata": {
    "task_pack": "project_evolution_v1",
    "task_case_key": "D01",
    "task_family": "debug",
    "task_length_bucket": "short",
    "model_mode": "orchestrated",
    "acceptance_gate": "regression_test"
  }
}
```

### 3.2 任务目标

```text
验证 Worker priority 覆盖回归与 Java 21 窄跑脚本都能稳定复用。
```

### 3.3 预期验收

```text
1. WorkerRegistryPriorityOverrideTest focused regression 通过。
2. Test-WithJava21.ps1 能真实把 -Dtest=... 透传给 Maven/Surefire。
3. 结果写回现有多轮任务文档链。
```

## 4. 执行过程

### Round 1

- 发现：入口文档已改成 articleeditor 风格，但 D01 的最新窄跑证据还没写回任务链。
- 使用的测试 / probe：无。
- 证据：`AGENTS.md`、`WAKE.md`、`docs/README.md` 当前结构。
- 结论：先补 focused regression 证据，再回写文档。

### Round 2

- 发现：`Test-WithJava21.ps1` 现在包含 `PassthroughMavenArgs` 和历史参数兼容逻辑。
- 使用的测试 / probe：脚本源码复核。
- 证据：`scripts/Test-WithJava21.ps1`
- 结论：具备验证裸 `-Dtest` 的前提。

### Round 3

- 发现：裸 `-Dtest` 已真实透传到 Maven。
- 使用的测试 / probe：`powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -Dtest=WorkerRegistryPriorityOverrideTest`
- 证据：运行日志打印 `Running: ... mvn.cmd -q test -Dtest=WorkerRegistryPriorityOverrideTest`
- 结论：脚本兼容已闭环，不再只是文档约定。

### Round 4

- 发现：`WorkerRegistryPriorityOverrideTest` 通过。
- 使用的测试 / probe：同上 focused regression。
- 证据：命令退出码 `0`。
- 结论：D01 的主回归测试可直接复跑。

## 5. 测试与探针

### 5.1 focused tests

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -Dtest=WorkerRegistryPriorityOverrideTest
```

结果：

```text
PASS
Running: ... mvn.cmd -q test -Dtest=WorkerRegistryPriorityOverrideTest
```

### 5.2 probes

```powershell
N/A
```

结果：

```text
本次仅做 focused regression 与脚本透传验证。
```

## 6. 观测证据

- `selected_worker`：N/A
- `route_source`：N/A
- `live_flow` 关键点：本次未启动 harness 实例。
- `judgment_trace` 关键点：本次未启动 harness 实例。
- `experiment_run` 关键点：本次未启动 harness 实例。
- `harness_trace` 关键点：本次未启动 harness 实例。
- `packet` / `checkpoint` 关键点：不适用。
- `tool_trace` 关键点：不适用。

## 7. 代码与文档变更

- 修改文件：
  - `docs/PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
  - `docs/TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
  - `docs/README.md`
- 新增文件：
  - `docs/D01_WORKER_PRIORITY_OVERRIDE_EXECUTION_RECORD_2026-06-15.md`
- 关键改动摘要：
  - 把 D01 的 focused regression 与脚本透传证据写回多轮任务文档链。

## 8. 验收结果

- `acceptance_result`：pass
- `failure_reason`：N/A
- `next_action`：继续按同样方式补 D03、M01、O03 的执行记录。
- `是否需要回到 D01/D03/M01/O03`：D03 / M01 / O03 仍需后续执行记录。

## 9. 结论

```text
D01 的测试与文档链已形成闭环：入口文档明确、脚本窄跑可复用、focused regression 已通过，并且证据已沉淀到 docs。
```
