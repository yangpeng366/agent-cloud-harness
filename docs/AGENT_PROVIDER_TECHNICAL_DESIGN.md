# Agent Provider 接入技术设计（面向 agent-cloud-harness）

## 1. 文档目标

本文档是 `MULTICA_BENCHMARK_AND_BORROWING_PLAN.md` 的直接续篇。

目标不是抽象讨论，而是回答：

1. 如何在 `agent-cloud-harness` 里引入 **Agent Provider** 概念
2. 如何把 Codex / Claude Code / OpenClaw / OpenCode 这类真实 Agent 接进当前 control plane
3. 如何尽量少破坏现有 `task / session / worker / control graph / continuity packet` 骨架
4. 第一阶段最小实现应该改哪些包、哪些接口、哪些 API

当前这份文档的定位也需要收紧一下：

- 它不再只是“纯未来设计稿”
- 更准确地说，它描述的是 **已经进入代码与 API 接面阶段的 provider 接入面，下一步如何继续收硬**

建议与以下文档配套阅读：

- `ARCHITECTURE.md`
- `API_CONTRACTS.md`
- `AGENT_PROVIDER_API_CONTRACT_ADDENDUM.md`
- `AGENT_INVENTORY_AND_RUNTIME_HEALTH_CONSOLE_PLAN.md`
- `GOAL_ORIENTED_EVAL_PLAN.md`
- `EVAL_SCENARIOS.md`

---

## 1.1 当前状态说明

截至当前仓库状态，这条 Provider 线已经不是纯设计：

- `agent/` 包下已存在 `AgentProvider`、`AgentProviderRegistry`、`AgentProviderStatus`、`AgentRunRef` 等基础骨架
- `server/` 已挂出 `/api/v1/agents`、`/api/v1/agent_runs`、`/api/v1/runtime_health`
- `tasks/{id}` 相关接口已补出 `provider_selection` 与 `agent_run` 读取面
- `AgentRunService` 已开始把 worker 执行结果投影成 provider-aware 的 run / event / artifact 观测对象

因此本文档后续各节应理解为：

- 一部分描述当前已存在的接入面
- 一部分描述下一步要补硬的边界、契约与运行质量问题

---

## 2. 设计原则

### 2.1 保留现有 control plane 核心
不推翻当前项目已有的：
- `TaskService`
- `SessionService`
- `ControlNodeGraph`
- `WorkerRouter`
- `PacketBuilder`
- `TaskRuntimeContextBuilder`
- `ToolAwareWorkerExecutor`
- `ExperimentRunService`

新设计应作为 **增量层** 叠加，而不是重构性替换。

### 2.2 把 Provider 与 Worker 分层
当前已经在代码结构和 API 面上开始区分两个概念，后续需要继续把边界收硬：

#### Agent Provider
表示真实接入源，例如：
- codex
- claude-code
- openclaw
- opencode

#### Worker Role
表示编排中的执行角色，例如：
- planner
- executor
- reviewer
- consolidator

这样才能支持：
- 一个 Provider 承担多个角色
- 同一个角色由不同 Provider 实现
- orchestration 策略与具体 Agent 解耦

### 2.3 Provider 负责“接入与运行”，Worker 负责“路由与角色”
边界建议：

- **Provider Layer**：解决怎么发现、怎么启动、怎么执行、怎么看状态
- **Worker Layer**：解决任务应该交给谁、哪个角色、为什么这么选

### 2.4 machine-readable first
所有新对象、新 API、新 trace 字段，保持当前项目已有风格：
- 结构化优先
- trace 解释明确
- human summary 作为补充，不替代结构字段

---

## 3. 总体架构

当前仓库已经出现这层增量骨架，下面这张图更适合被理解成“现有接入面 + 下一步收硬方向”：

```text
Task / Control Graph
        |
        v
WorkerRouter (role-aware)
        |
        v
AgentProviderResolver / provider-aware projection
        |
        v
AgentProviderRegistry
        |
   +----+----+-------------------+
   |         |                   |
   v         v                   v
Codex     ClaudeCode         OpenClaw ...
Provider   Provider           Provider
        |
        v
AgentRuntimeSupervisor
        |
        v
CLI / Local runtime / Session process / Logs
```

