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
- evaluation system 虽已有 matrix / run surface，但仍需要更稳定的对比闭环

---

## 3. 能力差距总表

| Priority | 能力项 | 当前状态 | 估计完成度 | 最大缺口 | 下一步最具体动作 |
|---|---|---:|---:|---|---|
| P1 | 强模型调小模型最小闭环 | 有基础但未闭环 | 20%-30% | 没有真实 strong planner -> small executor -> strong evaluator 路径 | 先固定一个单场景闭环，并把 model tier / selection reason 写进 trace |
| P2 | Baseline experiment matrix | 有 eval 意识但缺执行框架 | 10%-20% | 无三模式固定对比、无统一指标落盘 | 先建立 strong_only / small_only / orchestrated 三模式 run skeleton |
| P3 | Checkpoint / handoff packet spec | 概念最成熟但协议未封板 | 45%-60% | schema 边界不够正式，machine-readable first 不够稳定 | 冻结最小字段集，并让 runtime 输出与 schema 对齐 |
| P4 | Tool-aware 最小多步执行 | 已有真实执行雏形 | 55%-70% | 已有多步工具链、failure recovery、自动续跑与 trace，但“长任务如何自动收口成可信最终结果”仍不够硬 | 先把 `WorkerExecutionResult`、`ToolInvocationRecord`、checkpoint / judgment / live flow 聚合进一步收束成统一 hardness contract，并继续强化 long-task closure contract |
| P5 | 状态变更接口 + 消息投影 | 有用户面雏形但产品收口未完成 | 35%-50% | runtime / message layer 仍未完全统一，控制接口过渡态明显 | 把关键控制动作改成 POST/PATCH，并固定生命周期消息投影 |

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

**20% - 30%**

### 为什么不是更高

因为真正的主价值链条仍未闭环：

- 没有稳定的 strong planner -> small executor -> strong evaluator 执行路径
- 没有明确的 model tier trace
- 没有稳定的 fallback / escalation 原因可解释链
- 无法证明当前系统确实在按“强模型负责规划判断，小模型负责执行”这个主叙事运行

### 最大缺口

最大的缺口不是“没有更多 worker 类型”，而是：

> 缺少一个最小但真实可运行的分层协作闭环。

### 下一步最具体动作

先不要全面泛化，先固定一个最小场景：

- 强模型：task breakdown / judgment / acceptance
- 小模型：子任务执行

并至少在 trace 中记录：

- `selected_model_tier`
- `selected_worker`
- `why_selected`
- `fallback_reason`
- `evaluation_result`

### 风险提醒

如果这一层迟迟不落地，项目会长期停留在：

- continuity runtime 有潜力
- 但 orchestration 价值没有被证明

---

## 4.2 P2. Baseline experiment matrix

### 当前状态

项目已经明显有 evaluation 意识，且文档层面对“goal fit、场景、路径、价值证明”的关注已经存在。

这说明方向感是有的。

### 估计完成度

**10% - 20%**

### 为什么偏低

因为目前更多还是：

- 文档上的评估意识
- 方向性的评估规划

而不是：

- 固定模式
- 固定任务集
- 固定指标
- 可复跑结果落盘

### 最大缺口

当前缺的不是“更多评估理念”，而是：

> 一个最小可执行、可复跑、可对比的实验骨架。

### 下一步最具体动作

先建立三种固定模式：

- `strong_only`
- `small_only`
- `orchestrated`

并统一落盘：

- `completion_status`
- `acceptance_result`
- `total_cost`
- `handoff_count`
- `resume_count`
- `human_gate_count`
- `failure_reason`

任务集先从 3+3+3 开始：

- 3 个短任务
- 3 个中任务
- 3 个长任务

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

**45% - 60%**

### 为什么不是更高

因为虽然概念已经清楚，但协议层还未完全封板：

- schema 没有彻底冻结
- resume packet 与 handoff packet 的职责边界仍不够正式
- machine-readable first 的规范尚未完全统一

### 最大缺口

当前最大的缺口是：

> continuity object 已经存在，但还没有成为稳定的、可严格依赖的协议对象。

### 下一步最具体动作

优先冻结最小字段集。

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

说明执行层已经开始向“可操作系统”移动，而且已经能支持真实长任务的证据收集与多轮推进。

### 估计完成度

**55% - 70%**

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

### 估计完成度

**35% - 50%**

### 为什么还未完成

因为当前仍然可见明显过渡态：

- 控制动作接口仍不够正式
- runtime 与 message layer 尚未完全统一
- 投影规则还不是稳定协议

### 最大缺口

缺的不是“再多几个 UI 页面”，而是：

> task lifecycle 是否有一个稳定、统一、可回放的用户可见面。

### 下一步最具体动作

优先正式化关键控制动作：

- `POST /tasks/{id}/pause`
- `POST /tasks/{id}/resume`
- `POST /tasks/{id}/continue`
- `POST /tasks/{id}/escalate`

并固定关键生命周期消息：

- `task_receipt`
- `task_action`
- `task_state`
- `task_progress`
- `task_result`

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
