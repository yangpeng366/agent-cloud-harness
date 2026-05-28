# GOAL_ORIENTED_EVAL_PLAN

## 1. 目的

本文档用于把 `agent-cloud-harness` 的项目目标，转化为一套可验证、可对比、可复跑的目标导向评测计划。

目标不是继续泛化讨论“agent 很聪明”，而是直接回答下面这个问题：

> `agent-cloud-harness` 是否真的让强模型调度小模型，在长周期任务中更稳定地完成更大的任务，并提升小模型的有效能力表现？

因此，本文档重点关注：

- 强模型调度是否有效
- 小模型在 harness 中是否优于裸跑
- continuity 是否为长任务带来显著收益
- 成本与效果之间是否出现更优平衡

本文档默认建立在一个更小也更准确的当前态前提上：

> `agent-cloud-harness` 当前首先要被评测为一个 continuity-first orchestration harness；“强模型调度小模型”的命题则是这个 harness 近期最关键、最值得尽快证明的价值闭环。

建议与以下文档一起看：

- `ARCHITECTURE.md`
- `CURRENT_CAPABILITY_GAP_ASSESSMENT.md`
- `NEXT_5_ENGINEERING_PRIORITIES.md`
- `EVAL_SCENARIOS.md`
- `AGENT_PROVIDER_TECHNICAL_DESIGN.md`
- `AGENT_PROVIDER_API_CONTRACT_ADDENDUM.md`
- `ORCHESTRATION_MVP_PLAN.md`

---

## 1.1 近期验证边界

在进入“大任务长期收益”论证前，建议先把近端证明问题收紧为：

> harness 是否已经能让 `strong_only / small_only / orchestrated` 三种模式，在一组**小而真实**的仓库任务上形成可比较、可审计、可复跑的闭环？

这里的“小而真实”建议满足：

- 人工预估在 10-30 分钟内可完成
- 1-3 个文件范围内可验收
- 验收标准清楚，不依赖开放域主观判断
- 允许插入一次 pause / resume 或一次路由纠偏，以验证 continuity 与 orchestration 的真实价值

近期 proof 不要求一步证明“大任务完全自动化”，而是先证明：

- orchestrated 模式确实能闭环完成一批小任务
- strong / small 分工在这些任务上已有可见收益
- 系统已经能通过 `provider_selection / agent_run / live_flow / experiment_run` 留下足够证据链

---

## 2. 核心评测问题

建议围绕 4 个问题展开。

### Q1. 强模型调度小模型，是否比固定执行策略更有效？

要验证的不是“模型会不会选”，而是：

- 是否选对了 worker / model
- 是否在合适时机 handoff
- 是否能在错误路由后纠偏
- 调度是否带来更高成功率或更低成本

### Q2. 小模型在 harness 中，是否比裸跑表现更好？

要验证的是：

- 完成率是否提高
- 漂移率是否下降
- 中断后恢复率是否提高
- 在长链任务中是否更稳定

### Q3. continuity 机制，是否真的提升了长任务成功率？

要验证的是：

- pause / resume 后是否能稳定继续
- handoff 后是否仍保持任务面
- checkpoint 是否足够支撑恢复
- 长任务拉长后，系统优势是否更明显

### Q4. 分层协作后，是否能实现更优的成本-效果平衡？

要验证的是：

- 是否减少了高成本模型的使用占比
- 总成本是否下降
- 成本下降是否没有带来明显失真
- 长任务越大，这种收益是否越明显

---

## 3. 对比基线

如果没有 baseline，对“能力提升”和“成本优化”的判断就不成立。

因此至少要建立 3 组基线。

这里的 baseline 不应和当前已落地的 runtime / trace / packet / provider 观测能力脱节。更稳妥的做法是：

- 复用现有 `experiment_run / experiment_summary / harness_trace / provider_selection / agent_run` 观测面
- 先证明 strong_only / small_only / orchestrated 三模式在同一 harness 里可比较
- 再逐步增加任务集和 acceptance 复杂度

### B1. 强模型单独完成任务

定义：

- 使用高能力模型完成整个任务流程
- 不做大小模型分工，或只做最小必要流程控制

意义：

- 作为“质量上限 / 成本上限”参考

### B2. 小模型单独完成任务

定义：

- 使用低成本模型直接完成整个任务流程
- 不借助强模型调度与纠偏

