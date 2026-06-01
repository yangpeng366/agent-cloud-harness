# Agent Cloud Harness — Reasonix Agent 接入与改进计划

> 本文档基于对仓库源码（`src/main/java/com/agentcloud/`）的全面扫描编制。
> 当前版本 v0.2.0-SNAPSHOT，状态为 functional but rough-edged。
>
> 🟢 **已实现**: Reasonix Agent 注册（Provider + Worker + CLI 协议适配），见下方标注。

---

## 目录

1. [项目架构总览](#1-项目架构总览)
2. [支持 Reasonix Agent 的分层方案](#2-支持-reasonix-agent-的分层方案)
3. [架构改进意见](#3-架构改进意见)
4. [代码质量改进意见](#4-代码质量改进意见)
5. [测试改进意见](#5-测试改进意见)
6. [运维改进意见](#6-运维改进意见)
7. [实施路线图](#7-实施路线图)

---

## 1. 项目架构总览

```
HTTP (JDK HttpServer + Virtual Threads)
│
├── /api/v1/*            REST handlers (TaskHandler, SessionHandler, etc.)
├── /v1/*                OpenAI-compatible facade (chat, responses, models)
├── /console/            Web Console frontend (HTML/CSS/JS)
├── /dialogue/           Dialogue frontend
│
├── engine/              Core orchestration
│   ├── TaskService          Task CRUD + control graph traversal
│   ├── SessionService       Session lifecycle + message projection
│   ├── ControlNodeGraph     6-node state machine (intake→scheduler→...→end)
│   ├── ConsolidationService 5-step consolidation pipeline
│   ├── LearningMemoryService Operational learning with reinforcement
│   ├── RuntimeJudgmentService Rule-based migration decisions
│   ├── SkillRegistry/Router  Skill memory + routing
│   ├── memory/               PacketBuilder, ContextReconstructor
│   └── router/               WorkerRegistry, WorkerRouter
│
├── worker/              Execution layer
│   ├── DefaultWorkerExecutor         Pure LLM (no tools)
│   ├── ToolAwareWorkerExecutor       Tool-chain (max 3 rounds)
│   ├── ProviderCliWorkerExecutor     Native CLI (cursor/claude/gemini/…)
│   ├── CodexAppServerWorkerExecutor  Codex JSON-RPC
│   └── WorkerExecutorRouter          Routing facade
│
├── tool/                Local tool system
│   ├── Tool (interface) + ToolRegistry + ToolPolicy
│   ├── File tools: ListFiles, ReadFile, SearchText, WriteFile(s), PatchFile
│   └── Command tools: Git, Shell, PowerShell, Cmd
│
├── llm/                 LLM client
│   ├── LlmClient (interface) + OpenAiCompatibleClient
│   └── LlmConfig (env-based: OPENAI_API_KEY, OPENAI_BASE_URL, etc.)
│
├── agent/               Provider abstraction
│   ├── AgentProvider + AgentProviderRegistry
│   ├── providers/BuiltinAgentProviders
│   └── AgentDiscoveryService
│
├── judgment/            LLM-based judgment
│   ├── JudgmentService (interface) + PromptBasedJudgmentService
│   └── model/ CompletionDecision, ExecutionDecision
│
├── runtime/             Working memory / Active Context
│   ├── ActiveContext + ActiveContextBuilder
│   ├── TaskRuntimeContext + TaskRuntimeContextBuilder
│   └── context/ MountedContextPromptRenderer
│
├── model/               ~40+ Java Records
├── store/               JDBI SQL Object DAOs (~15+ tables)
└── server/              NioHttpServer + HTTP Handlers
```

**关键设计特点**：

| 特性 | 状态 |
|------|------|
| Tool-aware 多步执行 | ✅ 最多 3 轮工具调用，含重复工具防护 |
| 外部 Agent CLI 集成 | ✅ 7+ providers（cursor/claude/gemini/deepseek/kimi/copilot/opencode） |
| Learning Memory | ✅ 沉淀候选经验 + hint_key 强化 |
| Consolidation 5 步 | ✅ Reactivation→Selection→Compression→Abstraction→Integration |
| Resume/Handoff 续跑 | ✅ ResumePacket + HandoffPacket |
| OpenAI 兼容 facade | ✅ /v1/chat/completions + /v1/responses |
| Web Console + Dialogue | ✅ 内置 |

---

## 2. 支持 Reasonix Agent 的分层方案

### 2.1 什么是 "支持 Reasonix Agent"

Reasonix 是一个面向 AI coding agent 的 **运行与控制框架**。本 harness 支持 Reasonix agent 意味着：

1. **Reasonix 作为 Worker** — 本 harness 可以把 Reasonix 当作一个 worker 来调度，分配任务，接收执行结果
2. **Reasonix 的 Agent 可以在本 harness 中注册和路由** — 遵循现有的 `AgentProvider → WorkerRouter → WorkerExecutor` 链路
3. **利用 Reasonix 的 MCP 工具生态** — Reasonix 的 tool-call 协议可以与 harness 的 `ToolAwareWorkerExecutor` 互通

### 2.2 方案选择

有三种渐进式方案（推荐并行推进）：

```
方案 A：Reasonix 作为 native CLI provider   ★ 推荐首期
方案 B：Reasonix 作为 MCP server 集成
方案 C：双向 MCP bridge（高级，远期）
```

#### 方案 A：Native CLI Provider（最轻量，与现有架构一致）

**原理**：Reasonix 提供 CLI（如 `reasonix code`），类似已支持的 cursor/claude/deepseek CLI。

**需要新增的文件**：

| 文件 | 内容 |
|------|------|
| `agent/providers/ReasonixProvider.java` | 实现 `AgentProvider` 接口，读取 `REASONIX_PATH` / `REASONIX_MODEL` 环境变量 |
| `worker/ReasonixWorkerExecutor.java` | 实现 `WorkerExecutor`，通过 Reasonix CLI 的 stdin/stdout JSON 协议通信 |

**WorkerRegistry 注册**（`engine/router/WorkerRegistry.java`）：

```java
register(new Worker("reasonix", "reasonix",
    List.of("coding", "reading", "writing", "research"),
    List.of(),  // tool capabilities via harness tools
    List.of(),
    Map.of("api_key", true, "backend_reachable", true),
    Map.of(
        "model_tier", "strong",
        "primary_role", "planner_executor",
        "selection_priority", 85,  // 与 deepseek（87）、gemini（88）同级
        "local_workspace_access", true,
        "workspace_access_mode", "native_cli_cwd",
        "execution_backend", "provider_native_cli",
        "auto_route_task_types", List.of("coding", "reading", "writing", "research")
    ),
    false, true));
```

**ProviderCliWorkerExecutor 扩展现有 provider 识别逻辑**（`ProviderCliWorkerExecutor.java`）：

```java
// 在 executeOneRound 中增加 reasonix CLI 调用分支
// Reasonix 通过 `reasonix code` 启动，通过 stdin 接收 prompt + context
// 通过 stdout 流式接收 JSON 事件（与 cursor/claude 的 stream-json 协议类似）
```

#### 方案 B：MCP Server 集成（充分利用 Reasonix 的 MCP 协议）

**原理**：Reasonix 通过 MCP 协议暴露工具，harness 内嵌一个 MCP client 来调用 Reasonix 的工具集。

**需要新增的文件**：

| 文件 | 内容 |
|------|------|
| `tool/mcp/McpClient.java` | MCP 协议客户端（stdio transport），连接 Reasonix 的 MCP server |
| `tool/mcp/McpToolAdapter.java` | 将 MCP 工具适配为 `Tool` 接口 |
| `tool/mcp/McpToolRegistry.java` | MCP 工具的注册与发现 |
| `llm/McpLlmClient.java` | 通过 MCP 协议调用 Reasonix 的 LLM 接口 |

**集成点**：

```java
// ToolRegistry 扩展：加载 Reasonix MCP 工具
ToolRegistry toolRegistry = new ToolRegistry()
    .register(/* local tools */)
    .registerMcpTools(new McpClient("reasonix", "reasonix mcp --stdio"));
```

#### 方案 C：双向 MCP Bridge（远期目标）

**原理**：harness 既作为 MCP client 调用 Reasonix，也作为 MCP server 把自己的能力（tool/worker/session）暴露为 MCP 资源给 Reasonix 或其他 MCP client 使用。

**需要新增的文件**：

| 文件 | 内容 |
|------|------|
| `server/McpServerAdapter.java` | 将 harness 的 `ToolRegistry` 暴露为 MCP server |
| `tool/mcp/McpBridge.java` | 双向桥接管理 |

---

### 2.3 首期实现步骤（方案 A — ✅ 已实现）

> 状态：2026-06-01 已完成核心集成，通过编译 + 启动验证 + puppeteer 截图确认。

| 步骤 | 文件 | 操作 | 状态 |
|------|------|------|------|
| 1 | `agent/providers/BuiltinAgentProviders.java` | 利用现成 `LocalCliAgentProvider` 注册 `reasonix` provider，读取 `MULTICA_REASONIX_PATH` / `MULTICA_REASONIX_MODEL` | ✅ |
| 2 | `engine/router/WorkerRegistry.java` | 注册 `reasonix` worker，`selection_priority=85`，`execution_backend=provider_native_cli` | ✅ |
| 3 | `worker/ProviderExecutionSupport.java` | 将 `"reasonix"` 加入 `PROVIDER_NATIVE_CLI` 允许注册集 | ✅ |
| 4 | `worker/ProviderCliWorkerExecutor.java` | 新增 `buildReasonixPlan()` + `consumeReasonix()` + switch cases | ✅ |
| 5 | `agent/providers/LocalCliAgentProvider.java` | `dispatchProbeArgs()` 添加 `reasonix` → `["run", "--help"]` | ✅ |
| 6 | Worker readiness warmup 通过 | 启动日志: `Worker dispatch preflight warmup ready. worker=reasonix mode=active_probe cached=false` | ✅ |

**API 验证结果**（通过 puppeteer 实时抓取）：

```json
// /api/v1/workers → reasonix worker
{"worker_id":"reasonix","ready":true,"capabilities":["coding","reading","writing","research"],
 "selection_priority":85,"execution_backend":"provider_native_cli"}

// /api/v1/agents → reasonix provider
{"provider_id":"reasonix","installed":true,"version":"0.53.2","ready":true,
 "launch_target":"C:\\nvm4w\\nodejs\\reasonix.cmd","transport":"pty"}
```

**CLI 集成详情** — `reasonix run` 是最合适的命令：

```
reasonix run <task> [--model <id>] [--no-config] [--no-proxy]
```

输出是纯文本流，包含 MCP 连接状态（`⌘` 开头行）、响应正文、成本摘要（`—` 开头行）。`consumeReasonix()` 过滤掉元数据行只保留正文。

**不需要 `ReasonixProvider.java` 或 `ReasonixWorkerExecutor.java`** — 现成的 `LocalCliAgentProvider` 和 `ProviderCliWorkerExecutor` 通过 `buildPlan()`/`consume()` 的 provider 分派机制已覆盖。

---

## 3. 架构改进意见

### 3.1 🟢 工具系统：Tool 接口缺少 Schema 描述

**问题**：`Tool` 接口（`tool/Tool.java:5`）只提供 `name()`、`description()`、`argumentContract()`（返回 string）。对 LLM 来说，parameter schema 是最关键的。

```java
// 当前（tool/Tool.java）
public interface Tool {
    String name();
    default String description() { return ""; }
    default String argumentContract() { return ""; }  // ← 脆弱
    ToolResult invoke(ToolRequest request) throws Exception;
}
```

**建议**：

```java
public interface Tool {
    String name();
    String description();
    /** 返回 JSON Schema 对象，描述 tools.parameters */
    JsonNode inputSchema();  // ← 替代 argumentContract
    ToolResult invoke(ToolRequest request) throws Exception;
}
```

**影响面**：`ToolRegistry.describeTools()` 和 `ToolAwareWorkerExecutor.planTool()` 都需要更新。

### 3.2 🟢 工具系统：去重 ToolRegistry 的重复注册

**问题**：`ToolRegistry`（`tool/ToolRegistry.java`）使用 `LinkedHashMap`，`register()` 方法不检查重复名称，后注册的静默覆盖前者。

**建议**：在 `register()` 中增加重复检测日志或异常。

### 3.3 🟡 控制平面粒度：ControlNodeGraph 过大

**问题**：`ControlNodeGraph.java` 达 4153 行，集成了状态转移、worker 执行、judgment、packet 构建等逻辑。单一职责原则在此被严重破坏。

**建议**：
- 拆为：`ControlNodeGraph`（状态机核心）+ `ControlNodeExecutor`（执行节点）+ `ControlNodeJudge`（判断节点）
- 每个节点类型对应一个独立的类

### 3.4 🟡 ProviderCliWorkerExecutor 过重

**问题**：`ProviderCliWorkerExecutor.java` 达 1918 行，内部通过 `if/else` 判断 provider 类型来分流。每次新增 provider 都要修改，违反开闭原则。

**建议**：
- 引入 `ProviderCliProtocol` 接口，每个 provider 实现自己的协议类：

```java
interface ProviderCliProtocol {
    String providerId();
    String[] buildCommand(LocalCliProviderConfig.ResolvedConfig config, String prompt, Worker worker);
    WorkerExecutionResult parseOutput(String rawOutput, String providerId);
    boolean supportsBackend(String backend);
}
```

- 已有 provider（cursor/claude/gemini/deepseek/kimi）各自提取为独立类

### 3.5 🟡 WorkerRouter 路由策略不可注入

**问题**：`WorkerRouter`（`engine/router/WorkerRouter.java`）的路由算法硬编码在 `selectWorker()` 中，无法通过配置或插件切换。

**建议**：
- 定义 `RoutingStrategy` 接口（`default / cost-optimized / latency-optimized / round-robin`）
- 路由策略可通过 `metadata_json` 或 `LlmConfig` 注入

### 3.6 🟡 无配置热加载

**问题**：`LlmConfig`、`ToolPolicy` 等在启动时从环境变量读取，修改需要重启进程。

**建议**：
- 添加简单的文件监听（`WatchService`）或定期重读机制
- 至少为 `LlmConfig`（model、baseUrl）和 `ToolPolicy`（timeout、blacklist）添加信号量刷新

### 3.7 🔴 Agent Provider 与 Worker 边界模糊

**问题**：`WorkerRegistry`（第 70-230 行）内联注册 12 个 worker 的定义，每个 worker 同时包含 provider 信息（如 `execution_backend=provider_native_cli`）和业务角色信息。代码中多次出现 `providerId(worker)` 的回推逻辑。

**建议**：
- 将 `AgentProvider` 的注册信息从 `WorkerRegistry` 解耦，建立显式的 `Worker → AgentProvider` 映射表
- 允许一个 Provider 对应多个 Worker role（如 `codex-planner`、`codex-executor`）

### 3.8 🟢 HTTP Handler 手工路由可维护性

**问题**：每个 Handler 内部用 `if/else` 匹配 `method + path`（如 `TaskHandler.java` 约 800 行），路径变量提取靠 `pathVar(exchange, 2)` 这种脆弱的索引方式。

**建议**：
- 引入一个极简的 `RouteRegistry` 类，用 `Map<RoutePattern, HttpHandler>` 做路由分发
- 为路径变量提供命名参数（非索引）

---

## 4. 代码质量改进意见

### 4.1 🔴 日志不一致

| 问题 | 示例 |
|------|------|
| 中文夹杂英文日志 | `"Task 进入 waiting"` vs `"Task created successfully"` |
| 日志级别不一致 | 成功路径用 `info` 扩散，错误路径有时用 `error` 有时 `warn` |
| console/ 代码中无日志 | `WebConsoleHandler.java` 没有任何访问日志 |

**建议**：统一为英文日志 + 规则：`info` = 外部可见事件，`debug` = 内部细节，`warn` = 可恢复异常，`error` = 不可恢复。

### 4.2 🟡 Record 默认值模式重复

**问题**：几乎所有 `model/*.java` 的 Record 在 compact constructor 中手动设置 `createdAt` / `updatedAt` 默认值，代码重复约 40+ 处。

**建议**：
- 引入 `@Default` 注解或一个 `ModelDefaults` 工具类：

```java
public static <T> T withDefaults(T record) { ... }
```

### 4.3 🟢 异常处理风格不统一

**问题**：部分 handler 捕获 `Exception` 后返回通用 500，部分抛 `RuntimeException` 让 `NioHttpServer` 的 `doHandle` 统一处理——但 `doHandle` 中没有 try-catch 包装。

**建议**：在 `NioHttpServer.sendJson()` 或 `exchangeHandler.handle()` 外层添加统一异常捕获。

### 4.4 🟡 魔法字符串散落

**问题**：工具名称（`"list_files"`, `"read_file"`）、路由路径（`"/api/v1/tasks"`）、metadata keys（`"execution_backend"`, `"selection_priority"`）均以字符串形式散落各处。

**建议**：引入集中的常量类 `Constants.java`：

```java
public final class Constants {
    public static final String TOOL_LIST_FILES = "list_files";
    public static final String ROUTE_API_V1_TASKS = "/api/v1/tasks";
    public static final String META_EXECUTION_BACKEND = "execution_backend";
    public static final String META_SELECTION_PRIORITY = "selection_priority";
}
```

### 4.5 🟢 JSON 列 序列化/反序列化集中化

**问题**：DAO 中直接调用 `JsonMapper.write(map)` / `JsonMapper.readMap(json)` 来处理 `metadata_json` 列，调用点分散在 15+ 个 DAO 文件中。

**建议**：为 JDBI 注册全局 `@RegisterColumnMapper` 或自定义 `ColumnMapperFactory`，自动处理所有 `metadata_json` 列。

---

## 5. 测试改进意见

### 5.1 测试现状

| 维度 | 统计 |
|------|------|
| 测试总数 | 54 |
| 单元测试 | 约 15（tool/*Test, judgment/*Test, runtime/*Test） |
| 集成测试 | 约 39（主要是 HTTP test + engine flow test） |
| Mock 使用 | 不统一：部分用 Mockito，部分用假实例/匿名函数 |

### 5.2 🔴 HTTP 测试缺乏基础设施抽象

**问题**：每个 `*HttpTest.java` 启动真正的 `NioHttpServer`，使用真实 SQLite 数据库（`jdbc:sqlite::memory:`），初始化和清理逻辑在每个测试类重复。

**建议**：
- 抽取基类 `HttpIntegrationTestBase`：

```java
abstract class HttpIntegrationTestBase {
    static NioHttpServer server;
    static HttpClient client;
    @BeforeAll static void startServer() { ... }
    @AfterAll static void stopServer() { ... }
    HttpResponse<String> post(String path, String body) { ... }
    HttpResponse<String> get(String path) { ... }
}
```

### 5.3 🟡 控制节点图缺乏边界测试

**问题**：`ControlNodeGraph` 的 6 个节点（intake→scheduler→continue→packet→human_gate→handoff→end）的边界转换在 `ControlNodeGraphOrchestrationFlowTest` 有端到端覆盖，但缺乏 **节点级别** 的单元测试。

**建议**：为每个 control node 写独立 `@Test`：

```java
@Test void intake_transitions_to_scheduler_when_worker_assigned() { ... }
@Test void scheduler_transitions_to_continue_when_executor_ready() { ... }
@Test void human_gate_blocks_on_waiting_reason() { ... }
```

### 5.4 🟢 ToolAwareWorkerExecutor 的多轮工具链测试覆盖率不足

**问题**：`ToolAwareWorkerExecutorMultiStepTest` 和 `ToolAwareWorkerExecutorMultiToolTest` 存在，但没有覆盖**重复工具调用防护**和**no-progress 防护**分支。

**建议**：补充：

```java
@Test void blocks_when_tool_repeated_twice() { ... }
@Test void blocks_when_no_progress_after_3_rounds() { ... }
```

### 5.5 🟡 DAO 层缺乏数据迁移测试

**问题**：`DatabaseManagerCompatibilityMigrationTest` 存在但只测试了 schema 的创建。没有测试 **schema 升级迁移**（例如从 v0.1 到 v0.2）。

**建议**：为 `schema.sql` 的每个版本变更编写迁移脚本和对应的 roll-forward 测试。

---

## 6. 运维改进意见

### 6.1 🔴 无健康检查探测面

**问题**：目前 `/api/v1/health` 只返回 `200 OK`，不检查 LLM 可达性、数据库连接、worker readiness 等。

**建议**：健康检查应返回：

```json
{
  "status": "ok",
  "uptime_seconds": 3600,
  "checks": {
    "db": { "status": "ok", "latency_ms": 2 },
    "llm": { "status": "ok", "model": "gpt-4o-mini" },
    "workers": { "status": "degraded", "ready": 8, "total": 12 }
  }
}
```

### 6.2 🔴 没有请求超时控制

**问题**：`NioHttpServer` 使用虚拟线程执行 `exchangeHandler.handle()`，但没有设置 `HttpServer.setExecutor()` 的拒绝策略或超时。

**建议**：
- 为每个 HTTP 请求设置 `HttpExchange.setAttribute("timeout", Duration.ofSeconds(30))`
- 在 handler 内定期检查耗时，超时返回 503

### 6.3 🟡 没有限流和熔断

**问题**：`OpenAiCompatibleClient` 有重试逻辑（`maxRetries=2`，`BASE_RETRY_BACKOFF_MS=1500`），但没有熔断（circuit breaker）。连续失败的 provider 只是通过 `temporarilyUnavailableWorkers` 标记 10 分钟不可用。

**建议**：
- 引入简单计数器熔断：连续 N 次失败 → 熔断 30 秒 → 半开 → 成功则关闭
- 独立的 provider 故障计数器，避免一个 provider 的失败拖慢整体

### 6.4 🟡 ProviderCliWorkerExecutor 进程管理风险

**问题**：`ProviderCliWorkerExecutor`（第 1918 行）对每个 provider 启动一个新的 `ProcessBuilder` 进程。如果任务并发高，可能同时运行数十个子进程，消耗内存和端口。

**建议**：
- 引入 **进程池** 或 **信号量** 限制并发子进程数（默认 ≤ 4）
- 对 CLI 进程添加 `process.onExit().thenRun()` 清理回调，确保 `destroyForcibly()` 不会泄漏文件描述符

### 6.5 🟢 SQLite 写扩散

**问题**：每次工具调用、决策、事件都单独写入 SQLite。在工具链场景下（一次 `ExecuteOneRound` 写入 ~20 行），SQLite 的 WAL 模式虽然比 journal 好，但高频写入仍然形成瓶颈。

**建议**：
- 对观测类写入（事件、tool_invocations）使用 **批量插入**（batch `INSERT` 而非逐行 `INSERT`）
- 评估将 `event` 表改为 append-only `event_log` 表，使用定期 compaction

### 6.6 🟢 Dialogue 前端没有错误状态提示

**问题**：Dialogue 前端（`web/dialogue/`，约 50+ JS 文件）在 API 请求失败时静默忽略，用户看不到连接断开或服务端错误。

**建议**：在 `facade-client-plan.js` 等核心 HTTP 调用点添加统一的错误渲染组件：

```js
function showError(message, retryFn) {
    const banner = document.createElement('div');
    banner.className = 'error-banner';
    banner.textContent = message;
    // + retry button
}
```

---

## 7. 实施路线图

### Phase 1 — 首期 Reasonix 集成（1-2 周）

```
  ┌─────────────────────────────────────────────┐
  │ 1. ReasonixProvider.java           agent/    │
  │ 2. WorkerRegistry 注册 reasonix    router/   │
  │ 3. ReasonixWorkerExecutor.java     worker/   │
  │ 4. Main.java wiring                cli/      │
  │ 5. BuiltinAgentProviders 注册      agent/    │
  │ 6. 测试                            test/     │
  └─────────────────────────────────────────────┘
```

### Phase 2 — 架构加固（2-3 周）

```
  ┌─────────────────────────────────────────────┐
  │ 1. Tool 接口加 inputSchema()        tool/    │
  │ 2. ProviderCliProtocol 接口         worker/   │
  │ 3. ControlNodeGraph 拆分为多类      engine/   │
  │ 4. 常量集中管理                     util/     │
  │ 5. HTTP 测试基类提取                test/     │
  │ 6. 统一异常处理                     server/   │
  └─────────────────────────────────────────────┘
```

### Phase 3 — 运维与可靠性（2-3 周）

```
  ┌─────────────────────────────────────────────┐
  │ 1. 健康检查深度增强                 server/   │
  │ 2. HTTP 超时控制                   server/   │
  │ 3. 熔断器（circuit breaker）        llm/      │
  │ 4. 进程池限制                      worker/   │
  │ 5. SQLite 批量写入                  store/    │
  │ 6. MCP Bridge（方案 C）             tool/mcp/ │
  └─────────────────────────────────────────────┘
```

---

## 附录：文件改动清单（按优先级）

### P0 — Reasonix 接入

| 操作 | 路径 |
|------|------|
| 新增 | `src/main/java/com/agentcloud/agent/providers/ReasonixProvider.java` |
| 修改 | `src/main/java/com/agentcloud/engine/router/WorkerRegistry.java` |
| 新增 | `src/main/java/com/agentcloud/worker/ReasonixWorkerExecutor.java` |
| 修改 | `src/main/java/com/agentcloud/cli/Main.java` |
| 新增 | `src/test/java/com/agentcloud/worker/ReasonixWorkerExecutorTest.java` |

### P1 — 架构加固

| 操作 | 路径 |
|------|------|
| 修改 | `src/main/java/com/agentcloud/tool/Tool.java` + 所有 Tool 子类 |
| 新增 | `src/main/java/com/agentcloud/util/Constants.java` |
| 新增 | `src/main/java/com/agentcloud/worker/ProviderCliProtocol.java` |
| 重构 | `src/main/java/com/agentcloud/worker/ProviderCliWorkerExecutor.java` |
| 重构 | `src/main/java/com/agentcloud/engine/ControlNodeGraph.java` |
| 新增 | `src/test/java/com/agentcloud/server/HttpIntegrationTestBase.java` |

### P2 — 运维改进

| 操作 | 路径 |
|------|------|
| 修改 | `src/main/java/com/agentcloud/server/NioHttpServer.java` |
| 新增 | `src/main/java/com/agentcloud/util/CircuitBreaker.java` |
| 新增 | `src/main/java/com/agentcloud/worker/WorkerProcessPool.java` |
| 新增 | `src/main/java/com/agentcloud/tool/mcp/McpClient.java` |
| 修改 | `src/main/java/com/agentcloud/store/DatabaseManager.java` |