说明：
- `WorkerRouter` 仍保留，用于决定角色与任务分配逻辑
- `AgentProviderRegistry` 用于统一管理真实 provider
- `AgentRuntimeSupervisor` 仍是后续值得补上的完整执行层
- 当前代码更偏向先用 `AgentProviderResolver + AgentRunService` 把 provider 维度投影进现有控制流
- 后续若 provider 执行链继续增强，再引入更完整的 `AgentExecutionPlanner`

---

## 4. 当前代码骨架与后续包结构

```text
src/main/java/com/agentcloud/
  agent/
    AgentProvider.java
    AgentProviderRegistry.java
    AgentProviderDescriptor.java
    AgentProviderStatus.java
    AgentProviderResolver.java
    AgentDiscoveryService.java
    SimpleAgentDiscoveryService.java
    AgentSessionRef.java
    AgentRunRef.java
    AgentArtifactRef.java
    AgentRunResult.java
    providers/
      CodexProvider.java
      OpenClawProvider.java

  engine/
    AgentRunService.java

  server/
    AgentHandler.java
    AgentRunHandler.java
    RuntimeHealthHandler.java
```

如需持久化 provider/run 维度，再补：

```text
src/main/java/com/agentcloud/store/
  AgentRunDao.java
```

其中更偏未来态、当前尚未落地的包括：

- `AgentExecutionPlanner / AgentExecutionPlan`
- 更完整的 `AgentRuntimeSupervisor`
- 更多 provider 实现，如 Claude Code / OpenCode
- provider inventory 的独立持久化表

---

## 5. 核心领域模型设计

## 5.1 AgentProviderDescriptor
表示一个 provider 的稳定身份与静态能力。

建议字段：

```ts
record AgentProviderDescriptor(
  String providerId,
  String displayName,
  String providerType,
  String transport,
  List<String> capabilities,
  Map<String, Object> metadata
)
```

字段说明：
- `providerId`: `codex`, `claude-code`, `openclaw`, `opencode`
- `displayName`: UI 展示名
- `providerType`: `local_cli` / `embedded` / `remote_api`
- `transport`: `pty` / `process` / `http` / `inproc`
- `capabilities`: 如 `chat`, `code`, `patch`, `session`, `tool_use`

---

## 5.2 AgentProviderStatus
表示某 provider 当前状态。

```ts
record AgentProviderStatus(
  String providerId,
  boolean installed,
  String version,
  String authStatus,
  boolean ready,
  String readinessReason,
  Instant checkedAt,
  Map<String, Object> metadata
)
```

建议 `authStatus` 枚举：
- `ok`
- `auth_needed`
- `unknown`
- `unsupported`

建议 `ready` 语义：
- 当前机器上确实可以接单执行

---

## 5.3 AgentSessionRef
表示 provider 侧会话引用。

```ts
record AgentSessionRef(
  String providerId,
  String sessionId,
  String externalSessionId,
  Map<String, Object> metadata
)
```

说明：
- `sessionId` 为 harness 内部引用 id
- `externalSessionId` 为 provider 原生 session 标识

---

## 5.4 AgentRunRef
表示一次 provider 侧执行引用。

```ts
record AgentRunRef(
  String providerId,
  String runId,
  String sessionId,
  String taskId,
  String status,
  Instant startedAt,
  Map<String, Object> metadata
)
```

建议状态：
- `queued`
- `running`
- `completed`
- `failed`
- `cancelled`

---

## 5.5 AgentArtifactRef
表示 provider 运行产生的工件。

```ts
record AgentArtifactRef(
  String providerId,
  String runId,
  String artifactType,
  String title,
  String path,
  String summary,
  Instant createdAt,
  Map<String, Object> metadata
)
```

---

## 6. AgentProvider 接口建议

