# Continuity / Control Plane

本专题覆盖控制面主链、continuity、packet、checkpoint、goal loop、runtime context、active context，以及多轮续跑相关文档。

当前 `continuity/` 已升级到轻量工作区：除 `README.md` 外，已启用 `PROGRESS.md`，并新增 `runs/README.md` 作为 dated execution evidence 聚合入口，用来承接控制面主链、packet、goal loop、live flow 与多轮续跑的持续推进。当前默认阅读顺序是 `README.md -> PROGRESS.md -> 当前子线文档 -> runs/README.md`；`tasks/`、`archive/` 仍未启用。

当前 continuity 主题内部也已经不止一条线，不要把所有 `HARNESS_*`、`GOAL_*`、`RUNTIME_*`、`MULTI_ROUND_*` 文档都当成同层主线。先判断当前任务属于哪一类，再进入对应子主题：

- 稳定控制面基线与合同
- continuity / packet / runtime-memory 主线
- goal loop / agent action / bounded autonomy 设计线
- live validation / 多轮执行 / runbook 证据线
- provider partial-result / 本地 agent 重设计这类跨主题诊断线

## 命中信号

- 任务提到 `control node`、`pause/resume/handoff`、`packet`、`checkpoint`
- 任务提到 `runtime context`、`active context`、`goal loop`、`continuity`
- 任务是在看控制面主链、多轮续跑、状态机或聚合诊断

## 先做子主题判断

| 当前问题 | 先看哪里 | 再下钻 |
|------|------|------|
| 今天控制图、packet、checkpoint、control action、runtime 聚合到底以什么合同为准 | `../ARCHITECTURE.md` | `../SPEC.md`、`../API_CONTRACTS.md`、`../HARNESS_CHANGE_CONTRACT.md` |
| 要改 continuity、packet、active context、progress accumulation、checkpoint/refined packet 语义 | `../AGENT_CLOUD_HARNESS_EXECUTION_CONTINUITY_MEMORY_FLOW.md` | `../PROGRESS_ACCUMULATION_LANDING_PLAN.md`、`../M02_PACKET_SCHEMA_EXECUTION_RECORD_2026-06-30.md` |
| 要推进 goal loop、agent action、bounded autonomy、长周期 outer loop 设计 | `../GOAL_LOOP_LANDING_PLAN.md` | `../AGENT_ACTION_MODEL_DRAFT.md`、`../BOUNDED_AUTONOMY_REFACTOR_V1.md` |
| 要跑真实控制面链路、multi-round 任务、live flow、pause/resume/handoff 验证 | `../LIVE_FLOW_RUNBOOK.md` | `../TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`、`../PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`、`../MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md` |
| 要处理 provider 502、partial result、artifact 已落盘但 Dialogue 只显示失败、本地 agent 执行链重设计 | `../HARNESS_REDESIGN_FOR_LOCAL_AGENTS.md` | `../LIVE_FLOW_RUNBOOK.md`、`../TROUBLESHOOT.md` |
| 要回看 packet / control-route / multi-round / closure 的真实 execution evidence | `runs/README.md` | 再进入对应 dated record，并把仍然有效的结论回收到 runbook / 基线 / `PROGRESS.md` |

## 最小阅读顺序

1. `PROGRESS.md`
2. `../ARCHITECTURE.md`
3. `../SPEC.md`
4. `../API_CONTRACTS.md`
5. `../LIVE_FLOW_RUNBOOK.md`
6. `../TROUBLESHOOT.md`
7. 如果任务已经明确是在查 dated execution evidence，转到 `runs/README.md`。
8. 其余情况再按上面的子主题判断进入对应文档，不需要把所有 continuity 相关设计稿全文扫一遍。

## 稳定基线

- `../ARCHITECTURE.md`
- `../SPEC.md`
- `../API_CONTRACTS.md`
- `../LIVE_FLOW_RUNBOOK.md`
- `../TROUBLESHOOT.md`
- `../HARNESS_CHANGE_CONTRACT.md`

这些文档更接近“今天仍然为真”的控制面结构、行为语义、验证入口和改动边界。若本轮改动改变了状态机、packet 合同、验证链或回归解释口径，优先回写这里。

## 当前主线文档

### 主题进度

- `PROGRESS.md`

### Continuity / Packet / Runtime-Memory 主线

- `../AGENT_CLOUD_HARNESS_EXECUTION_CONTINUITY_MEMORY_FLOW.md`
- `../PROGRESS_ACCUMULATION_LANDING_PLAN.md`

### Goal Loop / Agent Action / Bounded Autonomy

- `../GOAL_LOOP_LANDING_PLAN.md`
- `../LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md` — 下一阶段方向主入口：Loop 主闭环 + Goal 合同 + 交接 packet + UI 状态与结果
- `../NEXT_EVOLUTION_PLAN.md` — 下一阶段演进计划：Loop Decide 消费 Goal Progress + 端到端验证 + UI Loop Activity
- `../AGENT_ACTION_MODEL_DRAFT.md`
- `../BOUNDED_AUTONOMY_REFACTOR_V1.md`

### Live Validation / Multi-Round Execution

- `../LIVE_FLOW_RUNBOOK.md`
- `../PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
- `../TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
- `../MULTI_ROUND_TASK_EXECUTION_RECORD_TEMPLATE.md`
- `../MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`

### Cross-Topic Redesign / Diagnostic Slice

- `../HARNESS_REDESIGN_FOR_LOCAL_AGENTS.md`

