# Loop / Goal / 交接 / UI 状态与结果聚焦计划

## 1. 背景与边界调整

Codex 不同 provider 的协议差异（Responses API ↔ Chat Completions 转换、多 provider 路由、Key 轮换与 failover）现在已经通过本机 **CCX 网关**（`BenedictKing/ccx`，默认监听 `127.0.0.1:4243`）统一收敛。Codex 侧 `config.toml` 统一写 `model_provider = "custom"`、`experimental_bearer_token = "PROXY_MANAGED"`，真实上游 Key 与渠道编排由 CCX 内部管理。

因此 harness 的职责边界随之收窄：

- **不再负责** Codex provider 差异收敛：CCX 负责 Responses ↔ Chat Completions 转换、渠道优先级、Key 轮换、模型名混淆兜底。
- **harness 只消费** 规范化的 provider run metadata / status：把 provider 执行层视为一个返回 `worker_execution_result / status / events` 的归一化后端信号。
- harness 原有的 `codex-openai / xfyun / deepseek` profile lane、codebuddy/deveco CLI protocol 接入这类 provider 专项工作，从主线优先级中降级，只在需要新协议接入或读面诊断时再推进。

本计划用于把下一阶段的工程重点从“provider 差异收敛”切换到下面四条线：

1. **Loop**：goal → plan → execute → judge → decide（continue / handoff / human）的主闭环。
2. **Goal**：目标表达、分解、进度与验收的最小合同。
3. **上下交接文档**：上游 worker 产出与下游 worker 必须消费的交接 packet。
4. **UI 页面展示结果 / 返回 与执行中状态判断**：把 `active / running / waiting_human / failed / partial / done` 的状态语义与页面展示收成一致口径。

本计划取代 `NEXT_5_ENGINEERING_PRIORITIES.md` 与 `CURRENT_CAPABILITY_GAP_ASSESSMENT.md` 中原有 P1–P5 的排序口径；下面两份基线文档的“方向调整 (2026-07-21)”段以本文为准。

## 2. Provider 边界（CCX 收敛层）

| 关注点 | 归属 | 说明 |
|------|------|------|
| Responses API ↔ Chat Completions 转换 | CCX | 商汤 GLM 5.2 等只提供 Chat Completions 的上游由 CCX 转换 |
| 多 provider 路由 / 优先级 / failover | CCX | 渠道编排、429 冷却、Key 轮换 |
| Key 管理 / 明文 Key | CCX | `config.toml` 只写 `PROXY_MANAGED`，上游 Key 存 CCX |
| 模型名混淆兜底 | CCX | `fuzzyModeEnabled` + `modelMapping` 兜底 Codex CLI 混淆名 |
| provider run metadata / status / events | harness | harness 只消费归一化后的执行结果与状态 |
| worker readiness / dispatch preflight | harness | 仍由 harness 判断 worker 是否可分发 |
| 新 CLI protocol 接入（非 Codex） | harness | codebuddy/deveco 等仍由 harness 接入，但优先级降低 |

**约束**：不要把 CCX 的渠道编排、Key 轮换、模型名映射逻辑复制进 harness；harness 侧的 provider lane 配置只保留“指向 CCX 网关 + 模型名”这一层。

## 3. Loop：主闭环

### 目标

把已经存在的 `intake / scheduler / continue / packet / human_gate / handoff` 控制图收成一条可证明的主闭环：

```
goal -> plan -> execute -> judge -> decide(continue | handoff | human | halt)
```

### 当前缺口

- 控制图节点已存在，但 `judge -> decide` 的状态判断仍偏隐式，没有把“执行中”与“已结束”的边界收成稳定口径。
- `continue` 的触发条件没有和 goal 进度绑定：现在仍存在“HTTP `/continue` 超时就把 active 任务判成失败”的风险。
- 多轮续跑的 `resume -> continue` 链缺少 goal 级别的“还差多少”度量。

### 开发目标

- `judge` 输出显式 `ContinuationAction`：`continue / halt / handoff / retry / human_gate`。 **[done: RuntimeJudgmentService + ContinuationAction enum]**
- `decide` 必须消费 goal 进度，而不是只看单轮执行结果。 **[done: resolveAction goal progress priority]**
- 状态判断不允许把“HTTP `/continue` 超时”直接映射成 `failed`；active 任务的活跃判定以 runtime fact 为准。 **[done: LoopContinueTimeoutInvariantTest]**

### 验收标准

1. 一条任务链中能明确看到 `plan -> execute -> judge -> decide` 四段证据。 **[done: ControlNodeGraphOrchestrationFlowTest]**
2. `decide` 的输出与 goal 进度挂钩，而不是只看最后一轮 result。 **[done: ControlNodeGraphDecideGoalProgressPriorityTest + P2 e2e smoke]**
3. HTTP 层超时不会污染 task 级状态：active 任务在 `/continue` 超时后仍是 `active`，不是 `failed`。 **[done: LoopContinueTimeoutInvariantTest + P2 e2e smoke]**

详细后续推进见 `NEXT_EVOLUTION_PLAN.md`。

## 4. Goal：目标合同

### 目标

把 task 的 `goal` 从“自由文本”收成最小可度量合同，让 loop 的 `judge -> decide` 能基于 goal 进度决策。