```java
public interface AgentProvider {
    AgentProviderDescriptor descriptor();

    AgentProviderStatus detect();

    AgentProviderStatus refreshStatus();

    AgentSessionRef createSession(AgentCreateSessionRequest request);

    AgentRunRef runTask(AgentRunTaskRequest request);

    AgentRunResult getRun(String runId);

    List<AgentArtifactRef> listArtifacts(String runId);

    void interruptRun(String runId);
}
```

说明：
- `detect()` 偏轻量探测，可在启动时统一跑
- `refreshStatus()` 可用于手动刷新 readiness/auth/version
- `runTask()` 是第一阶段最关键的统一入口
- `getRun()` 用于查询状态与基础输出
- `listArtifacts()` 保持当前项目 artifact 语义一致

---

## 7. 请求对象设计

## 7.1 AgentCreateSessionRequest

```java
public record AgentCreateSessionRequest(
    String taskId,
    String preferredRole,
    String workingDirectory,
    Map<String, Object> metadata
) {}
```

## 7.2 AgentRunTaskRequest

```java
public record AgentRunTaskRequest(
    String taskId,
    String sessionId,
    String workerRole,
    String objective,
    String prompt,
    String workingDirectory,
    Map<String, Object> context,
    Map<String, Object> metadata
) {}
```

这里的 `context` 可以直接复用：
- runtime context 摘要
- latest packet 摘要
- learned hints
- route trace

这样 provider 执行时能自然接入现有 continuity 资产。

---

## 8. Provider Registry 设计

## 8.1 AgentProviderRegistry 职责

```java
public class AgentProviderRegistry {
    AgentProvider get(String providerId)
    List<AgentProvider> list()
    List<AgentProviderStatus> listStatuses()
    void register(AgentProvider provider)
    AgentProviderStatus refresh(String providerId)
}
```

职责：
- 管理所有 provider 实例
- 暴露 provider status 列表
- 供 UI / API / routing 查询

建议实现方式：
- 第一阶段直接内存 registry
- provider status 实时 detect，不急着先入库
- 后续如果需要历史追踪，再做表

---

## 9. Runtime Supervisor 设计

## 9.1 AgentDiscoveryService
负责本机 agent 探测。

最小能力：
- 检查命令是否存在
- 执行 `--version` 或类似探针
- 返回 provider status

第一阶段支持：
- codex
- openclaw

第二阶段再扩：
- claude-code
- opencode

建议接口：

```java
public interface AgentDiscoveryService {
    AgentProviderStatus detect(String providerId);
    List<AgentProviderStatus> detectAll();
}
```

## 9.2 AgentRuntimeSupervisor
负责 provider 执行生命周期。

职责：
- 启动外部 CLI 进程
- 记录 runId -> process 关联
- 收集 stdout/stderr
- 检测退出码
- 暴露运行状态

建议接口：

```java
public interface AgentRuntimeSupervisor {
    AgentRunRef startRun(AgentProvider provider, AgentRunTaskRequest request);
    AgentRunResult getRun(String runId);
    void interrupt(String runId);
    List<AgentRuntimeEvent> recentEvents(String runId, int limit);
}
```

### 运行状态建议
- `queued`
- `starting`
- `running`
- `completed`
- `failed`
- `cancelled`

### 事件建议
- `run.started`
- `run.stdout`
- `run.stderr`
- `run.completed`
- `run.failed`
- `artifact.created`

---

## 10. Worker 与 Provider 连接层

## 10.1 新增 AgentExecutionPlanner
这是非常关键的一层。

原因：
- 现有 `WorkerRouter` 不该直接知道 Codex/Claude/OpenClaw 的 CLI 细节
- provider 层也不该直接决定 orchestration role

所以需要一个桥接层，把：
- task
- route result
- runtime context
- packet / learning hint

转成：
- provider 选择
- provider 执行请求

建议接口：

```java
public interface AgentExecutionPlanner {
    AgentExecutionPlan plan(Task task,
                            WorkerRouter.RouteResult route,
                            TaskRuntimeContext runtimeContext);
}
```

