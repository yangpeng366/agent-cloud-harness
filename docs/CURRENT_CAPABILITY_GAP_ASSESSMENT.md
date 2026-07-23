## 方向调整 (2026-07-21)

Codex 不同 provider 的协议差异已经由本机 CCX 网关统一收敛，harness 不再负责 provider 差异收敛。下一阶段工程重点调整为四条线，下列 P1–P5 排序口径以 `LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md` 为准；本文件下方原有的 P1–P5 描述保留作为上下文，但优先级排序已被本段取代。

新排序：

| 新序 | 能力项 | 说明 |
|---|---|---|
| P1 | Loop 主闭环 + 执行中状态判断 | `goal -> plan -> execute -> judge -> decide`；HTTP 超时不污染 task 级状态 |
| P2 | Goal 目标合同 | `goal / subgoals / subgoal_status / acceptance_criteria / progress_summary` |
| P3 | 上下交接文档 / handoff packet | Resume / Handoff packet 最小字段集写进 `API_CONTRACTS.md` / `SPEC.md` |
| P4 | UI 页面展示结果 / 返回 + 执行中状态判断 | `active / running / waiting_human / failed / partial / done` 状态语义与页面展示 |
| P5 | Provider 专项（降级） | codex profile lane、codebuddy/deveco CLI 接入只在接入新协议或读面诊断时推进；provider 差异由 CCX 收敛 |

详见 `LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md`。下方 P2 baseline matrix 的真实 worker smoke 证据继续有效，但“补全 3+3+3”不再是唯一主路径，应作为 loop 验证的真实执行证据来源。

### 完成状态 (2026-07-22)

上述 P1-P5 新排序方向已全部落地并验证：

| 方向 | 状态 | 验证证据 |
|------|------|---------|
| P1 Loop 主闭环 | done | ControlNodeGraphOrchestrationFlowTest + ControlNodeGraphDecideGoalProgressPriorityTest + LoopContinueTimeoutInvariantTest + P2 e2e smoke |
| P2 Goal 目标合同 | done | TaskServiceGoalContractTest + RuntimeJudgmentServiceTest + GoalProgressAutoUpdateTest + e2e: 1/1 subgoals done |
| P3 交接 packet | done | API_CONTRACTS.md / SPEC.md 字段集 + TaskServicePacketContractTest |
| P4 UI 状态展示 | done | task-status-tone-plan.js + console-status-tone-plan.js + task-subgoal-progress-plan.js + loop-activity-detector-plan.js + recovery-action-hint-plan.js（含 partial 独立 tone） |
| P5 Provider 专项 | done | CCX 网关收敛 + CCX_INTEGRATION_PRECHECK_EXECUTION_RECORD_2026-07-22.md |

后续推进方向见 NEXT_EVOLUTION_PLAN.md。

---
# CURRENT_CAPABILITY_GAP_ASSESSMENT

## 1. 目的

本文档用于把 `agent-cloud-harness` 当前核心能力、完成度、主要缺口与下一步最具体动作整理成一份面向推进的能力差距评估表。

它不重复描述理想架构，而是回答四个更直接的问题：

1. 当前哪些核心能力已经具备雏形
2. 哪些能力仍然只是概念或半成品
3. 当前最大的产品化/主价值风险在哪里
4. 下一步最值得补的具体动作是什么

本文档与 `NEXT_5_ENGINEERING_PRIORITIES.md` 配套使用。
前者回答“先做什么”，本文回答“现在做到哪了，还差什么”。

如果要顺着同一条主线继续看，建议同时联读：

- `ARCHITECTURE.md`：当前代码里的 continuity-first control plane 落点
- `GOAL_ORIENTED_EVAL_PLAN.md`：如何验证“强模型调小模型”的近端命题
- `PHASE2_ROADMAP.md`：当前 runtime contract 收硬方向
- `AGENT_PROVIDER_TECHNICAL_DESIGN.md`：provider 接入面如何接到现有 harness 上

---

## 2. 总体判断

如果压缩成一句话，当前项目的状态是：