意义：

- 作为“低成本下限 / 裸跑能力基线”参考

### B3. 强模型调度小模型

定义：

- 强模型承担规划、路由、验收、恢复、纠偏
- 小模型承担可分解执行任务
- harness 负责 continuity、checkpoint、handoff、audit

意义：

- 这是项目要验证的主方案

---

## 4. 评测维度

建议所有实验统一记录以下维度。

## 4.1 任务完成度

指标示例：

- 是否完成任务
- 完成是否符合预期验收标准
- 完成后是否还存在悬空 next_step

## 4.2 长任务稳定性

指标示例：

- 中断后恢复成功率
- handoff 后继续成功率
- 连续多阶段执行成功率
- 漂移 / 跑偏发生率

## 4.3 小模型能力放大程度

指标示例：

- 同一任务下，小模型裸跑成功率 vs harness 内成功率
- 小模型在被调度时的错误率变化
- 小模型在多阶段任务中的上下文保持能力变化

## 4.4 成本

指标示例：

- 总 token / 调用成本
- 高能力模型调用占比
- 单任务平均成本
- 每次成功完成任务的平均成本

## 4.5 人工介入负担

指标示例：

- 需要人工修正的次数
- 需要重新解释背景的次数
- human gate 触发频率
- 人工恢复后继续成功率

## 4.6 审计与可解释性

指标示例：

- 是否保留结构化 trace
- 是否能还原关键路径
- 是否能解释为什么切换 worker / model
- checkpoint / packet 是否能独立理解当前面

---

## 5. 任务集设计

要证明目标成立，任务不能只选很短的小任务。

建议设计三层任务集。

## 5.1 短任务集

特点：

- 1-2 步可完成
- 不太依赖 continuity
- 主要测试调度是否有额外开销

用途：

- 观察 harness 是否在简单任务上过度复杂化

## 5.2 中等复杂任务集

特点：

- 3-5 个阶段
- 需要至少一次判断、一次中间状态更新
- 可能出现信息不足或局部失败

用途：

- 测试调度、纠偏、验收机制是否开始产生价值

## 5.3 长周期复杂任务集

特点：

- 需要 5 个以上阶段
- 至少一次 pause / resume 或 handoff
- 至少包含一个需要强模型判断的决策点
- 不能轻易靠单轮提示完成

用途：

- 这是验证项目价值的主战场
- 最适合体现 continuity 和模型分工的收益

## 5.4 近期 proof 任务集

为尽快回答近端问题，建议先固定一组更容易复跑的小型真实任务切片：

- 文档增量整理：例如补一段约束、统一术语、补 cross-link，并要求改动范围可控
- 单接口/单文件低风险修补：例如小范围 handler/contract/脚本修正，并有明确验收点
- 带一次恢复动作的小任务：在前两类任务中人为插入一次 `pause/resume` 或一次轻量 handoff

这组任务的价值不在于“难”，而在于：

- 足够真实，不是纯合成 benchmark
- 足够小，便于用三种 `model_mode` 快速对比
- 足够可验收，能形成第一版闭环证据

## 5.5 当前内置 baseline case 合同

当前代码层已经提供第一版 `baseline_matrix_v1` case catalog：

- 3 个 `short`
- 3 个 `medium`
- 3 个 `long`
- 每个 case 都会在 `strong_only / small_only / orchestrated` 三种 `model_mode` 下创建可比较 task

这些 case 不再只依赖 title / intent / goal。`GET /api/v1/experiment_matrix/cases` 暴露的每个 `BaselineTaskCase` 还必须带：

- `workspace_preconditions`：运行前置条件，例如当前 worktree、临时 SQLite、架构边界
- `acceptance_criteria`：可人工或脚本审计的最小通过标准
- `expected_artifacts`：期望产物类别，例如 `fix_plan`、`regression_assertion`、`acceptance_gate`
- `recovery_policy`：该长度 bucket 允许的 retry / handoff / human gate 边界

`POST /api/v1/experiment_matrix/runs` 创建 task 时，也会把同一份合同同步写入 task metadata：

- `baseline_workspace_preconditions`
- `baseline_acceptance_criteria`
- `baseline_expected_artifacts`
- `baseline_recovery_policy`

这一步把 P2 缺口从“没有 3+3+3 任务集”推进为更具体的问题：

