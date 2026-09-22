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
| 要做外部 skill / framework 设计 pattern 借鉴类调研（wbbench 两个 skill、AgentENV 等） | `../WBBENCH_BENCH_SKILLS_RESEARCH.md` | 续写该调研或在对应主线文档落启发点，不在本主题落代码 | `../STATE.md`、必要时 `../DECISIONS.md` |
| 要吸收 agent-desktop 的桌面代理模式并复用到 OpenEyes / FEAT-05 | `../AGENT_DESKTOP_OPENEYES_LEARNING.md` | 对比 skeleton/drill、风险闸门、stable ref、bounded loop；落结构化 assertion，不引入付费 API | `../OPENEYES_UI_AUTOMATION_RESEARCH.md`、`../STATE.md` |
| 要评估上下文评分门控 / System One Model 在 harness 上下文预算、Handoff Packet、Judgment 前置过滤三条链路上的借鉴价值 | ../JEV_CONTEXT_SCORING_RESEARCH.md | 与 WBBENCH 调研同形态；立项后挂 ROADMAP §E1 / §E2，不在调研阶段落代码 | ../STATE.md、必要时 ../DECISIONS.md |
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
- `../E2_CODEX_FREE_E2E_SMOKE_EXECUTION_RECORD_2026-07-29.md` - codex-free lane（经本地 CCX + codex app-server）真机 e2e 冒烟 + 长任务收口合同字段验证

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
- `../WBBENCH_BENCH_SKILLS_RESEARCH.md` — Tencent WorkBuddy Bench 两个 skill
- ../JEV_CONTEXT_SCORING_RESEARCH.md — TypeSafe Jev
- ../JEV_HANDS_ON_NOTES.md — Jev 本地实测记录
- ../JEV_OFFICIAL_SKILL_ABSORPTION.md — Jev 官方材料吸收笔记（typesafe-ai/skills GitHub 仓库 + 5 cookbook + 2 pattern + confidence.md + system-one.md 共 9 份原始材料），引入 confidence 3 段阈值 / ThresholdDict 模式 / progressive disclosure / 官方 6 个 common issues。
- ../JEV_REAL_API_DEMO.md — Jev 真 API demo metrics
- ../JEV_PATROL_INTEGRATION_PLAN.md — Jev × 飞书巡检流程接入 system map
- ../JEV_GRAPH_NODES_PLAN.md — Jev × 决策树 × 复杂网络 算法借鉴研究
- ../JEV_FEASIBILITY_FILTER.md — 前沿方向逻辑可行性过滤层:三个方向(图算法替代 / NN layer / 软算子)的"成/不成/待验证"判定,基于 Jev 官方 4-workflow 68% 准确率 + community benchmark;图算法替代部分成立(hybrid 形态),NN layer 否决,软算子强推荐。
- ../JEV_GRAPH_HYBRID_EXPERIMENT.md — Jev × Graph Hybrid 实验记录
- ../JEV_GRAPH_HYBRID_EXPERIMENT_REAL.md — Jev × Graph Hybrid 实验 Phase 2 真 API 数据
- ../JEV_GRAPH_HYBRID_EXPERIMENT_BASELINE.md — Jev × Graph Hybrid 实验 Phase 3 baseline 验证
- ../JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md — Jev / OpenEyes 立项表 Schema 设计
- [INITIATIVES/INDEX.md](../INITIATIVES/INDEX.md) — Jev / OpenEyes 立项跟踪总览(本地落仓,17 张 candidate card: FEAT-03 / HW-09-13 / FEAT-04-05 / Jev-Patrol-L2-L3-L5 / Jev-Graph-A-B / Jev-ToolRecall-Filter / Jev-A-B-C,每张 card 含状态机 / 立项门槛 / blocker / sign-off checklist)。:16 字段(状态机/owner/signoff/blocker/phase 等);17 条候选映射(harness 借鉴 4 / OpenEyes 借鉴 5 / 巡检流程 3 / 图算法 2 / tool recall 1 / 横切脑洞 3);新建独立 Bitable 立项表(与项目方向表正交);2026-09-21 Toutiao 全网解禁当日新增 Jev-ToolRecall-Filter(真 Jev precision@5=100%)。(G(100,0.05) 100 节点全量,real Jev τ=+0.10 sign 反转 / top-5=5/5 / top-10=9/10;hybrid α=0.5 γ=0.5 仍 top-10=9/10)。les_miserables 77 节点 string label 跑通未完成,作为 pre-existing 待补。(karate_club 34 节点全量,5 batches,real Jev noise std=0.119,top-5 hit=5/5,top-10 hit=8/10,hybrid(α=0.7,γ=0.3) top-10=9/10 vs baseline 10/10;cost $0.005 / latency 5.8s)。修正 Phase 1 结论:真实 Jev 在 top-K 召回上比 fake 加噪 Gaussian 表现更好。(fake Jev × karate_club 真实数据):4 个场景对比(perfect/noisy/random/binary threshold),**核心发现 σ=0.15 noise 下 Kendall τ=0.19 < random**,确认 hybrid 路径(Jev = rerank layer 不是 oracle)。（networkx 算法集 5 类 fixture + 3 类借鉴路径：路径 A mounted_context_view 图节点评分 / 路径 B RuntimeJudgmentService parallel questions / 路径 C decision_trace × centrality）。（5 层接入路径 L1-L5 + 3 个中长期脑洞；L1 已做 / L2 高价值待启动）。（9 次真实调用 3 场景 × 3 threshold），latency p50 ≈ 300ms / goal awareness 极强 / 单 batch cost ~$0.001。（clone fast-jev-compaction v0.2.0 + npm install + typecheck + 29 用例全绿 + 读完 src 全 8 文件 + tests 2 文件）。修正 plan §1.2（endpoint 是 /v1/systemone 不是 OpenAI-compatible）/§1.4（answer 三种形态 noul/choice/score）/§3（fallback 库级 throw → caller 决定）/§13（fake 改用 Map<name,Double>）。fake Jev 迁入 src/test/java/com/agentcloud/scorer/ 时按本文 §2 模板实现。
- ../OPENEYES_UI_AUTOMATION_RESEARCH.md — yangpeng366/openeyes
- `../AGENT_DESKTOP_OPENEYES_LEARNING.md` — lahfir/agent-desktop 快照学习：skeleton/drill、headless policy、BARS 风险闸门、stable ref、bounded loop；映射到 OpenEyes FEAT-04/05。
- ../OPENEYES_ACCEPTANCE_DEMO_PLAN.md — HW-11 + HW-12 演示脚本骨架（不落 scripts/），含 PR-1（profile 持久化）+ PR-2（native app probe）双骨架；与现有 puppeteer-core acceptance 套件叠加，不替换。
- ../THIRD_HAND_COMPUTER_USE_RESEARCH.md — shhivv/third-hand（macOS menu bar 计算机使用助手；Accessibility + Vision OCR + CDP + Jev 决策）；OpenEeyes 第 4 调研样本；提炼多后端渐进 fallback + Electron/CDP 探测 + Jev 决策协议 + focus 守护 + 受限文本输入 5 模式，建议落地为「OpenEyes 决策层可选叠加」默认 enabled=false。
- ../FAST_JEV_COMPACTION_RESEARCH.md — tamaratran/fast-jev-compaction（Claude Code plugin + npm library：用 Jev 决策替换默认 compaction summary）；与 JEV_HANDS_ON_NOTES.md 互为 fast-jev-compaction 视角补充。
- ../JEV_ULTRAFAST_RESEARCH.md — browser-use/jev-ultrafast（description: "i. am. speed."；"A browser agent that chooses instead of generating"）；浏览器 agent 用 Jev 做选择而非生成，ACH 借鉴价值在「decision over generation」范式。
- ../JEV_TOOL_RECALL_FILTER_DEMO.md — 2026-09-21 Toutiao Jev 全网解禁当日跑通的真 Jev 实测，10 候选 / 5 relevant / 5 noise / 2 noul questions / precision@5=100% / 1.05s / ~$0.00009；Jev-ToolRecall-Filter 立项门槛达成证据。（UIA + CDP + MCP 13 tool）借鉴类调研；提炼 HW-11/12/13（浅：profile 持久化 + UIA probe + MCP precheck）+ FEAT-04（中深：MCP 13 tool 注册为可选 worker tool）+ FEAT-05（深：structured UI assertion 替代 screenshot diff）五档落地路径；ACH 当前 puppeteer-core 路径不被替换，OpenEyes 走叠加语义；仓库零凭据、CI 走 fake。（System One Model）+ 	amaratran/fast-jev-compaction 等 2026-09 涌现的上下文评分门控借鉴类调研；提炼 System One vs LLM Judgment 的角色切分、评分式剪枝替代字符级硬截、keep-probability 过滤 key_decisions、judgment 前置过滤四条启发，备查落地于 ROADMAP §E1 / §E2 与 continuity/ / provider/。本仓库当前不能直接调 Jev（外部 SaaS + 凭据），价值在 pattern 借鉴。（`wbbench-run-setup` 7 阶段、`wbbench-report-skills` 三子工作流）的借鉴类调研；提炼 5+1 design pattern（id 边界辨认 / secrets 分离 / 阶段化+Guardrails / 报告卫生 / local_proxy 通用化 / 阶段化 references/ 目录结构），备查落地于 `provider/` 与 `evaluation/`。harness 现在不能直接调这两个 skill（详见本文档），价值在 pattern 借鉴。
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
  - 或 JEV_CONTEXT_SCORING_RESEARCH.md（上下文评分门控 / System One Model 借鉴）
  - 或 OPENEYES_UI_AUTOMATION_RESEARCH.md（OpenEyes UI 自动化借鉴类调研）
  - 或 OPENEYES_ACCEPTANCE_DEMO_PLAN.md（OpenEyes acceptance 演示脚本骨架）
  - 或 THIRD_HAND_COMPUTER_USE_RESEARCH.md（Third Hand 计算机使用借鉴；OpenEyes 第 4 调研样本）
  - 或 FAST_JEV_COMPACTION_RESEARCH.md（fast-jev-compaction 借鉴类调研）
  - 或 JEV_ULTRAFAST_RESEARCH.md（jev-ultrafast 浏览器 agent 借鉴类调研）
  - 或 JEV_TOOL_RECALL_FILTER_DEMO.md（Jev tool recall filter 真 Jev demo · precision@5=100%）
  - 或 JEV_HANDS_ON_NOTES.md（Jev 本地实测记录）
  - 或 JEV_OFFICIAL_SKILL_ABSORPTION.md（Jev 官方材料吸收笔记）
  - 或 JEV_REAL_API_DEMO.md（Jev 真 API demo metrics）
  - 或 JEV_PATROL_INTEGRATION_PLAN.md（Jev × 飞书巡检流程接入 system map）
  - 或 JEV_GRAPH_NODES_PLAN.md（Jev × 决策树 × 复杂网络 算法借鉴）
  - 或 JEV_FEASIBILITY_FILTER.md（前沿方向逻辑可行性过滤）
  - 或 JEV_GRAPH_HYBRID_EXPERIMENT.md（Jev × Graph Hybrid 实验记录，σ=0.15 noise 实测）
  - 或 JEV_GRAPH_HYBRID_EXPERIMENT_REAL.md（Jev × Graph Hybrid Phase 2 真 API 数据，hybrid 公式验证）
  - 或 JEV_GRAPH_HYBRID_EXPERIMENT_BASELINE.md（Jev × Graph Hybrid Phase 3 baseline，G(100,0.05) 100 节点）
  - 或 JEV_OPENEYES_INITIATIVE_TABLE_DESIGN.md（Jev / OpenEyes 立项表 Schema 设计 + 16 条候选映射）
  - 或 [INITIATIVES/INDEX.md](../INITIATIVES/INDEX.md)（Jev / OpenEyes 立项跟踪总览，本地 16 张 card）
  - 或 [JEV_DECISION_TREE_REAL_EXPERIMENT.md](../JEV_DECISION_TREE_REAL_EXPERIMENT.md)（Jev 做元决策的真实验，路径 B prototype，1 call / 5 questions / $0.001 / 5/5 票对）
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