> `agent-cloud-harness` 已经具备相当明确的 continuity runtime / control plane 雏形，而且已经拥有真实的 tool-aware execution、tool trace 持久化、runtime context、judgment trace 与 experiment surface；但还没有把这些能力进一步收束成统一的 hardness phase-1 runtime contract 链，也还没有把“强模型调度小模型完成长任务”的主价值闭环稳定证明出来。

因此当前项目：

- 已经不像一个纯 demo
- 也不只是“只有设计没有落地”
- 但还不能完全证明自己是一个成熟的 orchestration harness

更具体地说，当前最应该坚持的叙事顺序是：

1. 先把项目描述为 **continuity-first orchestration harness**
2. 再把“强模型调度小模型完成更长任务”作为近端最关键 proof target
3. 避免重新退回“所有 runtime/provider 能力都还是未来工作”的旧表述

更细一点看：

- continuity / packet / control 方向已经达到中等成熟度
- tool-aware execution 已进入真实实现阶段
- orchestration 主闭环仍偏早期
- evaluation system 已有 matrix / run surface、统一指标字段与汇总面，但仍需要把真实任务集和 acceptance gate 固化成可复跑基准

---

## 3. 能力差距总表

| Priority | 能力项 | 当前状态 | 估计完成度 | 最大缺口 | 下一步最具体动作 |
|---|---|---:|---:|---|---|
| P1 | 强模型调小模型最小闭环 | runtime trace 已有最小 strong->small->strong 闭环；P1 验收标准 1-4 全部有 contract test 证据 | 55%-65% | 单元级闭环已有，仍缺 provider-backed 真实复跑和质量/成本对比证据 | 把 orchestration trace 接入 baseline_matrix_v1 的真实 worker 冒烟 |
| P2 | Baseline experiment matrix | 已有三模式 run skeleton、3+3+3 case catalog、HTTP gate 探针、指标落盘与最小 acceptance/quality/cost gate 聚合字段，并已拿到 `short-001` 三模式 provider-backed real worker smoke 证据；首轮 initialize 超时已定位为 Codex app-server 参数兼容问题并完成代码修复 | 75%-85% | 仍缺修复后复跑的 accepted/completed 样本、完整 3+3+3 结果、成本阈值标定和 release gate 提升 | 重新 build 后复跑 `short-001` real worker smoke，再扩到 `medium-001 / long-001` 与完整 3+3+3 |
| P3 | Checkpoint / handoff packet spec | 概念成熟，协议测试已覆盖核心字段 | 55%-70% | 最小字段集已有测试保护，但 packet spec 文档和 runtime 输出仍需最终冻结 | 把 resume/handoff packet 最小 schema 正式写成 API/contract，并补跨 worker path 验证 |
| P4 | Tool-aware / provider execution 与 recovery | 已有多步工具链、provider 续跑、failure recovery 和 UI 验收 | 65%-75% | 长任务最终收口、质量判定和 human gate 边界仍需继续硬化 | 继续强化 long-task closure contract：done/waiting_human/auto-continue 的可证明边界 |
| P5 | 状态变更接口 + 消息投影 | POST 控制动作与核心 lifecycle projection 已基本收口 | 60%-70% | 旧 GET 兼容入口仍在，长尾 runtime subtype 与浏览器级 lifecycle gate 仍需治理 | 制定 GET 兼容下线策略，并扩浏览器级 lifecycle acceptance |

---

## 4. 分项评估

## 4.1 P1. 强模型调小模型最小闭环

### 当前状态

当前项目已经有一批关键基础设施：

- routing
- handoff
- continuity runtime
- worker execution 雏形
- message layer
- control flow / live flow

这些说明项目并不是从零开始，也不是只能做静态文档演示。

### 估计完成度

**55% - 65%**

### 为什么不是更高

因为真正的主价值链条已经有 runtime 骨架，但还没有被真实 provider run 稳定证明：

- `ControlNodeGraph` 已在 planner 阶段写入 `planner_worker / planner_model_tier`
- execution 阶段已写入 `executor_worker / executor_model_tier`
- completion judgment 已写入 `evaluator_role / evaluator_model_tier / evaluation_result`
- `ExperimentRunService` 已把 `orchestration_closed_loop_observed`、planner/executor/evaluator 证据聚合进 experiment run metadata
- `ExperimentMatrixService` summary 已能统计 strong planner / small executor / strong evaluator / strong-small-strong loop

