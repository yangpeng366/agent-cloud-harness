# Architecture

<!-- 更新时间：2026-04-28 -->
<!-- 分析依据：当前工作区源码与资源文件 -->

## 1. 项目简介

Agent Cloud Harness 是一个面向多智能体协作场景的轻量控制平面服务。它的当前主线不是“通用 agent 平台大全”，而是一个 **continuity-first orchestration harness**：负责创建会话与任务、为任务路由 worker、执行单轮 worker round、在暂停/恢复/移交时生成 continuity packet，并把运行时轨迹、判断结果、工具调用、学习记忆与实验指标持久化到本地 SQLite。当前形态是单机 harness，不是分布式 control plane。

当前最值得关注的近端证明目标也已经比较明确：

- 先证明 continuity-first control plane 本身成立
- 再证明它能支撑“强模型负责规划/判断，小模型负责执行”的最小 orchestration 闭环

如果需要进一步理解这条主线与评测、优先级、roadmap 的关系，建议联读：

- `docs/CURRENT_CAPABILITY_GAP_ASSESSMENT.md`
- `docs/NEXT_5_ENGINEERING_PRIORITIES.md`
- `docs/GOAL_ORIENTED_EVAL_PLAN.md`

## 2. 技术栈与约束

### 2.1 技术栈全景

| 类别 | 技术 | 版本 | 备注 |
|------|------|------|------|
| 后端语言 | Java | 21 | `pom.xml` 启用 `--enable-preview` |
| HTTP 接入 | JDK `HttpServer` | JDK 自带 | `com.sun.net.httpserver.HttpServer` + 虚拟线程 |
| 前端 | Vanilla JS + CSS | N/A | 内置 `/console/` 与 `/dialogue/` |
| JSON | Jackson | 2.17.2 | 统一 `snake_case` 输出 |
| 数据库 | SQLite | 3.46.0.0 驱动 | 本地文件，零外部依赖 |
| DAO | Jdbi SQL Object | 3.45.1 | 注解式 SQL，无 ORM |
| 连接池 | HikariCP | 5.1.0 | 轻量连接管理 |
| LLM 适配 | OpenAI-compatible client | N/A | `llm/OpenAiCompatibleClient` |
| 工具层 | 内置 Tool Registry | N/A | 受 `ToolPolicy` 约束的受控本地工具，含文件工具与命令工具；`git/shell/powershell/cmd` 都按宿主机真实可执行性探测动态暴露，其中 `powershell/cmd` 仍仅 Windows 宿主可用 |
| 日志 | SLF4J + Logback | 2.0.13 / 1.5.6 | root=`INFO` |
| 测试 | JUnit Jupiter | 5.11.0 | 当前已有 27 个测试文件 |

### 2.2 技术栈约束规则

- **语言版本**: Java 21，且必须带 preview 编译参数。
- **包管理**: Maven 单模块结构，不要擅自拆成多模块。
- **Web 框架**: 继续沿用 `HttpHandler` 手写路由，不引入 Spring/Jersey。
- **持久化**: 继续沿用 SQLite + Jdbi SQL Object；schema 由 `schema.sql` 初始化。
- **JSON 契约**: 统一共享 `ObjectMapper`，输出 `snake_case`。
- **前端形态**: 当前前端是 resources 下的静态页面，不存在额外 SPA 构建链。
- **测试基线**: 已有 packet、orchestration、judgment、tool-aware execution、experiment、message projection 等回归测试。

## 3. 目录结构