### Dated Execution Evidence 聚合入口

- `runs/README.md`

## 验证与证据
- `../CCX_INTEGRATION_PRECHECK_EXECUTION_RECORD_2026-07-22.md`
- `../P2_E2E_INTEGRATION_SMOKE_EXECUTION_RECORD_2026-07-22.md`

- `../M02_PACKET_SCHEMA_EXECUTION_RECORD_2026-06-30.md`
- `../M03_LEGACY_GET_CONTROL_ROUTE_EXECUTION_RECORD_2026-06-30.md`
- `../M01_O03_MULTI_ROUND_EXECUTION_RECORD_2026-06-15.md`
- `../O04_LONG_TASK_CLOSURE_EXECUTION_RECORD_2026-06-15.md`

## 写回顺序

- 主题级短进展、当前焦点、未完成/下一步/风险：
  - 优先写 `PROGRESS.md`

- 控制节点、状态机、runtime 聚合、packet 最小面变化：
  - 优先写 `ARCHITECTURE.md`、`SPEC.md`、`API_CONTRACTS.md`
  - 需要明确改动边界时同步 `HARNESS_CHANGE_CONTRACT.md`
- continuity、packet、checkpoint、active context、progress accumulation 变化：
  - 优先写 `AGENT_CLOUD_HARNESS_EXECUTION_CONTINUITY_MEMORY_FLOW.md`
  - 或 `PROGRESS_ACCUMULATION_LANDING_PLAN.md`
- goal loop、agent action、bounded autonomy、outer loop 设计变化：
  - 优先写 `GOAL_LOOP_LANDING_PLAN.md`
  - 或 `AGENT_ACTION_MODEL_DRAFT.md`、`BOUNDED_AUTONOMY_REFACTOR_V1.md`
- live flow、多轮任务、pause/resume/handoff 验证链变化：
  - 优先续写 `LIVE_FLOW_RUNBOOK.md`
  - 需要时同步 `TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`、`MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`
- 需要保留一次真实验证、focused regression、multi-round 收口证据：
  - 写 dated `*_EXECUTION_RECORD_YYYY-MM-DD.md`
  - 同步把入口补进 `runs/README.md`
  - 再把跨主题摘要压缩到 `../STATE.md`
- provider partial-result、本地 agent 重设计、artifact 展示断链这类跨主题问题：
  - 优先写 `HARNESS_REDESIGN_FOR_LOCAL_AGENTS.md`
  - 必要时再同步 `../TROUBLESHOOT.md`

## 历史材料

- `../HARNESS_EVOLUTION.md`
- `../ORCHESTRATION_MVP_PLAN.md`
- `../GOAL_RUNTIME_LANDING_DIFF_2026-05-11.md`
- `../HARDNESS_PHASE1_ALIGNMENT.md`
- `../HARDNESS_PHASE1_IMPLEMENTATION_ROADMAP.md`
- `../HOW_AGENT_CLOUD_HARNESS_FITS_MEMORY_ARCHITECTURES.md`
- `../EXECUTION_ENVELOPE_PHASE1_STATUS.md`
- `../LIVE_FLOW_TRACE.md`

这些材料仍可参考，但默认不作为新任务第一入口。若其中结论仍有效，应优先回收到稳定基线或当前主线文档。

## 历史材料使用规则

- `HARNESS_EVOLUTION.md`、`ORCHESTRATION_MVP_PLAN.md`、`HOW_AGENT_CLOUD_HARNESS_FITS_MEMORY_ARCHITECTURES.md` 更适合解释“为什么当时往这个方向设计”，不应用来替代今天的控制面基线。
- 需要在多份 control-plane execution record 之间切换时，先从 `runs/README.md` 进入，不要在 root-level 长名单里猜。
- `GOAL_RUNTIME_LANDING_DIFF_2026-05-11.md`、`HARDNESS_PHASE1_ALIGNMENT.md`、`HARDNESS_PHASE1_IMPLEMENTATION_ROADMAP.md` 保留的是“当时如何把外部 blueprint 压回当前代码”的过程，只有在做差异对比或回看历史方案时再进入。
- `EXECUTION_ENVELOPE_PHASE1_STATUS.md`、`LIVE_FLOW_TRACE.md` 是旧状态快照和原始 trace，不应替代当前 `LIVE_FLOW_RUNBOOK.md` 或 `TROUBLESHOOT.md`。
- `HARNESS_REDESIGN_FOR_LOCAL_AGENTS.md` 虽然仍在当前主线可用，但它是跨主题的重设计/诊断材料；只有任务明确落在 provider output、partial result、artifact 展示断链或本地 agent 执行链重设计时才优先进入。

## 当前入口建议

- 要先看最近活跃焦点和风险：`PROGRESS.md`
- 要理解控制面主链和装配边界：先看 `../ARCHITECTURE.md`
- 要改状态机或行为语义：先看 `../SPEC.md`
- 要改 continuity / packet / context reconstruction：先看 `../AGENT_CLOUD_HARNESS_EXECUTION_CONTINUITY_MEMORY_FLOW.md`
- 要推进 goal / autonomy 外层设计：先看 `../GOAL_LOOP_LANDING_PLAN.md`
- 要做 packet / live flow / 多轮续跑验证：先看 `../LIVE_FLOW_RUNBOOK.md`
- 要处理 provider 结果断链或本地 agent 重设计：先看 `../HARNESS_REDESIGN_FOR_LOCAL_AGENTS.md`
- 要回看 control-plane execution evidence：`runs/README.md`
