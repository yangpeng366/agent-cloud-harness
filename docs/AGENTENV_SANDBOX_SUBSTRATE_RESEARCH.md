# AgentENV 沙箱执行底座调研与启发

> 调研对象：kimi3（月之暗面 Kimi K3）开源的 AgentENV（https://github.com/kvcache-ai/AgentENV），本地克隆于 `F:\github\AgentENV`（GitHub 直连 443 超时，经 `gh-proxy.com` 镜像克隆成功）。
> 性质：外部项目调研，结论用于启发 agent-cloud-harness 演进，本轮不落代码。

## 调研背景

Kimi K3 团队开源了 AgentENV（AENV）——驱动其 agentic RL 训练的“agent 运行环境”平台。本仓库 agent-cloud-harness 定位是 agent loop 的控制面/编排 harness（任务状态、packet 连续性、worker 路由、goal 进度、控制图、恢复与 handoff），二者处在不同层：AENV 解决“agent 在哪跑、怎么隔离、怎么快起快停、怎么 fork”，harness 解决“哪个 agent 跑、跑到哪、失败怎么恢复、怎么交接”。本调研目的是看 AENV 的设计与机制能给 harness 带来哪些启发，哪些可以吸收，哪些是 scope 不匹配。

## AgentENV 是什么

- 语言与形态：Rust workspace（核心 server + `aenv` CLI + 若干 crate）+ Go 分布式控制面（`services/` gateway/scheduler）+ mdBook 文档。E2B 兼容 HTTP API，可直用 E2B Python/TS SDK。
- 核心定位：以 Firecracker microVM 为单位，大规模运行可快照、可 fork 的隔离 agent 执行环境；本地磁盘做有界缓存，镜像按需经 overlaybd 加载，无需每台机预热。
- 关键能力（README 卖点）：
  - 跨机跨镜像大规模运行 Firecracker 环境，overlaybd 按需加载，本地盘做有界缓存、冷数据淘汰。
  - 快照使空闲环境廉价：启动/恢复 <50ms，暂停 <100ms，空闲释放 CPU/内存，有活再来。
  - 原生快照与 fork：内存+文件系统增量快照 <100ms（重盘改写下亦然）；运行中环境可 fork 成多个独立沙箱做并行 agent 工作流；快照持久化到 S3 兼容对象存储或共享分布式文件系统。
  - 长时间保持性能与密度：ublk 高性能 I/O，宿主 page cache 跨存储与内存快照数据共享；memory ballooning 回收可回收 guest 内存，支撑高超分。
- 前置：Linux 6.8+、`/dev/kvm`、Ubuntu 24.04（安装脚本）。
- 安全现状（与 harness 同病）：README 明确“当前不支持鉴权，不要暴露到公网，只在受信网络或鉴权代理后运行”——这与 harness 的 S01（所有端点匿名访问）是同一类风险。

## 核心抽象与机制

### Sandbox 生命周期状态机

`src/orchestrator/types.rs` 定义 `SandboxState`：Creating / Resuming / Running / Snapshotting / Forking / Pausing / Paused / Killing。生命周期：Creating -> Running ->（Pausing -> Paused -> Resuming -> Running / Snapshotting / Forking / Killing）。每个 sandbox 有 TTL，到期默认 pause（保留状态）或 kill（永久删除）。

### Template / Snapshot 三层持久化

- Template = 用户面（OCI image -> committed snapshot），支持 `aenv pull`（image -> template）与 `aenv build`（Dockerfile 式声明式构建 -> snapshot）。
- Snapshot = 持久运行层：内存快照 + 磁盘 writable 层，可 fork、可持久化。
- 三层存储模型（`docs/src/concepts/snapshots.md`、`docs/src/internals/persistence-artifact-inventory.md`）：
  1. Builder staging（构建期临时工作区，非持久真相）
  2. Committed snapshot repository（发布后的持久真相，source of truth）
  3. Node-local runtime cache（启动前派生的运行时输入，可从 committed 元数据重建，disposable）
- 所有权纪律明确：snapshot 仓库产物是持久用户可见状态，node-local GC 不得删；paused sandbox 产物只对该 paused sandbox 持久；runtime `image.json` 是派生产物，必须可从 committed 元数据重建。

### 生命周期 Hook 与 instance identity

`docs/src/concepts/custom-extension.md`：外部扩展通过 hook 参与沙箱生命周期：start-fresh / start-resume / patch-params / stop。关键设计：

- `sandboxId` 跨 pause/resume 复用；每次 start 带新 `sandboxInstanceId`。
- `stop` 是 best-effort 且可能乱序（pause 的 stop 可能在 resume 的 start-resume 之后到达），扩展按 `(sandboxId, sandboxInstanceId)` 作为运行实例身份，忽略非最新实例的 stop。
- `stop` 也在 pause 时触发（pause 持久化后停 VM、释放 netns；resume 起新 runtime 触发 start-resume）。原地 pause+resume 期间不触发 hook。