但仍不能评为更高，因为：

- `short-001` 已有 provider-backed baseline 失败样本，但还不是一轮可证明收益的成功对比结果
- 真实 worker smoke 目前只覆盖 `short-001`，还缺 `medium-001 / long-001` 与完整 strong-small-strong 成功闭环
- 质量 acceptance / 成本 gate 聚合字段已落盘到 run metadata 与 mode summary，也已接到 `short-001` 失败样本，但尚未形成真实 artifact acceptance 与 release gate
- fallback / escalation 原因链已有字段和真实失败样本，但还需要把 `initialize` 超时复盘沉淀成 operator 可用的 runbook

### 最大缺口

最大的缺口不是“没有更多 worker 类型”，而是：

> 把已存在的 strong->small->strong runtime trace 跑成 provider-backed 的真实质量与成本证据。

### 下一步最具体动作

先不要全面泛化，下一步直接复用 P2 的 baseline matrix：

- 选择 `short-001 / medium-001 / long-001`
- 三种模式都创建 run，但重点观察 `orchestrated`
- 验证 `orchestrated` 的 experiment run metadata 是否同时具备：
- `planner_model_tier = strong`
- `executor_model_tier = small`
- `evaluator_model_tier = strong`
- `orchestration_closed_loop_observed = true`
- 再对比 `strong_only / small_only` 的 completion、acceptance、cost、human gate

### 风险提醒

如果这一层迟迟不落地，项目会长期停留在：

- continuity runtime 有潜力
- 但 orchestration 价值没有被证明

---

## 4.2 P2. Baseline experiment matrix

### 当前状态

项目已经明显有 evaluation 意识，且文档层面对“goal fit、场景、路径、价值证明”的关注已经存在。

这说明方向感是有的，而且当前代码层已经越过早期规划阶段。

### 估计完成度

**75% - 85%**

### 为什么还没有更高

因为当前已经具备：

- `strong_only / small_only / orchestrated` 三种实验模式
- `ExperimentRunRecord` 与 experiment run 落盘
- matrix service / summary surface
- `completion_status / acceptance_result / recovery_success / failure_reason` 等统一字段
- 内置 `baseline_matrix_v1` 的 3 short + 3 medium + 3 long case catalog
- 每个 case 已有 `workspace_preconditions / acceptance_criteria / expected_artifacts / recovery_policy`
- 创建 run 时会把 case 合同同步到 task metadata，避免只靠 title / goal 解释验收口径
- `scripts/Run-BaselineMatrixGateProbe.ps1` 已提供最小 HTTP release-gate 探针，并会检查 `acceptance_gate_result_counts / artifact_quality_gate_status_counts / cost_gate_status_counts / runs_with_failure_reason`
- 2026-05-19 已在隔离端口 `18084` 跑通探针：9 个 catalog case、9 个冒烟 run、summary 9 runs
- 2026-07-21 已在隔离端口 `18081` 复跑升级后的 probe，report 写入 `.tmp\\baseline-matrix-gate-20260721.json`；当前 `mode_gate_rollup` 已能证明三种 mode 的 `not_evaluated=3`、`within_threshold=3`、`runs_with_failure_reason=0`，且重复 `experiment_name` 会在创建前直接失败。
- 2026-07-21 后续复盘 `.tmp\provider-runs\codex\...\events.jsonl`，确认 `initialize` 超时根因是 app-server 启动命令带了 Codex CLI `0.144.4` 不接受的 `--no-alt-screen`；当前代码已把 app-server plan 改成 `codex app-server --listen stdio://`，exec-json 仍保留 `--no-alt-screen`。

但还没有到更高成熟度，因为：

- 已有 `short-001` 的三模式真实 worker smoke 和启动参数根因修复，但还没有修复后复跑结果，`medium-001 / long-001` 与完整 3+3+3 也还没有正式结果
- 成本口径仍偏占位，不能支撑严肃 cost comparison
- acceptance / artifact quality / cost 三类 gate 已在 run metadata 与 mode summary 落盘，但尚未和真实 artifact 内容与 release gate 绑定
- 当前 gate 已证明 HTTP 合同和 `short-001` 真实失败样本可回收，还不能证明 orchestration 优于 baseline

