# Docs README

本文档是 `docs/` 的总索引。它只负责三件事：当前任务属于哪个主题、应该先读哪几份、做完该写回哪里。更细的结构合同、命名规则、审计口径，统一转到 `meta/README.md` 和 `DOCS_GOVERNANCE.md`。

## 开工规则

1. 先按 `../WAKE.md` 和 `../AGENTS.md` 建立上下文。
2. 如果任务主题不明确，先看下面的“按任务找入口”。
3. 确认主题后，先读对应 `docs/<topic>/README.md`；如果该主题已启用 `PROGRESS.md`，接着读它，再下钻到具体文档。
4. 文档治理或结构整理任务，先从 `meta/README.md` 进入，不要直接改一圈历史文件。

## 按角色找入口

| 你现在是谁 / 要做什么 | 先看哪里 | 再做什么 |
|------|------|------|
| 只想启动或验证服务 | `../STARTUP_GUIDE.md` | 跑起来后再按主题进入 `docs/` |
| 继续开发或排查 | 本文 | 先按主题分流，再进专题入口 |
| 做文档结构整理 | `meta/README.md` | 再看 `DOCS_GOVERNANCE.md` 与 `meta/PROGRESS.md` |
| AI Agent 接手任务 | `../WAKE.md`、`../AGENTS.md` | 回到本文做任务分流 |
| 只想看最近进展或固定结论 | `../STATE.md`、`../DECISIONS.md` | 需要细节时再下钻专题入口 |

## 根目录文档职责

| 文档 | 职责 |
|------|------|
| `../README.md` | 对外概览、能力说明、快速开始 |
| `../STARTUP_GUIDE.md` | 构建、启动、运行验证、启动期排障 |
| `../WAKE.md` | Agent 开工顺序 |
| `../AGENTS.md` | Agent 协作规则、文档边界、写回约束 |
| `../STATE.md` | 跨主题短进度、已完成/未完成/下一步 |
| `../DECISIONS.md` | 已固定的设计取舍与稳定规则 |
| 本文 | `docs/` 总索引、任务分流、新文档落点判断 |

## 按任务找入口

| 任务类型 | 先看入口 | 优先续写 | 需要同步的稳定面 |
|------|------|------|------|
| 文档治理、结构审计、命名合同、专题工作区升级 | `meta/README.md` | `meta/PROGRESS.md` 或 `DOCS_GOVERNANCE.md` | `../AGENTS.md`、`../WAKE.md`、`../DECISIONS.md` |
| 控制图、packet、runtime、checkpoint、goal loop、hardness、goal runtime diff | `continuity/README.md` | 最贴近的 continuity 方案文档或 runbook | `ARCHITECTURE.md`、`SPEC.md`、`API_CONTRACTS.md` |
| Loop / Goal / 交接 / UI 状态与结果聚焦（下一阶段方向） | `LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md` | `NEXT_5_ENGINEERING_PRIORITIES.md`、`CURRENT_CAPABILITY_GAP_ASSESSMENT.md` 的方向调整段 | `../STATE.md`、`../DECISIONS.md` |
| 下一阶段演进计划（Loop Decide / Goal Progress / 端到端验证 / UI Loop Activity） | `NEXT_EVOLUTION_PLAN.md` | `LOOP_GOAL_HANDOFF_UI_FOCUS_PLAN.md`、`CCX_PI_HARNESS_ADVISOR_INTEGRATION_PLAN.md` | `../STATE.md`、`../DECISIONS.md` |
| CCX + Pi + Harness + Advisor 集成落地（产品决策与优先级） | `CCX_PI_HARNESS_ADVISOR_INTEGRATION_PLAN.md` | Pi protocol、Advisory handoff、Trae protocol | `provider/README.md`、`API_CONTRACTS.md` |


