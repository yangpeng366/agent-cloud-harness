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

#### Step 7.1 provider run failure 分类字段

provider 执行器现在会在 `WorkerExecutionResult.metadata` 里输出稳定失败分类，不再只把原始 stderr/stdout 塞进 `provider_error`。`ProviderCliWorkerExecutor` 与 `CodexAppServerWorkerExecutor` 统一复用 `ProviderFailureClassifier`。

已落地字段：

- `provider_failure_class`: 面向恢复策略的稳定枚举。
- `provider_failure_reason`: 归一化空白并截断后的可读原因。
- `provider_retryable`: 当前失败是否适合自动重试或切 worker。

当前第一阶段分类：

| class | retryable | 触发信号 | 用途 |
|-------|-----------|----------|------|
| `provider_runtime_transient` | true | `thread not found`、`session expired`、`provider unavailable`、`connection reset`、`timeout`、进程启动失败 | 驱动 cold retry / handoff / provider deprioritization |
| `provider_auth_required` | false | `auth required`、`login required`、`not authenticated`、`unauthorized` | 提示登录或人工处理 |
| `provider_not_installed` | false | binary missing / command not found / not recognized | 提示安装或配置路径 |
| `provider_protocol_error` | true | JSON-RPC protocol / app-server response parse / no thread id | 允许换 worker 或冷启动 |
| `provider_execution_failed` | false | 其他 provider 执行失败 | 默认人工判断 |

设计约束：

- `ControlNodeGraph` 仍保留自己的 `failure_class`，这是任务级恢复分类。
- provider 执行器输出的是 provider 级分类，供 `AgentRunService`、runtime health、dispatch preflight 和后续 UI 直接消费。
- 任务级恢复可继续把 `provider_runtime_transient` 映射为 `worker_runtime_transient`，但不应靠全文字符串重复猜测。

当前验证入口：

- `ProviderFailureClassifierTest.classifiesThreadNotFoundAsRetryableRuntimeTransient()`
- `ProviderFailureClassifierTest.providerFailureReasonIsReadableAndBounded()`
- `ProviderCliWorkerExecutorTest.claudeMissingBinaryReturnsFailedMetadataWithoutThrowing()`
- `CodexAppServerWorkerExecutorTest.missingBinaryReturnsFailedMetadataWithoutThrowing()`

#### Step 7.2 Codex 执行结果打通方案

当前现场已经确认：Codex worker 报 `thread not found (...)` 时，不一定代表 `.codex` 不存在，也不一定代表 harness 没有成功启动 Codex。更常见的情况是：

- `CodexAppServerWorkerExecutor` 已成功启动 `codex app-server --listen stdio://`
- JSON-RPC trace 已出现 `thread/started`、`turn/started`、`item/commandExecution/outputDelta`
- Codex home 下已经生成 `sessions/YYYY/MM/DD/rollout-*.jsonl`
- 但 worker artifact 的 `output_text` 混入了命令输出、乱码、超长 stdout 或短线程号错误，导致 UI / task summary 把噪声当成根因

因此修改方向不是“寻找 `./codex` 目录”，而是把 Codex 执行数据分成三层稳定打通：

| 层级 | 数据来源 | 用途 | 注意 |
|------|----------|------|------|
| harness 结构化执行元数据 | `agent_runs.metadata_json.worker_metadata`、`artifacts.metadata_json.latest_worker_metadata` | 判断本轮是否启动、是否超时、provider thread/session 是什么 | 这是恢复和 UI 诊断的第一优先级 |
| Codex 持久化会话 | `<CODEX_HOME>/sessions/YYYY/MM/DD/rollout-*.jsonl` | 回收 Codex 实际 prompt、命令输出、agent message、工具事件 | 需要使用 Codex UUID，不要用 `thread not found (...)` 里的短数字 |
| 可选非交互输出文件 | `codex exec --json -o <file>` 或 `codex exec resume <SESSION_ID> --json -o <file>` | 获取稳定 JSONL 事件流和最终消息文件 | 更适合后续做 deterministic worker output ingestion |

推荐分两阶段改造。

**阶段 A：收紧现有 app-server 链路，不改变执行入口**

目标是继续使用 `CodexAppServerWorkerExecutor`，但让 UI、recovery 和 operator 看到结构化根因，而不是 output 噪声。

需要修改：

1. `ControlNodeGraph.buildWorkerArtifactMetadata`
   - 确保 worker artifact 顶层 metadata 同步带出：
     - `provider_session_id`
     - `provider_thread_id`
     - `resume_provider_session_id`
     - `provider_error`
     - `provider_turn_status`
     - `provider_failure_class`
     - `provider_protocol_trace`
   - 当前这些字段已经在 `latest_worker_metadata` 中可用，但 artifact 顶层字段不完整时，人工查 DB 和 UI 详情会更容易误读。

2. `AgentRunService.summarize`
   - 已经优先使用 `provider_error / provider_failure_reason`。
   - 后续需要补测试锁定：当 `output_text` 含 `thread not found (...)` 且 `provider_error=codex turn completion timed out` 时，所有 run summary / task progress / live flow primary reason 都优先显示 provider error。

3. `RuntimeFactSetAssembler` / `RuntimeCognitionSurfaceAssembler`
   - 保证 `provider_session_id / provider_thread_id / provider_error / provider_turn_status` 在 live flow execution 面板稳定出现。
   - 不要求用户翻 raw JSON 才能看到真实根因。

4. `TaskService` 消息投影
   - task progress 的 `full_content` 应优先使用结构化 `provider_error`。
   - `thread not found (...)` 可以保留在 raw output 或 evidence 中，但不应覆盖主摘要。

验收标准：

- Codex run 已经进入 `turn/started` 但超时时，UI 主摘要显示 `codex turn completion timed out`
- details / live flow 能看到 `provider_thread_id` 和 `provider_protocol_trace`
- artifact 原文仍保留 Codex 输出，便于人工回收中间结果
- recovery 仍按 `provider_runtime_transient` 走 fresh-session，不复用旧 `provider_thread_id`

当前进展（2026-05-20）：