```text
agent-cloud-harness/
├── src/
│   ├── main/
│   │   ├── java/com/agentcloud/
│   │   │   ├── cli/                 # Main.java，手工装配全部依赖
│   │   │   ├── engine/              # Task/Session/Control Graph/Experiment/Learning Memory
│   │   │   ├── engine/memory/       # Resume/Handoff packet 构建与重建
│   │   │   ├── engine/router/       # Worker 注册与路由
│   │   │   ├── judgment/            # Prompt-based execution/completion judgment
│   │   │   ├── llm/                 # OpenAI-compatible LLM client 与配置
│   │   │   ├── runtime/             # Active Context / Runtime Context 组装
│   │   │   ├── server/              # HttpServer 与各资源 Handler
│   │   │   ├── store/               # DB 初始化、Mapper、DAO
│   │   │   ├── tool/                # 受控本地工具与 ToolPolicy
│   │   │   ├── worker/              # 默认执行器、tool-aware 执行器、执行分流
│   │   │   └── model/               # Record DTO / View / Packet / Trace
│   │   └── resources/
│   │       ├── schema.sql           # SQLite schema
│   │       ├── logback.xml          # 日志配置
│   │       └── web/
│   │           ├── console/         # Web Console 静态前端
│   │           └── dialogue/        # Dialogue 静态前端
│   └── test/java/com/agentcloud/    # 当前已有 27 个测试文件
├── docs/                            # 架构、规格、契约、排查文档
├── scripts/                         # Java 21 构建/测试/运行脚本
├── pom.xml
└── dependency-reduced-pom.xml
```

## 4. 系统架构

### 4.1 架构总览

```text
Browser / curl / SDK
    |                       +-----------------------------+
    +---------------------> | NioHttpServer              |
                            | Task/Session/... Handlers  |
                            | /console/ /dialogue/       |
                            +-------------+---------------+
                                          |
                                          v
                            +-----------------------------+
                            | TaskService / SessionService|
                            | ExperimentRun/Matrix        |
                            +-------------+---------------+
                                          |
                                          v
                            +-----------------------------+
                            | ControlNodeGraph            |
                            | intake -> scheduler -> ...  |
                            +------+------+------+--------+
                                   |      |      |
                                   |      |      +--> PacketBuilder / Consolidation
                                   |      |
                                   |      +--> TaskRuntimeContextBuilder
                                   |             + PromptBasedJudgmentService
                                   |
                                   +--> WorkerRouter
                                          + LearningMemoryService
                                          + WorkerExecutorRouter
                                                |
                                                +--> DefaultWorkerExecutor
                                                +--> ToolAwareWorkerExecutor
                                                       + ToolRegistry/ToolPolicy
                                                       + LLM Client

All runtime traces / packets / messages / experiments
                    |
                    v
         +-----------------------------+
         | Jdbi DAO + SQLite           |
         | sessions/tasks/events/...   |
         +-----------------------------+
```

### 4.2 分层结构

| 层级 | 目录/命名空间 | 职责 | 典型类/文件 |
|------|-------------|------|------------|
| 前端层 | `src/main/resources/web` | 本地观测与交互 UI | `console/app.js`, `dialogue/app.js` |
| 接入层 | `src/main/java/com/agentcloud/server` | HTTP API、静态资源服务、错误包装 | `NioHttpServer`, `TaskHandler`, `SessionHandler`, `WebConsoleHandler` |
| 应用层 | `src/main/java/com/agentcloud/engine` | 任务/会话生命周期、实验、学习记忆、控制动作编排 | `TaskService`, `SessionService`, `ControlNodeGraph`, `ExperimentMatrixService` |
| 运行时层 | `src/main/java/com/agentcloud/runtime` | Active Context 与 Runtime Context 构建 | `ActiveContextBuilder`, `TaskRuntimeContextBuilder` |
| Judgment 层 | `src/main/java/com/agentcloud/judgment` | 执行判断、完成判断与 prompt 组装 | `PromptBasedJudgmentService`, `JudgmentContext` |
| 路由与续跑层 | `src/main/java/com/agentcloud/engine/router`, `engine/memory` | worker 路由、packet 构建、上下文重建 | `WorkerRouter`, `PacketBuilder`, `ContextReconstructor` |
| 执行与工具层 | `src/main/java/com/agentcloud/worker`, `src/main/java/com/agentcloud/tool` | 单轮执行、tool-aware 多步工具链、工具访问控制 | `WorkerExecutorRouter`, `ToolAwareWorkerExecutor`, `ToolPolicy` |
| LLM 适配层 | `src/main/java/com/agentcloud/llm` | LLM 配置与兼容客户端封装 | `LlmConfig`, `OpenAiCompatibleClient` |
| 数据层 | `src/main/java/com/agentcloud/store` | SQLite 初始化、DAO、Mapper、JSON 列转换 | `DatabaseManager`, `TaskDao`, `ExperimentRunDao` |
| 模型层 | `src/main/java/com/agentcloud/model` | API DTO、View、Packet、Record | `Task`, `ResumePacket`, `LearningMemory`, `ExperimentRunRecord` |

