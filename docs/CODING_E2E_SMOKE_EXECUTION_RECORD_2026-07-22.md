# Coding E2E Smoke Execution Record

> 执行日期：2026-07-22
> 验证对象：Harness -> CCX -> LLM -> Loop -> Decide 在真实编码任务上的端到端闭环

## 执行环境

- CCX 网关：`http://127.0.0.1:3688`（v2.9.37）
- Harness：`http://localhost:18082`（含 worker execution timeout 保护）
- 模型路由：`codex` -> CCX 自动路由到 `glm-4-flash`

## 执行步骤

1. 运行 `Run-CcxIntegrationPrecheck.ps1`，确认 health + models + completion 全部 PASS
2. 启动 harness（含 worker execution timeout 120s 保护）
3. 创建 session + auto_start task，intent="Write a simple Python function that adds two numbers"

## 验证结果

| 检查项 | 状态 | 证据 |
|--------|------|------|
| Task 创建 | PASS | `task_d985df5b71e8498e` 已创建 |
| Worker 派发 | PASS | `assigned_worker=codex`, `execution_backend=provider_app_server` |
| Worker 执行结果 | PASS | summary="I'll create a simple Python add function...Created `add.py`..." |
| Loop judge | PASS | `orchestration_stage=completed` |
| Loop decide | PASS | `status=done`, `control_node=end` |
| Goal contract | PASS | `subgoal_status=[{status: done, title: Write a Python add function}]` |
| Goal auto-update | PASS | `progress_summary=1/1 subgoals done` |
| Last loop tick | PASS | `last_loop_tick=2026-07-22T15:30:33.300433200Z` |

**整体结果**：ALL PASS — 编码任务端到端闭环验证成功。

## 与前一轮的区别

| 维度 | P2 E2E Smoke (greeting) | 本轮 (coding) |
|------|------------------------|---------------|
| 任务类型 | "Say hello world" | "Write a Python add function" |
| task_type | coding | coding |
| 完成状态 | done | done |
| Worker 产出 | "Hello world" (文本) | "Created `add.py` with `add(a, b)` function" (代码) |
| Subgoal auto-update | 1/1 done | 1/1 done |
| last_loop_tick | written | written |

## 已知限制

复杂编码任务（如 baseline matrix short-001 "Fix a small regression in task routing"）会导致 codex app-server hang。worker execution timeout (120s) 会将其转为 failure recovery 路径，不会无限阻塞。

这表明 codex CLI app-server 对简单任务（greeting、简单函数）能正常响应，但对需要深度代码分析的任务可能 hang。后续可考虑：
- 调查 codex app-server hang 根因（可能是 codex CLI 版本/配置问题）
- 使用更简单的 baseline task case 让 codex worker 能完成
- 或使用其他 worker（如通过 CCX 路由到更强的 LLM）处理复杂任务