### 最小字段

- `goal`：用户原始目标
- `subgoals`：拆解后的子目标列表
- `subgoal_status`：每个子目标的 `pending / in_progress / done / blocked`
- `acceptance_criteria`：验收口径
- `progress_summary`：当前完成度摘要

### 开发目标

- task metadata 在每次 `judge` 后更新 `subgoal_status` 与 `progress_summary`。 **[done: autoUpdateSubgoalStatus + GoalProgressAutoUpdateTest]**
- `decide` 优先消费 `subgoal_status`：全部 done 才进 `halt/done`，有 blocked 才进 `human_gate`。 **[done: resolveAction goal progress priority + ControlNodeGraphDecideGoalProgressPriorityTest]**

## 5. 上下交接文档

### 目标

固化上游 worker 产出与下游 worker 必须消费的交接 packet，让跨 worker 续跑不靠隐式上下文。

### Resume Packet 最小字段

- `task_identity`
- `current_objective`
- `current_status`
- `current_node`
- `assigned_worker`
- `latest_summary`
- `next_step`
- `blockers`
- `open_questions`
- `recent_artifacts`
- `recent_decisions`

### Handoff Packet 最小字段

- `from_worker`
- `to_worker`
- `why_handoff`
- `what_done`
- `what_remaining`
- `cautions`
- `resume_hint`

### 开发目标

- 把上述字段集正式写进 `API_CONTRACTS.md` 与 `SPEC.md`，不再只停留在建议清单。 **[done]**
- handoff 场景下下游 worker 必须消费 `HandoffPacket`，resume 场景必须消费 `ResumePacket`。 **[done]**
- 补跨 worker path 的 packet 稳定性验收。 **[done: TaskServicePacketContractTest]**

## 6. UI 页面展示结果 / 返回 与执行中状态判断

### 目标

让 `/dialogue/` 和 `/console/` 的结果展示与状态判断收成一致口径，区分“还在跑”和“已经结束”。

### 状态语义

| 状态 | 含义 | 页面应展示 |
|------|------|------|
| `active` | 任务仍在 loop 中 | 当前 node、最新执行摘要、进度 |
| `running` | 单轮执行中 | 正在执行的 worker、tool、elapsed |
| `waiting_human` | 等待人工介入 | human_gate 原因、可执行的人工动作 |
| `failed` | 任务已终止且未达成 | 失败原因链、partial artifacts |
| `partial` | 部分达成 | 已完成 subgoals、未完成 subgoals、partial artifacts |
| `done` | 全部达成 | 验收结果、最终产物、成本 |

### 开发目标

- HTTP `/continue` 超时不改变 task 级状态，页面不把超时渲染成 `failed`。 **[done]**
- `/dialogue/` pinned 输出区分“最新一轮结果”与“任务级结果”。 **[done: task-status-tone-plan.js + task-subgoal-progress-plan.js]**
- `/console/` operator 读面能区分 worker 级 `running / idle / failed` 与 task 级 `active / waiting_human / done`。 **[done: console-status-tone-plan.js]**
- `partial` 状态有独立 tone，不被误判为 `done` 或 `failed`。 **[done: task-status-tone-plan.js + console-status-tone-plan.js]**

### 验收标准

1. 页面在 task `active` 时不会显示 `failed`。 **[done: task-status-tone-plan.js]**
2. `waiting_human` 时页面显示人工动作入口，而不是只显示一个错误。 **[done: recovery-action-hint-plan.js]**
3. `partial` 与 `done` 都能看到已完成的 subgoals 与产物。 **[done: task-subgoal-progress-plan.js + partial tone in task-status-tone-plan.js + console-status-tone-plan.js]**

## 7. 写回顺序

- 本计划为主入口，先更新本文，再同步 `NEXT_5_ENGINEERING_PRIORITIES.md` 与 `CURRENT_CAPABILITY_GAP_ASSESSMENT.md` 的方向调整段。
- loop / goal 变化写 `continuity/PROGRESS.md` 与 `GOAL_LOOP_LANDING_PLAN.md`。
- 交接 packet 变化写 `API_CONTRACTS.md`、`SPEC.md`。
- UI 状态与结果展示变化写 `dialogue/PROGRESS.md` 与 `WEB_CONSOLE.md`。
- provider 降级写 `provider/PROGRESS.md`。
- 稳定取舍写 `DECISIONS.md`，跨主题短摘要写 `STATE.md`。

## 8. 与现有文档的关系

- 取代：`NEXT_5_ENGINEERING_PRIORITIES.md`、`CURRENT_CAPABILITY_GAP_ASSESSMENT.md` 的 P1–P5 排序口径。
- 续用：`GOAL_LOOP_LANDING_PLAN.md`（goal loop 落地）、`LIVE_FLOW_RUNBOOK.md`（真实链路验证）、`AGENT_CLOUD_HARNESS_EXECUTION_CONTINUITY_MEMORY_FLOW.md`（continuity 主线）。
- 降级：`CODEX_MULTI_API_PROFILE_ROUTING_*`、`DEVECO_AND_CODEBUDDY_PROVIDER_PARAMS_PLAN.md` 等 provider 专项文档，只在接入新协议或读面诊断时再推进。