- case 已有内置合同
- 仍缺真实仓库任务的一轮正式复跑结果
- 仍缺成本口径与 acceptance gate 自动判定
- 已有最小 HTTP release-gate 探针：`scripts/Run-BaselineMatrixGateProbe.ps1`
- 仍缺真实 worker 执行后的质量 gate 和成本 gate

---

## 6. 推荐最小实验集

## E0. 小型真实任务闭环证明

### 目标

先证明 harness 已经能在近端任务上跑出可审计闭环，而不是只证明控制图能转。

### 对比组

- `strong_only`
- `small_only`
- `orchestrated`

### 任务范围

- 优先先跑内置 `baseline_matrix_v1` 的 3+3+3 catalog
- 如只做冒烟，可先选 `short-001 / medium-001 / long-001`
- 每个任务都必须保留 `baseline_acceptance_criteria` 与 `baseline_recovery_policy`
- 后续再把 `5.4` 中的新真实任务追加为 `baseline_matrix_v2`

### 最小 release-gate 探针

当前可以先用不触发真实 worker 的 HTTP 探针验证 baseline matrix 接面是否仍可作为 release gate 起点：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Run-BaselineMatrixGateProbe.ps1 `
  -BaseUrl http://localhost:8080 `
  -ExperimentName baseline-gate-smoke `
  -ReportPath .tmp\baseline-matrix-gate-probe.json
```

该探针会验证：

- `/api/v1/health` 可用
- `/api/v1/experiment_matrix/cases` 返回 3+3+3 catalog
- 每个 case 都带 workspace / acceptance / artifact / recovery 合同
- `/api/v1/experiment_matrix/runs` 能创建 `short-001 / medium-001 / long-001` 的三模式冒烟 run
- 创建出的 task metadata 保留 baseline 合同
- `/api/v1/experiment_matrix/summary` 能按 mode 和 case 聚合这 9 条 run

2026-05-19 已在隔离实例 `http://localhost:18084` 验证通过，报告：

- `.tmp/baseline-matrix-gate-probe-20260519.json`
- `catalog_case_count = 9`
- `created_run_count = 9`
- `summary_total_runs = 9`

注意：这条 gate 只证明 baseline matrix 的 HTTP 合同、case 合同和 summary 骨架可复跑；它不证明真实 worker 已完成任务，也不证明 acceptance quality gate 或 cost gate 已闭环。

### 必看观测

- `/api/v1/tasks/{id}/provider_selection`
- `/api/v1/tasks/{id}/agent_run`
- `/api/v1/tasks/{id}/live_flow`
- `/api/v1/tasks/{id}/experiment_run`

### 希望看到的结果

- orchestrated 模式能稳定闭环完成多数小任务
- small_only 在这些任务上更容易暴露漂移或验收不足
- strong_only 可作为质量参考，但成本更高
- 三组结果都能留下结构化证据，而不只是人工印象

## E1. 单模型 vs 分层协作

### 目标

验证强模型调小模型，是否比单模型方案更有综合优势。

### 对比组

- 强模型单独完成
- 小模型单独完成
- 强模型调度小模型

### 关注指标

- 完成率
- 成本
- 阶段数增长后的稳定性
- 人工介入次数

### 希望看到的结果

- 主方案成本显著低于强模型单跑
- 主方案完成率显著高于小模型单跑
- 主方案在长任务上保持更好的稳定性

---

## E2. 小模型裸跑 vs harness 内执行

### 目标

验证 harness 是否真的提升了小模型的有效能力表现。

### 对比组

- 小模型直接执行全部任务
- 小模型在强模型调度 + checkpoint + handoff + 验收下执行

### 关注指标

- 成功率
- 漂移率
- 恢复率
- 多阶段任务的一致性

### 希望看到的结果

- 小模型在 harness 中更稳
- 即使犯错，也更容易被纠偏并继续
- 长任务中不容易因上下文丢失而崩掉

---

## E3. 中断恢复实验

### 目标

验证 continuity 是否是长任务完成的关键增益。

### 对比组

- 无 checkpoint / 弱恢复能力
- 有 checkpoint / resume / packet 恢复能力

### 关注指标

- 中断后继续成功率
- 恢复所需人工说明量
- 恢复后跑偏率
- 恢复耗时

### 希望看到的结果