## 5. 模块清单

### 5.1 启动与装配模块

- **目录**: `src/main/java/com/agentcloud/cli`
- **职责**: 作为唯一进程入口，按固定顺序装配 DAO、学习记忆、实验、路由、packet、runtime、judgment、tool、worker executor、service、HTTP server。
- **入口文件**: `src/main/java/com/agentcloud/cli/Main.java`
- **关键点**: 当前依赖顺序必须以 `Main.main` 为准，不要再参考旧文档中的简化装配链。

### 5.2 HTTP 接入模块

- **目录**: `src/main/java/com/agentcloud/server`
- **职责**: 暴露 `/api/v1/*` JSON API，同时挂载 `/console/` 与 `/dialogue/` 静态前端。
- **关键类**:
  - `NioHttpServer` — 注册 API 与前端路由，配置虚拟线程执行器。
  - `TaskHandler` — task CRUD、control action、runtime/judgment/tool/experiment 观测接口。
  - `SessionHandler` — session CRUD、pause/resume/close、messages。
  - `AgentHandler` / `AgentRunHandler` / `RuntimeHealthHandler` — provider inventory、agent run、runtime health 观测接口。
  - `ExperimentRunHandler` / `ExperimentMatrixHandler` / `LearningMemoryHandler` — 新增观测与评估端点。
  - `WebConsoleHandler` — 静态资源服务与根路径重定向。

### 5.3 任务编排与实验模块

- **目录**: `src/main/java/com/agentcloud/engine`
- **职责**: 负责 task/session 生命周期、control action、消息投影、实验 run 落盘、experiment matrix 汇总，以及 provider 运行结果与任务侧轨迹的拼接。
- **关键类**:
  - `TaskService` — 创建任务、控制动作、live flow/runtime context/judgment trace/tool trace 聚合。
  - `SessionService` — session 生命周期与 session message 投影。
  - `AgentRunService` — 记录 provider run、provider selection 与 runtime health 摘要。
  - `ExperimentRunService` — 记录每个 run 的成本、恢复次数、route trace 等指标。
  - `ExperimentMatrixService` — 生成 baseline case matrix，按 mode/case 汇总结果。

### 5.4 路由、学习记忆与 Continuity 模块

- **目录**: `src/main/java/com/agentcloud/engine/router`, `src/main/java/com/agentcloud/engine/memory`
- **职责**: 决定“任务交给谁做”和“停下来之后如何继续做”，并把可复用的 routing / retention 经验沉淀回 runtime 上游。
- **关键类**:
  - `WorkerRegistry` — 维护内置与动态注册 worker。
  - `WorkerRouter` — 同时考虑 capability、readiness、`model_mode`、learning memory preferred hint、fallback reason。
  - `LearningMemoryService` — 当前已捕获并强化 `routing_preference`、`context_retention_hint`、`completion_pattern`、`worker_heuristic` 四类经验；其中 `routing_preference` 会反哺 `WorkerRouter`，`context_retention_hint` 会回流 `ActiveContextBuilder`，并且 retention hint 已优先从 `mounted_context_view` 提取 retained item，保留 panel / retention state / selection trace 证据。
  - `PacketBuilder` — 构建 `ResumePacket`、`HandoffPacket` 与稳定协议头。
  - `ContextReconstructor` — 回放 packet 视图与共享上下文。

### 5.5 Runtime / Judgment / LLM 模块

