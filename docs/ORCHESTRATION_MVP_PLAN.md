# ORCHESTRATION_MVP_PLAN

## 1. 目的

本文档用于把 `agent-cloud-harness` 下一阶段最关键的工程目标收敛成一个真正可执行的 MVP 方案。

它不试图一次性定义完整的 agent 平台路线，也不试图解决所有多 agent / memory / protocol 问题。

它只回答一个最关键的问题：

> 如何用最小范围证明 `agent-cloud-harness` 真的能让强模型调度小模型完成更长周期、可恢复、可交接的任务执行。

这份文档的目标是把项目从“方向判断正确”推进到“主价值链开始可运行、可验证、可复盘”。

---

## 2. MVP 的核心目标

第一版 MVP 不追求通用性，追求的是：

- 跑通一条最小但真实的 orchestration 主链路
- 能明确区分强模型与小模型的职责
- 能在 runtime / trace / message / packet 中看见这种分工
- 能与 baseline 模式进行对比

用一句话概括，MVP 的核心目标是：

> 证明 `agent-cloud-harness` 不只是 continuity runtime，而是一个能够让强模型做规划判断、小模型做执行推进的 orchestration runtime。

---

## 3. 第一版不做什么

为了避免范围失控，第一版明确**不做**以下内容：

- 不做通用多 agent 网络
- 不做 A2A / ACP / MCP 标准协议实现
- 不做全任务类型支持
- 不做完整 autonomous agent loop
- 不做复杂 graph memory / long-term memory 平台
- 不做完全自动化的记忆管理系统
- 不追求一次性解决所有 worker / tool / routing 问题

如果第一版同时试图覆盖这些目标，项目会继续停留在“大方向很对，但闭环迟迟不落地”的状态。

---

## 4. 第一版只做什么

第一版只做一条最小主链路：

## `strong planner -> small executor -> strong evaluator`

也就是：

1. **强模型负责规划**
   - 读取任务
   - 进行 task breakdown
   - 决定交给哪个执行 worker
   - 生成最小执行边界

2. **小模型负责执行**
   - 在明确边界内完成一个局部子任务
   - 产出结果、状态、证据、异常说明

3. **强模型负责验收**
   - 判断是否达成要求
   - 判断是否继续下一步
   - 判断是否需要重试、回退、换 worker 或升级人工

4. **系统负责 continuity control**
   - 保留 runtime context
   - 记录 trace
   - 输出 checkpoint / packet
   - 支持失败后的恢复与交接

这就是第一版需要证明的全部。

---

## 5. 第一版场景选择原则

MVP 不能依赖“最复杂任务”来证明自己，应选择：

- 有明确目标
- 可以拆成 2-3 个阶段
- 中间容易出现恢复 / 交接需求
- 结果可验收
- 强弱模型分工有现实意义

### 推荐场景特征

优先选择这样的任务：

- 强模型适合做任务理解、拆解、质量判断
- 小模型适合做局部执行、草稿生成、结构化整理、低成本迭代
- 任务不是一句话就能完成
- 任务结果可以用简单 acceptance 规则判断

### 不推荐的第一版场景

- 纯聊天型任务
- 无法定义成功标准的开放性任务
- 需要大量外部系统联动的复杂流程
- 全靠工具链强度才能成立的任务

### 更合适的第一版场景模板

例如：

- 强模型拆解一个中等复杂任务
- 小模型完成其中一个或两个明确子步骤
- 强模型对结果验收并决定是否进入下一阶段

关键不是任务名字，而是：

> 是否能清楚展示“强模型负责判断，小模型负责执行”的价值分工。

---

## 6. MVP 中的角色分工

第一版建议显式引入四类角色。

### 6.1 Planner（强模型）

职责：

- 读取任务目标
- 生成 task breakdown
- 判断当前阶段目标
- 选择执行 worker / model tier
- 决定最小执行边界

输出内容建议包括：

- 当前阶段目标
- 本轮执行子任务
- 预期产出
- 验收条件
- 为什么选择该 worker

### 6.2 Executor（小模型）

职责：

- 在给定边界内完成子任务
- 输出结果、证据、异常、未完成项
- 不承担全局规划责任

输出内容建议包括：

- 执行结果
- 结果摘要
- 使用的步骤 / 工具
- 未完成项
- 遇到的问题

### 6.3 Evaluator（强模型）

职责：

- 判断执行结果是否满足要求
- 判断是否可以继续下一阶段
- 判断是否需要重试 / 改派 / 升级

输出内容建议包括：

- acceptance result
- reason
- next step
- retry / fallback / escalate decision

### 6.4 Recovery / Router（强模型或规则层）

职责：

- 当执行失败或结果不足时决定：
  - 是否重试
  - 是否改派 worker
  - 是否回退阶段
  - 是否升级人工

第一版可以先不完全独立成新节点，但要在 trace 中体现该决策逻辑。

---

## 7. 运行时必须新增或稳定化的字段

为了让 orchestration 闭环真正“可见”，runtime / trace 至少要补齐以下字段。

### 7.1 选择相关

- `selected_model_tier`
- `selected_worker`
- `why_selected`
- `selection_scope`（可选，例如 planner / executor / evaluator）

### 7.2 执行结果相关

- `execution_result_summary`
- `execution_status`
- `evidence_refs`
- `unfinished_items`