`AgentExecutionPlan` 建议包含：
- `providerId`
- `workerRole`
- `selectedWorkerId`
- `objective`
- `prompt`
- `workingDirectory`
- `selectionTrace`
- `metadata`

这样后续 control graph 只需要拿 plan 去执行。

---

## 11. 与现有核心模块的改造点

## 11.1 Main.java
建议新增装配顺序：

1. `AgentDiscoveryService`
2. `AgentRuntimeSupervisor`
3. `AgentProviderRegistry`
4. 注册 provider 实例
5. `AgentExecutionPlanner`
6. 注入 `TaskService` / `ControlNodeGraph` / API handler

### 当前状态
`Main` 和 `NioHttpServer` 已经开始插入 provider 相关装配与 HTTP 路由。

### 继续补硬方向
先不大改 `Main` 的总体结构，只继续保持 provider 相关对象以内存注册 + 显式装配的方式接入。

---

## 11.2 WorkerRouter.java
当前已经有：
- `selectedWorker`
- `selectedModelTier`
- `selectedExecutionRole`
- `whySelected`
- `fallbackReason`

当前更适合 Phase 1.5 的做法仍是：扩充 trace 字段，不改原路由骨架。

建议继续补强的字段：
- `selectedProvider`
- `providerReason`
- `providerReadiness`
- `providerAuthStatus`

注意：
- `WorkerRouter` 本身不直接调用 provider
- 当前可以先由 `AgentProviderResolver` 和 task/provider 读面完成投影，后续再决定是否引入 `AgentExecutionPlanner`

---

## 11.3 TaskService.java
建议新增接口：
- `getProviderSelection(taskId)`
- `getAgentRunTrace(taskId)`
- `listAgentProviders()`

并在 task 相关 view 中增加：
- selected provider
- provider run status
- provider artifacts

### 最小改造点
创建 task 后，controlGraph 在选中 worker 后，如果进入 provider-aware path：
1. 构建 runtime context
2. 调用 `AgentExecutionPlanner`
3. 调用 provider `runTask`
4. 将 run 结果映射回当前 artifact / event / decision 体系

补充说明：

- 当前仓库已经先落了一条更保守的路径：执行仍主要沿现有 worker/control graph 进行，再由 `AgentRunService` 把结果投影到 provider-aware 读面
- 这使得近期可以先服务评估文档里的“小型真实任务闭环 proof”，而不是立刻重构全部执行链

---

## 11.4 ControlNodeGraph
这里是落地关键点。

建议不要把 provider 直接塞进每个 node，而是在“真正执行 worker round”的节点里引入 provider-aware execution。

也就是：
- control graph 仍负责流程推进
- provider layer 只参与具体执行节点

这样可以避免整个控制图被 provider 细节污染。

---

