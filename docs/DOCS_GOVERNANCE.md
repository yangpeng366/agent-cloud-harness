# Docs Governance

本文是当前仓库的稳定文档结构合同。`docs/README.md` 负责总索引，`docs/meta/README.md` 负责文档治理任务入口；更细的结构、命名、审计规则以本文为准。

## 目标

- 让新任务先命中文档入口，再命中最贴近的 plan / runbook / record
- 让文档治理本身有正式专题入口、持续写回面和可重复审计入口
- 保持轻量结构，只吸收 `articleeditor` 的连续性习惯，不引入全局 `memory/`、`state/` 目录树

## 文档层级

| 层级 | 入口 | 作用 |
|------|------|------|
| 根目录入口 | `../README.md`、`../STARTUP_GUIDE.md`、`../WAKE.md`、`../AGENTS.md`、`../STATE.md`、`../DECISIONS.md` | 对外说明、启动方式、Agent 开工、跨主题状态、稳定决策 |
| `docs/` 总索引 | `docs/README.md` | 按任务分流，决定应该进入哪个主题 |
| `meta` 入口 | `docs/meta/README.md` | 文档治理任务分流、治理进度入口 |
| 专题入口 | `docs/<topic>/README.md` | 每个主题的命中信号、最小阅读顺序、主线文档、写回顺序 |
| 主题进度面 | `docs/<topic>/PROGRESS.md` | 只在该主题持续高频推进时启用，记录活跃线程、下一步、风险 |
| 主题级证据聚合面 | `docs/<topic>/runs/README.md` | 当 dated execution/acceptance/precheck 证据开始密集增长时，提供主题内 evidence 聚合入口 |
| 基线文档 | `ARCHITECTURE.md`、`API_CONTRACTS.md`、`SPEC.md`、`TROUBLESHOOT.md`、`WEB_CONSOLE.md` 等 | 今天仍然为真的结构事实、合同与排障口径 |
| 活跃文档 | `*_PLAN.md`、`*_TECHNICAL_DESIGN.md`、`*_ROADMAP.md`、各类 runbook | 当前正在推进的方案和执行主线 |
| dated 证据 | `*_EXECUTION_RECORD_YYYY-MM-DD.md`、`*_ACCEPTANCE_RECORD_YYYY-MM-DD.md`、`*_PRECHECK_YYYY-MM-DD.md` | 一轮真实执行、验收、预检证据 |
| 临时产物 | `.tmp/` | 截图、脚本输出、草稿；若要持续引用，必须提炼回正式文档 |

## 主题工作区合同

- 默认状态是 `README-only`：只有 `docs/<topic>/README.md`，负责分流、阅读顺序和写回地图。
- `README-only` 不是半成品状态；当某个主题的当前真相已经能被少量稳定入口覆盖，而剩余材料主要是历史证据或低频参考时，继续维持 `README-only` 就是正确形态。
- 只有单一主题连续高频推进时，才允许升级到 `PROGRESS.md`。
- 并行子线明显增多时，再增加 `tasks/`。
- dated execution / acceptance / precheck 证据开始密集增长时，再增加 `runs/`。
- 只要启用了 `runs/`，该目录就必须至少有一个 `runs/README.md` 做主题级 evidence 聚合入口；先聚合入口，再考虑是否需要物理迁移历史文档。
- 已启用的 `runs/README.md` 也不是自由文本；至少应保留 `## 命中信号`、`## 最小阅读顺序`、`## 当前分组`、`## 使用规则` 这组最小结构，避免 evidence 入口重新退回成长名单。
- 一批材料已经退出主线但仍需保留追溯时，再增加 `archive/`。
- 无论升级到哪一层，`README.md` 始终是第一入口。
- 物理迁移历史文件永远放在最后一步；先补入口、再补审计、最后才决定是否移动。

## 根入口合同

- `../README.md` 只负责公开概览、快速开始与总导航，不承载开发期长篇事实说明。
- `../STARTUP_GUIDE.md` 只负责构建、启动、运行验证与启动期排障，不承担开发主题分流。
- `../WAKE.md` 负责开工顺序与默认阅读链，不扩成项目百科。
- `../AGENTS.md` 负责 Agent 协作约束、写回规则、开工红线、项目事实入口与必须先知道的风险，不重复承载整份架构/API/技术栈长说明。
- 稳定项目事实应优先沉淀在 `docs/ARCHITECTURE.md`、`docs/API_CONTRACTS.md`、`docs/SPEC.md`、`docs/TROUBLESHOOT.md`、`docs/WEB_CONSOLE.md` 这类正式文档，而不是长期堆回 `AGENTS.md`。

## PROGRESS 合同