### 7.3 验收与回退相关

- `evaluation_result`
- `evaluation_reason`
- `fallback_reason`
- `retry_decision`
- `escalation_decision`

### 7.4 continuity 相关

- `current_phase`
- `next_step`
- `blockers`
- `open_questions`
- `latest_checkpoint_id`

这些字段的意义不是“让日志更丰富”，而是：

> 让系统能够明确回答，这一轮为什么交给这个模型，它做了什么，结果是否合格，下一步为什么这么走。

---

## 8. Trace、Packet、Message 三层需要补的可见性

MVP 不只是要“内部跑通”，还要让闭环过程可观测、可回放、可复盘。

### 8.1 Trace 层

Trace 里至少应可见：

- 本轮角色（planner / executor / evaluator）
- selected model tier
- selected worker
- why selected
- execution summary
- evaluation result
- fallback / retry / escalate reason

### 8.2 Packet / Checkpoint 层

Checkpoint / packet 至少应反映：

- 当前在哪个阶段
- 最近由谁执行
- 已完成什么
- 尚未完成什么
- 下一步是谁做什么
- 如果恢复，应恢复到哪一个边界

### 8.3 Message / User-facing 层

对用户可见的消息面至少应投影出：

- 当前阶段开始
- 已派发给哪个类型的 worker
- 执行已完成 / 未完成
- 是否进入下一步
- 是否触发重试 / 交接 / 升级

这不是为了做花哨 UI，而是为了让 `/dialogue/` 真正成为任务生命周期的稳定回放面。

---

## 9. 第一版最小流程建议

建议先实现一个严格受控的最小流程：

### Step 1. Receive task
- 创建 task / session
- 进入 planning

### Step 2. Strong planner
- 生成当前阶段目标
- 生成单个子任务
- 选择小模型 executor
- 写入 selection trace

### Step 3. Small executor
- 执行单个子任务
- 返回结果摘要、证据、未完成项
- 写入 execution trace

### Step 4. Strong evaluator
- 判断是否达标
- 决定 continue / retry / fallback / escalate
- 写入 evaluation trace

### Step 5. Continuity write-back
- 生成 checkpoint / summary / next_step
- 如未完成，留下可恢复边界

### Step 6. Optional next round
- 若继续，进入下一阶段
- 若失败，进入 recovery / escalation
- 若完成，写 result

第一版甚至可以只要求：

- 至少完成一轮 planner -> executor -> evaluator
- 并在必要时进入一次 retry / fallback

这样就已经能证明关键结构存在。

---

## 10. 第一版验收标准

MVP 是否成立，不看文档多少，而看是否满足以下标准。

### 10.1 主链路验收

至少有一条任务链能够稳定完成：

- strong planner
- small executor
- strong evaluator

三段角色分工清晰可见。

### 10.2 可见性验收

trace / message / packet 中能够明确看到：

- 谁负责规划
- 谁负责执行
- 谁负责验收
- 为什么这么选
- 失败时为什么 fallback / retry / escalate

### 10.3 continuity 验收

若任务未完成，系统能够留下：

- current phase
- latest summary
- next step
- blockers / open questions
- resume boundary

### 10.4 baseline 对比验收

同类任务至少能比较三种模式：

- `strong_only`
- `small_only`
- `orchestrated`

并至少记录：

- completion status
- acceptance result
- total cost
- retry / handoff / resume count

### 10.5 价值判断验收

MVP 需要至少能支持下面其中一种结论：

- orchestration 成本更低但完成度相近
- orchestration 在长任务连续性上明显更稳
- orchestration 在需要恢复 / 交接的任务上明显优于 baseline

如果完全无法支持任何一条价值判断，就说明 MVP 还不算真正成立。

---

## 11. 与现有优先级的关系

这份 MVP 方案并不替代前面的优先级文档，而是把优先级收束成第一刀。

它与前述五项优先级的关系如下：

- **P1** 是这份文档的主轴
- **P2** 为这份文档提供对比验证骨架
- **P3** 为这份文档提供 checkpoint / packet 地基
- **P4** 作为执行层增强项逐步接入
- **P5** 作为用户面与产品化收口逐步补强

也就是说：

> ORCHESTRATION_MVP_PLAN 是把 P1 具体化，并借助 P2-P5 支撑它落地的执行文档。

---

## 12. 推荐实施顺序

建议按以下顺序实现，而不是并行扩散：

1. 选定一个 MVP 场景
2. 固定 planner / executor / evaluator 三段角色
3. 补齐 runtime/trace 关键字段
4. 跑通单条 orchestrated 流程
5. 补最小 checkpoint / packet write-back
6. 建立 strong_only / small_only / orchestrated 三模式对比
7. 再逐步增强 tool-aware、多步执行、消息投影

这个顺序的核心是：

- 先证明主闭环存在
- 再证明它值得继续投入

---

## 13. 最终结论

`agent-cloud-harness` 当前最需要的，不是更多抽象层，也不是更大的平台叙事。

当前最需要的是：

> 用一个小而硬的 MVP，证明强模型调小模型的 orchestration 主闭环真的能跑、能看见、能恢复、能对比、能复盘。

这就是 `ORCHESTRATION_MVP_PLAN` 的意义。

只要这条主线跑通，项目就会从“有潜力的 continuity runtime”明显升级为“有证明路径的 orchestration runtime”。
