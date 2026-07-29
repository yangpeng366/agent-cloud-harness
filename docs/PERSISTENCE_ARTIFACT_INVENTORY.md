# 持久化产物清单（Persistence Artifact Inventory）

> 本文档列出 agent-cloud-harness 在单次运行之外会留在磁盘/内存的持久化与运行时产物，按拥有其生命周期的模块组织。
> 借鉴 AgentENV 的 `persistence-artifact-inventory` 纪律（见 `AGENTENV_SANDBOX_SUBSTRATE_RESEARCH.md` 启发点 2），明确每项的 owner / 是否 source of truth / 是否可重建。
> 性质：稳定基线文档（"今天仍然为真"），行为变化时优先回写本文。

## 路径根

| 根 | 默认 | Owner | 覆盖方式 |
|---|---|---|---|
| `~/.agentcloud/` | `$user.home/.agentcloud` | `Main` | DB / 状态 / 配置默认根 |
| SQLite DB | `~/.agentcloud/agent_cloud.db` | `DatabaseManager` | `-Ddb.path=<path>` |
| 自动发现状态 | `~/.agentcloud/harness-state.json` | `HarnessStateWriter` | 不可覆盖（每次启动重写） |
| 用户配置 | `harness-config.yml` | 用户（gitignored） | 搜索路径：`./`、`./config/`、`~/.agentcloud/`、`-Dagentcloud.config.path` |

DB 连接参数（`DatabaseManager`）：HikariCP 池上限 10、`journal_mode=delete`、`busy_timeout=5000`、`foreign_keys=on`。schema 由 `src/main/resources/schema.sql` 幂等建表（`CREATE TABLE IF NOT EXISTS`），新列通过 `ensureColumn` 做 `ALTER TABLE ADD COLUMN` 兼容迁移。

## 持久化分层（committed / 派生 / 临时）

借鉴 AgentENV 三层纪律，harness 持久化分三层：

| 层 | 内容 | source of truth | 可重建 |
|---|---|---|---|
| Committed truth | SQLite 表：`sessions` / `tasks` / `resume_packets` / `checkpoints` / `decisions` | 是 | 否（用户可见状态，不得丢失） |
| Durable trace | SQLite 表：`events` / `agent_runs` / `agent_actions` / `tool_invocations` / `session_messages` / `artifacts` / `task_recovery_jobs` / `experiment_runs` | 是（历史证据，append-mostly） | 否 |
| Registry / catalog | SQLite 表：`skills` / `relations` / `learning_memories` | 是 | 部分（skills 可重新探测；learning_memories 为累积启发） |
| Auto-discovered state | `harness-state.json`（workers / providers / ccx 渠道） | 否 | 是（启动时 `HarnessStateWriter.discover` 重写） |
| User config | `harness-config.yml` | 否（用户覆盖自动发现） | 否（用户拥有） |
| Runtime derived | `ControlNodeGraph` 内存态 / `RuntimeFactSet` / `ActiveContext` / `TaskRuntimeContext` | 否 | 是（从 committed DB 重建） |
| Operational | 日志（logback `STDOUT`，默认无文件 appender） | 否 | 是（一次性） |

## SQLite 表清单与所有权