- 只有主题已经进入持续高频推进时，才允许启用 `docs/<topic>/PROGRESS.md`。
- 只要某个主题启用了 `PROGRESS.md`，对应 `docs/<topic>/README.md` 就必须显式保留 `README.md -> PROGRESS.md -> 当前主线文档` 这条默认阅读链。
- 已启用的 `PROGRESS.md` 至少要保留五个固定段落：`## 当前状态`、`## 已完成`、`## 活跃子线`、`## 下一步`、`## 风险`。
- `PROGRESS.md` 负责主题级连续性写回，不替代该主题的稳定基线文档、专题入口 README 或 root-level dated 证据。
- 如果后续某个主题降回低频维护，优先先把活跃信息回收到 README / 基线文档，再决定是否保留 `PROGRESS.md`。

## 当前工作区状态

- `meta/`：`README.md + PROGRESS.md`
- `continuity/`：`README.md + PROGRESS.md`
- `continuity/runs/`：已启用 `README.md`
- `provider/`：`README.md + PROGRESS.md`
- `provider/runs/`：已启用 `README.md`
- `dialogue/`：`README.md + PROGRESS.md`
- `dialogue/runs/`：已启用 `README.md`
- `evaluation/`：`README.md + PROGRESS.md`
- `evaluation/runs/`：已启用 `README.md`
- `release/`：`README-only`

## 文档类型与命名合同

| 类型 | 用途 | 文件名约定 |
|------|------|------|
| `PLAN` | 目标、步骤、拆解、待办推进 | `*_PLAN.md` |
| `TECHNICAL_DESIGN` / `DESIGN` | 稳定方案边界、字段合同、设计取舍 | `*_TECHNICAL_DESIGN.md` 优先；历史 `*_DESIGN.md` 继续保留 |
| `RUNBOOK` | 操作步骤、命令链、排障顺序 | `*_RUNBOOK.md` |
| `EXECUTION_RECORD` | 一轮真实执行证据 | `*_EXECUTION_RECORD_YYYY-MM-DD.md` |
| `ACCEPTANCE_RECORD` | 一轮真实验收证据 | `*_ACCEPTANCE_RECORD_YYYY-MM-DD.md` |
| `PRECHECK` | release / 发布前预检快照 | `*_PRECHECK_YYYY-MM-DD.md` |
| `TEMPLATE` | 可复用骨架 | `*_TEMPLATE.md` |
| `ROADMAP` | 中长期阶段推进 | `*_ROADMAP.md` |

- 新增 dated 文档统一使用绝对日期后缀 `YYYY-MM-DD`。
- 当前显式保留的历史例外主要是 release 旧链路：`*_DRY_RUN_*`、`*_STAGE_PREVIEW_*`、`*_COMMIT_DRY_RUN_*`，以及少量已在专题入口里明确降级的旧 dated 设计/快照。
- 新 dated 文档默认只能进入核心合同，不再继续扩散新的例外命名模式。

## 结构调整顺序

1. 先改 `docs/README.md`，让任务分流和主题入口路径正确。
2. 再改对应 `docs/<topic>/README.md`，收口命中信号、主线文档和写回顺序。
3. 如果规则本身变了，再改本文。
4. 若主题确实进入高频续写，再按需启用 `PROGRESS.md / tasks / runs / archive`。
5. 若启用了 `tasks / runs / archive`，先补该目录自己的 `README.md` 入口，再同步主题 README 与总索引。
6. 只有入口和审计都收实后，才考虑物理迁移历史文件。

## 审计与回归入口

