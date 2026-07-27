# Evaluation / Priorities / Multi-round Tasks

本专题覆盖项目评估、工程优先级、多轮任务包、测试驱动推进计划、执行 runbook 与 dated execution record。

当前 `evaluation/` 已升级到轻量工作区：除 `README.md` 外，已启用 `PROGRESS.md`，并新增 `runs/README.md` 作为 dated execution evidence 聚合入口，用来承接项目评估、工程优先级、多轮任务包、matrix 与执行证据的持续推进。当前默认阅读顺序是 `README.md -> PROGRESS.md -> 当前子线文档 -> runs/README.md`；`tasks/`、`archive/` 仍未启用。

当前 evaluation 主题内部也已经不止一条线，不要把“优先级 / 评测 / 对标 / 产品化 / 多轮任务链 / dated 执行证据”都当成同层主线。先判断当前任务属于哪一类，再进入对应子主题：

- 能力差距、项目评价、工程优先级
- 目标导向评测与评估场景
- 对标、借鉴、产品化与 go-to-market
- 多轮任务包、测试驱动推进、执行 runbook
- dated execution record / 专项历史设计

## 命中信号

- 任务提到 priority、roadmap、gap、phase、next actions
- 任务提到多轮任务包、baseline matrix、focused regression、execution record
- 任务不是直接改某个模块，而是在判断“现在最该做什么”或“该怎么验证”

## 先做子主题判断

| 当前问题 | 先看哪里 | 再下钻 |
|------|------|------|
| 现在最缺什么、能力差距在哪里、下一阶段最该做什么 | `../CURRENT_CAPABILITY_GAP_ASSESSMENT.md` | `../PROJECT_EVALUATION_AND_NEXT_PLAN.md`、`../NEXT_5_ENGINEERING_PRIORITIES.md`、`../PHASE2_ROADMAP.md` |
| 要验证“强模型调小模型”“continuity-first orchestration harness”这些目标是否成立 | `../GOAL_ORIENTED_EVAL_PLAN.md` | `../EVAL_SCENARIOS.md`、`../PROJECT_GOAL_FIT_EVALUATION.md`、`../AGENT_EVAL_AND_REFERENCES.md` |
| 要看 Multica 对标、借鉴路径、产品化、对外叙事与 go-to-market | `../MULTICA_BENCHMARK_AND_BORROWING_PLAN.md` | `../GO_TO_MARKET_AND_PRODUCTIZATION_PLAN.md` |
| 要把真实需求变成 harness 多轮任务、测试计划、可执行 runbook | `../PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md` | `../TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`、`../MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`、`../MULTI_ROUND_TASK_EXECUTION_RECORD_TEMPLATE.md` |
| 要复查某一轮真实执行证据或专项长任务历史设计 | `runs/README.md` | 再进入对应 dated `*_EXECUTION_RECORD_YYYY-MM-DD.md`，若结论仍有效，再回收到当前评估主线或 `../STATE.md` |

## 最小阅读顺序

1. `PROGRESS.md`
2. `../CURRENT_CAPABILITY_GAP_ASSESSMENT.md`
3. `../PROJECT_EVALUATION_AND_NEXT_PLAN.md`
4. `../NEXT_5_ENGINEERING_PRIORITIES.md`
5. `../PHASE2_ROADMAP.md`
6. 如果任务已经明确是在看 dated 执行证据，转到 `runs/README.md`。
7. 其余情况再按上面的子主题判断进入对应文档，不需要把所有 eval/priority/benchmark/task-pack 文档全文扫一遍。


### P2 端到端集成验证证据

- `../CCX_INTEGRATION_PRECHECK_EXECUTION_RECORD_2026-07-22.md` — CCX precheck（health + models + completion）全 PASS
- `../P2_E2E_INTEGRATION_SMOKE_EXECUTION_RECORD_2026-07-22.md` — Harness -> CCX -> LLM -> Loop -> Decide 端到端闭环全 PASS
- `../CCX_RND_CASE_DEBUG_EXECUTION_RECORD_2026-07-25.md` - CCX+harness 真实研发案例调试（3 案例：openclaw-native 伪完成 vs codex 真成功对比 + 最佳实践）

## 稳定基线

- `../CURRENT_CAPABILITY_GAP_ASSESSMENT.md`
- `../PROJECT_EVALUATION_AND_NEXT_PLAN.md`
- `../NEXT_5_ENGINEERING_PRIORITIES.md`
- `../LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md` — 下一阶段方向主入口：取代原 P1–P5 排序口径（Loop / Goal / 交接 / UI 状态）
- `../PHASE2_ROADMAP.md`

这些文档更接近“今天仍然为真”的项目评价、能力差距、工程优先级与阶段推进口径。若本轮改动改变了项目主叙事、当前 gap 判断或阶段顺序，优先回写这里。

## 当前主线文档

### 主题进度

- `PROGRESS.md`

### 能力差距 / 项目评价 / 工程优先级

- `../PROJECT_EVALUATION_AND_NEXT_PLAN.md`
- `../CURRENT_CAPABILITY_GAP_ASSESSMENT.md`
- `../NEXT_5_ENGINEERING_PRIORITIES.md`
- `../LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md` — 下一阶段方向主入口：取代原 P1–P5 排序口径（Loop / Goal / 交接 / UI 状态）
- `../PHASE2_ROADMAP.md`

### 目标导向评测 / 评估场景

