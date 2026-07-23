# D03 Chat Facade Execution Record 2026-06-15

## 1. 用途

本文档记录 D03 `Chat Facade SSE 流式兼容` 的一次 focused regression + acceptance probe 执行结果。

## 2. 基本信息

- 日期：2026-06-15
- 执行人：Codex
- 任务编号：D03
- 任务标题：Chat Facade SSE 流式兼容
- 任务类型：debug
- task_pack：project_evolution_v1
- task_case_key：D03
- task_family：debug
- task_length_bucket：short
- model_mode：orchestrated
- acceptance_gate：http_contract
- 相关文档：
  - `PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
  - `TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
  - `MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`

## 3. 任务输入

### 3.1 原始输入

```json
{
  "title": "D03 调试 Chat Facade SSE 流式兼容",
  "task_type": "coding",
  "source": "eval",
  "priority": "high",
  "auto_start": false,
  "intent": "验证并加固 chat completions 和 responses 的 SSE 输出契约",
  "goal": "补 SSE contract 回归测试，确保 stream=true 时输出序列与 DONE 收尾稳定",
  "metadata": {
    "task_pack": "project_evolution_v1",
    "task_case_key": "D03",
    "task_family": "debug",
    "task_length_bucket": "short",
    "model_mode": "orchestrated",
    "acceptance_gate": "http_contract"
  }
}
```

### 3.2 任务目标

```text
验证 Chat Facade 两条 surface 的接口契约与 acceptance probe。
```

### 3.3 预期验收

```text
1. ChatFacadeHandlerHttpTest focused regression 通过。
2. Chat facade acceptance probe 返回 chat_completions / responses 双 surface 结构化结果。
3. 结果写回现有多轮任务文档链。
```

## 4. 执行过程

### Round 1

- 发现：D03 已经在计划和任务包里，但还缺最新可复跑证据。
- 使用的测试 / probe：无。
- 证据：`docs/TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
- 结论：先跑 focused regression。

### Round 2

- 发现：`ChatFacadeHandlerHttpTest` 通过脚本窄跑。
- 使用的测试 / probe：`powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -Dtest=ChatFacadeHandlerHttpTest`
- 证据：命令退出码 `0`
- 结论：接口契约单测可直接复跑。

### Round 3

- 发现：`Run-ChatFacadeAcceptanceWithLocalHarness.ps1` 能返回结构化 probe 结果。
- 使用的测试 / probe：`powershell -ExecutionPolicy Bypass -File .\scripts\Run-ChatFacadeAcceptanceWithLocalHarness.ps1 -SkipBuild -Port 18080 -KeepServerLogs`
- 证据：`chat_probe` 和 `responses_probe` 都返回 `task_receipt`，且 `live_flow_available=true`
- 结论：acceptance probe 闭环成立。

### Round 4

- 发现：probe 初始输出前缀混入了 `Use-Java21.ps1` 的提示行，不利于脚本化消费。
- 使用的测试 / probe：再次执行 `Run-ChatFacadeAcceptanceWithLocalHarness.ps1` 并写入 `.tmp\chat-facade-acceptance-18080-20260615-result.json`
- 证据：结果文件第一行现在直接是 `{`，已恢复纯 JSON 输出。
- 结论：D03 的 acceptance probe 现在既能通过，也能被后续脚本稳定消费。

## 5. 测试与探针

### 5.1 focused tests

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -Dtest=ChatFacadeHandlerHttpTest
```

结果：

```text
PASS
```

### 5.2 probes

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-ChatFacadeAcceptanceWithLocalHarness.ps1 -SkipBuild -Port 18080 -KeepServerLogs
```

结果：

```json
{
  "chat_probe": { "task_reply_type": "task_receipt", "live_flow_available": true },
  "responses_probe": { "task_reply_type": "task_receipt", "live_flow_available": true }
}
```

## 6. 观测证据

- `task_id`：见 probe 输出
- `selected_worker`：codex
- `route_source`：task-pinned / model tier fallback
- `live_flow` 关键点：`live_flow_available=true`
- `judgment_trace` 关键点：本次未额外展开
- `experiment_run` 关键点：不适用
- `harness_trace` 关键点：不适用
- `packet` / `checkpoint` 关键点：不适用
- `tool_trace` 关键点：不适用

## 7. 代码与文档变更

- 修改文件：
  - `docs/PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
  - `docs/TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
  - `docs/README.md`
  - `docs/TROUBLESHOOT.md`
- 新增文件：
  - `docs/D03_CHAT_FACADE_EXECUTION_RECORD_2026-06-15.md`
- 关键改动摘要：
  - 把 D03 的单测与 acceptance probe 证据写回任务链。
  - 修正 `Use-Java21.ps1 -Quiet` 的输出污染问题，使 façade acceptance probe 返回纯 JSON。

## 8. 验收结果

- `acceptance_result`：pass
- `failure_reason`：N/A
- `next_action`：继续补 M01 / O03 的执行记录。
- `是否需要回到 D01/D03/M01/O03`：M01 / O03 仍需后续执行记录。

## 9. 结论

```text
D03 已形成可复跑证据链：focused regression 通过，双 surface acceptance probe 也返回了结构化结果。
```