### 最大缺口

当前缺的不是“更多评估理念”，而是：

> 把已经拿到首轮 `short-001` smoke 的 baseline_matrix_v1 扩成可比较的真实 worker 质量与成本证据。

### 下一步最具体动作

下一步不是再造 run skeleton，也不是再补 case 字段或 HTTP 冒烟 gate，而是做真实 worker 复跑：

- 复用 `scripts/Run-BaselineMatrixGateProbe.ps1` 做 HTTP 合同前置检查
- 用 `POST /api/v1/experiment_matrix/runs` 创建真实 `baseline_matrix_v1`
- 重新 build 后复跑 `short-001`，确认 `codex app-server --listen stdio://` 不再卡在 initialize，再把 smoke 扩到 `medium-001 / long-001`
- 再跑完整 3+3+3
- 把 `experiment_summary` 与失败 case 的 `live_flow / judgment_trace / tool_trace` 作为验收证据
- 在 gate 中加入真实 artifact acceptance 与 cost threshold

### 风险提醒

如果没有实验骨架，后续会持续出现：

- 感觉某个设计更合理
- 但不能证明它是否真的更有效

---

## 4.3 P3. Checkpoint / handoff packet spec

### 当前状态

这是目前相对最成熟的一项能力。

当前项目已经具备：

- packet
- checkpoint
- handoff
- consolidation
- continuity boundary 的明确意识

而且从概念上看，项目已经把“任务恢复边界”当成核心基础设施之一，而不是附属特性。

### 估计完成度

**55% - 70%**

### 为什么不是更高

因为虽然概念已经清楚，且核心 packet 行为已有测试保护，但协议层还未完全封板：

- schema 文档没有彻底冻结
- resume packet 与 handoff packet 的职责边界仍需要在 API contract 里正式化
- machine-readable first 的规范还需要与 runtime 输出逐字段对齐
- recovery / provider handoff 场景下的跨 worker packet 稳定性还需要更强验收

### 最大缺口

当前最大的缺口是：

> continuity object 已经存在，但还没有成为稳定的、可严格依赖的协议对象。

### 下一步最具体动作

优先把当前已经存在的最小字段集写成正式 contract，而不是继续停留在建议清单。

#### Resume Packet 最小字段建议

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

#### Handoff Packet 最小字段建议

- `from_worker`
- `to_worker`
- `why_handoff`
- `what_done`
- `what_remaining`
- `cautions`
- `resume_hint`

### 风险提醒

如果这层继续长期半隐式存在，未来会影响：

- resume
- handoff
- audit
- replayability
- model delegation

---

## 4.4 P4. Tool-aware execution 最小多步

### 当前状态

项目已经不是“只会 plan 一下、打一把 tool 就结束”的阶段。当前真实落地能力已经包括：

- 多步 tool chain 执行
- `tool_chain_termination_reason`、`unfinished_items`、`execution_status` 等结构化 trace
- failure recovery：same-worker retry / auto handoff / human gate
- runtime fact aggregation、live flow、message projection
- `declared rounds` / `auto_multi_round` / grounded write 等续跑信号
- provider-backed worker 的 fresh-session recovery 与 provider failure diagnostics
- `/dialogue/` 与 `/console/` 上的 recovery job 可见性

说明执行层已经开始向“可操作系统”移动，而且已经能支持真实长任务的证据收集与多轮推进。

### 估计完成度

**65% - 75%**

### 为什么还不高

因为当前 weakest point 已经不再是“不会多步执行”，而是“长任务如何自动收口”：

- 可以收证据，但不总能自然收成可信的最终结果
- completion / continuation / human gate 三者边界仍在持续收硬
- task result / final artifact / human gate 的终态合同还不够稳定
- 对长任务来说，自动推进与最终收口之间仍有灰区

### 最大缺口

最大的缺口不是“工具数量不够”，而是：

> long-task closure contract 还不够硬。