- `ControlNodeGraph.buildWorkerArtifactMetadata` 已把 `provider_session_id`、`provider_thread_id`、`resume_provider_session_id`、`provider_error`、`provider_turn_status`、`provider_failure_class`、`provider_failure_reason`、`provider_retryable`、`provider_protocol_trace` 从 `latest_worker_metadata` 同步到 artifact 顶层 metadata。
- `RuntimeFactSetAssembler`、`RuntimeCognitionSurfaceAssembler`、`RuntimeFactSurfaceExporter` 已把 `provider_protocol_trace` 纳入 execution surface / live flow / fact export 投影。
- 已补回归验证：artifact 顶层 metadata 和 `latest_worker_metadata` 都能看到 Codex provider 诊断字段；live flow execution surface 能直接看到 `provider_protocol_trace`。
- `TaskService` 消息投影已优先使用结构化 `provider_error / provider_failure_reason` 作为失败主摘要；当 raw output / `failure_summary_readable` 仍是 `thread not found (...)` 时，task progress 的 `summary_preview`、正文和 `full_content` 的 `Failure Summary` 仍显示 `codex turn completion timed out`。
- HTTP 层已补 `/api/v1/tasks/{id}/live_flow` 验收，确认 JSON 响应中的 `runtime_cognition_surface.execution` 直接透出 `provider_session_id`、`provider_thread_id`、`provider_error`、`provider_turn_status`、`provider_failure_class`、`provider_failure_reason`、`provider_retryable`、`provider_protocol_trace`。
- provider run 文件路径已纳入 execution surface / live flow / fact export 投影，字段包括 `provider_run_dir`、`provider_prompt_path`、`provider_stdout_path`、`provider_event_log_path`、`provider_last_message_path`、`provider_run_metadata_path`。
- Dialogue / Console 诊断摘要已显示 `run files`，operator 在“Codex 已被调用但页面没拿到最终结果”时，可以直接打开本地 `last_message.md`、`events.jsonl`、`stdout.log` 或 `metadata.json` 排查。
- 新增只读 operator action：`GET /api/v1/tasks/{id}/provider_run_file?kind=last_message|events|stdout|metadata|prompt`。服务端只从该任务最新 execution metadata 里的 provider run 文件路径读取内容，不接受任意 path 参数；读取内容限制为 64 KiB，并校验目标文件位于 `provider_run_dir` 内。
- Dialogue 任务详情弹窗与 Console inspector 的工具轨迹区域已增加 `Provider Run 文件` 预览入口，可直接预览 `Last message`、`Events log`、`Stdout`、`Metadata`、`Prompt`；前端通过 live flow execution surface 生成按钮，不把文件内容默认塞进 prompt 或 SQLite。
- 验证命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=TaskHandlerLiveFlowHttpTest#liveFlowHttpExposesProviderExecutionDiagnostics,TaskServiceMessageReceiptTest#continueWritesAssistantProgressMessageWithProviderDiagnostics,TaskServiceLiveFlowViewTest#liveFlowProjectsProviderExecutionSurface,ControlNodeGraphOrchestrationFlowTest#providerBackedRoundPersistsContinuationMetadata"
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=TaskHandlerLiveFlowHttpTest#providerRunFileHttpReadsBoundedLastMessageFromLatestExecutionMetadata,TaskHandlerLiveFlowHttpTest#providerRunFileHttpRejectsPathOutsideProviderRunDir"
node --test src/test/js/dialogue-execution-boundary-plan.test.mjs
node --test src/test/js/dialogue-provider-run-file-plan.test.mjs
```

阶段 A 剩余动作：

- 如继续追求“稳定获取 Codex 最终消息”，进入阶段 B 的 `codex exec --json -o <file>` ingestion 链路。

#### Step 7.2a Codex 长任务超时与 partial timeout 合同

背景：

- 2026-05-28 现场任务 `task_e59573c1306e4e74` 中，Codex 已成功启动 app-server、创建 provider thread，并持续产出 `agent_message`、tool call、tool output。
- SQLite 中两条 Codex artifact 的 `output_text` 分别约 1.1MB / 1.3MB，但 `execution_status=timeout`，耗时约 151s / 152s。
- 对应 Codex JSONL 末尾出现 `turn_aborted`，`duration_ms=150021`，说明 harness 固定 `TURN_COMPLETION_TIMEOUT_MS=150_000` 先到点，Codex 被截断时仍在工作。
- 这类情况不是“Codex 没返回”，而是“Codex 有部分结果，但没有在固定 150s 内形成 final answer / task_complete”。

目标行为：

1. Codex 超时策略必须可配置，不能把 150s 写死成所有任务的默认上限。
2. coding / research / investigation 类任务默认应给更长窗口，建议至少 10-15 分钟。
3. app-server 等待逻辑从“固定总时长超时”升级为“活动超时 + 最大硬上限”。
4. 只要 Codex 仍在发 `agent_message`、tool call、tool output、command output delta，就不应被判定为卡死。
5. 超过最大硬上限仍未完成时，若已有有效输出，应标记为 `partial_timeout`，而不是普通 `timeout`。
6. `partial_timeout` 不应直接触发静默 auto-handoff；系统应把中间结论投影到主对话流，并给出“继续 Codex thread / 手动移交”入口。

建议配置：

| 配置 | 建议默认 | 说明 |
|------|----------|------|
| `agentcloud.providers.codex.turn_activity_timeout_ms` | `180000` | Codex 长时间无任何活动事件后才认为卡死 |
| `agentcloud.providers.codex.turn_max_duration_ms` | `900000` | 单轮最大硬上限，coding 调研默认 15 分钟 |
| `agentcloud.providers.codex.coding_turn_max_duration_ms` | `900000` | task_type 为 `coding` / `research` / `investigation` 时覆盖 |
| `agentcloud.providers.codex.partial_timeout_min_output_chars` | `200` | 有足够输出才允许归类为 partial timeout |

兼容说明：实现优先读取 `agentcloud.providers.codex.*` 文档属性，同时保留旧 `agentcloud.codex.turnActivityTimeoutMs` / `agentcloud.codex.turnMaxDurationMs` / `agentcloud.codex.codingTurnMaxDurationMs` / `agentcloud.codex.partialTimeoutMinOutputChars` 作为兼容 fallback。`coding / research / investigation` 类任务会优先使用 `coding_turn_max_duration_ms`，再回退到通用 `turn_max_duration_ms`。

执行器语义：

- `CodexAppServerWorkerExecutor.JsonRpcSession` 需要记录 `lastActivityAtMs`。
- app-server 路径的单轮完成以 Codex turn terminal event 为准，不再要求 app-server 进程本身在固定窗口内退出；turn 完成后仅给短暂清理宽限，常驻进程未退出也不会把本轮 `completed` 覆盖成 `timeout/failed`。
- `codex exec --json` 兼容路径也必须复用 `turn_max_duration_ms / coding_turn_max_duration_ms`，不能继续使用固定 180s 进程等待上限。
- `codex exec --json` 的失败/超时路径也必须保留 `provider_output_parser=codex_exec_json` 与本轮 `provider_turn_max_duration_ms`，不能因为复用通用失败构造而伪装成 `codex_json_rpc`。
- 收到以下事件时刷新 activity：
  - `agent_message`
  - `item/commandExecution/*`
  - `item/completed`
  - `outputDelta`
  - legacy `exec_command_begin / exec_command_end`
  - legacy `patch_apply_begin / patch_apply_end`
- `awaitTurnCompletion` 的循环条件应同时考虑：
  - 未超过 `turn_max_duration_ms`
  - 距离最近活动未超过 `turn_activity_timeout_ms`
- `turn_max_duration_ms` 是真正硬上限；即使误配置或调试参数让 `turn_activity_timeout_ms` 大于它，也不能把单轮等待时间延长到 activity timeout。
- 当 activity timeout 命中且没有有效输出时，状态为 `timeout`。
- 当 max duration 命中但存在有效输出时，状态为 `partial_timeout`。
- 当收到 `turn_aborted` 且已有有效输出时，状态也应优先归一为 `partial_timeout`，metadata 保留 `provider_turn_status=cancelled/interrupted` 和 `provider_abort_reason`。

metadata 合同：

| 字段 | 含义 |
|------|------|
| `execution_status=partial_timeout` | 本轮被时间策略截断，但已有可用中间输出 |
| `provider_turn_status` | Codex 原始 turn 状态，例如 `running`、`cancelled`、`interrupted` |
| `provider_timeout_kind` | `activity_timeout` / `max_duration` / `user_interrupted` |
| `provider_activity_timeout_ms` | 本轮使用的活动超时；`provider_turn_activity_timeout_ms` 作为兼容别名继续保留 |
| `provider_turn_max_duration_ms` | 本轮使用的最大硬上限 |
| `partial_output=true` | 明确告诉 UI / recovery 该结果不是空失败 |
| `partial_output_chars` | 有效输出长度 |
| `partial_timeout_min_output_chars` | 本轮使用的 partial timeout 判定阈值 |
| `provider_thread_id` | 可用于继续 Codex thread |
| `provider_last_message_path` | 可人工回收中间结果 |

恢复策略：

- `partial_timeout` 不应进入 `worker_runtime_transient` 的默认 same-worker retry / auto-handoff 链。
- 默认进入 `partial_result_or_quality_risk`，推荐动作：
  - `continue_same_worker_thread`
  - `handoff_to_worker_x`
  - `human_review_partial_output`
- 控制图落库时不得把 `partial_timeout` 的 human gate 写成已发生的 `auto_handoff_count / auto_handoff_target`；如存在候选 worker，只能记录为 `manual_handoff_candidate`，避免 Dialogue recovery 详情误报“已自动移交”。
- Codex legacy `turn_aborted` 且已有部分输出时，executor 已保留 `provider_abort_reason`，并通过 artifact / worker_round projection 进入主消息流诊断 metadata。
- Dialogue provider signal 已展示 `partial_timeout` 的 timeout kind 与 `partial_output_chars / partial_timeout_min_output_chars`，用于解释为什么该轮被归为 partial timeout。
- `GET /api/v1/tasks/{id}/live_flow` 的 `runtime_cognition_surface.execution` 同步透出 `provider_timeout_kind`、`provider_abort_reason`、`partial_output_chars`、`partial_timeout_min_output_chars`，details/open 面板不用回退解析 artifact metadata。
- 如果用户显式点“继续 Codex thread”，可带 `resume_provider_session_id/provider_thread_id` 继续。
- 如果用户点“手动移交”，走现有 handoff 流，但 handoff packet 必须包含 partial output 摘要和 provider run 文件路径。

验收入口：

- 构造 Codex app-server mock：持续输出 `agent_message` / command events 超过 150s，但未到 `turn_max_duration_ms`，不应被判定为 `timeout`。
- 构造 Codex app-server mock：`turn/completed` 后进程继续常驻，harness 应保留本轮 `completed` 与输出，不应因清理进程得到的非 0 exit code 改判失败。
- 构造 Codex app-server mock：`turn_activity_timeout_ms` 大于 `turn_max_duration_ms` 时，仍必须先命中 `max_duration`，有输出则为 `partial_timeout`。
- 构造 `codex exec --json` mock：设置 `agentcloud.providers.codex.turn_max_duration_ms` 后，metadata 应透出本轮使用的 `provider_turn_max_duration_ms`，证明不再走固定 180s 上限。
- 构造 `codex exec --json` 非 0 退出 mock：失败 metadata 仍应显示 `provider_output_parser=codex_exec_json`，并保留 `exit_code / provider_error / provider_turn_max_duration_ms`。
- 构造 max duration 命中且有输出：artifact 和 agent run 应写 `execution_status=partial_timeout`。
- 构造 max duration 命中且无输出：仍写 `execution_status=timeout`。
- Dialogue 对 `partial_timeout` 显示“Codex 产出部分结果，等待继续/移交决策”，而不是只显示普通失败。
- `live_flow.runtime_cognition_surface.execution` 可直接看到 abort reason 与 partial output 阈值，便于确认 timeout 是“有输出的 partial timeout”还是“空失败”。

#### Step 7.2b harness tool_trace 基础能力

当前需要区分两个概念：

- `tool_trace` 是 harness 自己执行 `ToolRegistry` 工具后落库的调用轨迹。
- provider-native CLI 自己在进程内部读文件、跑命令或修改文件，不会自动生成 harness `tool_trace`。

因此“让 worker 有本地代码文件读写能力”分两层推进：

1. 能力与权限层：所有非 suggest-only 且可执行的 worker 默认获得 harness 基础工具能力。
2. worker 自主执行层：provider prompt 明确给出本地 workspace / reference path，并要求 worker 自行读取文件、搜索代码和运行必要命令。

当前进展（2026-05-21）：

- `WorkerRegistry.enrich` 已给 `provider_native_cli`、`provider_app_server`、`tool_aware` worker 注入基础 harness 工具能力：
  - `list_files`
  - `read_file`
  - `search_text`
  - `write_file`
  - `write_files`
  - `patch_file`
  - 宿主机真实可用的 `git/shell/powershell/cmd`
- suggest-only worker 仍不注入工具能力，避免推荐型 worker 被误当成可执行 worker。
- `WorkerRegistry.enrich` 会给上述 worker 填充默认 `tool_scope`，默认是 harness 当前进程工作目录；同时在 metadata 中标记：
  - `harness_tool_access=true`
  - `harness_tool_scope_source=worker_or_default`
  - `host_tool_availability`
- `ToolRequest` 已携带 `task_metadata`，`ToolPolicy` 会把任务级工作区视为动态允许范围。支持的 metadata key：
  - `workspace_root`
  - `workspace`
  - `working_directory`
  - `cwd`
  - `repo_path`
  - `workspace_roots`
  - `workspaces`
- 这意味着任务指向 `D:\gitAll\articleeditor` 这类 harness 进程目录之外的本地仓库时，只要任务 metadata 带出工作区，`read_file/search_text/write_file/patch_file` 就能通过 harness 工具层访问，而不是被静态 worker scope 拦住。

当前验证入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=AgentProviderSupportTest#workerRegistryEnrichesWorkerCapabilityMatrixFields,PatchFileToolTest#readFileAllowsTaskWorkspaceOutsideStaticWorkerScope,WorkerExecutorRouterProviderNativeTest#routesDeepSeekToProviderNativeExecutor,CommandToolExecutionTest#shellToolExecutesGuardedEchoCommand"
```

当前进展（2026-05-22）：

- 默认 scheduler/provider pre-read 已放弃，不作为主路径落地。
- 放弃原因：pre-read 会把 harness 变成半执行器，增加调度层复杂度、prompt 污染、文件截断策略和 provider 原生本地访问能力之间的重复。
- 当前取舍：不要让 harness 在调度前主动读取大量本地代码文件。harness 只负责识别和归一化本地路径、工作区、交付物、校验命令、修改范围和验收标准；真实阅读、搜索、修改、运行命令应交给具备本地执行能力的 worker/provider。
- 复杂度边界：pre-read 一旦默认开启，就必须处理文件选择、大小截断、二进制/大文件过滤、敏感信息泄露、跨 repo 范围、缓存失效、prompt token 污染和与 provider 原生命令行能力重复的问题。对当前本地 harness 来说，这些成本高于收益。
- 推荐路径：把“worker 可以访问这些本地路径”作为任务合同的一部分显式传给 provider，而不是把文件内容塞进 prompt。provider prompt 不再暴露 `pre-read` 这类内部实现术语，只明确说明 harness 传递的是本地路径和执行边界，不传文件内容；worker 必须自行检查 `Workspaces` / `Reference Inputs` 中列出的本地路径。
- 保留能力与权限层：provider-native、provider app-server、tool-aware worker 仍声明基础 harness tools；`ToolPolicy` 仍支持任务级动态 workspace scope。
- `ProviderTaskPromptBuilder` 改为明确提示 worker：harness 只传本地路径和执行边界，不传文件内容；worker 应以提供的本地 workspace / reference path 为主要上下文，自行检查本地文件并运行必要的 search / command-line checks。
- `ProviderTaskPromptBuilder` 继续补强任务合同，但不做 harness 代读：已识别 `repo_path`、`desired_output_dir`、`validation_commands` / `test_commands` / `build_commands`、`acceptance_criteria`、`write_scope` / `target_paths` 等 metadata，并在 prompt 中显式生成 Workspaces、Expected Deliverables、Validation Commands、Acceptance Criteria、Allowed Modification Scope。
- ChatFacade 上游创建链路已补齐 provider 执行合同透传：OpenAI-compatible metadata 中的 `repo_path`、`reference_paths`、`validation_commands`、`write_scope`、`acceptance_criteria` 等会进入 Task metadata；Dialogue composer 参数区也提供本地路径、校验命令、修改范围/验收标准输入。
- 当用户只在自然语言里写了 Windows 本地路径时，ChatFacade 只推断 workspace scope 与 `reference_paths/target_paths`，不读取文件内容。
- `TaskService.createTask` 也已补齐同一套最低限度归一化：直调服务或 `/api/v1/tasks` 直建任务时，只要 `intent/goal/title` 或 metadata 中出现本地绝对路径，就会补出 `workspace_root/workspace/working_directory/cwd/repo_path` 和 `reference_paths/target_paths`；显式传入的 `validation_commands`、`write_scope`、`acceptance_criteria` 等合同字段原样保留。
- `ProviderTaskContractNormalizer` 已抽出为共享组件，ChatFacade 与 TaskService 不再各自维护一套 Windows 路径提取、repo 根目录识别和 provider 合同字段补齐逻辑；后续扩展任务 metadata 合同时应优先改这个 normalizer。
- 这条路径的边界是“告诉 worker 去哪里看、改哪里、怎么验收”，不是“scheduler 先把文件内容读出来塞给 worker”。后者复杂度高且和 Codex/Claude/DeepSeek 等 provider 的本地执行能力重复。
- `tool_trace` 语义保持真实：只记录 harness `ToolRegistry` 实际执行的工具调用。provider 自己在内部读文件、搜索代码或跑命令，不伪造为 harness `tool_trace`。
- provider 执行结果的可观测性不再靠 pre-read，而靠 provider run 文件路径、结构化 provider failure 字段和 UI run files 摘要打通。

当前验证入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=AgentProviderSupportTest#workerRegistryEnrichesWorkerCapabilityMatrixFields,PatchFileToolTest#readFileAllowsTaskWorkspaceOutsideStaticWorkerScope,WorkerExecutorRouterProviderNativeTest#routesDeepSeekToProviderNativeExecutor,CommandToolExecutionTest#shellToolExecutesGuardedEchoCommand,ProviderCliWorkerExecutorTest#deepSeekPlanUsesExecSubcommandWithoutFacadeOnlyFlags"
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=ProviderTaskPromptBuilderTest"
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=ChatFacadeHandlerHttpTest#postChatCompletionInfersCodingTaskTypeForRepoModificationRequests,ChatFacadeHandlerHttpTest#postChatCompletionPreservesProviderExecutionContractMetadata"
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=TaskServiceAutoStartTest#directTaskCreationInfersWorkspaceContractFromLocalPathIntent,TaskServiceAutoStartTest#directTaskCreationExpandsExplicitRepoPathAndPreservesProviderContract"
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=TaskHandlerControlActionHttpTest#postCreateTaskReturnsNormalizedProviderExecutionContract"
node --test src/test/js/dialogue-phase6-path-matrix.test.mjs
```

剩余动作：

- 上游调用方仍应尽量显式填充 `repo_path` / `workspace_roots`、`reference_paths`、`desired_output_file` / `desired_output_dir`、`validation_commands`、`acceptance_criteria`、`write_scope`。自动推断只解决“本地绝对路径”这种高置信信号，不能替代完整任务合同。
- 如果未来确实需要 harness 代读文件，只能做成显式 opt-in metadata，例如 `harness_preread=enabled`，不能作为 scheduler 默认行为。该模式还必须带上 `preread_paths`、`preread_max_files`、`preread_max_bytes`、`preread_redaction`、`preread_reason` 等约束，并在 trace 中明确记录，避免静默扩大权限和 token 面。
- 后续不再为了 operator 排障引入默认 pre-read；排障依赖 provider run files、结构化 failure metadata、live flow/fact export 和 UI 预览。

**阶段 B：增加 Codex exec JSONL ingestion 链路**

如果目标是“稳定获取 Codex 执行后的数据”，app-server 不是唯一选择。可以新增一个可配置执行模式：

- `provider_app_server`: 当前默认，适合未来做长连接/remote-control 能力
- `provider_native_cli_json`: 新增，使用 `codex exec --json -o <file>` 或 `codex exec resume <SESSION_ID> --json -o <file>`

建议新增配置：

| 配置 | 默认 | 说明 |
|------|------|------|
| `agentcloud.providers.codex.execution_mode` | `app_server` | 可选 `app_server` / `exec_json` |
| `agentcloud.provider_runs.dir` / `AGENTCLOUD_PROVIDER_RUNS_DIR` | `.tmp/provider-runs` | provider run 根目录；Codex app-server 会落到 `{root}/codex/{task_id}/{execution_id}/`，包含 prompt、JSONL event、last message、metadata |
| `agentcloud.providers.codex.resume_mode` | `fresh_on_recovery` | recovery 阶段默认不复用旧 session |

当前状态（2026-05-22）：

- 已在 `CodexAppServerWorkerExecutor` 内落地可配置 `exec_json` 分支，避免修改默认路由和 app-server 默认行为。
- 默认仍走 `provider_app_server`；只有显式设置 `-Dagentcloud.providers.codex.execution_mode=exec_json` 或 `AGENTCLOUD_CODEX_EXECUTION_MODE=exec_json` 时才执行 `codex exec --json`。
- `exec_json` 使用现有 provider run 文件根目录，不新增第二套 output dir 配置，避免 run artifact 分散：
  - `prompt.txt`
  - `events.jsonl`
  - `last_message.md`
  - `metadata.json`
- 命令当前固定为 `codex exec --json -o <last_message.md> --skip-git-repo-check`，prompt 通过 stdin 从 `prompt.txt` 输入。
- `stdout` JSONL 原样落到 `events.jsonl`，`last_message.md` 作为 `WorkerExecutionResult.outputText`，metadata 写入 `provider_output_parser=codex_exec_json`、`execution_backend=provider_native_cli_json`、`provider_session_id` 与全部 run file 路径。
- 当前已从 JSONL 提取基础 `session_id/status/error/fallback message`；命令执行计数、输出摘要、hash/size 索引仍是后续增强项。

`exec_json` 模式的计划：

1. 为每轮生成独立目录
   - `.tmp/provider-runs/codex/{task_id}/{execution_id}/prompt.txt`
   - `.tmp/provider-runs/codex/{task_id}/{execution_id}/events.jsonl`
   - `.tmp/provider-runs/codex/{task_id}/{execution_id}/last_message.md`

2. 新增或扩展 executor
   - 当前先在 `CodexAppServerWorkerExecutor` 内按配置分支实现，降低装配改动。
   - 如果后续继续膨胀，再抽出 `CodexExecJsonWorkerExecutor`，但对外 metadata/run file 合同保持不变。

3. 命令形态

```powershell
codex exec --json -o <last_message.md> --skip-git-repo-check < prompt.txt
```

恢复已知 Codex UUID 时：

```powershell
codex exec resume <SESSION_ID> --json -o <last_message.md> < prompt.txt
```

4. 解析规则
   - stdout JSONL 原样写入 `events.jsonl`
   - `last_message.md` 作为 `WorkerExecutionResult.outputText`
   - 从 JSONL 中提取：
     - command execution count
     - command output summary
     - final agent message
     - error event
     - Codex session id
   - metadata 写入：
     - `provider_output_parser=codex_exec_json`
     - `provider_event_log_path`
     - `provider_last_message_path`
     - `provider_session_id`
     - `provider_failure_class`

5. artifact 合同
   - `artifact.summary` 使用 `last_message.md` 的短摘要
   - `artifact.metadata.output_text` 可以截断
   - 完整 JSONL 不直接塞进 SQLite 大字段，只保存文件路径和 hash

已验证：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=CodexAppServerWorkerExecutorTest"
```

验收标准：

- 不依赖 app-server runtime thread，也能获取 Codex 最终消息
- `events.jsonl` 可离线复盘命令执行过程
- `last_message.md` 可直接投影到 worker artifact
- 失败时仍通过 `ProviderFailureClassifier` 输出同一套 `provider_failure_class`

**优先级建议**

短期先做阶段 A。它改动小，能解决当前用户看到的“明明有 `.codex` 但页面仍显示 thread not found”的误导问题。

阶段 B 已完成最小闭环。下一步应优先补 worker 启动期 CLI profile/warm-up，让 `codex/deepseek/kimi/claude` 这类 provider 的真实参数能力进入 harness profile，再由 executor 按 profile 裁剪命令，而不是继续依赖静态参数假设。

#### Step 7.3 其他 worker 优化计划

Codex 的问题暴露的是 provider worker 的共性问题：当前 harness 已经能把多个 worker 接进来，但不同 worker 的命令形态、输出格式、工作区能力、恢复语义并不一致。后续优化不应逐个临时修参数，而应按 worker 类型建立统一 contract。

当前 worker 可分为四类：

| 类型 | 当前代表 | 执行入口 | 主要风险 |
|------|----------|----------|----------|
| app-server provider | `codex` | `provider_app_server` | runtime thread 与持久化 session 容易混淆，长输出超时后 UI 易误读 |
| native CLI provider | `cursor`、`openclaw`、`claude`、`gemini`、`deepseek`、`kimi`、`copilot`、`opencode` | `provider_native_cli` | 命令参数漂移、输出 parser 不稳定、stdin/arg/cwd 差异大 |
| tool-aware 本地 worker | `openclaw-native` | `tool_aware` | 适合 search/read/patch，但不应接需要真实代码 agent 推理的任务 |
| 已注册但未完全接通 worker | `hermes`、`pi`、`kiro`、`codebuddy`、`trae` | 当前标注 `provider_native_cli`，但支持矩阵未覆盖或能力未知 | readiness 可能误导，容易被路由选中后 fail fast |

优化目标：

1. 每个 worker 都要有明确的 `execution_backend`、`workspace_access_mode`、`output_contract` 和 `recovery_policy`。
2. 分发前 readiness 必须验证“本 worker 的真实命令形态可启动”，而不是只验证二进制存在。
3. 所有 provider worker 的输出都要落成统一结构化 metadata，UI 主摘要不直接依赖 stdout/stderr。
4. suggest-only 或未接通 worker 不应进入自动执行候选，只能进入推荐或人工选择面。

**阶段 A：建立 worker capability matrix**

先补一张代码侧可生成、文档侧可读的 worker 支持矩阵。建议字段：

| 字段 | 说明 |
|------|------|
| `worker_id` | worker 唯一 id |
| `provider_id` | 映射到 provider registry 的 id |
| `execution_backend` | `provider_app_server` / `provider_native_cli` / `tool_aware` / `unsupported` |
| `command_shape` | 真实命令形态，例如 `claude --print --output-format stream-json` |
| `input_mode` | `stdin` / `argv_prompt` / `prompt_file` |
| `output_mode` | `jsonl` / `stream_json` / `text` / `file` |
| `workspace_access_mode` | `cwd` / `workspace_arg` / `work_dir_arg` / `none` |
| `supports_resume` | 是否能安全 resume |
| `recovery_resume_policy` | `fresh_only` / `resume_if_session_uuid` / `provider_specific` |
| `side_effect_risk` | `low` / `medium` / `high` |

验收标准：

- `/api/v1/workers` 或 provider detail 能展示上述关键字段
- `WorkerExecutorRouter` 对 `unsupported` backend fail fast
- router 不会把 `local_workspace_access=false` 的 worker 自动派给需要改代码的任务

当前进展（2026-05-20）：

- `WorkerRegistry.enrich` 已统一补齐 capability matrix 的核心字段，避免每个内置 worker 手工维护字段：
  - `command_shape`
  - `input_mode`
  - `output_mode`
  - `output_contract`
  - `workspace_access_mode`
  - `recovery_resume_policy`
  - `supports_resume`
  - `side_effect_risk`
- `provider_app_server`、`provider_native_cli`、`tool_aware`、`unsupported` backend 已有不同默认合同。
- `WorkerExecutorRouter` 已对 `execution_backend=unsupported` fail fast，不再 fallback 到 `DefaultWorkerExecutor` 产生假执行。
- `WorkerRouter` 已把本地工作区约束纳入自动路由：
  - `coding` 和 `ops` 任务只要显式携带 `workspace_root / workspace / working_directory / cwd / repo_path`，或 goal/title/intent 中出现本地代码路径信号，就要求候选 worker 具备 `local_workspace_access=true` 或可用 tool scope。
  - learning memory hint 不能把无本地工作区能力的 worker 拉回候选集。
  - `preferred_worker` / pinned worker 也不能绕过本地工作区约束。
- `/api/v1/workers` 已通过 HTTP 验收确认能直接展示 matrix metadata；当前不需要新增 handler 字段，因为 `WorkerHandler` 返回的 `Worker.metadata` 已包含上述合同字段。
- `docs/TROUBLESHOOT.md` 已补 operator 人工验收样例，说明如何通过 `/api/v1/workers` 对照 `execution_backend / command_shape / input_mode / output_mode / workspace_access_mode / local_workspace_access / recovery_resume_policy` 判断 worker 是否适合自动执行。
- 验证命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=AgentProviderSupportTest#workerRegistryEnrichesWorkerCapabilityMatrixFields,WorkerExecutorRouterProviderNativeTest#unsupportedBackendFailsFastInsteadOfFallingBackToDefault,TaskHandlerLiveFlowHttpTest#liveFlowHttpExposesProviderExecutionDiagnostics"

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=WorkerRouterRouteTraceTest#pinnedWorkerWithoutWorkspaceAccessCannotOverrideLocalWorkspaceRequirement,WorkerRouterRouteTraceTest#localWorkspaceOpsTaskRejectsCandidateWithoutWorkspaceAccess,WorkerRouterRouteTraceTest#localWorkspaceCodingTaskRejectsLearningHintWithoutWorkspaceAccess"

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=ApiErrorContractHttpTest#listWorkersExposesCapabilityMatrixMetadata,AgentProviderSupportTest#workerRegistryEnrichesWorkerCapabilityMatrixFields"
```

阶段 A 剩余动作：

- 阶段 A 已形成最小闭环；后续进入阶段 B 收紧 native CLI worker 命令计划。

**阶段 B：收紧 native CLI worker 命令计划**

`ProviderCliWorkerExecutor` 当前已经覆盖多种 provider，但需要把“每个 provider 的命令形态”从隐式代码分支提升为可观测 plan。

建议按 worker 逐个收口：

| worker | 优化方向 | 优先级 |
|--------|----------|--------|
| `claude` | 固定 `--print/--output-format stream-json` 或等价 JSONL 入口；确认 stdin payload 与 session resume 参数 | 高 |
| `cursor` | 校验 `chat -p --output-format stream-json --workspace` 是否仍是当前 CLI 合同；失败时给出参数不兼容分类 | 高 |
| `kimi` | 保留已成功的 provider-native CLI 路径，补最终消息提取和 output truncation 证据 | 高 |
| `deepseek` | 解决 `--yolo` 这类参数漂移；dispatch preflight 必须验证真实子命令帮助 | 高 |
| `openclaw` | 区分外部 `openclaw` CLI 与内置 `openclaw-native` tool-aware worker，避免路由语义混淆 | 中 |
| `gemini` | 固定 JSON/stream 输出 parser；无结构化输出时降级成 text parser，但要显式标注 | 中 |
| `copilot` | 锁定 JSONL event parser；失败时保留 `exit_code`、event error 和 last message | 中 |
| `opencode` | Windows binary 自动发现要和 dispatch preflight 同步；输出 parser 保存原始 JSON path | 中 |

每个 native CLI worker 的 `WorkerExecutionResult.metadata` 至少应包含：

- `provider_id`
- `execution_backend=provider_native_cli`
- `cli_command_preview`
- `cli_resolved_binary`
- `cli_launch_mode`
- `provider_output_parser`
- `provider_error`
- `provider_failure_class`
- `provider_event_log_path` 或 `provider_raw_output_path`
- `provider_last_message_path`，如果该 worker 支持文件型最终输出

验收标准：

- 每个 provider 至少有一个 `missing binary` 测试
- 每个 provider 至少有一个 `parser consumes minimal success output` 测试
- 每个 provider 至少有一个 `bad args classified as provider_protocol_error or provider_execution_failed` 测试
- dispatch preflight 的失败原因能直接显示具体不兼容参数，而不是泛化成 `not ready`

当前进展（2026-05-20）：

- `ProviderCliWorkerExecutor.baseMetadata` 已补结构化 command plan 可观测字段，失败态和成功态都会带出：
  - `cli_command_shape`: 脱敏后的命令参数列表，prompt 会归一成 `<prompt>`
  - `cli_command_arg_count`
  - `cli_prompt_delivery`: `argv_prompt` / `stdin_jsonl`
  - `cli_uses_stdin`
  - `cli_uses_resume`
  - `cli_resume_arg_name`
  - `provider_expected_output_mode`
  - `provider_expected_parser`
- 当前已用 `claude` 和 `kimi` 锁定两类代表：
  - `claude`: stdin JSONL prompt，期望 `claude_stream_json`
  - `kimi`: argv prompt，支持 `--session` resume，期望 `kimi_stream_json`
- 已补齐 `cursor / gemini / openclaw / opencode` 的 missing-binary command plan metadata 用例；`copilot / deepseek` 的原有 missing-binary 用例也已扩展到 command plan 字段。
- 已补齐 `claude / cursor / gemini / openclaw / copilot / opencode / kimi` 的 resume command plan metadata 用例，分别锁定：
  - `claude`: `--resume`
  - `cursor`: `--resume`
  - `gemini`: `-r`
  - `openclaw`: `--session-id`
  - `copilot`: `--resume`
  - `opencode`: `--session`
  - `kimi`: `--session`
- `openclaw` 的命令计划已避免在无 resume id 时生成 `--session-id null`，`cli_uses_resume` 只在真实 resume 参数存在时为 `true`。
- `ProviderFailureClassifier` 已识别 CLI 参数/子命令不兼容信号，例如 `unknown option`、`unrecognized argument`、`invalid subcommand`，统一归类为 `provider_protocol_error`。
- `LocalCliAgentProvider.dispatchPreflight()` 已把 active probe 失败套用同一套 provider failure metadata：
  - `provider_failure_class`
  - `provider_failure_reason`
  - `provider_retryable`
  - 同时保留 `dispatch_preflight_exit_code`、`dispatch_preflight_output_preview`、`dispatch_preflight_command_shape`
- 已补不依赖真实 CLI 的 parser 最小成功输出用例，覆盖：
  - `cursor_stream_json`
  - `openclaw_json`
  - `gemini_stream_json`
  - `deepseek_exec_text`
  - `copilot_jsonl`
  - `opencode_json`
- `copilot` 的 `provider_expected_output_mode / provider_expected_parser` 已和实际 parser 对齐为 `jsonl / copilot_jsonl`，避免 matrix 与执行结果漂移。
- `deepseek` 的 `provider_expected_output_mode / provider_expected_parser` 已和实际 parser 对齐为 `text / deepseek_exec_text`，避免 executor 计划显示 `stream_json/deepseek_text` 但实际按文本消费。
- `deepseek` 的执行命令已收敛为 `deepseek exec <prompt>`；不再为真实 `deepseek` CLI 注入 `--skip-onboarding`、`--yolo`、`--provider deepseek` 这类 facade-only 参数。
- provider-native CLI 退出码非 0 且 stderr 为空时，失败分类现在会使用 bounded stdout 诊断文本；因此 `unexpected argument '--yolo' found` 会稳定归类为 `provider_protocol_error`，不再退化为泛化的 `provider_execution_failed`。
- 已做本机真实 CLI smoke（2026-05-20，Windows PATH）：
  - 已发现：`deepseek.ps1`、`claude.ps1`、`openclaw.ps1`、`codex.ps1`
  - 未发现：`cursor-agent`、`cursor`、`opencode`、`copilot`、`gemini`、`kimi`
  - `deepseek exec --help` 成功返回非交互子命令帮助；因此 `deepseek` dispatch preflight 已固定验证 `exec --help`，不再只测顶层 `--help`
  - `claude -p --help` 成功返回帮助，未触发真实模型调用
  - `codex --version` 成功返回 `codex-cli 0.130.0`
  - `openclaw agent --help` 在本机 smoke 中超时，不应贸然作为低副作用 active probe；当前仍保留默认顶层 `--help` probe
- `WorkerRegistry.dispatchPreflightMetadata` 已放行 `provider_failure_class / provider_failure_reason / provider_retryable`，`/api/v1/workers/{id}/readiness?mode=dispatch` 可通过 `dispatch_preflight_metadata` 看到参数不兼容分类。
- `WorkerRegistry.warmupDispatchPreflight()` 已接入启动流程：`Main` 初始化 worker 后会对 provider worker 执行一次 dispatch preflight warm-up，提前校准本机 CLI 是否能接受当前子命令形态，并把结果写入短期缓存与启动日志。
- `LocalCliAgentProvider.dispatchPreflight()` 已从 help 输出生成轻量 CLI profile，并通过 status/readiness metadata 暴露：
  - `cli_profile_evidence_available`
  - `supports_yolo`
  - `supports_model`
  - `supports_json_output`
  - `supports_resume`
  - `supports_workspace_arg`
  - `supports_work_dir_arg`
  - `supports_output_file`
- `ProviderCliWorkerExecutor` 已开始消费该 profile：没有 profile 时保持原有命令形态；只有 profile 明确显示不支持某能力时，才裁剪可选参数，并在本轮 metadata 写入：
  - `cli_profile`
  - `cli_profile_adjustments`
- 当前已覆盖高风险裁剪：`cursor/gemini` 的 `--yolo`，以及 `cursor/claude/gemini/kimi/copilot` 的 model/resume/workspace/work-dir 等可选参数。该策略避免再次把 facade-only 或旧版本不支持的参数硬塞给真实 CLI。
- `AgentProviderRegistry` 已增加进程内 runtime profile cache：`dispatchPreflight()` 得到的 CLI profile 会按 providerId 缓存；后续普通 `refresh/status` 即使只走 passive detect，也会把最近的 profile 合并回 metadata，避免 executor 因 status cache 刷新丢失参数校准结果。
- 当前 runtime cache 通过 `AgentProviderRegistry.cliProfileMetadata(providerId)` 可读，主要用于测试和后续 UI/诊断扩展；它仍是进程内缓存，不跨重启。
- `/api/v1/workers/{id}/readiness` 已把 CLI profile 提升为顶层 `cli_profile` 字段；`dispatch_preflight_metadata` 仍保留相同字段用于兼容和排障。
- Console provider detail 的 worker dispatch probe 卡片已展示 CLI profile badge，例如 `yolo: no`、`model: yes`、`resume: yes`，operator 不需要翻 raw metadata 才能判断 executor 为什么裁剪了参数。
- `/api/v1/workers/{id}/readiness` 已把 provider 失败分类提升为顶层字段：
  - `provider_failure_class`
  - `provider_failure_reason`
  - `provider_retryable`
- Console provider detail 的 worker dispatch probe 卡片已展示 provider failure class 与 retryability，例如 `provider_protocol_error`、`retryable` / `manual`，operator 不需要翻 `dispatch_preflight_metadata` 才能判断恢复策略。
- `WorkerRouter.RouteResult` 已增加结构化 `dispatch_skipped_workers` 诊断列表；当 dispatch preflight 跳过某个候选 worker 时，route trace 不再只有 `fallback_reason` 文本，还会带出：
  - `worker_id`
  - `reason`
  - `provider_failure_class`
  - `provider_failure_reason`
  - `provider_retryable`
- `ControlNodeGraph` 会把 `dispatch_skipped_workers` 写入当前 worker round metadata；`AgentRunService` 会把同一字段写入 provider-aware run metadata，便于后续 UI/recovery 不再解析自然语言 fallback reason。
- `RuntimeFactSetAssembler`、`RuntimeCognitionSurfaceAssembler`、`RuntimeFactSurfaceExporter` 已把 `dispatch_skipped_workers` 投影到 runtime facts、`route_preview` export 和 `runtime_cognition_surface.route`；`/api/v1/tasks/{id}/live_flow` 因此能直接返回结构化 skipped worker 诊断。
- 验证命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=ProviderCliWorkerExecutorTest#claudeMissingBinaryReturnsFailedMetadataWithoutThrowing,ProviderCliWorkerExecutorTest#kimiMissingBinaryReturnsFailedMetadataWithoutThrowing,ProviderCliWorkerExecutorTest#kimiMissingBinaryReportsResumeCommandPlanMetadata,ProviderCliWorkerExecutorTest#kimiPlanUsesPrintModeWorkdirAndSessionMetadata"

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=ProviderCliWorkerExecutorTest#providerParsersConsumeMinimalSuccessOutputs,ProviderCliWorkerExecutorTest#copilotMissingBinaryReturnsFailedMetadataWithoutThrowing,ProviderCliWorkerExecutorTest#kimiMissingBinaryReturnsFailedMetadataWithoutThrowing"

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=ProviderCliWorkerExecutorTest#cursorGeminiOpenClawAndOpenCodeMissingBinaryExposeCommandPlanMetadata,ProviderCliWorkerExecutorTest#copilotMissingBinaryReturnsFailedMetadataWithoutThrowing,ProviderCliWorkerExecutorTest#deepSeekMissingBinaryReturnsFailedMetadataWithoutThrowing,ProviderCliWorkerExecutorTest#providerParsersConsumeMinimalSuccessOutputs,ProviderCliWorkerExecutorTest#kimiMissingBinaryReturnsFailedMetadataWithoutThrowing,ProviderCliWorkerExecutorTest#kimiMissingBinaryReportsResumeCommandPlanMetadata"

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=ProviderCliWorkerExecutorTest#nativeCliProvidersExposeResumeCommandPlanMetadata,ProviderCliWorkerExecutorTest#cursorGeminiOpenClawAndOpenCodeMissingBinaryExposeCommandPlanMetadata,ProviderCliWorkerExecutorTest#deepSeekMissingBinaryReturnsFailedMetadataWithoutThrowing,ProviderCliWorkerExecutorTest#providerParsersConsumeMinimalSuccessOutputs"

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=ProviderFailureClassifierTest,AgentProviderSupportTest#localCliDispatchPreflightRunsActiveProbeAndReportsCommandMetadata,AgentProviderSupportTest#localCliDispatchPreflightClassifiesBadArgumentsWithProviderFailureMetadata,AgentProviderSupportTest#workerRegistryCachesDispatchPreflightAndKeepsCommandMetadata,ProviderCliWorkerExecutorTest#deepSeekMissingBinaryReturnsFailedMetadataWithoutThrowing"

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=AgentProviderSupportTest#localCliDispatchPreflightRunsActiveProbeAndReportsCommandMetadata,AgentProviderSupportTest#deepSeekDispatchPreflightValidatesExecSubcommandHelp,AgentProviderSupportTest#localCliDispatchPreflightClassifiesBadArgumentsWithProviderFailureMetadata,AgentProviderSupportTest#workerRegistryCachesDispatchPreflightAndKeepsCommandMetadata"

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=ProviderCliWorkerExecutorTest#deepSeekPlanUsesExecSubcommandWithoutFacadeOnlyFlags,ProviderCliWorkerExecutorTest#deepSeekUnexpectedArgumentStdoutIsClassifiedAsProtocolError,ProviderCliWorkerExecutorTest#deepSeekMissingBinaryReturnsFailedMetadataWithoutThrowing,ProviderCliWorkerExecutorTest#outputCaptureCapsHugeProviderOutput,AgentProviderSupportTest#workerRegistryCachesDispatchPreflightAndKeepsCommandMetadata"

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=AgentProviderSupportTest#localCliDispatchPreflightRunsActiveProbeAndReportsCommandMetadata,AgentProviderSupportTest#deepSeekDispatchPreflightValidatesExecSubcommandHelp,ProviderCliWorkerExecutorTest#providerCliPlanDropsYoloWhenCliProfileShowsUnsupportedFlag,ProviderCliWorkerExecutorTest#deepSeekPlanUsesExecSubcommandWithoutFacadeOnlyFlags"

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=AgentProviderSupportTest#agentProviderRegistryKeepsCliProfileAcrossPassiveStatusRefresh,AgentProviderSupportTest#workerRegistryCachesDispatchPreflightAndKeepsCommandMetadata,ProviderCliWorkerExecutorTest#providerCliPlanDropsYoloWhenCliProfileShowsUnsupportedFlag"

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=AgentProviderSupportTest#workerRegistryCachesDispatchPreflightAndKeepsCommandMetadata,ApiErrorContractHttpTest#workerReadinessDispatchModeProjectsPreflightFields"
node --check src/main/resources/web/console/app.js
node --test src/test/js/console-time-normalization.test.mjs

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=AgentProviderSupportTest#localCliDispatchPreflightClassifiesBadArgumentsWithProviderFailureMetadata,ApiErrorContractHttpTest#workerReadinessDispatchModeProjectsPreflightFields"

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=WorkerRouterRouteTraceTest#routeSkipsWorkerWhenDispatchPreflightFailsEvenIfPassiveReadinessPasses,AgentRunServiceTest#recordWorkerRunPrefersProviderErrorForFailedDiagnostics"

powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven -MavenArgs "-Dtest=TaskHandlerLiveFlowHttpTest#liveFlowRoutePreviewExposesDispatchSkippedWorkers,WorkerRouterRouteTraceTest#routeSkipsWorkerWhenDispatchPreflightFailsEvenIfPassiveReadinessPasses,AgentRunServiceTest#recordWorkerRunPrefersProviderErrorForFailedDiagnostics"
```

阶段 B 剩余动作：

- `providers.yaml` / `providers.json` 当前的动态发现是轻量 native CLI generic provider 能力，已支持 `protocol/native_cli_text/json/lines/stream_json`、`binary`、`args`、`env` 别名；其中 `native_cli_stream_json` 当前按行保留输出，不等同于 Claude/Cursor provider-specific stream-json parser。未写 `protocol` 但配置了 `binary` 或 `command` 时，会保守推断为 `native_cli_text`，并在 discovered provider metadata 标记 `provider_protocol_inferred=true`。`app_server_json_rpc` 仍由 Codex app-server 执行器主链处理，`mcp` 与基于真实 `--help` / handshake 的深度自动探测还不能宣称已完成。
- 在安装 `cursor-agent / opencode / copilot / gemini / kimi` 后，继续补真实 CLI smoke 证据，确认 `cursor chat --help`、`opencode run --help` 等 probe args 与本机版本一致。
- 当前 CLI profile 已有进程内 runtime cache，但没有落 SQLite；如果要跨进程复用，需要新增 profile 持久化或把 warm-up 结果写入 worker runtime cache 文件。
- profile 解析仍是 help 文本启发式，后续应按 provider 增加更精确的 probe，例如 `cursor chat --help`、`gemini --help`、`kimi --help` 的真实输出 fixture。
- provider failure 顶层字段已进入 route trace、runtime facts、fact export 与 live_flow 的结构化 dispatch skipped worker 摘要；后续 UI 可直接读取 `dispatch_skipped_workers`，不必解析 `fallback_reason`。

**阶段 C：区分 coding worker 与 research/message worker**

当前 recovery 候选里已经对 coding worker 做了优先级约束，但还需要把 worker 适配范围写成显式 contract。

建议：

1. `coding` 任务默认只允许：
   - `codex`
   - `cursor`
   - `copilot`
   - `opencode`
   - `codebuddy`
   - `trae`
   - `deepseek`
   - `claude`
   - `kimi`，仅在其 workspace access 和 output parser 已验证时
2. `openclaw-native` 默认只承接：
   - 本地文件 search/read
   - 文档抽取
   - 轻量 patch
   - browser/doc/message/search 工具链
3. `research` / `writing` / `message` 任务可以考虑：
   - `kimi`
   - `gemini`
   - `pi`
   - `hermes`
   - 但只有 provider backend 支持矩阵确认后才能自动执行

验收标准：

- 代码修改任务不会自动切到 `openclaw-native`，除非 task 明确是 tool-only 或用户手工指定
- 未支持 backend 的 worker 不进入 auto route，只进入 candidate explanation
- `fallback_reason` 能解释“为什么某 worker capability 符合但 execution backend 不可用”

当前进展（2026-05-20）：

- `WorkerRegistry` 已为内置 worker 增加显式 `auto_route_task_types` 合同，并通过 `/api/v1/workers` 的 `metadata` 可见。
- `WorkerRouter` 已在 capability / workspace access 之后、dispatch readiness 之前应用 `auto_route_task_types` 过滤：
  - `coding` 自动路由不会再因为 `gemini` 声明了 `coding` capability 就派给 `gemini`；当前 `gemini` 自动范围收窄为 `research / browser`。
  - `openclaw-native` 自动范围限定为 `browser / doc / message / search / reading`，不会在 coding ready fallback 中接代码修改任务。
  - `research` 可以选择 `gemini`；`message` 默认可以选择 `openclaw-native` 的 tool-aware 路径。
  - 未声明 `auto_route_task_types` 的外部临时 worker 仍保持旧兼容语义，避免破坏已有测试和外部注册。
- `fallback_reason` 已能显示 capability 命中但被任务类型合同过滤的 worker，例如 `auto-route task type contract for taskType=coding; gemini skipped: auto_route_task_types=[research, browser]`。
- 验证命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=WorkerRouterRouteTraceTest#codingAutoRouteSkipsResearchOnlyGeminiEvenWhenCapabilityMatches,WorkerRouterRouteTraceTest#codingReadyFallbackDoesNotSelectOpenClawNativeToolWorker,WorkerRouterRouteTraceTest#researchAutoRouteCanSelectGeminiFromResearchContract,WorkerRouterRouteTraceTest#messageAutoRouteUsesToolAwareWorkerBeforeSuggestOnlyAssistants,WorkerRouterRouteTraceTest#orchestratedPlannerStagePrefersStrongTier,WorkerRouterRouteTraceTest#orchestratedExecutionStagePrefersSmallTier,AgentProviderSupportTest#workerRegistryEnrichesWorkerCapabilityMatrixFields,ApiErrorContractHttpTest#listWorkersExposesCapabilityMatrixMetadata"
```

阶段 C 剩余动作：

- 对 `codebuddy / trae / kiro / hermes / pi` 继续做真实 CLI 支持矩阵核验；在未补 command plan、dispatch preflight、output parser 前，即使 capability 包含 coding，也不应提高其自动执行优先级。
- 如后续需要让 `openclaw-native` 承接 tool-only coding patch，应通过明确 task metadata 增加一个 tool-only 路由开关，而不是放宽默认 coding 自动路由。

**阶段 D：统一 worker 输出落盘策略**

长输出直接塞进 SQLite 会导致 artifact 过大、UI 卡顿、乱码误判和 summary 污染。后续应引入 provider run 文件目录：

```text
.tmp/provider-runs/{provider_id}/{task_id}/{execution_id}/
├── prompt.txt
├── stdout.log
├── stderr.log
├── events.jsonl
├── last_message.md
└── metadata.json
```

规则：

- SQLite 只保存摘要、hash、路径和关键 metadata
- UI 默认展示 `last_message.md` 摘要
- details 允许跳转或读取 raw output
- failure classifier 只读取 bounded error snippet，不扫描整段超长输出

验收标准：

- 超过阈值的 provider output 不再完整写入 `artifact.metadata.output_text`
- artifact metadata 包含 `provider_output_truncated=true`
- operator 能从 metadata 找到完整输出文件
- `thread not found` 这类 stdout 噪声不会覆盖结构化 `provider_error`

当前进展（2026-05-21）：

- `ProviderCliWorkerExecutor` 已为 provider-native CLI 引入文件型 run 目录，默认落到 `.tmp/provider-runs/{provider_id}/{task_id}/{execution_id}/`，也可通过 JVM 属性 `agentcloud.provider_runs.dir` 或环境变量 `AGENTCLOUD_PROVIDER_RUNS_DIR` 覆盖。
- 当前已落盘文件：
  - `prompt.txt`
  - `stdout.log`
  - `last_message.md`
  - `metadata.json`
- provider stdout 现在会完整写入 `stdout.log`，内存只保留 bounded capture；超过 SQLite 阈值时，`WorkerExecutionResult.outputText` 截断到 16,384 字符，并写入：
  - `provider_output_truncated=true`
  - `provider_output_total_bytes`
  - `provider_output_capture_limit_bytes`
  - `provider_output_sqlite_limit_chars`
  - `provider_run_dir`
  - `provider_prompt_path`
  - `provider_stdout_path`
  - `provider_last_message_path`
  - `provider_run_metadata_path`
- `ControlNodeGraph` 已把上述 provider run 文件字段同步到 artifact 顶层 metadata，并在 `artifact.metadata.output_text` 后追加明确截断提示，operator 可直接从 metadata 找到完整 stdout。
- `metadata.json` 写入顺序已调整为先补齐 SQLite 截断 metadata，再写文件，避免文件元数据与 artifact metadata 不一致。
- 已补回归验证：超长 provider output 不会完整进入 SQLite output text，artifact 顶层 metadata 能投影 `provider_output_truncated` 与完整输出路径。
- `GET /api/v1/tasks/{id}/provider_run_file` 已支持 `stream=true` / `Accept: text/event-stream`：服务端返回受控 SSE 窗口流，先发 `provider_run_file.snapshot`，文件内容变化时发 `provider_run_file.update`，并用心跳和 `provider_run_file.stream.done` 收口；默认读取尾部窗口，适合观察长 `stdout.log` / `events.jsonl`。这仍是 provider run file polling/SSE 读面，不是 token 级执行 stdout streaming。

验证命令：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=ProviderCliWorkerExecutorTest#providerNativeCliWritesRunFilesAndTruncatesSqliteOutputText,ProviderCliWorkerExecutorTest#deepSeekMissingBinaryReturnsFailedMetadataWithoutThrowing,ControlNodeGraphActionResolutionTest#workerArtifactMetadataProjectsProviderRunFilesAndBoundedOutputText"
& { . .\scripts\Use-Java21.ps1 -Quiet; $mvn = & .\scripts\Resolve-MavenCommand.ps1; & $mvn -q '-Dtest=TaskHandlerLiveFlowHttpTest#providerRunFileHttpSupportsSseTailSnapshots' test }
```

阶段 D 剩余动作：

- `stderr.log` 与 `events.jsonl` 尚未对 provider-native CLI 独立拆分；当前实现仍沿用 `redirectErrorStream(true)`，stderr 与 stdout 合并进入 `stdout.log`。
- Codex `exec_json` 链路已作为可配置执行模式落地；剩余增强是从 stdout JSONL 继续提取 command execution count、command output summary、hash/size 索引。
- 后续可继续补 hash / size 索引，便于 UI 展示和离线校验。

当前进展（2026-05-22）：

- `CodexAppServerWorkerExecutor` 已接入同一套 provider run 文件目录合同。
- 每轮 Codex app-server 执行会创建 `.tmp/provider-runs/codex/{task_id}/{execution_id}/`，默认可通过 `-Dagentcloud.provider_runs.dir` 或 `AGENTCLOUD_PROVIDER_RUNS_DIR` 改写根目录。
- 当前 Codex app-server run 目录包含：
  - `prompt.txt`
  - `events.jsonl`
  - `last_message.md`
  - `metadata.json`
- `WorkerExecutionResult.metadata` 会输出：
  - `provider_run_dir`
  - `provider_prompt_path`
  - `provider_event_log_path`
  - `provider_last_message_path`
  - `provider_run_metadata_path`
- 启动失败、协议失败、超时和正常完成都会尽力写入 `metadata.json` 与 `last_message.md`，避免“Codex 已被调用但页面拿不到结果”时只能翻控制台日志。

当前验证入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=CodexAppServerWorkerExecutorTest"
```

当前进展（2026-05-22，retention）：

- `ProviderCliWorkerExecutor` 与 `CodexAppServerWorkerExecutor` 已共用 `ProviderRunFileSupport` 处理 provider run 根目录、路径 segment 清理和旧 run 清理。
- 每次创建新 run 前，会按 provider/task 维度清理旧 run 目录，避免 `.tmp/provider-runs` 无限增长。
- 默认保留策略：
  - 每个 provider/task 最多保留 20 个 run 目录。
  - 超过 7 天的 run 目录会被清理。
- 可配置项：
  - `-Dagentcloud.provider_runs.max_per_task` / `AGENTCLOUD_PROVIDER_RUNS_MAX_PER_TASK`
  - `-Dagentcloud.provider_runs.max_age_hours` / `AGENTCLOUD_PROVIDER_RUNS_MAX_AGE_HOURS`

当前验证入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=ProviderRunFileSupportTest,CodexAppServerWorkerExecutorTest,ProviderCliWorkerExecutorTest#providerNativeCliWritesRunFilesAndTruncatesSqliteOutputText"
```

**阶段 E：unsupported / suggest-only worker 降级为推荐面**

对 `hermes`、`pi`、`kiro`、`codebuddy`、`trae` 这类当前支持矩阵未完整覆盖的 worker，先不要假装可执行。

计划：

1. 如果 `ProviderExecutionSupport` 不支持 provider/backend，readiness 返回 not ready。
2. worker 仍可出现在 provider inventory，用于提示“已发现但未接入执行器”。
3. router 可以在 trace 里显示它们被跳过，但不能把任务自动派过去。
4. 后续每接通一个 worker，必须补：
   - command plan
   - dispatch preflight
   - output parser
   - failure classifier
   - minimal integration test

验收标准：

- unsupported worker 不会产生 `DefaultWorkerExecutor empty` 假执行
- `/workers/{id}/readiness` 明确显示 `executor_backend:<backend>` 不支持
- provider inventory 能区分 `installed`、`detected`、`ready_for_dispatch`、`unsupported_backend`

优先级建议：

1. 先做 native CLI 命令计划和 parser 测试，优先 `deepseek / kimi / claude / cursor`。
2. 再做统一 provider output 文件落盘，减少 SQLite 与 UI 噪声。
3. 最后逐个接通 suggest-only worker，没接通前保持 not ready。

当前进展（2026-06-02，dynamic discovery unsupported visibility）：

- `ProviderProtocolDiscovery` 现在把 `app_server_json_rpc`、`mcp` 以及其他未知协议记录到 `DiscoveryResult.unsupportedProviders`，metadata 包含 `provider_discovery_supported=false` 与 `provider_discovery_unsupported_reason`。
- `Main` 会把这些 unsupported provider 注册为只读 `UnsupportedAgentProvider`，因此 `/api/v1/agents` 和 `/api/v1/agents/{id}` 能看到 `provider_type=unsupported`、`ready=false`、`ready_for_dispatch=false` 与跳过原因。
- 这些 unsupported provider 不会注册到 `ProviderProtocolRegistry` 或 `WorkerRegistry`，也不会调用 `ProviderExecutionSupport.registerProviderNativeCli`，因此不会被 router 当作 runnable worker 分发。
- 对配置了可探测 `binary` / `command` 的 unsupported provider，discovery 会执行同一套低副作用 startup probe，并把 `provider_protocol_probe_mode=unsupported_startup_probe`、command shape、exit code、success、parser hint、output preview 写入 metadata；该证据只用于 operator 诊断，不改变 unsupported provider 的不可分发语义。
- 这一步只解决“配置被静默忽略”和“operator 读面不可见”的问题；`app_server_json_rpc` 的通用动态执行器、`mcp` handshake / tool bridge 仍未实现。

当前验证入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-WithJava21.ps1 -QuietMaven "-Dtest=ProviderProtocolDiscoveryTest#recordsUnsupportedAppServerAndMcpProvidersWithoutRegisteringRunnableProtocols"
node .\scripts\provider-discovery-smoke.js --port 18467 --report .\.tmp\provider-discovery-smoke-18467\report.json --work-dir .\.tmp\provider-discovery-smoke-18467
```

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

### 17.1 当前新增收口：调度必须使用 dispatch readiness

`/api/v1/workers/{id}/readiness?mode=dispatch` 已经不只是观测入口，而应成为任务分发前的实际门禁：

- `passive` readiness 只证明 worker 静态配置、宿主工具、provider detect 和临时失败缓存当前没有明显阻断。
- `dispatch` readiness 额外触发 provider dispatch preflight，用来证明 provider 现在能接受一次新的任务轮次。
- `WorkerRouter.selectWorker(...)` 选择 pinned worker、learning memory hinted worker、capability match worker 时，都必须使用 `dispatch` readiness。
- 如果 `dispatch` 失败，`WorkerRegistry` 会短期标记 worker temporarily unavailable，后续 passive readiness 和 route fallback 都会看到这个状态。
- route trace 的 `fallback_reason` 需要暴露被跳过 worker 的 dispatch 失败原因，方便 operator 判断是 provider runtime/auth 问题，还是普通能力不匹配。
- 当前 router 已经补齐这三条入口：
  - capability match 会保留同一轮 `dispatchReadinessByWorker`，避免解释阶段重新查 passive readiness。
  - pinned worker 会先查 `mode=dispatch`，失败时 fallback 到其他候选并保留原始 preflight reason。
  - learning memory hint 会先查 `mode=dispatch`，失败时不会退化成泛化的 `not ready`。
- 当前回归证据：
  - `WorkerRouterRouteTraceTest.routeSkipsWorkerWhenDispatchPreflightFailsEvenIfPassiveReadinessPasses()` 覆盖普通 capability match：passive ready 但 dispatch preflight 失败的 worker 不会被选中，`fallback_reason` 保留原始 `thread not found during dispatch preflight`，并说明 preferred tier fallback。
  - `WorkerRouterRouteTraceTest.pinnedWorkerDispatchFailureKeepsOriginalPreflightReason()` 覆盖 pinned worker：被 pin 的 worker dispatch 失败时会 fallback，且不会把原因退化成 `temporarily unavailable`。
  - `WorkerRouterRouteTraceTest.learningMemoryHintDispatchFailureKeepsOriginalPreflightReason()` 覆盖 learning memory hint：hint worker dispatch 失败时不会继续命中 hint，fallback reason 仍保留原始 preflight reason。
  - `ControlNodeGraphOrchestrationFlowTest` 的 dispatch preflight flow 覆盖 scheduler 执行前发现 assigned worker dispatch 失败时，会清掉旧 worker 并重新路由。

这个收口的目的，是避免“API 看起来能主动验活，但真实 scheduler 仍把任务发给只能 passive ready 的 worker”。

### 17.2 当前新增收口：CLI worker 命令参数必须探测并缓存

Worker 状态不能只依赖静态注册信息。对本地 CLI / provider-backed worker，readiness 至少要区分三层信号：

- `passive` status：二进制是否能解析、provider detect 是否 ready、宿主工具与临时熔断状态是否允许。
- `dispatch` preflight：在真正调度前执行低副作用命令探测，确认当前 CLI 至少能启动并接受其目标子命令 / 参数形态。
- cached preflight：短时间内复用同一个 worker 的 dispatch 探测结果，避免列表刷新或路由解释反复启动 CLI。

当前约定：

- `detect()` 仍保持轻量，适合 `/workers`、inventory 和普通 readiness 展示。
- `dispatchPreflight()` 对 `LocalCliAgentProvider` 走 active probe，但只跑 help/version 级命令，不跑真实 prompt、不触发模型调用、不修改工作区。
- active probe metadata 必须回传被验证的 `launch_target`、`launch_mode`、`dispatch_preflight_command_shape`、`dispatch_preflight_probe_args`，用于排查“worker 看似 ready，但命令参数实际不兼容”的问题。
- `WorkerRegistry` 继续缓存 dispatch preflight。缓存命中时 `dispatch_preflight_cached=true`，且仍保留上一次探测到的命令形态。
- route / scheduler 只应该把 `mode=dispatch` 的结果当作分发门禁；`passive` 不再代表“可立即发任务”。