### 其他机制

- envd：guest 内守护进程，负责命令执行、流式输出、进程管理、健康上报。
- Reverse Proxy：把客户端 HTTP/WebSocket 路由进沙箱内服务（`/proxy`、routing header、sandbox 代理域名）。
- P2P（`src/p2p/`，iroh-based）：可选的 node 间 artifact 分发；scheduler 只存 key -> node 提示索引，不转发字节、不存 locator。
- 分布式控制面（Go，`services/`）：gateway 按 sandbox ID 路由；scheduler 做 node 选择/绑定/心跳/P2P peer 发现；支持 static / kubernetes 两种发现模式。
- 可观测性：host / machine / model 三层 + Prometheus。

## 与 agent-cloud-harness 的定位关系

| 维度 | agent-cloud-harness | AgentENV |
|---|---|---|
| 层 | agent loop 控制面/编排 | agent 执行底座/沙箱 |
| 语言 | Java 21（虚拟线程、Maven） | Rust + Go |
| 隔离 | 无；worker（codex CLI）直接对宿主仓库目录执行 | Firecracker microVM + 网络命名空间，内核级隔离 |
| 状态连续性 | ResumePacket（文本化）+ checkpoint + SQLite | 内存+磁盘快照（整环境可恢复） |
| 并行 | sibling lane auto_handoff（换 worker） | fork 运行中沙箱成多个独立副本 |
| 部署 | 单机本地 | 单机 / docker-compose / k8s DaemonSet |
| 鉴权 | 无（S01） | 无（README 同样警告） |

结论：二者互补而非竞争。harness 缺的正是 AENV 这一层“隔离、可快照、可 fork 的执行环境”。harness 最近在“workspace 安全边界”上踩坑（codex 静默回退到 harness 仓库烧 token），根因就是没有真正的执行环境隔离层——AENV 是这一层的北极星参照。

## 启发与可吸收点

按对 harness 现有主线的相关性与可落地性排序：

### 1. 生命周期 hook 的 instance identity -> 直接对应 enter 异步化后的事件乱序（高相关）

harness 2026-07-28 刚把 controlGraph.enter 改异步以避免 HTTP 超时 kill worker round 线程，但异步化会引入“worker round 完成事件 vs 任务状态变更”的潜在乱序。AENV 的 `(sandboxId, sandboxInstanceId)` + “忽略非最新实例的 stop”是处理这类异步生命周期事件乱序的成熟范式：给每个 worker round / runtime 实例一个单调 instanceId，恢复/重置时丢弃旧实例的迟到事件。建议在控制图事件处理中显式引入 instance-id 比较，作为 enter 异步化的回归保护补强。对应主线：continuity 控制面主链、provider PROGRESS（enter 异步化）。

### 2. 快照三层持久化纪律 -> harness packet/checkpoint 模型对齐 + persistence inventory（高相关）

AENV 的“committed = source of truth，runtime cache = 可重建 disposable”纪律，几乎一一对应 harness 现状：ResumePacket / committed checkpoint = source of truth，runtime/active context + RuntimeFactSet = 派生可重建，worker output = 临时。harness 已部分这么做但未显式成文。建议：

- 借鉴 `docs/src/internals/persistence-artifact-inventory.md` 的“按模块列产物 + 位置 + 内容 + 用途 + 生命周期 + 是否可重建”表格，为 harness 自己的持久化产物（SQLite 各表、agent_runs、packet、checkpoint、control graph 事件）写一份等价 inventory，明确每项的 owner / 是否 source of truth / 是否可重建。
- 固化不变式：runtime 派生面必须可从 committed packet/checkpoint 重建，node-local 缓存不得被当成真相。对应主线：continuity（packet）、`HARNESS_CHANGE_CONTRACT.md`。

### 3. 执行沙箱/工作区隔离的北极星（中相关，方向性）

AENV 每个 agent 一个 microVM + 独立 netns，是“工作区隔离”的理想形态。harness 不可能上 Firecracker（太重），但 AENV 指明了方向：harness 的“workspace”应从“指向任意宿主目录”演进为“有显式生命周期的隔离工作区”——至少是 per-task 独立工作目录 + 显式 create/pause/resume/kill，而非把 codex 直接指向操作者的真实仓库。这与 `LLM_TASK_UNDERSTANDING_PLAN.md`（workspace 定位完全 provider-driven）和 provider PROGRESS 的 workspace 安全边界收敛是同一条线的远期目标。

### 4. Fork 并行探索 -> 控制图分支/sibling lane 演进方向（中相关，方向性）

AENV fork 运行中沙箱成最多 16 个副本做并行 agent 工作流。harness 现在的“sibling lane auto_handoff”是失败后换 worker；fork 范式提示另一种执行模型：从同一 checkpoint 并行派生多个探索分支、择优合并——即“树搜式”执行。这与控制图的分支能力、`NEXT_EVOLUTION_PLAN.md` E1（Loop Decide 深度消费 Goal Progress）方向契合，可作为 loop decide 的远期执行后端构想，本轮仅记方向。