### 下一步最具体动作

优先继续收硬长任务收口合同：

- `continue` 什么时候应该自动续跑
- `done` 什么时候可以被可信宣告
- `waiting_human` 什么时候必须被显式触发
- 这些边界如何稳定投影到 artifact / decision / task_result / live flow

不要一开始做成全开放 autonomous loop。
先做：

- 最多 2 到 3 步工具链
- 支持 `search -> read -> write` 类模式
- 每步留下 trace：
  - `selected_tool`
  - `args`
  - `result_summary`
  - `why_next_step`

再加：

- `max_tool_rounds = 3`
- `repeated_tool_guard`
- `no_progress_guard`

### 风险提醒

如果执行层过弱，上层 orchestration 再漂亮，也难以在真实任务里展示优势。

---

## 4.5 P5. 状态变更接口 + 消息投影

### 当前状态

项目已经有明显的用户面和消息面意识：

- dialogue layer 已在
- task state / message projection 已有雏形
- 系统正在往“用户可见任务面”靠拢
- `pause/resume/continue/escalate/handoff/state` 已以 POST 写接口为正式路径
- 旧 GET 兼容入口仍保留审计标记
- `task_receipt / task_action / task_state / task_progress / task_result` 已形成核心 lifecycle projection
- `/dialogue/` 已有 task auto catch-up、message expansion、recovery state、人话化 provider diagnostics 等前端合同测试

### 估计完成度

**60% - 70%**

### 为什么还未完成

因为当前仍然有过渡态，但重心已经从“接口未正式化”转为“兼容入口治理和长尾投影治理”：

- 旧 GET 控制入口仍存在，需要迁移窗口和下线策略
- 长尾 runtime subtype 进入消息层前仍需要白名单和字段合同
- browser 级 lifecycle acceptance 仍要持续补强，尤其是真实 worker 慢响应/异步恢复场景

### 最大缺口

缺的不是“再多几个 UI 页面”，而是：

> task lifecycle 是否有一个稳定、统一、可回放的用户可见面。

### 下一步最具体动作

下一步不是再把 POST 接口“设想出来”，而是治理兼容期和长尾投影：

- 制定旧 GET 控制入口的下线策略
- 保留审计字段，观察外部调用方是否仍依赖 GET
- 对新增 runtime subtype 建立进入 message layer 的白名单
- 扩充浏览器级 lifecycle acceptance，覆盖 POST 控制动作、异步 recovery、human gate、terminal result 回放

### 风险提醒

如果 runtime 与 message layer 长期松耦合，后续会造成：

- UI 展示不稳定
- 回放困难
- 审计困难
- 用户面对系统状态的理解不一致

---

## 5. 能力成熟度排序

如果只从“当前已经做出来多少”看，成熟度大致是：

1. **P3 packet / checkpoint / handoff**
2. **P5 状态变更接口 + 消息投影**
3. **P4 tool-aware execution**
4. **P1 强模型调小模型闭环**
5. **P2 baseline experiment matrix**

如果只从“现在最该补什么”看，优先级则是：

1. **P1 强模型调小模型最小闭环**
2. **P2 baseline experiment matrix**
3. **P3 packet spec 固化**
4. **P4 tool-aware 多步执行**
5. **P5 接口与消息层收口**

这两个排序不同，恰好说明：

- 最成熟的，不一定是当前最关键的
- 当前最关键的，往往正是主价值链上最薄弱的那一环

---

## 6. 最终判断

当前 `agent-cloud-harness` 的状态可以概括为：

### 已经具备的

- continuity-first runtime 雏形
- control-plane 思路
- packet / checkpoint / handoff 基础
- 向用户面与可回放面演进的迹象

### 尚未证明的

- 强模型调小模型的主闭环
- orchestration 优于 baseline 的实验结果
- execution layer 在真实任务中的足够强度

### 最重要的战略含义

这意味着项目现在最不该继续扩更多概念，
而最该做的是：

> 把已有 continuity 与 control 基础，转成一个可以运行、可以比较、可以复盘、可以证明价值的 orchestration 主闭环。

只要这一闭环跑通，项目的整体定位会明显上一个台阶。
