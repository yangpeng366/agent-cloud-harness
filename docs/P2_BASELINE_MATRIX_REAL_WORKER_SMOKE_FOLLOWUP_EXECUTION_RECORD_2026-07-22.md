# P2 Baseline Matrix Real Worker Smoke Follow-up 2026-07-22

## 1. 用途

本文档记录 2026-07-22 对 `baseline_matrix_v1` real worker smoke 的 follow-up 复跑结论。目标不是重复 2026-07-21 的完整 report，而是验证上轮定位到的 app-server 启动参数问题是否已从真实执行链中移除。

## 2. 背景

2026-07-21 的 `P2_BASELINE_MATRIX_REAL_WORKER_SMOKE_EXECUTION_RECORD_2026-07-21.md` 已确认：

- `short-001 x strong_only/small_only/orchestrated` 三种 mode 能创建 run 并留下 trace 证据。
- 三种 mode 最终都停在 `waiting_human / human_gate`。
- 根因之一是 `codex app-server --no-alt-screen --listen stdio://` 在 Codex CLI `0.144.4` 下报 `unexpected argument '--no-alt-screen' found`，进而表现成 `initialize: timed out waiting for response`。
- 代码侧已把 app-server plan 修正为 `codex app-server --listen stdio://`，exec-json plan 仍保留 `--no-alt-screen`。

## 3. 本轮验证

### 3.1 构建

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.9+10"
& "D:\apache-maven-3.6.0\bin\mvn.cmd" package -DskipTests -q
```

结果：通过。

### 3.2 Harness 启动

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-HarnessWithJava21.ps1 -Port 18082 -Background -AutoStop
```

随后 `/api/v1/health` 返回 `status=up`，说明服务已启动。

### 3.3 Real worker smoke 尝试

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-BaselineMatrixRealWorkerSmoke.ps1 `
  -BaseUrl "http://localhost:18082" `
  -ReportPath ".tmp\baseline-matrix-real-worker-smoke-20260722.json" `
  -CaseKeys @('short-001') `
  -TaskPollTimeoutSec 120 `
  -MinimumTerminalRuns 1 `
  -MinimumEvaluatedRuns 1
```

CLI 调用在外层 300s 超时，未形成完整 report 文件；但 harness 内已创建 task/run，并且 provider run 文件已落盘。

## 4. 关键证据

### 4.1 app-server initialize 已不再卡在参数错误

检查 `.tmp/provider-runs/codex/<task_id>/run-*/events.jsonl`，可见：

```json
{"direction":"harness_send","line":"{...\"method\":\"initialize\"}"}
{"direction":"provider_recv","line":"{...\"remote installed plugin bundle sync failed\"...}"}
```

这说明 harness 已成功启动 `codex app-server --listen stdio://` 并收到 provider 侧响应。上一轮的 `unexpected argument '--no-alt-screen' found` 参数错误已不再是本轮阻塞点。

### 4.2 task 已进入真实执行链

本轮观察到的 task 状态包含：

- `active / scheduler`：仍在执行或等待继续调度的 task
- `waiting_human / human_gate`：恢复预算耗尽后进入人工 gate 的 task
- provider run 文件包含 `events.jsonl` 与 `prompt.txt`

这说明 smoke 已越过“run 未启动 / app-server 无响应”的阶段，进入真实执行与恢复路径。

### 4.3 当前剩余阻塞

仍存在 `waiting_human / human_gate` 结果，典型 `experiment_run.failure_reason`：

```text
Worker round failed and automatic recovery budget is exhausted.
```

当前 health 中 `llm.available=false`、`api_key_configured=false`，说明完整 accepted/completed baseline 样本仍受本机 LLM/认证配置限制。该限制不等同于 app-server 启动参数回归。

## 5. 结论

本轮验证结论：

- **已确认修复**：`codex app-server --listen stdio://` 能启动并响应 JSON-RPC initialize，不再被 `--no-alt-screen` 参数错误阻断。
- **仍未完成**：`short-001 x 3 mode` 尚未形成 accepted/completed 成功样本。
- **剩余瓶颈**：本机 provider 认证/LLM 可用性与 recovery budget，而不是 app-server 命令形态。

## 6. 下一步

1. 在 `llm.available=true` 且 provider auth 就绪的环境下复跑 `short-001`。
2. 目标从“确认 initialize 修复”升级为“拿到至少 1 个 accepted/completed 样本”。
3. 成功后再扩到 `medium-001 / long-001` 与完整 `3 case x 3 mode` release gate。