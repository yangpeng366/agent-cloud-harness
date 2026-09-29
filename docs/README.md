# Docs README

本文档是 `docs/` 的总索引。它只负责三件事：当前任务属于哪个主题、应该先读哪几份、做完该写回哪里。更细的结构合同、命名规则、审计口径，统一转到 `meta/README.md` 和 `DOCS_GOVERNANCE.md`。

## 开工规则

1. 先按 `../WAKE.md` 和 `../AGENTS.md` 建立上下文。
2. 如果任务主题不明确，先看下面的“按任务找入口”。
3. 确认主题后，先读对应 `docs/<topic>/README.md`；如果该主题已启用 `PROGRESS.md`，接着读它，再下钻到具体文档。
4. 文档治理或结构整理任务，先从 `meta/README.md` 进入，不要直接改一圈历史文件。

## 命中信号

- 任务提到“按任务找入口”里的任何条目；判断任务主题不明确
- 任务是在找“应该先读哪份文档”
- 任务是要在 `docs/` 根索引这一层做结构调整或入口收口
- 任务涉及 `DOCS_GOVERNANCE.md` 或 `docs/README.md` 本身的重写

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

## 最小阅读顺序

1. 先按 `../WAKE.md` 与 `../AGENTS.md` 建立上下文。
2. `本文` 命中信号 + 按任务找入口，判断当前任务属于哪个主题。
3. 进入对应 `docs/<topic>/README.md`；如果该主题已启用 `PROGRESS.md`，接着读它。
4. 如果任务落在某个稳定基线（架构 / API / 状态机 / 排障 / Web Console），直接读 `docs/` 根目录同名基线文档。
5. 跨主题短摘要读 `../STATE.md`，稳定设计取舍读 `../DECISIONS.md`。

## 按任务找入口

| 任务类型 | 先看入口 | 优先续写 | 需要同步的稳定面 |
|------|------|------|------|
| 文档治理、结构审计、命名合同、专题工作区升级 | `meta/README.md` | `meta/PROGRESS.md` 或 `DOCS_GOVERNANCE.md` | `../AGENTS.md`、`../WAKE.md`、`../DECISIONS.md` |
| 控制图、packet、runtime、checkpoint、goal loop、hardness、goal runtime diff | `continuity/README.md` | 最贴近的 continuity 方案文档或 runbook | `ARCHITECTURE.md`、`SPEC.md`、`API_CONTRACTS.md` |
| Provider、Worker、路由、profile、恢复、工具层、本地 CLI 兼容性/编码 | `provider/README.md` | 最贴近的 provider/routing 设计文档或 execution record | `AGENT_PROVIDER_TECHNICAL_DESIGN.md`、`API_CONTRACTS.md`、`TROUBLESHOOT.md` |
| `/dialogue/`、`/console/`、chat facade、UI 验证、页面 release gate | `dialogue/README.md` | 当前 UI 计划、runbook 或 acceptance record | `WEB_CONSOLE.md`、`TROUBLESHOOT.md` |
| 评估、优先级、多轮任务、task pack、benchmark、productization | `evaluation/README.md` | 评估文档、任务包、测试计划、execution record | `../STATE.md`、必要时 `../DECISIONS.md` |
| GitHub 首发、precheck、dry-run、release 范围、commit/stage/fileset | `release/README.md` | checklist、scope proposal、execution guide、dated precheck | `../README.md`、必要时 `../DECISIONS.md` |

## 稳定基线

- `DOCS_GOVERNANCE.md` — 文档结构合同、命名合同、dated 文档规则、专题工作区升级规则
- `ARCHITECTURE.md` — 模块边界、进程边界、状态机所在文档，今天仍然为真
- `API_CONTRACTS.md` — API 字段、存储表、JSON 形状、HTTP 错误体约定
- `SPEC.md` — 状态机、控制图节点、合并 / 包 / 持久化语义
- `TROUBLESHOOT.md` — 已知坑、排障步骤、回归点收口位置
- `WEB_CONSOLE.md` — Web Console / Dialogue 阅读面契约
- `HARNESS_CHANGE_CONTRACT.md` — harness 变更与控制面兼容合同，今天仍然为真
- `PERSISTENCE_ARTIFACT_INVENTORY.md` — 持久化产物 owner / source-of-truth / 可重建纪律

这些文档应尽量保持“今天仍然为真”的状态，不要把稳定结论只留在 dated record 里。

## 当前主线文档

### 总索引与入口分流

- 本文
- `../README.md` — 对外入口
- `../STARTUP_GUIDE.md` — 启动 / 部署 / 验证
- `../WAKE.md` — Agent 开工入口
- `../AGENTS.md` — Agent 协作约束

### 结构合同与命名合同

- `DOCS_GOVERNANCE.md`

### 审计与回归入口

- `scripts/Run-DocsIndexAudit.ps1`
- `src/test/java/com/agentcloud/docs/DocsStructureContractTest.java`
- `src/test/java/com/agentcloud/docs/DocsIndexAuditScriptTest.java`

### 专题入口