## 11.5 API 层
当前已经落地了一组 Agent API，后续重点是补契约稳定性和聚合读面。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/agents` | 列出全部 provider 状态 |
| GET | `/api/v1/agents/{id}` | 查看单个 provider |
| POST | `/api/v1/agents/{id}/refresh` | 刷新 provider 状态 |
| GET | `/api/v1/agents/{id}/runs` | 查看该 provider 最近 runs |
| GET | `/api/v1/agent_runs` | 按 provider/status/role/task 搜索 runs |
| GET | `/api/v1/agent_runs/{runId}` | 查看单次 provider run |
| GET | `/api/v1/agent_runs/{runId}/events` | 查看 provider run 事件 |
| GET | `/api/v1/agent_runs/{runId}/artifacts` | 查看 provider run 工件 |
| GET | `/api/v1/runtime_health` | 查看 managed runtime 健康摘要 |
| GET | `/api/v1/tasks/{id}/provider_selection` | 查看任务对应 provider 选择 |
| GET | `/api/v1/tasks/{id}/agent_run` | 查看任务最新 provider run |

这样可以与现有：
- `/workers`
- `/tasks`
- `/sessions`

形成互补，而不是替代。

---

## 12. 数据库落地建议

## 12.1 Phase 1 可不强制入库 provider inventory
当前已经处于“Phase 1 和 1.5 之间”的状态：

- provider status 仍以动态探测为主
- run trace 已开始经 `agent_runs` 和 event / artifact metadata 投影落盘
- provider inventory 仍未独立持久化

## 12.2 Phase 2 建议新增两张表

### `agent_runs`
建议字段：
- `run_id`
- `task_id`
- `session_id`
- `provider_id`
- `worker_role`
- `selected_worker_id`
- `status`
- `started_at`
- `ended_at`
- `exit_code`
- `summary`
- `output_path`
- `metadata_json`

这样后面 console / experiment / diagnostics 才有稳定数据面。

---

## 13. Phase 1 最小实现建议

### 13.1 当前阶段目标
**把 provider 作为一等对象稳定接入系统，并让它服务于近期“小任务闭环 proof”。**

### 13.2 第一阶段范围

#### 已基本完成
- 新增 `agent/` 包和基础接口
- 新增 `AgentProviderRegistry`
- 新增 `AgentDiscoveryService`
- 新增 `OpenClawProvider`、`CodexProvider` skeleton
- 新增 `/api/v1/agents`
- 已能通过 task 相关接口看到 provider 选择与最新 run

#### 继续必做
- 把 provider 维度进一步补进 `live_flow` / route trace / experiment 读面
- 收紧 provider probe 的稳定性和错误语义
- 校准 provider run 与 task acceptance 之间的映射关系，服务评估文档中的近端 proof

#### 暂缓
- 不先做复杂 session resume 到外部 provider
- 不先做全量 run log persistence
- 不先做 distributed runtime
- 不先改造全部 experiment schema

---

## 14. Phase 1 建议开发顺序

前 4 步骨架已经基本出现，后续建议开发顺序调整为：

### Step 5
把 provider trace 继续注入 `live_flow`、`experiment_run`、`harness_trace`

### Step 6
补 `Agent Inventory / Runtime Health` 的 console 面板

### Step 7
收紧 provider-ready 判定、auth 语义和 run failure 分类

---

## 15. 风险与边界提醒

### 风险 1，Provider 抽象过早过重
如果一开始就想把所有外部 agent 的所有能力统一，会把设计做得很重。

建议：
- 当前先守住最小能力：detect / status / run projection / artifacts / events

### 风险 2，Worker 与 Provider 混淆
如果让 `WorkerRouter` 直接变成 ProviderRouter，会伤到当前 orchestration 结构。

建议：
- Worker 继续表示 role / execution target
- Provider 表示真实接入源

### 风险 3，把 continuity 价值稀释掉
如果全盘按 Multica 思路走，可能会把项目做成“多 agent 列表 + 任务面板”，反而削弱当前项目的 continuity 优势。

建议：
- Provider 层做观测与接入外壳
- Packet / Handoff / Runtime Context / eval evidence 继续做核心差异化资产

---

## 16. 结论

对 `agent-cloud-harness` 来说，Agent Provider 设计不是额外装饰，而是把当前 harness 从：

- continuity-first control plane

推进成：

- continuity-first managed agents platform

最合适的做法不是重构现有骨架，而是新增：

1. **AgentProvider 抽象**
2. **AgentProviderRegistry**
3. **AgentDiscoveryService / AgentRuntimeSupervisor**
4. **AgentExecutionPlanner**
5. **Agent API + Agent Inventory UI**

这是与当前仓库结构最贴合、也最容易推进到代码实现的一条路线。

---

## 17. 下一步建议

基于这份文档，下一步最自然的是继续补：

1. **API Contract 增补稿**
   - `/api/v1/agents`
   - `/api/v1/agent_runs`
   - task detail 新字段

2. **Console 页面方案**
   - Agent Inventory
   - Runtime Health
   - Provider Run Detail

如果直接要进开发，我建议下一份先写 **API Contract 增补稿**。