- `../GOAL_ORIENTED_EVAL_PLAN.md`
- `../EVAL_SCENARIOS.md`
- `../PROJECT_GOAL_FIT_EVALUATION.md`
- `../AGENT_EVAL_AND_REFERENCES.md`

### 对标 / 借鉴 / 产品化

- `../MULTICA_BENCHMARK_AND_BORROWING_PLAN.md`
- `../GO_TO_MARKET_AND_PRODUCTIZATION_PLAN.md`

### 多轮任务包 / 测试驱动 / 执行链

- `../PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
- `../TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
- `../MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`
- `../MULTI_ROUND_TASK_EXECUTION_RECORD_TEMPLATE.md`

### Dated Execution Evidence 聚合入口

- `runs/README.md`

## 验证与证据

- `../D01_WORKER_PRIORITY_OVERRIDE_EXECUTION_RECORD_2026-06-15.md`
- `../D03_CHAT_FACADE_EXECUTION_RECORD_2026-06-15.md`
- `../M02_PACKET_SCHEMA_EXECUTION_RECORD_2026-06-30.md`
- `../M03_LEGACY_GET_CONTROL_ROUTE_EXECUTION_RECORD_2026-06-30.md`
- `../M01_O03_MULTI_ROUND_EXECUTION_RECORD_2026-06-15.md`
- `../O03_ACCEPTANCE_GATE_HTTP_EXECUTION_RECORD_2026-07-21.md`
- `../P2_BASELINE_MATRIX_REAL_WORKER_SMOKE_EXECUTION_RECORD_2026-07-21.md`
- `../P2_BASELINE_MATRIX_REAL_WORKER_SMOKE_FOLLOWUP_EXECUTION_RECORD_2026-07-22.md`
- `../O04_LONG_TASK_CLOSURE_EXECUTION_RECORD_2026-06-15.md`
- `../CODEX_MULTI_API_PROFILE_ROUTING_EXECUTION_RECORD_2026-06-30.md`

## 专项历史设计

- `../TASK_3809507EDBBE4231_LONG_TASK_SUCCESS_RATE_DESIGN_2026-05-15.md`

## 写回顺序

- 主题级短进展、当前焦点、未完成/下一步/风险：
  - 优先写 `PROGRESS.md`

- 需要收敛能力差距、项目评价、优先级、阶段目标：
  - 优先写 `CURRENT_CAPABILITY_GAP_ASSESSMENT.md`
  - 或 `PROJECT_EVALUATION_AND_NEXT_PLAN.md`、`NEXT_5_ENGINEERING_PRIORITIES.md`、`PHASE2_ROADMAP.md`
- 需要定义目标导向评测、评估场景、比较口径：
  - 优先写 `GOAL_ORIENTED_EVAL_PLAN.md`
  - 或 `EVAL_SCENARIOS.md`、`PROJECT_GOAL_FIT_EVALUATION.md`
- 需要补对标、借鉴、产品化、外部叙事：
  - 优先写 `MULTICA_BENCHMARK_AND_BORROWING_PLAN.md`
  - 或 `GO_TO_MARKET_AND_PRODUCTIZATION_PLAN.md`
- 需要把任务变成可执行的 harness 输入、测试计划和 runbook：
  - 优先写 `PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
  - 或 `TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`、`MULTI_ROUND_TASK_EXECUTION_RUNBOOK.md`
- 需要保留一轮真实执行证据：
  - 新增 dated `*_EXECUTION_RECORD_YYYY-MM-DD.md`
  - 同步把入口补进 `runs/README.md`
  - 再把摘要压缩到 `../STATE.md`

## 历史材料使用规则

- 旧 execution record 主要用于验证对照，不应用来替代当前优先级判断或当前阶段路线图。
- 需要在多份 dated execution record 之间切换时，先从 `runs/README.md` 进入，不要在 root-level 长名单里猜。
- `TASK_3809507EDBBE4231_LONG_TASK_SUCCESS_RATE_DESIGN_2026-05-15.md` 是早期专项长任务设计稿，不应与 dated execution record 混成同层默认入口；只有在回看当时的长任务失败样本与设计思路时再进入。
- 如果一条评估结论已经稳定，应从 dated record 或专项设计稿回收到主评估文档或 `STATE.md` / `DECISIONS.md`。

## 当前入口建议

- 要先看最近活跃焦点和风险：`PROGRESS.md`
- 要看“现在最该做什么”：`../NEXT_5_ENGINEERING_PRIORITIES.md`
- 要看“当前产品视角评估结论”：`../PROJECT_EVALUATION_AND_NEXT_PLAN.md`
- 要看“目标导向评测怎么做”：`../GOAL_ORIENTED_EVAL_PLAN.md`
- 要看“多轮任务该怎么投喂和验证”：`../PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
- 要把需求投喂成多轮任务：`../PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`
- 要按测试推进任务：`../TEST_DRIVEN_MULTI_ROUND_TASK_PLAN.md`
- 要回看 dated 执行证据：`runs/README.md`

## 巡检补登

- `../CODING_E2E_SMOKE_EXECUTION_RECORD_2026-07-22.md`（与 `P2_BASELINE_MATRIX_REAL_WORKER_SMOKE_EXECUTION_RECORD_2026-07-21.md` 同属 evaluation 主题 real-worker smoke 证据线；本轮 #auto-patrol# 末尾追加，修复 docs index audit orphan 回归）