- **目录**: `src/main/java/com/agentcloud/runtime`, `src/main/java/com/agentcloud/judgment`, `src/main/java/com/agentcloud/llm`
- **职责**: 为单轮执行构建高价值上下文，并基于 prompt 对执行结果进行结构化判断。
- **关键类**:
  - `ActiveContextBuilder` — 从事件、决策、产物、checkpoint、learning memory 中裁出工作记忆。
  - `TaskRuntimeContextBuilder` — 组装 worker round 与 judgment 共享的 runtime context；当前会同时挂上兼容旧面的 `activeContext` 与 panel/object 化的 `mountedContextView`。
  - `PromptBasedJudgmentService` — 输出 execution/completion judgment，并保留 trace；当前 judgment prompt 通过 `PromptRenderingMode` seam 决定是否注入 mounted context surface。
  - `RuntimeJudgmentService` — 基于 task metadata 做最小 continue / pause / escalate / handoff 规则判断。
  - `OpenAiCompatibleClient` — 对接兼容 OpenAI 协议的 LLM 接口。

**与 hardness phase-1 方案的当前对齐判断**:

- 当前代码里已经有 `WorkerExecutionResult` 的近邻配套上下文和 judgment 模块，说明 runtime / judgment 主线并不是概念层。
- 当前代码里已经有 `Checkpoint`、`ResumePacket`、`HandoffPacket` 与 `TaskRuntimeContext`，说明 resume / continuity 主线已具备真实落点；其中 `TaskRuntimeContext` 现在同时暴露 `active_context` 与 `mounted_context_view`。`mounted_context_view` 在 runtime 构建与 retention-hint capture 上始终有效，在 execution/planning/judgment prompt 上则通过 `PromptRenderingMode` 做安全 rollout，默认仍是 `active_context_only`。
- 当前 prompt seam 的稳定模式是：`active_context_only`（默认，仅旧 active context）、`mounted_context_shadow`（渲染 shadow metadata 但不注入 prompt）、`mounted_context_primary`（显式注入 mounted prompt，同时保留 active context 兼容面）。
- judgment 层已经是显式模块，而不是散落逻辑；但 `JudgmentInput` 仍更多隐含在 `JudgmentContext`、runtime context 和 trace 聚合里，尚未完全升级为 fact-aware 的统一输入对象。

也就是说，这一层当前最准确的状态不是“还没开始”，而是：**已有真实实现落点，但还未完全收束成统一 hardness contract。**

### 5.6 Worker 执行与工具模块

- **目录**: `src/main/java/com/agentcloud/worker`, `src/main/java/com/agentcloud/tool`
- **职责**: 在选中 worker 后执行一轮真实工作，并在需要时驱动工具链。
- **关键类**:
  - `WorkerExecutorRouter` — 在默认执行器与 tool-aware 执行器之间分流。
  - `DefaultWorkerExecutor` — 纯 LLM 单轮执行。
  - `ToolAwareWorkerExecutor` — 最多 3 步的工具链执行，含 `repeated_tool_guard`、`no_progress_guard` 与 grounded write 判定。
  - `ToolPolicy` / `ToolRegistry` — 约束文件路径、命令工作目录、只读 git 子命令、超时、输出长度与危险命令拦截。
  - `ToolInvocationRecord` + `ToolInvocationDao` — 持久化工具调用 trace。

**与 hardness phase-1 方案的当前对齐判断**:

- `ToolInvocationRecord`、`tool_invocations` 表、`ToolInvocationDao` 已经存在，所以“工具 trace 应优先持久化”这一步在代码里其实已经实现。
- `ToolPolicy` 已不只是概念边界，而是包含 `suggestOnly` 限制、`toolCapabilities` 校验、`toolScope` 路径边界、命令 allowlist / denylist、timeout 与输出长度限制的真实 enforcement。
- `ToolAwareWorkerExecutor` 已经是可运行的多步工具执行雏形，不应再被文档描述成“只有单工具设想”。当前更准确的 gap 是：虽然执行链和 trace 已存在，但还没有统一收束成更显式的 `WorkerExecutionEnvelope -> ToolInvocationRecord -> RuntimeFactSet -> ResumeCheckpoint -> JudgmentInput` runtime contract 链。

### 5.7 存储模块

- **目录**: `src/main/java/com/agentcloud/store`, `src/main/resources`
- **职责**: 提供 sessions、tasks、events、decisions、artifacts、resume_packets、checkpoints、session_messages、tool_invocations、learning_memories、experiment_runs、agent_runs 等持久化。
- **关键类**:
  - `DatabaseManager` — 初始化 HikariCP/Jdbi，执行 `schema.sql`。
  - `Mappers` / `JsonMapper` / `InstantArgumentFactory` — 处理 SQLite 时间与 JSON 列兼容。
  - `*Dao` — 每类实体对应一个 SQL Object DAO。