- 带 continuity 的系统在长任务中明显更稳
- 中断不会导致整体返工

---

## E4. 路由质量实验

### 目标

验证强模型的路由与切换决策是否真的有效。

### 对比组

- 固定 worker 执行
- 简单规则路由
- 强模型决策路由

### 关注指标

- 路由正确率
- 错误路由修正率
- 成功率差异
- 路由额外成本

### 希望看到的结果

- 强模型路由在复杂任务中显著优于固定策略
- 即使成本稍高，也带来更高的成功率或更低返工率

---

## 7. 验收标准建议

为了避免一直停留在“感觉不错”，建议先设一个最小验收门槛。

## 7.1 对项目目标的最小成立标准

在进入中长任务结论前，建议先加一个更近端的入口门槛：

- 至少 3 个小型真实任务在 `orchestrated` 模式下完成闭环，且可从 `provider_selection / agent_run / live_flow` 还原关键路径

至少满足以下 3 条中的 2 条：

1. 强模型调度小模型方案的完成率明显高于小模型裸跑
2. 强模型调度小模型方案的总成本明显低于强模型全程直跑
3. 在长周期任务中，带 continuity 的主方案恢复率、handoff 成功率明显更高

## 7.2 对“小模型能力被提升”的最小成立标准

至少出现以下现象中的多数：

- 小模型在 harness 中成功率提高
- 小模型在中断后恢复能力增强
- 小模型在长链任务中漂移率下降
- 小模型更少需要人工重新解释背景

## 7.3 对“更大任务可完成”的最小成立标准

随着任务阶段增加：

- 小模型单跑退化明显
- 主方案退化速度更慢
- 主方案在长任务中优势大于短任务

---

## 8. 数据记录建议

建议每次实验统一记录如下字段：

- task_id
- task_type
- task_length_bucket
- model_mode（强模型单跑 / 小模型单跑 / 强模型调小模型）
- total_steps
- completion_status
- acceptance_result
- total_cost
- strong_model_cost_ratio
- handoff_count
- resume_count
- human_gate_count
- failure_reason
- recovery_success
- final_artifact_quality_note
- strong-to-small 闭环证据：
  - runs_with_strong_planner_evidence
  - runs_with_small_executor_evidence
  - runs_with_strong_evaluator_evidence
  - runs_with_strong_small_strong_loop
  - evaluator_model_tier_counts

这些 strong-to-small 字段必须来自 harness 自动落盘的运行时证据：
`planner_worker/planner_model_tier`、`executor_worker/executor_model_tier` 与 completion judgment 的
`evaluator_model_tier`。实验记录中不要用人工备注补齐这些字段，否则 matrix summary 会把真实闭环能力和验收后判断混在一起。

这样后续才能做真实对比，而不是只留下印象。

---

## 9. 风险与注意事项

## 9.1 不要把所有提升都归功于模型调度

一些提升可能来自：

- 更严格的任务分解
- 更好的上下文打包
- 更清晰的验收标准
- 更可恢复的状态管理

这其实不是坏事，反而说明 harness 作为系统层是有效的。

但评估时要尽量区分：

- 提升来自强模型判断
- 提升来自 continuity 机制
- 提升来自更好的结构化流程

## 9.2 短任务上未必能体现优势

短任务往往不需要复杂控制层。

因此：

- 短任务不一定能体现主方案优势
- 不能因为短任务收益不明显就否定整套系统
- 重点要看中长任务的曲线变化

## 9.3 不要只看成功率，不看单位成本

如果主方案成功率略高，但成本暴涨，则商业价值可能不成立。

因此必须同时看：

- 完成率
- 成本
- 人工介入负担
- 长任务稳定性

---

## 10. 最终建议

如果要真正回答“项目是否满足目标”，接下来不该只继续写定位文档，而应该进入目标导向评测阶段。

最优先建议：

1. 固定 1 组长周期复杂任务集
2. 建立 3 个 baseline（强模型单跑 / 小模型单跑 / 强模型调小模型）
3. 先跑 E1、E2、E3 三组最小实验
4. 把结果沉淀成第一版目标验证报告

如果这一步做出来，项目就能从：

- 方向很对

走到：

- 目标开始被证据支持

这会是整个 `agent-cloud-harness` 从“好想法”走向“可证明价值”的关键拐点。