- 差集与结构审计：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-DocsIndexAudit.ps1`
- 需要保留 Markdown 快照时：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Run-DocsIndexAudit.ps1 -WriteMarkdown -MarkdownPath .tmp\docs-index-audit.md`
- Maven focused regression：
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -Dtest=DocsStructureContractTest`
  - `powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -Dtest=DocsStructureContractTest,DocsIndexAuditScriptTest`

当前审计口径至少包括：

- `../README.md` 必须保留公开入口导航，至少能把读者送到 `README.md`、`meta/README.md`、`DOCS_GOVERNANCE.md`、`../WAKE.md`、`../AGENTS.md`、`../STATE.md`、`../DECISIONS.md`
- `../STARTUP_GUIDE.md` 必须保留“本文边界”，并把非启动类任务导回 `README.md`、`../WAKE.md`、`../AGENTS.md`、`dialogue/README.md`、`provider/README.md`、`../STATE.md`、`../DECISIONS.md`
- `README.md` 必须引用所有专题入口
- `README.md` 必须保留 `按角色找入口` 这一层默认导航，至少继续给出 startup/verify、文档治理、Agent 接手、连续性读取这四条入口
- `README.md` 必须继续显式暴露 `meta/README.md`、`DOCS_GOVERNANCE.md`、`Run-DocsIndexAudit.ps1` 与 focused docs regression 命令，避免总索引失去治理入口和审计入口
- `meta/README.md` 必须继续显式保留文档治理写回链：`docs/README.md -> docs/<topic>/README.md -> DOCS_GOVERNANCE.md -> PROGRESS.md / STATE.md / DECISIONS.md`
- root-level `docs/*.md` 必须至少被一个专题入口引用，不能只停在总索引
- 每个专题入口都必须具备 `命中信号 / 最小阅读顺序 / 稳定基线 / 当前主线文档 / 写回顺序 / 历史材料` 这组最小合同
- 每个专题入口都必须在 `稳定基线` 段落里显式说明哪些正式文档“今天仍然为真”，避免入口只剩活跃主线、丢掉稳定事实入口
- 每个专题入口还必须继续保留稳定段落顺序：`命中信号 -> 最小阅读顺序 -> 稳定基线 -> 当前主线文档 -> 写回顺序`；`历史材料(使用规则)` 与业务主题附加段落可后置，但不要把核心开工路径打散
- `continuity / provider / dialogue / evaluation / release` 这些业务主题入口必须继续保留 `先做子主题判断`、`当前入口建议` 与 `| 当前问题 | 先看哪里 | 再下钻 |` 分流表，避免主题入口退回成纯长名单
- `continuity / provider / dialogue / evaluation / release` 这些业务主题入口在 `## 当前主线文档` 下必须继续保留分组子标题；其中已启用 `PROGRESS.md` 的业务主题还必须保留 `### 主题进度`，避免当前主线重新退回成一段平铺列表
- 只要某个主题仍保持 `README-only`，对应专题 `README.md` 就必须解释当前为什么仍维持轻量入口、何时才升级，并保留 `README.md -> docs/` 根目录主线文档 这条默认阅读链
- 只要某个主题启用了 `PROGRESS.md`，对应专题 `README.md` 就必须显式写出 `README.md -> PROGRESS.md -> 当前主线文档` 这条默认阅读链
- 只要某个主题启用了 `runs/`，对应专题 `README.md` 就必须显式说明 `runs/README.md` 的职责和入口位置
- 只要某个主题启用了 `runs/`，对应专题 `README.md` 的默认阅读顺序也必须显式写成 `README.md -> PROGRESS.md -> 当前子线文档 -> runs/README.md`，不能只写成 `runs/`
- 只要某个主题启用了 `runs/`，`docs/README.md` 的“当前专题工作区现状”表也必须把 `runs/README.md` 暴露进默认阅读路径，不能只让目录存在
- 只要某个主题启用了 `runs/`，对应 `runs/README.md` 还必须继续保留 `命中信号 / 最小阅读顺序 / 当前分组 / 使用规则` 这组最小段落，明确它是主题级 evidence 聚合入口，而不是裸列表
- 只要某个主题启用了 `PROGRESS.md`，该 `PROGRESS.md` 就必须保留 `当前状态 / 已完成 / 活跃子线 / 下一步 / 风险` 这组最小段落
- 只要某个主题启用了 `PROGRESS.md`，该 `PROGRESS.md` 还必须继续保留稳定段落顺序：`当前状态 -> 已完成 -> 活跃子线 -> 下一步 -> 风险`
- 只要某个主题启用了 `runs/`，`runs/README.md` 就必须存在，并承担该主题 dated evidence 的聚合入口，而不是让 `runs/` 成为无入口目录
- `README.md` 的“当前专题工作区现状”表必须和真实目录状态同步
- `../WAKE.md` 与 `../AGENTS.md` 必须显式列出每个主题当前是 `README.md + PROGRESS.md` 还是 `README-only`
- 只要某个主题启用了 `PROGRESS.md`，`../WAKE.md` 与 `../AGENTS.md` 就必须保留 `README.md -> PROGRESS.md -> 当前主线文档` 这条默认阅读链；`README-only` 主题则固定为 `README.md -> docs/` 根目录主线文档
- `../scripts/Run-DocsIndexAudit.ps1` 本身必须可执行，且输出的 `summary` 至少要稳定包含 `passed / violation_count / wake|agents workspace row coverage / 根入口阅读链布尔值`
- `../AGENTS.md` 的 Agent 入口角色也要进入脚本回归：必须保留 `开工红线 / 项目事实入口`，并把稳定项目事实导向 `ARCHITECTURE / API_CONTRACTS / SPEC / TROUBLESHOOT / WEB_CONSOLE`，同时不再长回 `项目概述 / 技术栈 / 代码组织 / API 端点速查`
- 主题目录顶层只允许 `README.md / PROGRESS.md / tasks / runs / archive`

## 写回合同

- 文档治理主题的活跃进度优先写 `docs/meta/PROGRESS.md`
- 跨主题短摘要写 `../STATE.md`
- 稳定规则写 `../DECISIONS.md`
- 某个业务主题自己的方案、runbook、record 仍应优先写回那个主题的现有主线文档，而不是都挤到文档治理主题

## 当前边界

- 不引入 `articleeditor` 风格的全局 `memory/`、`state/` 目录树。
- 不为“目录整齐”一次性给所有主题都建 `PROGRESS.md / tasks / runs / archive`。
- `README.md` 继续只承担总索引，不再承载过长的治理细则。
- 如果某条治理规则已经稳定，应优先固化到本文，而不是继续散在 `../STATE.md`、`../DECISIONS.md` 与对话里。