### 5.8 Agent Provider 模块

- **目录**: `src/main/java/com/agentcloud/agent`
- **职责**: 提供真实 agent/provider 的注册、发现、状态探测与最小运行引用模型，作为现有 worker/control plane 的增量接入层。
- **关键类**:
  - `AgentProviderRegistry` — 管理当前已接入 provider。
  - `AgentDiscoveryService` / `SimpleAgentDiscoveryService` — 负责本机侧 provider 探测与状态刷新。
  - `CodexProvider` / `OpenClawProvider` — 当前已落地的 provider skeleton。
  - `AgentRunRef` / `AgentRunResult` / `AgentArtifactRef` — provider 运行引用与产物模型。

这一层当前最准确的状态不是“纯未来设计”，而是：**provider inventory、agent run 与 runtime health 已进入代码与 API 接面，但 provider orchestration contract 仍在继续收硬。**

### 5.9 Web 前端模块

- **目录**: `src/main/resources/web`
- **职责**: 提供无需额外构建链的内置前端。
- **页面**:
  - `/console/` — 任务、session、worker、route、packet、experiment，以及逐步扩展中的 agent/provider 观测入口。
  - `/dialogue/` — session message 流、task 交互与任务进展回执入口。

## 6. 模块依赖与数据流

### 6.1 模块间依赖

```text
cli/Main
   |
   +--> server
   |      |
   |      +--> engine
   |              |
   |              +--> runtime
   |              +--> judgment
   |              +--> worker --> tool
   |              +--> llm
   |              +--> engine/router
   |              +--> engine/memory
   |              +--> store
   |              +--> model
   |
   +--> resources/web
```

### 6.2 核心数据流

```text
[POST /api/v1/tasks]
        |
        v
   TaskHandler
        |
        v
   TaskService.createTask
        |
        v
   ControlNodeGraph.enter
        |
        +--> WorkerRouter.selectWorker
        |         |
        |         +--> LearningMemoryService
        |
        +--> TaskRuntimeContextBuilder.build
        |
        +--> WorkerExecutorRouter.executeOneRound
        |         |
        |         +--> ToolAwareWorkerExecutor / DefaultWorkerExecutor
        |                 |
        |                 +--> ToolRegistry / LLM Client
        |
        +--> PromptBasedJudgmentService
        |
        +--> PacketBuilder / ConsolidationService
        |
        +--> ExperimentRunService / SessionMessage projection
        |
        v
[SQLite: tasks/events/decisions/artifacts/packets/messages/tools/experiments]
```

## 7. 构建与部署

### 7.1 环境要求

- Java 21
- Maven 3.9+ 为宜
- 运行用户对 `${user.home}/.agentcloud/` 有写权限

### 7.2 本地开发

```bash
mvn package
java --enable-preview -jar target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar
```

推荐脚本：

```powershell
.\scripts\Test-WithJava21.ps1
.\scripts\Run-HarnessWithJava21.ps1 -Port 18080
```

### 7.3 配置说明

| 配置项 | 来源 | 说明 | 默认值 |
|--------|------|------|--------|
| `server.port` | JVM System Property | HTTP 监听端口 | `8080` |
| `user.home` | JVM / OS 环境 | SQLite 文件落点 `${user.home}/.agentcloud/agent_cloud.db` | 当前用户主目录 |
| LLM 配置 | 环境变量 / 系统属性 | 由 `LlmConfig` 读取 | 依环境而定 |
| `schema.sql` | `src/main/resources/schema.sql` | 启动时初始化表结构 | 内置资源 |

## 8. 代码规模概要

| 指标 | 数值 |
|------|------|
| `src/main/java` Java 文件数 | 103 |
| `src/test/java` 测试文件数 | 27 |
| `src/main/resources/web` 前端文件数 | 6 |
| 最近活跃度 | 当前工作区无 `.git` 信息可用，本文档未尝试从历史提交推导 |
