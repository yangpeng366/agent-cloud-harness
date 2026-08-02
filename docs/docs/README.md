# Docs README

本文档是 `docs/` 的总索引。它只负责三件事：当前任务属于哪个主题、应该先读哪几份、做完该写回哪里。更细的结构合同、命名规则、审计口径，统一转到 `meta/README.md` 和 `DOCS_GOVERNANCE.md`。

## 开工规则

1. 任何新开工，先读根目录 `WAKE.md`，再看 `AGENTS.md`，再看本文件。
2. 判断任务属于哪个主题，直接打开对应 `docs/<topic>/README.md`。
3. 主题已经启用 `PROGRESS.md` 的，先看 `PROGRESS.md` 再看具体文档。
4. 调研、方案、验证结论：先沉淀到 `docs/`，再决定是否动代码；动代码要回填证据。
5. 历史文档优先"提炼吸收"，不要把物理迁移当第一步。

## 主题索引

按 `AGENTS.md` 的约定，本仓库当前在以下主题设立了独立工作区：

- `continuity/`：任务持久化、resume packet、checkpoint 链路。
- `provider/`：provider 协议、CLI 接入、能力画像、dispatch preflight。
- `dialogue/`：web console、dialogue 阅读面、消息流。
- `evaluation/`：探针任务、control flow 实验、§4 验证证据。
- `release/`：发布、迁移、版本管理。
- `meta/`：文档治理、结构合同、入口收口、规则同步。

每个主题的入口都是 `docs/<topic>/README.md`，活跃进度的摘要在 `docs/<topic>/PROGRESS.md`（仅当前持续高频推进的主题启用）。

## 跨主题落点

跨主题短摘要写在 `STATE.md`，稳定设计决策写在 `DECISIONS.md`，这两个文件都在仓库根目录。不要把跨主题结论塞进单主题的 README。

## 与代码的关系

- 文档只描述"为什么 / 怎么用"，具体函数、字段、状态机细节回到代码注释与 `docs/API_CONTRACTS.md`。
- API 契约改动要先改 `docs/API_CONTRACTS.md`，再改代码，最后回填测试。
- 状态机改动要先改 `docs/SPEC.md`，再改代码，最后回填 `docs/TROUBLESHOOT.md`。

## 文档入口速查

| 想问的事 | 入口文件 |
| --- | --- |
| 模块边界 / 进程边界 | `docs/ARCHITECTURE.md` |
| API 字段、存储表、JSON 形状 | `docs/API_CONTRACTS.md` |
| 状态机、控制图节点 | `docs/SPEC.md` |
| 已知坑、排查步骤 | `docs/TROUBLESHOOT.md` |
| Web Console / Dialogue 渲染 | `docs/WEB_CONSOLE.md` |
| 当前推进主线 | `docs/continuity/README.md`、`docs/provider/README.md`、`docs/dialogue/README.md`、`docs/evaluation/README.md`、`docs/release/README.md`、`docs/meta/README.md` |
| 跨主题进度 | `STATE.md` |
| 稳定设计决策 | `DECISIONS.md` |
| 启动与部署 | `README.md`、`STARTUP_GUIDE.md` |
| Agent 开工入口 | `WAKE.md`、`AGENTS.md` |