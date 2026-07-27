# Meta / Docs Governance

本专题覆盖文档结构治理、专题入口设计、命名合同、索引审计、Agent 开工约束，以及主题工作区的渐进升级规则。凡是“文档怎么组织、规则怎么复查、入口怎么收口”这类任务，都先从这里进。

当前 `meta/` 是本仓库第一个启用轻量工作区的专题：除 `README.md` 外，已启用 `PROGRESS.md`。如果后续再出现多条并行子线或 dated 证据开始密集增长，再按需增加 `tasks/`、`runs/`、`archive/`；默认阅读顺序保持 `README.md -> PROGRESS.md -> 当前主线文档 -> runs/archive`。

## 命中信号

- 任务提到文档结构、专题入口、索引、orphan docs、命名合同、dated 文档规则
- 任务提到 `Run-DocsIndexAudit.ps1`、`DocsStructureContractTest`
- 任务是在调整 `README.md`、`STARTUP_GUIDE.md`、`WAKE.md`、`AGENTS.md`、`docs/README.md`、专题 `README.md` 的协作边界

## 最小阅读顺序

1. `../DOCS_GOVERNANCE.md`
2. `PROGRESS.md`
3. `../../WAKE.md`
4. `../../AGENTS.md`
5. `../../STATE.md`
6. `../../DECISIONS.md`
7. 如果任务已明确落在某条子线，再进入下面对应主线文档或审计入口。

## 稳定基线

- `../DOCS_GOVERNANCE.md`
- `../../README.md`
- `../../STARTUP_GUIDE.md`
- `../../WAKE.md`
- `../../AGENTS.md`
- `../../STATE.md`
- `../../DECISIONS.md`

这些文档更接近“今天仍然为真”的文档治理合同、开工顺序和稳定规则。若本轮修改改变了结构合同或入口顺序，优先回写这里。

## 当前主线文档

### 总索引与入口分流

- `../../README.md`
- `../../STARTUP_GUIDE.md`
- `../README.md`
- `../../WAKE.md`
- `../../AGENTS.md`

### 结构合同与命名合同

- `../DOCS_GOVERNANCE.md`

### 审计与回归入口

- `../../scripts/Run-DocsIndexAudit.ps1`
- `../../src/test/java/com/agentcloud/docs/DocsStructureContractTest.java`
- `../../src/test/java/com/agentcloud/docs/DocsIndexAuditScriptTest.java`

### 活跃进度写回

- `PROGRESS.md`
- `../../STATE.md`
- `../../DECISIONS.md`

## 写回顺序

- 先改 `../README.md`（即 `docs/README.md` 总索引）
- 再改对应 `docs/<topic>/README.md`
- 如果结构合同本身变化，再改 `../DOCS_GOVERNANCE.md`
- 当前活跃文档治理进度写到 `PROGRESS.md`
- 跨主题短摘要写到 `../../STATE.md`
- 稳定规则写到 `../../DECISIONS.md`
- 默认写回链：`docs/README.md -> docs/<topic>/README.md -> DOCS_GOVERNANCE.md -> PROGRESS.md / STATE.md / DECISIONS.md`

## 历史材料使用规则

- 文档治理任务优先补入口、补阅读顺序、补审计规则，不优先做历史文件物理迁移。
- 历史结构结论默认以 `STATE.md` / `DECISIONS.md` 的 dated 条目为准，不再从零散对话回溯。
- 若某条规则已经稳定，应回收到 `DOCS_GOVERNANCE.md`，不要长期只留在 `PROGRESS.md`。
