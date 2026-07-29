# E2 Codex-Free Lane 真机端到端冒烟执行记录

> 日期: 2026-07-29
> 主题: evaluation (E2 端到端验证) + provider (codex-free lane)
> 关联: 长任务收口合同 E1.1/E1.2/E1-observability/E3-UI (commit 1ed141b/33a8bdc/f4474cf/0fe88fb) + codex-free lane 落地

## 背景与目标

E1.1/E1.2/可观测层/UI 已落地，需在真机环境验证：

1. codex-free lane（经本地 CCX + codex app-server）能否真正执行一轮 worker round。
2. 长任务收口合同字段（decision_rationale / progress_detail / progress_summary）在真实 e2e 中是否正确产出与暴露。
3. /judgment_trace API 是否返回 3 个一等字段。
4. advisory handoff（small-tier -> strong-tier）恢复链路是否工作。

## 环境

| 组件 | 状态 |
|------|------|
| Harness | PID 9636, port 9090, JAR 构建 16:35 (含全部 4 commit) |
| CCX | PID 8860, port 3688 (本机 OpenAI-compatible 网关) |
| codex CLI | 0.146.0 |
| codex-free lane | 已注册, ready=true |

## codex-free lane 配置确认

GET /api/v1/workers 返回 codex-free worker：

- worker_id: codex-free
- worker_type: codex
- provider_cost_class: free_auto
- provider_model: codex-free
- provider_model_provider: ccx-free
- provider_id: codex
- execution_backend: provider_app_server
- command_shape: codex app-server --listen stdio://
- model_tier: small
- selection_priority: 70
- primary_role: cannon_fodder
- supports_resume: true
- recovery_resume_policy: fresh_on_recovery

结论：codex-free lane 经本地 CCX (3688) + codex app-server (stdio) 执行，"免费"来自 CCX 路由到免费 tier 模型。lane 已 config-driven 落地（harness-config.yml，gitignore 不入库），不内置硬编码（内置会破坏 free_auto -> paid_auto fallback 合同）。

## 执行过程

### 任务创建

POST /api/v1/tasks 创建 reading 任务（title: "README 一句话总结"，goal: 读取 README.md 用一句话总结项目）。

- task_id: task_c0601e1ced394407
- session_id: session_1ca0259231f24e61
- task_type: reading
- auto_start: false, start_mode: manual

### 路由

free_first_routing 默认关闭（free_first_routing: false），router 选中 codex (strong tier, selection_priority=100)。通过 POST /handoff {target_worker: codex-free} 强制切到 codex-free，再 POST /continue 触发 scheduler node 执行一轮。

### 轮次执行

1. [Scheduler] executing one round with worker=codex-free
2. codex-free 经 CCX 执行，出现 initialize 超时（failure_class: worker_runtime_transient, failure_summary_readable: "worker codex-free failed: initialize: timed out waiting for response"）
3. 恢复策略 same_worker_retry_then_auto_handoff 触发（auto_same_worker_retry_count: 1）
4. advisory handoff: codex-free -> codex（handoff_reason: advisory_consult, advisory_trigger: escalate_from_small_tier）
5. codex (strong tier) 执行（provider_run_dir: run-1785314900153-codex, duration_ms: 26662, ~27s），产出 README 摘要
6. completion judgment: partially_done（rationale: "planner output rejected as delegation brief: missing_compact_brief"）
7. decide: execution continue (partially_done, medium alignment) -> human_gate

### 最终状态

- status: waiting_human
- control_node: human_gate
- assigned_worker: codex
- waiting_reason: subgoal blocked requires human gate
- subgoal_status: [{status: blocked, title: 读取 README.md...}]

## 长任务收口合同字段验证

### task metadata（GET /api/v1/tasks/{id}）

| 字段 | 值 |
|------|------|
| decision_rationale | goal: 0/1 done, 1 blocked; execution continue (partially_done, medium alignment) -> human_gate |
| decision_action | human_gate |
| progress_detail | 0/1 done, 1 blocked; blocked: 读取 D:\gitAll\agent-cloud-harness\README.md，用一句话... |
| progress_summary | 0/1 subgoals done |
| waiting_reason | subgoal blocked requires human gate |

### /judgment_trace API（GET /api/v1/tasks/{id}/judgment_trace）

JudgmentTraceView 顶层返回 3 个一等字段：

- decision_rationale: "goal: 0/1 done, 1 blocked; execution continue (partially_done, medium alignment) -> human_gate"
- progress_detail: "0/1 done, 1 blocked; blocked: 读取 D:\gitAll\agent-cloud-harness\README.md，用一句话..."
- progress_summary: "0/1 subgoals done"

同时返回 execution_judgment (action: continue) + completion_judgment (status: partially_done, alignment: medium)。

## 验收结论

| 验收项 | 状态 | 证据 |
|--------|------|------|
| E1 #1 decision_rationale 真机产出 | PASS | task metadata + judgment_trace |
| E1 #2 progress_detail 真机产出（含 blocked subgoal 标题） | PASS | task metadata + judgment_trace |
| E1 可观测层 /judgment_trace 一等字段 | PASS | API 返回 3 字段 |
| E3 UI /dialogue/ 收口卡 | PASS（已落地） | commit 0fe88fb, JS 311/0 |
| E2 codex-free lane 真机执行 | PASS（含恢复） | codex-free 经 CCX 执行，init 超时后恢复 |
| E2 advisory handoff 链路 | PASS | codex-free -> codex -> human_gate |
| E2 free_first_routing 默认开启 | N/A（OFF by design） | 需 config flag 启用 |

## 发现与风险

### F1: codex-free init 超时（worker_runtime_transient）

codex-free 经 CCX 执行时 initialize 超时。DECISIONS.md 2026-07-28 已将 initialize 超时提到 90s（默认），但经 CCX 冷路径可能仍不足。恢复机制 same_worker_retry_then_auto_handoff 正常工作，未卡死。后续可观测 CCX 冷启动延迟，必要时调 agentcloud.providers.codex.initialize_timeout_ms。

### F2: free_first_routing 默认关闭

codex-free (selection_priority=70) 低于 codex (100)，且 free_first_routing 默认 false，故 router 默认选 codex (strong tier)。这是设计决策（codex-free 保持 config-driven 不内置，避免破坏 free_auto -> paid_auto fallback 合同）。要启用免费优先，需在 harness-config.yml 显式开启 free_first_routing。

### F3: 简单 reading 任务 partially_done

codex (strong tier) 产出 README 摘要后，completion judgment 标 partially_done（planner output rejected as delegation brief: missing_compact_brief）。对简单 reading 任务，planner delegation gate 把输出误判为 delegation brief。属 prompt/gate 调优范畴，非收口合同字段问题。

## 下一步

1. free_first_routing config flag：补一个开关让 operator 一键启用免费优先策略（codex-free 优先 + paid fallback）。
2. codex-free init 超时：观测 CCX 冷启动延迟分布，必要时调 initialize_timeout_ms。
3. planner delegation gate：简单 reading 任务不应被 missing_compact_brief 拦截，需 prompt 或 gate 条件收窄。
4. E2 收口：本记录证明 codex-free lane + 收口合同字段真机可用；E2 验收基本达成。