| 表 | 用途 | Owner DAO | 主要消费模块 | 生命周期 | source of truth | 可重建 |
|---|---|---|---|---|---|---|
| `sessions` | 会话/任务图根 | `SessionDao` | `TaskService`、chat facade | 随会话创建/关闭 | 是 | 否 |
| `tasks` | 核心任务状态（`control_node`/`status`/`waiting_reason`/`assigned_worker`/`metadata_json`） | `TaskDao` | `TaskService`、`ControlNodeGraph`、router | 任务全生命周期 | 是 | 否 |
| `resume_packets` | 连续性真相（`packet_version`/`payload_json` NOT NULL + 摘要字段） | `ResumePacketDao` | `PacketBuilder`、`ContextReconstructor` | pause/continue 落库，随任务存续 | 是（连续性 source of truth） | 否 |
| `checkpoints` | 长任务 checkpoint（`periodic`/`pause_before`/`handoff_before`/`session_end`，含 `refined_packet_json`/`world_model_delta_json`） | `CheckpointDao` | `ConsolidationService`、recovery | consolidation 产物，历史保留 | 是 | 否 |
| `decisions` | 决策日志（含 `supersedes_decision_id` 链） | `DecisionDao` | judgment、trace 视图 | append-mostly | 是 | 否 |
| `events` | 事件流（`worker_round` 等运行时事件，`payload_json`） | `EventDao` | `ControlNodeGraph`、live flow | append-mostly | 是（证据） | 否 |
| `agent_runs` | 单次 worker 执行记录（provider/worker/duration/status） | `AgentRunDao` | router、console 读面 | append-mostly | 是（证据） | 否 |
| `agent_actions` | bounded-autonomy 动作（`requires_approval`/`accepted_by`） | `AgentActionReconciler`/`AgentActionDao` | action 审批 | append-mostly | 是（证据） | 否 |
| `tool_invocations` | 工具调用 trace（`tool_name`/`touched_paths_json`/`success`） | `ToolInvocationDao` | tool layer、console | append-mostly | 是（证据） | 否 |
| `session_messages` | chat facade 消息 | `SessionMessageDao` | chat facade、dialogue UI | append-mostly | 是 | 否 |
| `artifacts` | 任务产物（`uri`/`content_hash`/`summary`） | `ArtifactDao` | consolidation、console | 随任务存续 | 是 | 否 |
| `task_recovery_jobs` | 恢复作业（`failure_class`/`recommended_action`/`target_worker`） | `TaskRecoveryJobDao` | recovery、planner | 恢复链存续 | 是 | 否 |
| `experiment_runs` | 评估运行（`model_mode`/`completion_status`/成本与计数） | `ExperimentRunDao` | evaluation | append-mostly | 是（评估证据） | 否 |
| `skills` | skill 注册表（`ready`/`installed`/`risk_level`） | `SkillDao` | skill registry | 注册存续 | 是 | 部分（可重新探测 readiness） |
| `relations` | 通用关系（source/target 多态） | `RelationDao` | trace、graph | append-mostly | 是 | 否 |
| `learning_memories` | 路由/上下文启发（`candidate`/`reinforced`/`stable_hint`） | `LearningMemoryDao` | router、context retention | 累积 | 是 | 否（累积启发） |

## 运行时派生层（不持久化，必须可从 DB 重建）

- **`ControlNodeGraph`**：内存编排器（6 节点：Intake -> Scheduler -> Continue -> [Packet / Human Gate / Handoff]）。durable 状态投影到 `tasks.control_node`/`status`/`waiting_reason`/`assigned_worker` + `events` + `resume_packets` + `checkpoints`。2026-07-28 enter 异步化后为 per-task 内存生命周期；重启/恢复从 DB 重建。内存态不得被当真相。
- **`RuntimeFactSet` / `ActiveContext` / `TaskRuntimeContext`**：派生运行时投影，从 `resume_packets`/`checkpoints`/`events`/`tasks` 重建，供 prompt 装配与 live flow 读面。
- **HikariCP 连接池 / JDBI handle**：临时，进程级。

## 不变式（纪律）

1. Runtime/active context（`ControlNodeGraph` 内存态、`RuntimeFactSet`、`ActiveContext`）必须可从 committed DB（packet/checkpoint/event/task）重建；内存态不得被当真相。
2. `harness-state.json` 可重建（启动重探测）；丢失只影响 provider/worker 发现直到下次探测，不影响任务真相。
3. Trace 表（`events`/`agent_runs`/`tool_invocations` 等）append-mostly，当前无自动 GC/retention（见下"已知缺口"）。
4. harness 不持久化 agent 执行环境状态（只持久化文本化 packet/checkpoint）；与 AgentENV 的"整环境内存+磁盘快照"不同，详见 `AGENTENV_SANDBOX_SUBSTRATE_RESEARCH.md`。

## 已知缺口（对比 AgentENV）

- **无 trace 表 retention/GC**：`events` / `agent_runs` / `tool_invocations` 长期单机运行会无限增长。AgentENV 有基于 lease 的 image-cache GC；harness 暂无等价。建议未来加按 task/session 的 retention 或归档。
- **无执行环境快照层**：只有文本 packet，不保活环境（跨 pause/resume 不能恢复进程/文件系统状态）。见研究文档启发点 3 / 6。
- **单 SQLite 文件无租户隔离（S04）**：所有数据共享同一 DB 文件。

## 参考入口

- schema：`src/main/resources/schema.sql`
- DB 初始化/迁移：`src/main/java/com/agentcloud/store/DatabaseManager.java`
- 启动路径配置：`src/main/java/com/agentcloud/cli/Main.java:55`
- 自动发现状态：`src/main/java/com/agentcloud/engine/HarnessState.java`、`HarnessStateWriter.java`
- 用户配置加载：`src/main/java/com/agentcloud/agent/providers/HarnessConfigLoader.java`
- 控制图：`src/main/java/com/agentcloud/engine/ControlNodeGraph.java`
- 研究背景：`AGENTENV_SANDBOX_SUBSTRATE_RESEARCH.md`