### 5. Template builder（声明式任务环境模板）-> evaluation task pack / 可复现任务环境（中相关）

AENV template = 声明式（Dockerfile/OCI image config）构建出 committed snapshot，产出可复现的预装环境。harness 的 evaluation task pack 可借鉴：把“任务需要哪个仓库 + 哪些依赖 + 初始状态”声明式化，产出可复现的任务起始 checkpoint，替代当前“workspace_root 指向某目录”的隐式做法。对应主线：evaluation（多轮任务包、benchmark）、`PROJECT_EVOLUTION_MULTI_ROUND_TASK_PACK.md`。

### 6. TTL + auto-eviction（pause/kill）-> human_gate 长任务环境释放（低-中相关）

AENV 每 sandbox 有 TTL，到期 pause（保留状态，空闲释放资源）或 kill。harness 的 worker 超时是 per-round（已做 tier-aware），但 human_gate / waiting_human 的长任务环境会一直占着。可借鉴：长任务等人工时把环境“暂停”（释放 codex 进程/资源），人工响应后“恢复”。harness 现在靠 DB packet 保状态、不保活环境，这条更偏远期。

### 7. E2B 兼容 API 作为互操作标准（低相关，记方向）

AENV 刻意暴露 E2B 兼容 API，直用标准 E2B SDK。教训：暴露能力时优先采既有标准接口而非自造。若 harness 未来要暴露“代码执行”能力，应优先对齐 E2B / OpenAI code interpreter 形态。同时 AENV 的 API 是“agent 与环境的契约”——harness 的 worker/agent_run 契约可参考这种“执行环境作为独立契约层”的清晰切分。

### 8. 可观测性 host/machine/model 三层（低相关）

AENV 把可观测性分 host（节点）/ machine（per-VM）/ model（语义）三层 + Prometheus。harness 的 RuntimeFactSet + live flow 可借鉴这个分层框架：harness-node（JVM/宿主）/ per-task（machine 等价）/ per-goal-loop（model 等价）。

### 9. 文档卫生：生成代码边界 + Conventional Commits + 密集事实式 agent 指南（低相关，卫生项）

- AENV 把生成代码（OpenAPI client/server、firecracker client）明确标 machine-managed + `make` 重生成目标 + Conventional Commits。harness 可借鉴生成代码边界标注。
- AENV 的 `AGENTS.md` 只写“CLAUDE.md”重定向，CLAUDE.md 是密集事实式（项目是什么、构建/测试命令、每 crate 职责、约定）。harness 的 WAKE/AGENTS 是流程式（开工顺序、协作规则）。两种风格可互鉴：harness 可在保持流程式的同时，确保 AGENTS.md 不膨胀、关键事实（构建/测试命令、模块职责）集中可查。

## 不应吸收的部分（scope 不匹配）

- Firecracker / ublk / overlaybd：AENV 的核心 infra，但对本地 Java harness 过重，不可移植；harness 不应追求 microVM 级隔离。
- Go 分布式控制面（gateway/scheduler）：harness 单机本地，其“调度”是 worker lane 路由而非 node 选择。
- iroh P2P 层：单机过重；仅当 harness 未来要跨操作员机共享“准备好的任务环境/checkpoint”时才有概念价值。

## 落点与下一步建议

- 本轮为调研，不落代码。建议把上述第 1、2 条作为近期可落地项纳入对应主线：
  - 第 1 条（instance identity 事件乱序保护）-> continuity / provider PROGRESS，作为 enter 异步化的回归保护补强。
  - 第 2 条（harness 持久化产物 inventory）-> 新写一份 `docs/PERSISTENCE_ARTIFACT_INVENTORY.md`（或并入 `HARNESS_CHANGE_CONTRACT.md`），明确 source-of-truth / 可重建纪律。
- 第 3、4、5 条作为方向性输入记入 `NEXT_EVOLUTION_PLAN.md` / evaluation 主线，待后续主线推进时取用。
- 若 maintainer 认可，可单独开一个“执行环境隔离层”的远期构想 plan（不立即实现）。

## 参考入口（AgentENV 本地克隆 `F:\github\AgentENV`）

- 总览：`README.md`、`CLAUDE.md`（agent 开发指南，含每 crate 职责与约定）
- 架构：`docs/src/internals/architecture.md`、`docs/src/internals/services.md`（分布式控制面）
- 概念：`docs/src/concepts/sandboxes.md`（生命周期）、`snapshots.md`（三层存储）、`templates.md`、`custom-extension.md`（hook + instance identity）
- 持久化纪律：`docs/src/internals/persistence-artifact-inventory.md`
- 状态机源码：`src/orchestrator/types.rs`（`SandboxState`）
- 部署：`docs/src/deployment/`（docker / docker-compose / kubernetes / manual-compile）