| Provider、Worker、路由、profile、恢复、工具层、本地 CLI 兼容性/编码 | `provider/README.md` | 最贴近的 provider/routing 设计文档或 execution record | `AGENT_PROVIDER_TECHNICAL_DESIGN.md`、`API_CONTRACTS.md`、`TROUBLESHOOT.md` |
| 免费模型 Worker Lane + harness-config.yml 配置驱动 + CCX codex-free 映射 | `FREE_MODEL_WORKER_LANE_PLAN.md` | `provider/README.md`、`CODEX_MULTI_API_PROFILE_ROUTING_DESIGN.md` | `API_CONTRACTS.md`、`../DECISIONS.md` |
| `/dialogue/`、`/console/`、chat facade、UI 验证、页面 release gate | `dialogue/README.md` | 当前 UI 计划、runbook 或 acceptance record | `WEB_CONSOLE.md`、`TROUBLESHOOT.md` |
| 评估、优先级、多轮任务、task pack、benchmark、productization | `evaluation/README.md` | 评估文档、任务包、测试计划、execution record | `../STATE.md`、必要时 `../DECISIONS.md` |
| GitHub 首发、precheck、dry-run、release 范围、commit/stage/fileset | `release/README.md` | checklist、scope proposal、execution guide、dated precheck | `../README.md`、必要时 `../DECISIONS.md` |
| 任务还说不清属于哪里 | 本文 + `../STATE.md` + `../DECISIONS.md` | 先选一个主主题入口，再下钻 | 只在结论稳定后同步基线文档 |

## 文档分层

| 层级 | 入口 | 作用 |
|------|------|------|
| 根目录入口 | `../README.md`、`../WAKE.md`、`../AGENTS.md`、`../STATE.md`、`../DECISIONS.md` | 开工顺序、协作规则、跨主题状态、稳定取舍 |
| `docs/` 总索引 | 本文 | 按任务分流、决定应该进入哪个主题 |
| 文档治理入口 | `meta/README.md`、`DOCS_GOVERNANCE.md` | 结构合同、命名合同、索引审计、工作区升级规则 |
| 专题入口 | `meta/README.md`、`continuity/README.md`、`provider/README.md`、`dialogue/README.md`、`evaluation/README.md`、`release/README.md` | 每个主题的阅读顺序、主线文档、写回地图、历史分流 |
| 基线文档 | `ARCHITECTURE.md`、`API_CONTRACTS.md`、`SPEC.md`、`TROUBLESHOOT.md`、`WEB_CONSOLE.md`、`HARNESS_CHANGE_CONTRACT.md` | 今天仍然为真的结构事实、行为语义、契约与排障口径 |
| 活跃文档 | `*_PLAN.md`、`*_TECHNICAL_DESIGN.md`、`*_ROADMAP.md`、专题 runbook | 当前方案、执行步骤、推进主线 |
| 证据文档 | `*_EXECUTION_RECORD_YYYY-MM-DD.md`、`*_ACCEPTANCE_RECORD_YYYY-MM-DD.md`、`*_PRECHECK_YYYY-MM-DD.md` | 某一轮真实验证、操作轨迹、日期化结论 |

## 当前专题工作区现状

