# P2 End-to-End Integration Smoke Execution Record

> 执行日期：2026-07-22
> 验证对象：Harness -> CCX -> LLM -> Loop Judge -> Decide 端到端闭环
> 前提脚本：`scripts/Run-CcxIntegrationPrecheck.ps1`

## 执行环境

- CCX 网关：`http://127.0.0.1:3688`（v2.9.37）
- Harness：`http://localhost:18082`（agent-cloud-harness 0.1.0-SNAPSHOT）
- Provider Key：codex `config.toml` 的 `ccx` provider `experimental_bearer_token`
- 模型路由：`codex` -> CCX 自动路由到 `glm-4-flash`
- Java：JDK 21.0.9+10

## 执行步骤

1. 运行 `Run-CcxIntegrationPrecheck.ps1`，确认 health + models(30) + completion 全部 PASS
2. 构建 harness：`mvn -DskipTests package`
3. 启动 harness：

```powershell
.\scripts\Run-HarnessWithJava21.ps1 -Port 18082 -Background `
    -JavaArgs @(
        '-DOPENAI_BASE_URL=http://127.0.0.1:3688/v1',
        '-DOPENAI_API_KEY=ccx-YOUR_BEARER_TOKEN_HERE',
        '-DOPENAI_WIRE_API=chat_completions',
        '-DOPENAI_MODEL=codex',
        '-DOPENAI_REVIEW_MODEL=codex'
    )
```

4. 创建 session：`POST /api/v1/sessions` -> `session_a9167b611f03416f`
5. 创建 task（auto_start=true）：

```json
{
  "title": "E2E Smoke",
  "task_type": "coding",
  "source": "manual",
  "priority": "high",
  "intent": "Say hello world",
  "session_id": "session_a9167b611f03416f",
  "auto_start": true
}
```

6. HTTP 请求超时（10s），但 task 在服务端正常创建并执行
7. `GET /api/v1/tasks` 确认 task 状态

## 验证结果

| 检查项 | 状态 | 证据 |
|--------|------|------|
| Task 创建 | PASS | `task_aaeecc93170945ec` 已创建 |
| Worker 派发 | PASS | `assigned_worker=codex`, `execution_backend=provider_app_server` |
| Provider 路由 | PASS | CCX 路由 `codex` -> `glm-4-flash`，`provider_session_id` 非 null |
| Worker 执行结果 | PASS | `summary="Hello world"` |
| Loop judge | PASS | `orchestration_stage=completed` |
| Loop decide | PASS | `status=done`, `control_node=end` |
| Goal contract 初始化 | PASS | `subgoals=["Say hello world"]`, `subgoal_status=[{title, status}]` |
| Goal progress auto-update | PASS | `subgoal_status=[{status: done}]`, `progress_summary="1/1 subgoals done"` |
| Last loop tick | PASS | `metadata.last_loop_tick="2026-07-22T13:54:33.354578600Z"` |
| 完成时间 | ~22s | `started_at=...451` -> `completed_at=...473` |

**整体结果**：ALL PASS — Harness -> CCX -> LLM -> Loop Judge -> Decide 端到端闭环验证成功。

## 完整 Task 响应

```json
{
  "id": "task_aaeecc93170945ec",
  "status": "done",
  "summary": "Hello world",
  "goal": "Say hello world",
  "assigned_worker": "codex",
  "control_node": "end",
  "metadata": {
    "subgoals": ["Say hello world"],
    "subgoal_status": [{"status": "done", "title": "Say hello world"}],
    "progress_summary": "1/1 subgoals done",
    "last_loop_tick": "2026-07-22T13:54:33.354578600Z",
    "provider_session_id": "019f8a1b-1aad-75a3-a727-74a8721256b2",
    "execution_backend": "provider_app_server"
  }
}
```

## 验证的关键能力

1. **CCX 网关作为 LLM 接入层**：harness 通过 `OPENAI_BASE_URL=http://127.0.0.1:3688/v1` 访问 CCX，CCX 路由到实际 LLM 提供商
2. **Loop 主闭环**：harness 的 `goal -> plan -> execute -> judge -> decide` 链在真实 LLM 调用上完整跑通
3. **Goal progress auto-update**：worker 执行完成后 subgoal_status 自动从 `pending` 迁移到 `done`
4. **Last loop tick**：`continueNode` 完成后写入 `last_loop_tick` 供 UI loop activity 检测使用
5. **subgoal_status 驱动 decide**：`resolveAction` 消费 `subgoal_status` 全部 done -> `done`

## 已知限制

- HTTP `/continue` 级超时：auto_start 的 task 创建请求因 worker 执行耗时超过 HTTP 超时阈值，客户端收到 504/timeout 但服务端任务正常完成。这已被 Loop Continue 不变量覆盖（验收标准 #3）。
- `planner_delegation_gate=rejected`：简单任务（"Say hello world"）没有 compact brief 触发 planner delegation，是预期行为。