- `meta/README.md` — 文档治理、专题工作区、Agent 开工入口
- `continuity/README.md` — 控制面主链、continuity、packet、goal loop、checkpoint
- `provider/README.md` — Provider、Worker、路由、profile、工具层
- `dialogue/README.md` — `/dialogue/`、`/console/`、chat facade、UI 验证
- `evaluation/README.md` — 评估、优先级、多轮任务、execution record
- `release/README.md` — GitHub 首发、precheck、dry-run、commit/stage 边界

## 当前专题工作区现状

| 主题 | 当前状态 | 默认阅读路径 | 何时升级 |
|------|------|------|------|
| `meta/` | 已启用 `PROGRESS.md` | `meta/README.md -> meta/PROGRESS.md -> DOCS_GOVERNANCE.md` | 若后续再出现多条并行子线或 dated 证据堆积时 |
| `continuity/` | 已启用 `PROGRESS.md` | `continuity/README.md -> continuity/PROGRESS.md -> 当前子线文档 -> continuity/runs/README.md` | 如子线继续增多时补 `tasks/`；若 control-plane execution evidence 继续密集增长，再在 `runs/` 下补更细分组 |
| `provider/` | 已启用 `PROGRESS.md` | `provider/README.md -> provider/PROGRESS.md -> 当前子线文档 -> provider/runs/README.md` | 如子线继续增多时补 `tasks/`；若 provider route/profile/protocol evidence 继续密集增长，再在 `runs/` 下补更细分组 |
| `dialogue/` | 已启用 `PROGRESS.md` | `dialogue/README.md -> dialogue/PROGRESS.md -> 当前子线文档 -> dialogue/runs/README.md` | 如并行子线继续增多时补 `tasks/`；若 acceptance/precheck evidence 继续密集增长，再在 `runs/` 下补更细分组 |
| `evaluation/` | 已启用 `PROGRESS.md` | `evaluation/README.md -> evaluation/PROGRESS.md -> 当前子线文档 -> evaluation/runs/README.md` | 如子线继续增多时补 `tasks/`；若 dated execution evidence 继续密集增长，再在 `runs/` 下补更细分组 |
| `release/` | 仅 `README.md` | `release/README.md -> docs/` 根目录主线文档 | 新一轮 release 周期开始，且连续短进度或多份新 dated precheck/dry-run 证据需要在主题内集中追踪时 |
| `docs/` | 仅 `README.md` | `docs/README.md -> docs/<topic>/README.md -> DOCS_GOVERNANCE.md -> PROGRESS.md / STATE.md / DECISIONS.md` | 当 `docs/` 根索引本身需要独立活跃进度追踪时 |

更多工作区升级规则、命名合同和历史例外口径，统一见 `DOCS_GOVERNANCE.md`。

## 写回顺序

- 文档治理 / 结构审计 / 命名合同 / 入口收口：先改本文，再改 `meta/README.md`，必要时同步 `DOCS_GOVERNANCE.md`，活跃进度写 `meta/PROGRESS.md`
- 跨主题短摘要 / 已完成 / 未完成 / 下一步 / 风险：写 `../STATE.md`
- 稳定设计取舍 / 长期约束 / 历史例外口径：写 `../DECISIONS.md`
- 调研、方案、验收结论：先沉淀到对应专题的 `README.md` / `PROGRESS.md` / `tasks/` / `runs/`，再决定是否动代码
- 新增 plan / runbook / execution record / acceptance record / precheck 必须能从某个专题入口追到，不要在 `docs/` 根目录裸放
- 默认写回链：`docs/README.md -> docs/<topic>/README.md -> DOCS_GOVERNANCE.md -> PROGRESS.md / STATE.md / DECISIONS.md`

## 新文档落点决策

- 结论已经是稳定事实或长期边界：优先更新基线文档。
- 结论属于当前某条推进主线：优先续写该主题下最贴近的 `plan / design / roadmap`。
- 内容是操作步骤、观测命令、验收链路：优先写 `runbook`。
- 内容是一轮具体执行证据：优先写 dated `execution record / acceptance record / precheck`。
- 内容只是跨主题短状态：写 `../STATE.md`。
- 内容是稳定约束或取舍：写 `../DECISIONS.md`。

## 历史材料使用规则

- 旧 execution record / precheck 主要用于对照，不应用来替代当前优先级或当前基线。
- 同一份 dated 文档有多个并行入口时，先从对应主题的 `runs/README.md` 或子线入口进入，不要在 root-level 长名单里猜。
- dated doc 命名若不匹配 `*_EXECUTION_RECORD_YYYY-MM-DD.md` / `*_ACCEPTANCE_RECORD_YYYY-MM-DD.md` / `*_PRECHECK_YYYY-MM-DD.md`，需要走历史例外口径登记到 `DECISIONS.md` 或 `DOCS_GOVERNANCE.md`，否则视为命名合同违规。
- 已经稳定的结论应从 dated record / 历史专项设计稿回收到当前基线文档或 `STATE.md` / `DECISIONS.md`，不再长期只留在历史文档里。
- 如果某条规则已经稳定，应回收到 `DOCS_GOVERNANCE.md`，不要长期只留在 `PROGRESS.md` 或对话里。

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