| 主题 | 当前状态 | 默认阅读路径 | 何时升级 |
|------|------|------|------|
| `meta/` | 已启用 `PROGRESS.md` | `meta/README.md -> meta/PROGRESS.md -> DOCS_GOVERNANCE.md` | 若后续再出现多条并行子线或 dated 证据堆积时 |
| `continuity/` | 已启用 `PROGRESS.md` | `continuity/README.md -> continuity/PROGRESS.md -> 当前子线文档 -> continuity/runs/README.md` | 如子线继续增多时补 `tasks/`；若 control-plane execution evidence 继续密集增长，再在 `runs/` 下补更细分组 |
| `provider/` | 已启用 `PROGRESS.md` | `provider/README.md -> provider/PROGRESS.md -> 当前子线文档 -> provider/runs/README.md` | 如子线继续增多时补 `tasks/`；若 provider route/profile/protocol evidence 继续密集增长，再在 `runs/` 下补更细分组 |
| `dialogue/` | 已启用 `PROGRESS.md` | `dialogue/README.md -> dialogue/PROGRESS.md -> 当前子线文档 -> dialogue/runs/README.md` | 如并行子线继续增多时补 `tasks/`；若 acceptance/precheck evidence 继续密集增长，再在 `runs/` 下补更细分组 |
| `evaluation/` | 已启用 `PROGRESS.md` | `evaluation/README.md -> evaluation/PROGRESS.md -> 当前子线文档 -> evaluation/runs/README.md` | 如子线继续增多时补 `tasks/`；若 dated execution evidence 继续密集增长，再在 `runs/` 下补更细分组 |
| `release/` | 仅 `README.md` | `release/README.md -> docs/` 根目录主线文档 | 新一轮 release 周期开始，且连续短进度或多份新 dated precheck/dry-run 证据需要在主题内集中追踪时 |

更多工作区升级规则、命名合同和历史例外口径，统一见 `DOCS_GOVERNANCE.md`。

## 基线文档

- `DOCS_GOVERNANCE.md`
- `ARCHITECTURE.md`
- `API_CONTRACTS.md`
- `SPEC.md`
- `TROUBLESHOOT.md`
- `WEB_CONSOLE.md`
- `HARNESS_CHANGE_CONTRACT.md`

这些文档应尽量保持“今天仍然为真”的状态，不要把稳定结论只留在 dated record 里。

## 专题入口

### [meta/README.md](meta/README.md)

文档治理、结构审计、命名合同、专题工作区与 Agent 开工入口相关任务的专题入口。

### [continuity/README.md](continuity/README.md)

控制面主链、continuity、packet、goal loop、runtime/active context、control node 相关文档入口。

### [provider/README.md](provider/README.md)

Provider、Worker、路由、恢复策略、tool layer、本地 CLI 集成相关文档入口。

已启用 `provider/runs/README.md` 作为 provider 主题下 codex profile、route/recovery、CLI protocol 与 focused execution evidence 的聚合入口。

### [dialogue/README.md](dialogue/README.md)

`/dialogue/`、`/console/`、chat facade、UI 验证、operator 诊断面相关文档入口。

### [evaluation/README.md](evaluation/README.md)

评估、工程优先级、多轮任务包、runbook、execution record 相关文档入口。

### [release/README.md](release/README.md)

GitHub 首发、release checklist、precheck、dry-run、提交边界相关文档入口。

## 新文档落点决策

- 结论已经是稳定事实或长期边界：优先更新基线文档。
- 结论属于当前某条推进主线：优先续写该主题下最贴近的 `plan / design / roadmap`。
- 内容是操作步骤、观测命令、验收链路：优先写 `runbook`。
- 内容是一轮具体执行证据：优先写 dated `execution record / acceptance record / precheck`。
- 内容只是跨主题短状态：写 `../STATE.md`。
- 内容是稳定约束或取舍：写 `../DECISIONS.md`。

## 文档治理与审计

- 结构合同、工作区升级、命名/dated 规则：`DOCS_GOVERNANCE.md`
- 活跃文档治理进度：`meta/PROGRESS.md`
- 差集审计：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-DocsIndexAudit.ps1`
- Maven focused regression：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -Dtest=DocsStructureContractTest,DocsIndexAuditScriptTest`

## 当前结构边界

- 继续吸收 `articleeditor` 的“入口先行、持续写回、按主题升级工作区”思路，但不引入全局 `memory/`、`state/` 目录树。
- `docs/README.md` 继续只做总索引；更细规则不再在这里平铺长说明。
- 现有正式文档继续以 `docs/` 根目录原位维护为主；只有入口和审计都收实后，才考虑物理迁移历史文件。
